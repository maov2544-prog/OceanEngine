package com.aolandai.oceanengine.scheduler;

import com.aolandai.oceanengine.domain.SuixintuiReinvestCandidateSelector.CandidateOrder;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.service.suixintui.SuixintuiReinvestService;
import com.aolandai.oceanengine.storage.ConsumeSnapshotStore;
import com.aolandai.oceanengine.storage.ReinvestRecord;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 【QA 独立验证，临时】第⑤项：靠 ROAS 第二条路径纳入的候选（{@code admittedByRoas=true}）
 * 是否与靠使用率纳入的候选走<b>同一条</b>下游链路（幂等 → 资金护栏 → dry-run / 真实追投 → 落盘）。
 *
 * <p>做法：直接构造一个 {@code admittedByRoas=true} 的候选喂给 {@code reinvestOne}，
 * 断言它在真实模式下照样调 {@code addBudget} 并落 DONE 流水；dry-run 下不调用、不落盘。
 * 若新字段造成了任何分叉，这里会暴露。</p>
 */
class RoasQaDownstreamTest {

    @TempDir
    Path tempDir;

    private ConsumeSnapshotStore snapshotStore() throws Exception {
        return new ConsumeSnapshotStore(tempDir.resolve("consume-snapshot.jsonl"), 7);
    }

    private SuixintuiReinvestProperties props(boolean dryRun) {
        SuixintuiReinvestProperties p = new SuixintuiReinvestProperties();
        p.setAmount(100L);
        p.setDryRun(dryRun);
        p.setMaxAmountPerOrderPerDay(600L);
        SuixintuiReinvestProperties.AdvertiserTier tier =
                new SuixintuiReinvestProperties.AdvertiserTier();
        tier.setName("测试档");
        tier.setMaxAmountPerAdvertiserPerDay(5_000L);
        tier.setAdvertiserIds(List.of(100L));
        p.setAdvertiserTiers(List.of(tier));
        p.setMaxAmountPerRound(1_000L);
        p.setMaxReinvestCountPerOrderPerDay(3);
        p.setCooldownMinutes(1L);
        p.setRecordStorePath(tempDir.resolve("records.jsonl"));
        return p;
    }

    private static Method reinvestOneMethod() throws NoSuchMethodException {
        Method m = ReinvestRound.class.getDeclaredMethod("reinvestOne",
                String.class, Long.class, CandidateOrder.class, long.class,
                SuixintuiOrderDetailService.class, SuixintuiReinvestService.class);
        m.setAccessible(true);
        return m;
    }

    /** admittedByRoas=true 的候选：productId=null，使用率路径不达标（statCost/budget=0.6），但靠 ROAS 纳入。 */
    private static CandidateOrder roasAdmittedCandidate(long orderId) {
        return new CandidateOrder(orderId, orderId + 1, null, "aweme" + orderId, "测试抖音名",
                2.0, 1.8, 120.0, null, 200L, 0.6, 0.6, true);
    }

    @Test
    @DisplayName("⑤ 真实模式：ROAS 纳入的候选照样调 addBudget 并落 DONE 流水（无分叉）")
    void roasAdmitted_realRun_goesThroughSamePipeline() throws Exception {
        SuixintuiReinvestProperties p = props(false);
        ReinvestRecordStore store = new ReinvestRecordStore(p.getRecordStorePath());
        ReinvestRound round = new ReinvestRound(p, store, snapshotStore());

        SuixintuiOrderDetailService detailService = mock(SuixintuiOrderDetailService.class);
        SuixintuiReinvestService reinvestService = mock(SuixintuiReinvestService.class);
        doNothing().when(reinvestService).addBudget(anyLong(), anyLong(), anyLong(), anyDouble());

        CandidateOrder c = roasAdmittedCandidate(900L);
        assertThat(c.admittedByRoas).isTrue();

        reinvestOneMethod().invoke(round, "round-roas", 100L, c, 0L, detailService, reinvestService);

        verify(reinvestService).addBudget(100L, 900L, p.getAmount(), p.getDeliveryTime());
        List<ReinvestRecord> all = store.all();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getOrderId()).isEqualTo(900L);
        assertThat(all.get(0).getStatus()).isEqualTo(ReinvestRecord.Status.DONE);
    }

    @Test
    @DisplayName("⑤ dry-run：ROAS 纳入的候选只打印计划，不调用、不落盘（与使用率路径一致）")
    void roasAdmitted_dryRun_doesNotCall() throws Exception {
        SuixintuiReinvestProperties p = props(true);
        ReinvestRecordStore store = new ReinvestRecordStore(p.getRecordStorePath());
        ReinvestRound round = new ReinvestRound(p, store, snapshotStore());

        SuixintuiOrderDetailService detailService = mock(SuixintuiOrderDetailService.class);
        SuixintuiReinvestService reinvestService = mock(SuixintuiReinvestService.class);

        CandidateOrder c = roasAdmittedCandidate(901L);

        reinvestOneMethod().invoke(round, "round-roas-dry", 100L, c, 0L, detailService, reinvestService);

        verify(reinvestService, never()).addBudget(anyLong(), anyLong(), anyLong(), anyDouble());
        assertThat(store.all()).isEmpty();
    }
}
