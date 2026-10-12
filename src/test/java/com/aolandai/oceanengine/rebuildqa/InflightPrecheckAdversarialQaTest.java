package com.aolandai.oceanengine.rebuildqa;

import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.report.RebuildResult;
import com.aolandai.oceanengine.report.SuixintuiRebuildProperties;
import com.aolandai.oceanengine.report.SuixintuiRebuildService;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.storage.FinishedOrder;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecord;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecordStore;
import com.aolandai.oceanengine.suixintui.SuixintuiCreateOutcome;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderCreateService;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderParam;
import com.bytedance.ads.ApiException;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>独立证伪测试</b>（QA 严过关）—— 「复建前在投冲突预检」专项。
 *
 * <p>目标：不复述工程师已有预检用例，而是从<b>多账户限流、双字段 null 的旁路、
 * 幂等与冲突叠加、冲突永久标记、预检失败后撞 40000、未注入预检器</b>等角度去证伪。</p>
 *
 * <p>⚠️ 全部假 service 注入，<b>绝不真建单、绝不真调接口</b>；不改 src/main。</p>
 *
 * <h2>新口径（老板 2026-10 拍板）</h2>
 * <p>在投冲突跳过 → <b>写流水永久标记</b>（{@code isRebuilt==true}，后续永不再试），
 * F 列「已有投放中订单」、G 列 = 冲突订单号；且<b>不占每日复建额度</b>。</p>
 */
class InflightPrecheckAdversarialQaTest {

    private static final long ACC = 1779106144470100L;
    private static final long ACC2 = 1823379540880396L;
    private static final long AWEME_UID = 7446391238175474745L;
    private static final long PRODUCT_ID = 3700528515221946525L;

    @TempDir
    Path tempDir;

    private SuixintuiOrderDetailService detail;
    private SuixintuiOrderCreateService create;
    private SuixintuiReinvestProperties reinvestProps;
    private SuixintuiRebuildProperties rebuildProps;

    private final List<Long> fetchCalls = new ArrayList<>();
    private final List<SuixintuiOrderParam> createdParams = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        detail = mock(SuixintuiOrderDetailService.class);
        create = mock(SuixintuiOrderCreateService.class);
        reinvestProps = new SuixintuiReinvestProperties();
        reinvestProps.setDryRun(false);
        rebuildProps = new SuixintuiRebuildProperties();
        fetchCalls.clear();
        createdParams.clear();
        storeSeq.set(0);

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

        when(create.create(any(SuixintuiOrderParam.class), anyBoolean())).thenAnswer(inv -> {
            createdParams.add(inv.getArgument(0));
            return new SuixintuiCreateOutcome(false,
                    8_000_000_000_000_000_000L + createdParams.size(), "{}", List.of());
        });
    }

    private final AtomicInteger storeSeq = new AtomicInteger();

    private SuixintuiRebuildRecordStore store() throws Exception {
        // ⚠️ 每次给不同文件名：避免循环用例里跨迭代共享幂等流水（会让第 2 单因幂等被跳过，
        //    从而把「状态判定」用例污染成假失败）。
        return new SuixintuiRebuildRecordStore(
                tempDir.resolve("q-" + storeSeq.incrementAndGet() + ".jsonl"));
    }

    private SuixintuiRebuildService service(SuixintuiRebuildRecordStore s,
                                            SuixintuiRebuildService.InFlightOrderFetcher f) {
        return new SuixintuiRebuildService(rebuildProps, reinvestProps, s, detail, create, f);
    }

    private static QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner listOrder(
            long orderId, Long productId, Long awemeId, String status) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o =
                new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner();
        o.setOrderId(orderId);
        if (productId != null) {
            QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo pi =
                    new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo();
            pi.setProductId(productId);
            o.setProductInfo(pi);
        }
        if (awemeId != null) {
            QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo ai =
                    new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo();
            ai.setAwemeId(awemeId);
            o.setAwemeInfo(ai);
        }
        if (status != null) {
            o.setStatus(QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.fromValue(status));
        }
        return o;
    }

    private static FinishedOrder order(long orderId, long acc, Long awemeId, Long productId) {
        return new FinishedOrder(orderId, acc, "抖音名", "show_" + orderId,
                awemeId, productId, 2.0, "FINISHED", "2026-10-11 14:29", "2026-10-12 00:01:00");
    }

    // ==================================================================================
    // ① 命中在投冲突（核心正向 / 新口径：写流水永久标记、G=冲突单号）
    // ==================================================================================

    @Test
    @DisplayName("★证伪1：命中在投（DELIVERY_OK）→ 不建单、不调详情，但**写流水永久标记**、F=已有投放中订单、G=冲突单号")
    void conflictSkipsBeforeDetailAndCreate() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        SuixintuiRebuildService svc = service(s, acc -> {
            fetchCalls.add(acc);
            return List.of(listOrder(999L, PRODUCT_ID, AWEME_UID, "DELIVERY_OK"));
        });

        Map<Long, RebuildResult> status = svc.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));

        // 日报两列：F=已有投放中订单、G=冲突单号
        RebuildResult r = status.get(111L);
        assertThat(r.status()).isEqualTo(RebuildResult.Status.SKIPPED_INFLIGHT);
        assertThat(r.statusLabel()).isEqualTo("已有投放中订单");
        assertThat(r.orderIdLabel()).isEqualTo("999");
        // 不建单、不调详情
        assertThat(createdParams).isEmpty();
        org.mockito.Mockito.verify(detail, org.mockito.Mockito.never())
                .getOrderDetail(anyLong(), anyLong());
        // ★ 新口径关键：写流水永久标记 → isRebuilt==true
        assertThat(s.isRebuilt(111L)).isTrue();
        assertThat(s.size()).isEqualTo(1);
        SuixintuiRebuildRecord rec = s.findEffective(111L);
        assertThat(rec.effectiveResult()).isEqualTo(SuixintuiRebuildRecord.RESULT_SKIPPED_INFLIGHT);
        assertThat(rec.getConflictOrderId()).isEqualTo(999L);
        assertThat(rec.getNewOrderId()).isNull();
        assertThat(rec.isSettled()).isTrue();
        // 冲突不占每日额度
        assertThat(s.effectiveCountOn(java.time.LocalDate.now())).isZero();
    }

    @Test
    @DisplayName("★证伪1b：在投冲突是**永久**的 —— 第二轮再传同一原单 → 走幂等、不重新预检/建单、仍显示冲突单号")
    void conflictIsPermanentAcrossRuns() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        // 第一轮：命中冲突
        service(s, acc -> List.of(listOrder(999L, PRODUCT_ID, AWEME_UID, "DELIVERY_OK")))
                .rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));
        assertThat(s.isRebuilt(111L)).isTrue();

        // 第二轮：即便此时预检已「无在投」（fetcher 返回空），也绝不再尝试建单
        createdParams.clear();
        SuixintuiRebuildService svc2 = service(s, acc -> List.of());
        Map<Long, RebuildResult> status2 = svc2.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));

        assertThat(status2.get(111L).status()).isEqualTo(RebuildResult.Status.SKIPPED_INFLIGHT);
        assertThat(status2.get(111L).orderIdLabel()).isEqualTo("999");
        assertThat(createdParams).isEmpty(); // 永久标记 → 永不再建
    }

    // ==================================================================================
    // ③ 双字段 null 的旁路：不会「静默跳过预检去建单」
    // ==================================================================================

    @Test
    @DisplayName("★证伪3a：源单 productId 为 null → 预检旁路但它仍被 validateListFields 拦下（F=参数缺失、绝不建单）")
    void nullProductIdBypassesPrecheckButStillSkipped() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        SuixintuiRebuildService svc = service(s, acc -> {
            fetchCalls.add(acc);
            return List.of();
        });

        FinishedOrder noProduct = order(111L, ACC, AWEME_UID, null);
        Map<Long, RebuildResult> status = svc.rebuild(List.of(noProduct));

        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.SKIPPED_INVALID);
        assertThat(status.get(111L).statusLabel()).isEqualTo("参数缺失");
        assertThat(status.get(111L).orderIdLabel()).isNull();
        // 关键断言：没有建单（不是静默放行去建）
        assertThat(createdParams).isEmpty();
        assertThat(s.size()).isZero();
    }

    @Test
    @DisplayName("★证伪3b：源单 awemeId 为 null → 同样被拦（F=参数缺失），绝不建单")
    void nullAwemeIdNotBuilt() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        SuixintuiRebuildService svc = service(s, acc -> List.of());

        Map<Long, RebuildResult> status = svc.rebuild(List.of(order(111L, ACC, null, PRODUCT_ID)));

        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.SKIPPED_INVALID);
        assertThat(createdParams).isEmpty();
    }

    @Test
    @DisplayName("★证伪3c：在投列表里 productId/awemeId 为 null 的条目被忽略，不会误判成冲突")
    void inflightEntryWithNullFieldsIgnored() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        // 在投列表返回两条「字段为 null」的在投单；它们不该被加入冲突集合
        SuixintuiRebuildService svc = service(s, acc -> List.of(
                listOrder(999L, null, AWEME_UID, "DELIVERY_OK"),
                listOrder(998L, PRODUCT_ID, null, "DELIVERY_OK")));

        Map<Long, RebuildResult> status = svc.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));

        // 两个 null 条目都该被忽略 → 无冲突 → 正常建单
        assertThat(createdParams).hasSize(1);
        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.REBUILT);
    }

    // ==================================================================================
    // ① 多账户 + 限流：前面的账户数据被丢弃、整轮中止
    // ==================================================================================

    @Test
    @DisplayName("★证伪1d：某账户预检限流 → 整轮中止（本轮所有订单都不复建）")
    void anyAccountRateLimitAbortsWholeRound() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        SuixintuiRebuildService svc = service(s, acc -> {
            fetchCalls.add(acc);
            throw new RateLimitException(RateLimitException.Scope.DEVELOPER, "预检被频控", 0L);
        });

        Map<Long, RebuildResult> status = svc.rebuild(List.of(
                order(111L, ACC, AWEME_UID, PRODUCT_ID),
                order(222L, ACC, AWEME_UID, PRODUCT_ID)));

        assertThat(status).isEmpty();
        assertThat(createdParams).isEmpty();
        assertThat(s.size()).isZero();
    }

    @Test
    @DisplayName("★证伪1e：两账户、第二个账户限流 → 第一账户订单也一并被中止（设计取舍的直接证据）")
    void secondAccountRateLimitAlsoDropsFirstAccountOrders() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        SuixintuiRebuildService svc = service(s, acc -> {
            fetchCalls.add(acc);
            if (acc == ACC2) {
                throw new RateLimitException(RateLimitException.Scope.DEVELOPER, "预检被频控", 0L);
            }
            return List.of(); // ACC 无在投
        });

        Map<Long, RebuildResult> status = svc.rebuild(List.of(
                order(111L, ACC, AWEME_UID, PRODUCT_ID),
                order(222L, ACC2, AWEME_UID, PRODUCT_ID)));

        // 整轮中止：ACC 那一单本来可以建，也一并被放弃 —— 这是「限流保守」的取舍，非 bug
        assertThat(status).isEmpty();
        assertThat(createdParams).isEmpty();
        assertThat(s.size()).isZero();
    }

    // ==================================================================================
    // ④ 幂等 与 在投冲突 叠加：幂等在前，取回历史信息（真建→新单号；冲突→冲突单号）
    // ==================================================================================

    @Test
    @DisplayName("★证伪4：既已真建 → 幂等先于预检，日报仍显示历史新订单号（不再建/不再预检判定）")
    void alreadyRebuiltTakesPrecedenceOverInflight() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        // 先真建一次 111
        SuixintuiRebuildService svc1 = service(s, acc -> List.of());
        RebuildResult first = svc1.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID))).get(111L);
        Long built = first.orderId();
        assertThat(built).isNotNull();
        createdParams.clear();

        // 第二轮：同单再次传入，且此时预检报告「该组合在投」→ 幂等应先生效（显示新单号而非冲突单号）
        SuixintuiRebuildService svc2 = service(s, acc -> List.of(
                listOrder(777L, PRODUCT_ID, AWEME_UID, "DELIVERY_OK")));
        Map<Long, RebuildResult> status = svc2.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));

        RebuildResult r = status.get(111L);
        assertThat(r.status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(r.orderId()).isEqualTo(built);
        assertThat(r.orderIdLabel()).isEqualTo(String.valueOf(built)); // 显示的是新单号，不是 777
        assertThat(createdParams).isEmpty(); // 没有再建
    }

    @Test
    @DisplayName("★证伪4b：先因冲突被永久标记 → 幂等取回冲突单号（不是新单号）")
    void previouslyConflictIdempotentShowsConflictOrder() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        // 第一轮：命中冲突 → 永久标记，记下冲突单号 555
        service(s, acc -> List.of(listOrder(555L, PRODUCT_ID, AWEME_UID, "DELIVERY_OK")))
                .rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));
        createdParams.clear();

        // 第二轮：同单 → 幂等命中，取回冲突单号 555
        Map<Long, RebuildResult> status = service(s, acc -> List.of())
                .rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));

        RebuildResult r = status.get(111L);
        assertThat(r.status()).isEqualTo(RebuildResult.Status.SKIPPED_INFLIGHT);
        assertThat(r.orderIdLabel()).isEqualTo("555");
        assertThat(createdParams).isEmpty();
    }

    // ==================================================================================
    // ⑤ 预检失败放行 → 撞 40000 → 仍优雅：F=复建失败、不写流水、继续下一单
    // ==================================================================================

    @Test
    @DisplayName("★证伪5：预检拉取失败放行 → 建单撞 40000 → F=复建失败、不写流水、继续下一单")
    void precheckFailureFallthroughThen40000HandledGracefully() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        // 预检抛非限流异常 → 放行
        SuixintuiRebuildService svc = service(s, acc -> {
            fetchCalls.add(acc);
            throw new ApiException("列表接口炸了");
        });
        // 建单：第 1 单抛 40000，第 2 单成功
        AtomicInteger n = new AtomicInteger();
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean())).thenAnswer(inv -> {
            createdParams.add(inv.getArgument(0));
            if (n.incrementAndGet() == 1) {
                throw new ApiException(40000, "商品校验不通过");
            }
            return new SuixintuiCreateOutcome(false, 888L, "{}", List.of());
        });

        Map<Long, RebuildResult> status = svc.rebuild(List.of(
                order(111L, ACC, AWEME_UID, PRODUCT_ID),
                order(222L, ACC, AWEME_UID, PRODUCT_ID)));

        // 第 1 单失败：F=复建失败、G 留空、不写流水（下轮可重试）；第 2 单成功
        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.FAILED);
        assertThat(status.get(111L).orderIdLabel()).isNull();
        assertThat(s.isRebuilt(111L)).isFalse();
        assertThat(status.get(222L).status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(s.isRebuilt(222L)).isTrue();
    }

    // ==================================================================================
    // ⑥ 未注入预检器（5 参构造器）→ 预检静默跳过、仍建单
    // ==================================================================================

    @Test
    @DisplayName("★证伪6：未注入预检器（null fetcher）→ 预检静默跳过、照常建单（生产误配=预检失效）")
    void nullFetcherSilentlyBypassesPrecheckAndBuilds() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        // 5 参兼容构造器 → fetcher = null
        SuixintuiRebuildService svc = new SuixintuiRebuildService(
                rebuildProps, reinvestProps, s, detail, create);

        Map<Long, RebuildResult> status = svc.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));

        // 预检没跑，订单照建 —— 说明「若生产错用 5 参构造器，预检会静默失效」
        assertThat(createdParams).hasSize(1);
        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(fetchCalls).isEmpty();
    }

    // ==================================================================================
    // 额外（端到端口径）：冲突跳过不占每日额度 —— 同日 10 单冲突 + 第 11 单正常 → 第 11 单仍能建
    // ==================================================================================

    @Test
    @DisplayName("★证伪9：同日 10 单全部命中在投冲突（写流水永久标记、不占额度）→ 第 11 单正常原单仍应建成")
    void inflightConflictsDoNotEatDailyLimitEndToEnd() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        // 账户恒定返回「组合在投」，冲突单号 999 → 前 10 单全部走冲突跳过
        SuixintuiRebuildService svc = service(s,
                acc -> List.of(listOrder(999L, PRODUCT_ID, AWEME_UID, "DELIVERY_OK")));

        List<FinishedOrder> tenConflicts = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            tenConflicts.add(order(1000L + i, ACC, AWEME_UID, PRODUCT_ID));
        }
        svc.rebuild(tenConflicts);

        // 10 单都被永久标记；且不占每日额度
        for (long i = 1; i <= 10; i++) {
            assertThat(s.isRebuilt(1000L + i)).isTrue();
        }
        assertThat(s.effectiveCountOn(java.time.LocalDate.now())).isZero();
        assertThat(createdParams).isEmpty(); // 冲突单一个都没建

        // 第 11 单：换一个不冲突的账号 → 不命中冲突 → 应能真建（额度没被冲突吃掉）
        long ACC3 = 1999999999999999L;
        createdParams.clear();
        SuixintuiRebuildService svc2 = service(s,
                acc -> acc == ACC ? List.of(listOrder(999L, PRODUCT_ID, AWEME_UID, "DELIVERY_OK"))
                        : List.of());
        Map<Long, RebuildResult> status =
                svc2.rebuild(List.of(order(2000L, ACC3, AWEME_UID, PRODUCT_ID)));

        assertThat(status.get(2000L).status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(createdParams).hasSize(1);
        assertThat(s.effectiveCountOn(java.time.LocalDate.now())).isEqualTo(1);
    }

    // ==================================================================================
    // 额外：每账户 1 次（含重复账户 + 幂等单不重复拉账户）
    // ==================================================================================

    @Test
    @DisplayName("★证伪7：同一账户多单 + 其中含幂等单 → 该账户仍只被拉 1 次")
    void oneFetchPerAdvertiserEvenWithIdempotentOrders() throws Exception {
        SuixintuiRebuildRecordStore s = store();
        SuixintuiRebuildService svc = service(s, acc -> {
            fetchCalls.add(acc);
            return List.of();
        });
        // 3 单同账户，其中 111 先建一次使其幂等（幂等单在 ① 跳过，不影响预检次数）
        svc.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));
        int callsAfterFirst = fetchCalls.size();
        fetchCalls.clear();

        svc.rebuild(List.of(
                order(111L, ACC, AWEME_UID, PRODUCT_ID),
                order(222L, ACC, AWEME_UID, PRODUCT_ID),
                order(333L, ACC, AWEME_UID, PRODUCT_ID)));

        assertThat(callsAfterFirst).isEqualTo(1);
        assertThat(fetchCalls).containsExactly(ACC); // 仍只 1 次
    }

    // ==================================================================================
    // 额外：在投列表含未知/中间状态 → 计入在投（保守）
    // ==================================================================================

    @Test
    @DisplayName("★证伪8：在投列表含 CREATING/AUDIT/OFFLINE_AUDIT/UNPAID/BOOK/DELIVERY_OK → 均计入在投，跳过")
    void nonFinalStatusesAllCountAsInflight() throws Exception {
        for (String st : List.of("CREATING", "AUDIT", "OFFLINE_AUDIT", "UNPAID", "BOOK", "DELIVERY_OK")) {
            SuixintuiRebuildRecordStore s = store();
            SuixintuiRebuildService svc = service(s, acc ->
                    List.of(listOrder(999L, PRODUCT_ID, AWEME_UID, st)));
            createdParams.clear();

            Map<Long, RebuildResult> status = svc.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));

            assertThat(createdParams)
                    .as("状态 %s 应被判为在投 → 跳过", st)
                    .isEmpty();
            assertThat(status.get(111L).status())
                    .as("状态 %s 应记为在投冲突", st)
                    .isEqualTo(RebuildResult.Status.SKIPPED_INFLIGHT);
        }
    }

    @Test
    @DisplayName("★证伪8b：在投列表含终态 FINISHED/OVER/FAILED/TIMEOUT/UNPAIDCANCEL/FROZEN → 均不算在投，照建")
    void finalStatusesDoNotBlock() throws Exception {
        for (String st : List.of("FINISHED", "OVER", "FAILED", "TIMEOUT", "UNPAIDCANCEL", "FROZEN")) {
            SuixintuiRebuildRecordStore s = store();
            SuixintuiRebuildService svc = service(s, acc ->
                    List.of(listOrder(999L, PRODUCT_ID, AWEME_UID, st)));
            createdParams.clear();

            Map<Long, RebuildResult> status = svc.rebuild(List.of(order(111L, ACC, AWEME_UID, PRODUCT_ID)));

            assertThat(createdParams)
                    .as("状态 %s 应被判为终态 → 不跳过，正常建单", st)
                    .hasSize(1);
            assertThat(status.get(111L).status())
                    .as("状态 %s 应正常复建", st)
                    .isEqualTo(RebuildResult.Status.REBUILT);
        }
    }
}
