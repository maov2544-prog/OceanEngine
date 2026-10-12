package com.aolandai.oceanengine.report;

import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.storage.FinishedOrder;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecord;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecordStore;
import com.aolandai.oceanengine.suixintui.SuixintuiCreateOutcome;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderCreateService;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderParam;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10DataDeliverySettingBidType;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10DataDeliverySettingQcpxMode;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SuixintuiRebuildService} 单测：注入假的详情服务与建单服务，<b>绝不真建单、绝不真调接口</b>。
 *
 * <p>重点覆盖：幂等 / 每日上限跳过 / dry-run 语义 / 详情失败不写流水 / 限流停止 / null 字段跳过。</p>
 */
class SuixintuiRebuildServiceTest {

    private static final long ACC = 1779106144470100L;
    private static final long AWEME_UID = 7446391238175474745L;
    private static final long PRODUCT_ID = 3700528515221946525L;

    @TempDir
    Path tempDir;

    private SuixintuiOrderDetailService detail;
    private SuixintuiOrderCreateService create;
    private SuixintuiReinvestProperties reinvestProps;
    private SuixintuiRebuildProperties rebuildProps;

    /** 在投冲突预检的假列表拉取器：默认「无在投单」，各用例可覆盖 */
    private SuixintuiRebuildService.InFlightOrderFetcher inFlightFetcher;
    /** 记录每个账户被拉取列表的次数（断言「每账户只调一次」用） */
    private final List<Long> inFlightFetchCalls = new ArrayList<>();

    /** 记录被 create 调用时传入的参数，供断言 */
    private final List<SuixintuiOrderParam> createdParams = new ArrayList<>();

    @BeforeEach
    void setUp() {
        detail = mock(SuixintuiOrderDetailService.class);
        create = mock(SuixintuiOrderCreateService.class);
        reinvestProps = new SuixintuiReinvestProperties();
        rebuildProps = new SuixintuiRebuildProperties();
        createdParams.clear();
        inFlightFetchCalls.clear();
        // 默认预检器：返回空列表（无在投单），并记录调用
        inFlightFetcher = advertiserId -> {
            inFlightFetchCalls.add(advertiserId);
            return List.of();
        };
    }

    private SuixintuiRebuildRecordStore store() throws IOException {
        return new SuixintuiRebuildRecordStore(tempDir.resolve("rebuild-records.jsonl"));
    }

    private SuixintuiRebuildService service(SuixintuiRebuildRecordStore store) {
        return new SuixintuiRebuildService(
                rebuildProps, reinvestProps, store, detail, create, inFlightFetcher);
    }

    /** 造一个订单列表条目（用于预检返回的在投单） */
    private static QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner listOrder(
            long orderId, long productId, long awemeId, String statusValue) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o =
                new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner();
        o.setOrderId(orderId);
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo pi =
                new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo();
        pi.setProductId(productId);
        o.setProductInfo(pi);
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo ai =
                new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo();
        ai.setAwemeId(awemeId);
        o.setAwemeInfo(ai);
        o.setStatus(QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.fromValue(statusValue));
        return o;
    }

    /** 造一个「有量」的完成订单（8 参数齐全） */
    private static FinishedOrder order(long orderId) {
        return new FinishedOrder(orderId, ACC, "抖音名", "show_" + orderId,
                AWEME_UID, PRODUCT_ID, 2.0, "FINISHED",
                "2026-10-11 14:29", "2026-10-12 00:01:00");
    }

    /** 让详情服务返回一个完整的 delivery_setting */
    private void stubDetailOk() throws Exception {
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData data =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseData();
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting ds =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting();
        ds.setAmount(200L);
        ds.setDeliveryTime(24.0);
        ds.setBidType(QianchuanAwemeUniPromotionOrderDetailV10DataDeliverySettingBidType.MANUAL_BID);
        ds.setQcpxMode(QianchuanAwemeUniPromotionOrderDetailV10DataDeliverySettingQcpxMode.ON);
        ds.setRoiGoal(1.7);
        data.setDeliverySetting(ds);
        when(detail.getOrderDetail(anyLong(), anyLong())).thenReturn(data);
    }

    /** 让建单服务返回「真建成功」 */
    private void stubCreateSuccess() throws Exception {
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean()))
                .thenAnswer(inv -> {
                    SuixintuiOrderParam p = inv.getArgument(0);
                    boolean dryRun = inv.getArgument(1);
                    createdParams.add(p);
                    long newId = 8_000_000_000_000_000_000L + createdParams.size();
                    return new SuixintuiCreateOutcome(dryRun, dryRun ? null : newId, "{}", List.of());
                });
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("★ 真建模式：详情拿 5 参数 → 建单 → 写流水 → 返回「已复建」")
    void realRebuildWritesRecordAndReturnsStatus() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        assertThat(status).containsKey(111L);
        assertThat(status.get(111L).statusLabel()).isEqualTo("已复建");
        assertThat(status.get(111L).orderId()).isNotNull();
        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.size()).isEqualTo(1);

        // 参数照抄详情接口给的 5 个值 + 列表给的 3 个
        SuixintuiOrderParam p = createdParams.get(0);
        assertThat(p.advertiserId()).isEqualTo(ACC);
        assertThat(p.awemeId()).isEqualTo(AWEME_UID);
        assertThat(p.productId()).isEqualTo(PRODUCT_ID);
        assertThat(p.amount()).isEqualTo(200L);
        assertThat(p.deliveryTime()).isEqualTo(24.0);
        assertThat(p.bidType()).isEqualTo("MANUAL_BID");
        assertThat(p.qcpxMode()).isEqualTo("QCPX_MODE_ON");
        assertThat(p.roiGoal()).isEqualTo(1.7);
    }

    @Test
    @DisplayName("★ 幂等：同一原订单再次传入 → 不调详情/建单，仍返回「已复建」+ 从流水带出新订单ID")
    void idempotentSkipsAlreadyRebuilt() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();
        SuixintuiRebuildService svc = service(store);

        RebuildResult first = svc.rebuild(List.of(order(111L))).get(111L);
        Long firstNewOrderId = first.orderId();
        createdParams.clear();
        Map<Long, RebuildResult> second = svc.rebuild(List.of(order(111L)));

        // 幂等命中：状态仍是「已复建」，且新订单ID 从流水里取回（与第一次建成的一致）
        assertThat(second).containsKey(111L);
        assertThat(second.get(111L).statusLabel()).isEqualTo("已复建");
        assertThat(second.get(111L).orderId()).isEqualTo(firstNewOrderId);
        assertThat(createdParams).isEmpty();
        // 详情也不该再调第二次
        verify(detail, times(1)).getOrderDetail(anyLong(), anyLong());
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 每日上限：11 单、上限 10 → 只真建 10 单，第 11 单跳过（不是终止）")
    void dailyLimitSkipsExtraOrders() throws Exception {
        reinvestProps.setDryRun(false);
        rebuildProps.setMaxOrdersPerDay(10);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        List<FinishedOrder> orders = new ArrayList<>();
        for (long i = 1; i <= 11; i++) {
            orders.add(order(100L + i));
        }
        Map<Long, RebuildResult> status = service(store).rebuild(orders);

        assertThat(createdParams).hasSize(10);
        // 11 单都在返回值里（前 10 真建 + 第 11 超限），供日报写 F 列
        assertThat(status).hasSize(11);
        assertThat(store.size()).isEqualTo(10);
        // 第 11 单（111）超限跳过：日报 F 列「超每日上限」、G 列留空；不写流水、下次可重试
        assertThat(status).containsKey(111L);
        assertThat(status.get(111L).statusLabel()).isEqualTo("超每日上限");
        assertThat(status.get(111L).orderIdLabel()).isNull();
        assertThat(store.isRebuilt(111L)).isFalse();
    }

    @Test
    @DisplayName("★ dry-run：只组装请求体、不真建；写一条 dry-run 记录（不阻挡后续真建）")
    void dryRunDoesNotReallyCreateAndDoesNotBlock() throws Exception {
        reinvestProps.setDryRun(true);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        // dry-run 不算「已复建」→ 不返回状态（日报留空）
        assertThat(status).isEmpty();
        // 流水里只有一条 dry-run 记录，且不进入幂等索引
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.all().get(0).isDryRun()).isTrue();
        assertThat(store.isRebuilt(111L)).isFalse();

        // 之后再真建同一单：仍然会真建（dry-run 记录没挡住）
        reinvestProps.setDryRun(false);
        Map<Long, RebuildResult> second = service(store).rebuild(List.of(order(111L)));
        assertThat(second).containsKey(111L);
        assertThat(second.get(111L).orderId()).isNotNull();
        assertThat(store.isRebuilt(111L)).isTrue();
    }

    @Test
    @DisplayName("dry-run 不占每日上限：上限 1 时也不挡后续（因为没真建）")
    void dryRunDoesNotConsumeDailyLimit() throws Exception {
        reinvestProps.setDryRun(true);
        rebuildProps.setMaxOrdersPerDay(1);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L), order(222L)));

        // dry-run 不真建，两单都走到了建单（组装请求体）
        assertThat(createdParams).hasSize(2);
        assertThat(status).isEmpty();
    }

    // ==================== 跳过 / 失败路径 ====================

    @Test
    @DisplayName("★ 列表缺 awemeId/productId（为 null）→ 跳过该单、不调详情/建单、不写流水")
    void nullListFieldsAreSkipped() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        FinishedOrder missingAweme = new FinishedOrder(111L, ACC, "n", "s",
                null, PRODUCT_ID, 2.0, "FINISHED", "t", "ts");
        FinishedOrder missingProduct = new FinishedOrder(222L, ACC, "n", "s",
                AWEME_UID, null, 2.0, "FINISHED", "t", "ts");

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(missingAweme, missingProduct));

        // 日报 F 列「参数缺失」、G 列留空；不写流水
        assertThat(status).containsOnlyKeys(111L, 222L);
        assertThat(status.get(111L).statusLabel()).isEqualTo("参数缺失");
        assertThat(status.get(222L).statusLabel()).isEqualTo("参数缺失");
        assertThat(status.get(111L).orderIdLabel()).isNull();
        assertThat(createdParams).isEmpty();
        verify(detail, never()).getOrderDetail(anyLong(), anyLong());
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("★ 详情失败 → 跳过、不写流水（下次还能重试）")
    void detailFailureDoesNotWriteRecord() throws Exception {
        reinvestProps.setDryRun(false);
        when(detail.getOrderDetail(anyLong(), anyLong()))
                .thenThrow(new ApiException("详情接口炸了"));
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        // 日报 F 列「复建失败」、G 列留空；不写流水（下次还能重试）
        assertThat(status).containsKey(111L);
        assertThat(status.get(111L).statusLabel()).isEqualTo("复建失败");
        assertThat(status.get(111L).orderIdLabel()).isNull();
        assertThat(createdParams).isEmpty();
        assertThat(store.size()).isZero();
        assertThat(store.isRebuilt(111L)).isFalse();
    }

    @Test
    @DisplayName("★ 建单失败 → 不写流水（下次还能重试）、继续处理下一条")
    void createFailureDoesNotWriteRecordAndContinues() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean()))
                .thenAnswer(inv -> {
                    SuixintuiOrderParam p = inv.getArgument(0);
                    // 第一单失败，第二单成功
                    if (createdParams.isEmpty()) {
                        createdParams.add(p);
                        throw new ApiException("建单被拒");
                    }
                    createdParams.add(p);
                    return new SuixintuiCreateOutcome(false, 999L, "{}", List.of());
                });
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L), order(222L)));

        // 第 1 单建单失败：F 列「复建失败」、G 列留空；第 2 单成功
        assertThat(status).containsKey(111L).containsKey(222L);
        assertThat(status.get(111L).statusLabel()).isEqualTo("复建失败");
        assertThat(status.get(111L).orderIdLabel()).isNull();
        assertThat(status.get(222L).statusLabel()).isEqualTo("已复建");
        assertThat(status.get(222L).orderIdLabel()).isEqualTo("999");
        assertThat(store.isRebuilt(111L)).isFalse();
        assertThat(store.isRebuilt(222L)).isTrue();
    }

    @Test
    @DisplayName("★ 详情被限流 → 立即停止本轮（后续订单不再处理）")
    void detailRateLimitStopsRound() throws Exception {
        reinvestProps.setDryRun(false);
        when(detail.getOrderDetail(anyLong(), anyLong()))
                .thenThrow(new RateLimitException(RateLimitException.Scope.DEVELOPER,
                        "详情被开发者频控", 0L));
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store)
                .rebuild(List.of(order(111L), order(222L), order(333L)));

        assertThat(status).isEmpty();
        // 立刻停止：只对第一单尝试过详情
        verify(detail, times(1)).getOrderDetail(anyLong(), anyLong());
        assertThat(createdParams).isEmpty();
    }

    @Test
    @DisplayName("★ 建单被限流 → 立即停止本轮（已建成的保留在流水里）")
    void createRateLimitStopsRoundButKeepsBuilt() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean()))
                .thenAnswer(inv -> {
                    if (createdParams.isEmpty()) {
                        createdParams.add(inv.getArgument(0));
                        // 第一单先成功
                        return new SuixintuiCreateOutcome(false, 999L, "{}", List.of());
                    }
                    throw new RateLimitException(RateLimitException.Scope.DEVELOPER,
                            "建单被频控", 0L);
                });
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L), order(222L)));

        assertThat(status).containsKey(111L).doesNotContainKey(222L);
        assertThat(status.get(111L).orderIdLabel()).isEqualTo("999");
        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.isRebuilt(222L)).isFalse();
    }

    @Test
    @DisplayName("详情 delivery_setting 为空 / 缺 amount → 跳过该单")
    void nullDeliverySettingIsSkipped() throws Exception {
        reinvestProps.setDryRun(false);
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData data =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseData();
        when(detail.getOrderDetail(anyLong(), anyLong())).thenReturn(data);
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        // F 列「参数缺失」（详情缺 amount）、G 列留空；不写流水
        assertThat(status).containsKey(111L);
        assertThat(status.get(111L).statusLabel()).isEqualTo("参数缺失");
        assertThat(status.get(111L).orderIdLabel()).isNull();
        assertThat(createdParams).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("空输入：返回空 Map、不调任何接口")
    void emptyInputIsNoop() throws Exception {
        SuixintuiRebuildRecordStore store = store();
        assertThat(service(store).rebuild(List.of())).isEmpty();
        assertThat(service(store).rebuild(null)).isEmpty();
        verify(detail, never()).getOrderDetail(anyLong(), anyLong());
    }

    // ==================== 每日上限跨实例（持久化） ====================

    @Test
    @DisplayName("★ 每日上限跨重启：昨日/今日已真建的记录计入上限（重启后继续受限）")
    void dailyLimitCountsPersistedRecords() throws Exception {
        reinvestProps.setDryRun(false);
        rebuildProps.setMaxOrdersPerDay(2);
        stubDetailOk();
        stubCreateSuccess();
        Path file = tempDir.resolve("rebuild-records.jsonl");

        // 先跑一轮建 2 单（达到上限）
        SuixintuiRebuildRecordStore store1 = new SuixintuiRebuildRecordStore(file);
        service(store1).rebuild(List.of(order(111L), order(222L)));
        assertThat(store1.effectiveCountOn(LocalDate.now())).isEqualTo(2);

        // 模拟重启后再来 1 单：已达上限 → 跳过（日报仍显示「超每日上限」）
        SuixintuiRebuildRecordStore store2 = new SuixintuiRebuildRecordStore(file);
        createdParams.clear();
        Map<Long, RebuildResult> status = service(store2).rebuild(List.of(order(333L)));

        assertThat(status).containsKey(333L);
        assertThat(status.get(333L).statusLabel()).isEqualTo("超每日上限");
        assertThat(createdParams).isEmpty();
        assertThat(store2.isRebuilt(333L)).isFalse();
    }

    @Test
    @DisplayName("★ 幂等跨重启：重启后再次传入已复建的原单 → 状态「已复建」+ 新订单ID 从流水取回")
    void idempotentAcrossRestartReturnsPersistedNewOrderId() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        Path file = tempDir.resolve("rebuild-records.jsonl");

        // 第一轮：真建 111 → 拿到新订单号
        SuixintuiRebuildRecordStore store1 = new SuixintuiRebuildRecordStore(file);
        Long builtNewOrderId = service(store1).rebuild(List.of(order(111L))).get(111L).orderId();
        assertThat(builtNewOrderId).isNotNull();

        // 模拟重启：新实例从磁盘读回流水
        SuixintuiRebuildRecordStore store2 = new SuixintuiRebuildRecordStore(file);
        createdParams.clear();
        Map<Long, RebuildResult> status = service(store2).rebuild(List.of(order(111L)));

        // 不重复建单，但日报仍能显示「已复建」+ 那个新订单号
        assertThat(createdParams).isEmpty();
        assertThat(status).containsKey(111L);
        assertThat(status.get(111L).statusLabel()).isEqualTo("已复建");
        assertThat(status.get(111L).orderId()).isEqualTo(builtNewOrderId);
    }

    // ==================== 在投冲突预检 ====================

    @Test
    @DisplayName("★ 预检：同（商品+达人）组合已有在投单 → 跳过、不建单；写 SKIPPED_INFLIGHT 流水（永久标记）")
    void inflightConflictSkipsOrder() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        // 账户 ACC 有一张「投放中」（DELIVERY_OK）的单 999，product/aweme 与目标单一致
        inFlightFetcher = advertiserId -> {
            inFlightFetchCalls.add(advertiserId);
            return List.of(listOrder(999L, PRODUCT_ID, AWEME_UID, "DELIVERY_OK"));
        };
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        // 不建单
        assertThat(createdParams).isEmpty();
        verify(create, never()).create(any(SuixintuiOrderParam.class), anyBoolean());
        // 详情也不该调（冲突在建单前就跳过了）
        verify(detail, never()).getOrderDetail(anyLong(), anyLong());
        // 写了一条 SKIPPED_INFLIGHT 流水（永久标记）；isRebuilt=true 后续不再尝试
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.findEffective(111L).getConflictOrderId()).isEqualTo(999L);
        // 返回值：F 列「已有投放中订单」、G 列 = 冲突订单号
        assertThat(status).containsKey(111L);
        assertThat(status.get(111L).statusLabel()).isEqualTo("已有投放中订单");
        assertThat(status.get(111L).orderIdLabel()).isEqualTo("999");
    }

    @Test
    @DisplayName("★ 预检：在投冲突跳过后【再次传入同一原单】→ 幂等命中，仍报冲突单号、不再建单")
    void inflightConflictIsPermanentAcrossRuns() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        inFlightFetcher = advertiserId -> {
            inFlightFetchCalls.add(advertiserId);
            return List.of(listOrder(999L, PRODUCT_ID, AWEME_UID, "DELIVERY_OK"));
        };
        SuixintuiRebuildRecordStore store = store();
        SuixintuiRebuildService svc = service(store);

        svc.rebuild(List.of(order(111L)));   // 第一轮：冲突 → 写流水
        createdParams.clear();
        Map<Long, RebuildResult> second = svc.rebuild(List.of(order(111L)));   // 第二轮

        // 永久标记：第二轮不建单，且日报仍能显示「已有投放中订单」+ 冲突单号
        assertThat(createdParams).isEmpty();
        assertThat(store.size()).isEqualTo(1);   // 不再追加流水
        assertThat(second.get(111L).statusLabel()).isEqualTo("已有投放中订单");
        assertThat(second.get(111L).orderIdLabel()).isEqualTo("999");
    }

    @Test
    @DisplayName("★ 预检：组合无在投单 → 正常建单")
    void noInflightConflictBuildsNormally() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        inFlightFetcher = advertiserId -> List.of();   // 无在投单
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        assertThat(createdParams).hasSize(1);
        assertThat(status).containsKey(111L);
        assertThat(store.isRebuilt(111L)).isTrue();
    }

    @Test
    @DisplayName("★ 预检：仅 productId 相同、awemeId 不同 → 不跳过（双字段 AND）")
    void inflightSameProductDifferentAwemeDoesNotSkip() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        // 在投单：product 相同，但 aweme 不同 → 不算冲突
        inFlightFetcher = advertiserId -> List.of(
                listOrder(999L, PRODUCT_ID, AWEME_UID + 1, "DELIVERY_OK"));
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        assertThat(createdParams).hasSize(1);
        assertThat(status).containsKey(111L);
    }

    @Test
    @DisplayName("★ 预检：仅 awemeId 相同、productId 不同 → 不跳过（双字段 AND）")
    void inflightSameAwemeDifferentProductDoesNotSkip() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        inFlightFetcher = advertiserId -> List.of(
                listOrder(999L, PRODUCT_ID + 1, AWEME_UID, "DELIVERY_OK"));
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        assertThat(createdParams).hasSize(1);
        assertThat(status).containsKey(111L);
    }

    @Test
    @DisplayName("★ 预检：FROZEN（人工结束残留）不算在投 → 不跳过")
    void inflightFrozenDoesNotSkip() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        // FROZEN 明确计入终态（实测同组合 23 天前的 FROZEN 单不阻挡后续建单）
        inFlightFetcher = advertiserId -> List.of(
                listOrder(999L, PRODUCT_ID, AWEME_UID, "FROZEN"));
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        assertThat(createdParams).hasSize(1);
        assertThat(status).containsKey(111L);
    }

    @Test
    @DisplayName("★ 预检：源订单自己（同 orderId）不算冲突（它在在投集合里也是 FINISHED）")
    void inflightIgnoresSelfSourceOrder() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        // 列表里返回源订单自己（orderId=111），状态 FINISHED（终态）→ 不应判为在投
        inFlightFetcher = advertiserId -> List.of(
                listOrder(111L, PRODUCT_ID, AWEME_UID, "FINISHED"));
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        assertThat(createdParams).hasSize(1);
        assertThat(status).containsKey(111L);
    }

    @Test
    @DisplayName("★ 预检：批量预检每账户只调一次列表接口（防 N 次请求退化）")
    void inflightPrecheckFetchesOncePerAdvertiser() throws Exception {
        reinvestProps.setDryRun(false);
        rebuildProps.setMaxOrdersPerDay(10);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        // 同一账户 5 单 → 预检应只拉 1 次（不是 5 次）
        List<FinishedOrder> orders = new ArrayList<>();
        for (long i = 1; i <= 5; i++) {
            orders.add(order(100L + i));
        }
        Map<Long, RebuildResult> status = service(store).rebuild(orders);

        // 断言调用次数 = 账户数 = 1（而非订单数 5）
        assertThat(inFlightFetchCalls).containsExactly(ACC);
        assertThat(createdParams).hasSize(5);
        assertThat(status).hasSize(5);
    }

    @Test
    @DisplayName("★ 预检：多账户 → 每账户各调一次（2 账户 = 2 次，不随订单数膨胀）")
    void inflightPrecheckFetchesOncePerEachAdvertiser() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        long acc2 = ACC + 1;
        FinishedOrder a1 = order(111L);
        FinishedOrder a2 = new FinishedOrder(222L, acc2, "n", "s",
                AWEME_UID, PRODUCT_ID, 2.0, "FINISHED", "t", "ts");
        FinishedOrder a3 = new FinishedOrder(333L, acc2, "n", "s",
                AWEME_UID, PRODUCT_ID, 2.0, "FINISHED", "t", "ts");

        service(store).rebuild(List.of(a1, a2, a3));

        // 2 个账户 → 2 次拉取（账户内多单不重复拉）
        assertThat(inFlightFetchCalls).containsExactlyInAnyOrder(ACC, acc2);
        assertThat(createdParams).hasSize(3);
    }

    @Test
    @DisplayName("★ 预检：拉取非限流异常 → 放行、仍尝试建单（预检是优化非正确性必需）")
    void inflightFetchFailureFallsThroughAndBuilds() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        inFlightFetcher = advertiserId -> {
            inFlightFetchCalls.add(advertiserId);
            throw new ApiException("列表接口炸了");
        };
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        // 放行：照常建单
        assertThat(createdParams).hasSize(1);
        assertThat(status).containsKey(111L);
        assertThat(store.isRebuilt(111L)).isTrue();
    }

    // ==================== 日报 F/G 两列语义（5 种 F 值 + G 值） ====================

    @Test
    @DisplayName("★ 日报两列：一轮内同时覆盖 成功/在投冲突/超上限/参数缺失/建单失败 五种 F 值 + G 值")
    void reportColumnsCoverAllFiveStatuses() throws Exception {
        reinvestProps.setDryRun(false);
        rebuildProps.setMaxOrdersPerDay(1);   // 只允许真建 1 单

        // 详情：222（参数缺失——缺 amount）走特殊返回；其余返回完整 delivery_setting
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData ok =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseData();
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting ds =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting();
        ds.setAmount(200L);
        ds.setDeliveryTime(24.0);
        ds.setBidType(QianchuanAwemeUniPromotionOrderDetailV10DataDeliverySettingBidType.MANUAL_BID);
        ds.setQcpxMode(QianchuanAwemeUniPromotionOrderDetailV10DataDeliverySettingQcpxMode.ON);
        ds.setRoiGoal(1.7);
        ok.setDeliverySetting(ds);
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData noAmount =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseData();
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting dsNoAmount =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting();
        dsNoAmount.setDeliveryTime(24.0);   // 故意不设 amount
        noAmount.setDeliverySetting(dsNoAmount);
        when(detail.getOrderDetail(anyLong(), anyLong())).thenReturn(ok);
        when(detail.getOrderDetail(eq(222L), anyLong())).thenReturn(noAmount);

        // 建单：只有 PRODUCT_ID（111）成功，其余 productId 一律被拒（业务失败）
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean()))
                .thenAnswer(inv -> {
                    SuixintuiOrderParam p = inv.getArgument(0);
                    createdParams.add(p);
                    if (p.productId() == PRODUCT_ID) {
                        return new SuixintuiCreateOutcome(false, 777L, "{}", List.of());
                    }
                    throw new ApiException("建单被拒");
                });

        // 预检：账户 ACC 只有「PRODUCT_ID+9」这个组合在投（单 555），只应命中 444
        long conflictProduct = PRODUCT_ID + 9;
        inFlightFetcher = advertiserId -> {
            inFlightFetchCalls.add(advertiserId);
            return List.of(listOrder(555L, conflictProduct, AWEME_UID, "DELIVERY_OK"));
        };
        SuixintuiRebuildRecordStore store = store();

        FinishedOrder ok111 = order(111L);       // 成功（唯一真建额度）
        FinishedOrder invalidOrder = new FinishedOrder(222L, ACC, "n", "s", AWEME_UID,
                PRODUCT_ID + 3, 2.0, "FINISHED", "t", "ts");   // 参数缺失
        FinishedOrder failed = new FinishedOrder(333L, ACC, "n", "s", AWEME_UID,
                PRODUCT_ID + 1, 2.0, "FINISHED", "t", "ts");   // 建单失败
        FinishedOrder conflictOrder = new FinishedOrder(444L, ACC, "n", "s", AWEME_UID,
                conflictProduct, 2.0, "FINISHED", "t", "ts");  // 命中在投冲突
        FinishedOrder limitOrder = new FinishedOrder(666L, ACC, "n", "s", AWEME_UID,
                PRODUCT_ID + 2, 2.0, "FINISHED", "t", "ts");   // 超上限

        // 顺序很关键：先 参数缺失(222) 与 建单失败(333)（此时尚未占用每日额度），
        // 再 成功(111)（用掉唯一额度），最后 冲突(444)/超上限(666)。
        Map<Long, RebuildResult> status = service(store)
                .rebuild(List.of(invalidOrder, failed, ok111, conflictOrder, limitOrder));

        // ① 成功：F 已复建、G 新订单号
        assertThat(status.get(111L).statusLabel()).isEqualTo("已复建");
        assertThat(status.get(111L).orderIdLabel()).isEqualTo("777");
        // ② 在投冲突：F 已有投放中订单、G 冲突订单号
        assertThat(status.get(444L).statusLabel()).isEqualTo("已有投放中订单");
        assertThat(status.get(444L).orderIdLabel()).isEqualTo("555");
        // ③ 参数缺失：F 参数缺失、G 留空
        assertThat(status.get(222L).statusLabel()).isEqualTo("参数缺失");
        assertThat(status.get(222L).orderIdLabel()).isNull();
        // ④ 建单失败：F 复建失败、G 留空
        assertThat(status.get(333L).statusLabel()).isEqualTo("复建失败");
        assertThat(status.get(333L).orderIdLabel()).isNull();
        // ⑤ 超上限：F 超每日上限、G 留空
        assertThat(status.get(666L).statusLabel()).isEqualTo("超每日上限");
        assertThat(status.get(666L).orderIdLabel()).isNull();

        // 流水：只有成功(1) + 冲突(1) 两条；失败/超限/参数缺失都不写
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.isRebuilt(444L)).isTrue();
        assertThat(store.isRebuilt(222L)).isFalse();
        assertThat(store.isRebuilt(333L)).isFalse();
        assertThat(store.isRebuilt(666L)).isFalse();
        // 每日真复建计数只算成功那条（冲突不算额度）
        assertThat(store.effectiveCountOn(LocalDate.now())).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 日报两列：成功后的新订单号必须写进 G 列（orderIdLabel = 新单号字符串）")
    void rebuiltOrderIdLabelIsNewOrderId() throws Exception {
        reinvestProps.setDryRun(false);
        stubDetailOk();
        stubCreateSuccess();
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        assertThat(status.get(111L).statusLabel()).isEqualTo("已复建");
        assertThat(status.get(111L).orderIdLabel()).isEqualTo(String.valueOf(status.get(111L).orderId()));
    }
}
