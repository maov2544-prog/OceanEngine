package com.aolandai.oceanengine.scheduler;

import com.aolandai.oceanengine.client.ApiErrors;
import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.domain.SuixintuiReinvestCandidateSelector.CandidateOrder;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.service.suixintui.SuixintuiReinvestService;
import com.aolandai.oceanengine.storage.ConsumeSnapshotStore;
import com.aolandai.oceanengine.storage.ReinvestRecord;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for the single-order reinvest execution logic (reinvestOne).
 *
 * Approach: reinvestOne is a private helper inside ReinvestRound; tests invoke it via reflection
 * and assert side-effects (calls to SuixintuiReinvestService and record store state).
 */
public class ReinvestRoundTest {

    @TempDir
    Path tempDir;

    /** 每个测试用例一份独立的消耗快照存储（tempDir 下），避免用例之间相互干扰 */
    private ConsumeSnapshotStore snapshotStore() throws IOException {
        return new ConsumeSnapshotStore(tempDir.resolve("consume-snapshot.jsonl"), 7);
    }

    @Test
    void reinvestOne_dryRun_doesNotCallAddBudget() throws Exception {
        // Arrange
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.setAmount(200L);
        props.setDryRun(true);
        props.setMaxAmountPerOrderPerDay(600L);
        props.setAdvertiserTiers(List.of(tier(5_000L, 100L)));
        props.setMaxAmountPerRound(1_000L);
        props.setMaxReinvestCountPerOrderPerDay(3);
        props.setCooldownMinutes(60L);
        props.setRecordStorePath(tempDir.resolve("records.jsonl"));

        ReinvestRecordStore store = new ReinvestRecordStore(props.getRecordStorePath());
        ReinvestRound round = new ReinvestRound(props, store, snapshotStore());

        SuixintuiOrderDetailService detailService = mock(SuixintuiOrderDetailService.class);
        SuixintuiReinvestService reinvestService = mock(SuixintuiReinvestService.class);

        CandidateOrder c = new CandidateOrder(123L, 456L, null, "aweme123", "测试抖音名A",
                2.5, 1.8, 50.0, null, 200L, 0.25, 0.5);

        // Act: invoke private method reinvestOne
        Method m = ReinvestRound.class.getDeclaredMethod("reinvestOne",
                String.class, Long.class, CandidateOrder.class, long.class,
                SuixintuiOrderDetailService.class, SuixintuiReinvestService.class);
        m.setAccessible(true);
        Object outcome = m.invoke(round, "round-1", 100L, c, 0L, detailService, reinvestService);

        // Assert: reinvestService.addBudget should NOT be called in dry-run
        verify(reinvestService, never()).addBudget(anyLong(), anyLong(), anyLong(), anyDouble());

        // Also ensure no records were persisted (dry-run doesn't persist PENDING)
        List<ReinvestRecord> all = store.all();
        assertThat(all).isEmpty();
    }

    @Test
    void reinvestOne_realRun_successfulCallsAddBudgetAndMarksDone() throws Exception {
        // Arrange
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.setAmount(100L);
        props.setDryRun(false);
        props.setMaxAmountPerOrderPerDay(600L);
        props.setAdvertiserTiers(List.of(tier(5_000L, 100L)));
        props.setMaxAmountPerRound(1_000L);
        props.setMaxReinvestCountPerOrderPerDay(3);
        props.setCooldownMinutes(1L);
        props.setRecordStorePath(tempDir.resolve("records.jsonl"));

        ReinvestRecordStore store = new ReinvestRecordStore(props.getRecordStorePath());
        ReinvestRound round = new ReinvestRound(props, store, snapshotStore());

        SuixintuiOrderDetailService detailService = mock(SuixintuiOrderDetailService.class);
        SuixintuiReinvestService reinvestService = mock(SuixintuiReinvestService.class);
        doNothing().when(reinvestService).addBudget(anyLong(), anyLong(), anyLong(), anyDouble());

        CandidateOrder c = new CandidateOrder(200L, 456L, null, "aweme200", "测试抖音名B",
                2.5, 1.8, 50.0, null, 200L, 0.25, 0.5);

        // Act
        Method m = ReinvestRound.class.getDeclaredMethod("reinvestOne",
                String.class, Long.class, CandidateOrder.class, long.class,
                SuixintuiOrderDetailService.class, SuixintuiReinvestService.class);
        m.setAccessible(true);
        Object outcome = m.invoke(round, "round-2", 100L, c, 0L, detailService, reinvestService);

        // Assert: reinvestService.addBudget should be called once
        verify(reinvestService).addBudget(100L, 200L, props.getAmount(), props.getDeliveryTime());

        // And the store should contain a DONE record for this order
        List<ReinvestRecord> all = store.all();
        assertThat(all).hasSize(1);
        ReinvestRecord r = all.get(0);
        assertThat(r.getOrderId()).isEqualTo(200L);
        assertThat(r.getStatus()).isEqualTo(ReinvestRecord.Status.DONE);

        // committed amount on today should include the amount
        long committed = store.committedAmountOn(LocalDate.now(), 100L, 200L);
        assertThat(committed).isEqualTo(props.getAmount());
    }

    /** 构造一个档次：单账户日限额 cap，包含给定账户。 */
    private static SuixintuiReinvestProperties.AdvertiserTier tier(long cap, Long... ids) {
        SuixintuiReinvestProperties.AdvertiserTier tier =
                new SuixintuiReinvestProperties.AdvertiserTier();
        tier.setName("测试档");
        tier.setMaxAmountPerAdvertiserPerDay(cap);
        tier.setAdvertiserIds(List.of(ids));
        return tier;
    }

    // ==================== 限流处理 ====================

    private static SuixintuiReinvestProperties realRunProps(Path recordPath) {
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.setAmount(100L);
        props.setDryRun(false);
        props.setMaxAmountPerOrderPerDay(600L);
        props.setAdvertiserTiers(List.of(tier(5_000L, 100L)));
        props.setMaxAmountPerRound(1_000L);
        props.setMaxReinvestCountPerOrderPerDay(3);
        props.setCooldownMinutes(1L);
        props.setRecordStorePath(recordPath);
        return props;
    }

    private static Method reinvestOneMethod() throws NoSuchMethodException {
        Method m = ReinvestRound.class.getDeclaredMethod("reinvestOne",
                String.class, Long.class, CandidateOrder.class, long.class,
                SuixintuiOrderDetailService.class, SuixintuiReinvestService.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void reinvestOne_whenAddBudgetIsRateLimited_marksFailedAndPropagates() throws Exception {
        // Arrange：加预算被 40110（开发者频控）拦截
        SuixintuiReinvestProperties props = realRunProps(tempDir.resolve("records.jsonl"));
        ReinvestRecordStore store = new ReinvestRecordStore(props.getRecordStorePath());
        ReinvestRound round = new ReinvestRound(props, store, snapshotStore());

        SuixintuiOrderDetailService detailService = mock(SuixintuiOrderDetailService.class);
        SuixintuiReinvestService reinvestService = mock(SuixintuiReinvestService.class);
        RateLimitException rateLimit =
                ApiErrors.rateLimitOrNull(40110L, "随心推全域订单加预算", "req-rl");
        doThrow(rateLimit).when(reinvestService)
                .addBudget(anyLong(), anyLong(), anyLong(), anyDouble());

        CandidateOrder c = new CandidateOrder(300L, 456L, null, "aweme300", "测试抖音名C",
                2.5, 1.8, 50.0, null, 200L, 0.25, 0.5);

        // Act
        InvocationTargetException thrown = catchThrowableOfType(
                () -> reinvestOneMethod().invoke(round, "round-rl", 100L, c, 0L,
                        detailService, reinvestService),
                InvocationTargetException.class);

        // Assert：必须上抛（供轮次层中止本轮），而不是被当成普通失败吞掉
        assertThat(thrown).isNotNull();
        assertThat(thrown.getCause()).isInstanceOf(RateLimitException.class);

        // 且必须记 FAILED —— 限流请求被网关直接拒绝、没有真正扣费。
        // 记 UNKNOWN 会被「重启校验」永久拦下，该订单就再也不会被追投。
        List<ReinvestRecord> all = store.all();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getOrderId()).isEqualTo(300L);
        assertThat(all.get(0).getStatus())
                .as("限流导致的失败必须记 FAILED，不能记 UNKNOWN")
                .isEqualTo(ReinvestRecord.Status.FAILED);
        assertThat(all.get(0).getStatus()).isNotEqualTo(ReinvestRecord.Status.UNKNOWN);
    }

    @Test
    void reinvestOne_whenPendingCheckIsRateLimited_propagatesInsteadOfBlockingOrder()
            throws Exception {
        // Arrange：该订单有遗留 PENDING 记录，重启校验要调详情接口 —— 而详情接口被限流
        SuixintuiReinvestProperties props = realRunProps(tempDir.resolve("records.jsonl"));
        ReinvestRecordStore store = new ReinvestRecordStore(props.getRecordStorePath());
        ReinvestRound round = new ReinvestRound(props, store, snapshotStore());

        store.markPending("round-old", 100L, 400L, 100L, 24.0, 200L, "aweme400", "测试抖音名D");

        SuixintuiOrderDetailService detailService = mock(SuixintuiOrderDetailService.class);
        SuixintuiReinvestService reinvestService = mock(SuixintuiReinvestService.class);
        RateLimitException rateLimit =
                ApiErrors.rateLimitOrNull(40110L, "随心推全域订单详情 order_id=400", "req-d");
        doThrow(rateLimit).when(detailService).getTotalBudget(anyLong(), anyLong());

        CandidateOrder c = new CandidateOrder(400L, 456L, null, "aweme400", "测试抖音名D",
                2.5, 1.8, 50.0, null, 200L, 0.25, 0.5);

        // Act
        InvocationTargetException thrown = catchThrowableOfType(
                () -> reinvestOneMethod().invoke(round, "round-rl2", 100L, c, 0L,
                        detailService, reinvestService),
                InvocationTargetException.class);

        // Assert：限流不能被当成「未决记录校验失败」而返回 SKIPPED_UNRESOLVED
        // （那会把订单永久锁死），必须上抛。
        assertThat(thrown).isNotNull();
        assertThat(thrown.getCause()).isInstanceOf(RateLimitException.class);
    }
}
