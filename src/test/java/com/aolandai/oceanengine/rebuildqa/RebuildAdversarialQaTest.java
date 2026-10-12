package com.aolandai.oceanengine.rebuildqa;

import com.aolandai.oceanengine.client.ApiErrors;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <b>独立证伪测试</b>（QA 严过关）—— 复建「花钱路径」专项。
 *
 * <p>目标：不复述工程师已有单测，而是用<b>更刁钻、更贴近生产事故</b>的角度去证伪。
 * 每个 {@code @DisplayName} 以「★证伪」开头的用例，说明它是针对某一个具体失效模式设计的。</p>
 *
 * <p>⚠️ 全部使用假 detail / 假 create service，<b>绝不真建单、绝不真调接口</b>。</p>
 *
 * <h2>新口径（老板 2026-10 拍板）关键契约</h2>
 * <ul>
 *   <li>F 列「复建状态」5 值：已复建 / 已有投放中订单 / 超每日上限 / 参数缺失 / 复建失败。</li>
 *   <li>G 列「复建订单ID」：成功→新单号；在投冲突→冲突单号；其它失败→留空。</li>
 *   <li><b>幂等策略（已反转）</b>：在投冲突跳过→<b>写流水永久标记</b>（{@code isRebuilt==true}，
 *       永不再试）；超上限/参数缺失/建单失败→<b>不写流水</b>（下轮可重试）。</li>
 *   <li>每日真复建计数（{@code effectiveCountOn}）<b>不</b>计在投冲突记录。</li>
 * </ul>
 */
class RebuildAdversarialQaTest {

    private static final long ACC = 1779106144470100L;
    private static final long AWEME_UID = 7446391238175474745L;
    private static final long PRODUCT_ID = 3700528515221946525L;

    @TempDir
    Path tempDir;

    private SuixintuiOrderDetailService detail;
    private SuixintuiOrderCreateService create;
    private SuixintuiReinvestProperties reinvestProps;
    private SuixintuiRebuildProperties rebuildProps;

    private final AtomicInteger detailCalls = new AtomicInteger();
    private final AtomicInteger createCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        detail = mock(SuixintuiOrderDetailService.class);
        create = mock(SuixintuiOrderCreateService.class);
        reinvestProps = new SuixintuiReinvestProperties();
        rebuildProps = new SuixintuiRebuildProperties();
        detailCalls.set(0);
        createCalls.set(0);

        // 统计调用次数（独立于 Mockito verify，便于断言「根本没碰接口」）
        when(detail.getOrderDetail(anyLong(), anyLong())).thenAnswer(inv -> {
            detailCalls.incrementAndGet();
            return okDetail();
        });
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean())).thenAnswer(inv -> {
            createCalls.incrementAndGet();
            boolean dryRun = inv.getArgument(1);
            long newId = 8_000_000_000_000_000_000L + createCalls.get();
            return new SuixintuiCreateOutcome(dryRun, dryRun ? null : newId, "{}", List.of());
        });
    }

    private SuixintuiRebuildRecordStore store() throws Exception {
        return new SuixintuiRebuildRecordStore(tempDir.resolve("records.jsonl"));
    }

    private SuixintuiRebuildService service(SuixintuiRebuildRecordStore store) {
        // 5 参兼容构造器 → 无预检器（本文件不测预检，预检专项在 InflightPrecheckAdversarialQaTest）
        return new SuixintuiRebuildService(rebuildProps, reinvestProps, store, detail, create);
    }

    private static QianchuanAwemeUniPromotionOrderDetailV10ResponseData okDetail() {
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
        return data;
    }

    private static FinishedOrder order(long orderId) {
        return new FinishedOrder(orderId, ACC, "抖音名", "show_" + orderId,
                AWEME_UID, PRODUCT_ID, 2.0, "FINISHED", "2026-10-11 14:29", "2026-10-12 00:01:00");
    }

    // ==================================================================================
    // A3 —— 幂等：第二次调用「绝不碰详情/建单接口」（不是靠返回值，而是靠调用计数）
    // ==================================================================================

    @Test
    @DisplayName("★证伪 A3：真建成后再传同一单 → detailCalls 与 createCalls 都不增长，status 走幂等重建")
    void a3_secondCallTouchesNoExternalApi() throws Exception {
        reinvestProps.setDryRun(false);
        SuixintuiRebuildRecordStore store = store();
        SuixintuiRebuildService svc = service(store);

        RebuildResult first = svc.rebuild(List.of(order(111L))).get(111L);
        int detailAfterFirst = detailCalls.get();
        int createAfterFirst = createCalls.get();
        assertThat(detailAfterFirst).isEqualTo(1);
        assertThat(createAfterFirst).isEqualTo(1);
        assertThat(first.status()).isEqualTo(RebuildResult.Status.REBUILT);

        // 第二次传入同一原单
        RebuildResult second = svc.rebuild(List.of(order(111L))).get(111L);

        // 接口调用次数必须原地不动 —— 这才是「不重复花钱」的硬证据
        assertThat(detailCalls.get()).isEqualTo(detailAfterFirst);
        assertThat(createCalls.get()).isEqualTo(createAfterFirst);
        // 日报仍要能显示是哪一单复建的 → 新订单号必须与第一次一致，且 F/G 列文案正确
        assertThat(second.status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(second.orderId()).isEqualTo(first.orderId());
        assertThat(second.statusLabel()).isEqualTo("已复建");
        assertThat(second.orderIdLabel()).isEqualTo(String.valueOf(first.orderId()));
    }

    @Test
    @DisplayName("★证伪 A3b：同一批次里重复出现同一原单 → 第 2 次出现同样被幂等挡住")
    void a3b_duplicateWithinSameBatchIsIdempotent() throws Exception {
        reinvestProps.setDryRun(false);
        SuixintuiRebuildRecordStore store = store();

        // 列表里同一条订单出现两次（底表/data 源异常时真会发生）
        Map<Long, RebuildResult> status =
                service(store).rebuild(List.of(order(111L), order(111L)));

        // 只真建一次
        assertThat(createCalls.get()).isEqualTo(1);
        assertThat(detailCalls.get()).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
        assertThat(status).containsOnlyKeys(111L);
        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.REBUILT);
    }

    // ==================================================================================
    // A4 —— 向后兼容：老流水（无 result 字段）读回必须当作成功；@JsonIgnore 派生方法不入库
    // ==================================================================================

    @Test
    @DisplayName("★证伪 A4：手写「老格式」JSONL（真建行无 result 字段 + dry-run 行）→ 读回仍认成功、幂等索引正确")
    void a4_handWrittenJsonlRoundTrip() throws Exception {
        Path file = tempDir.resolve("hand.jsonl");
        // ⚠️ 关键：这条「真建行」故意**不写** result 字段（模拟 2026-10 改造前的老流水），
        //    且 newOrderId 非空、dryRun=false。新代码读回后**必须**把它当成功
        //    （effectiveResult() 归一为 REBUILT），绝不能被误判成 SKIPPED_INFLIGHT。
        String oldSuccessLine = "{\"sourceOrderId\":111,\"newOrderId\":999,"
                + "\"advertiserId\":1779106144470100,\"awemeId\":7446391238175474745,"
                + "\"productId\":3700528515221946525,\"amount\":200,\"deliveryTime\":24.0,"
                + "\"bidType\":\"MANUAL_BID\",\"qcpxMode\":\"QCPX_MODE_ON\",\"roiGoal\":1.7,"
                + "\"dryRun\":false,\"rebuiltAt\":\"2026-10-12 09:00:00\"}";
        // dry-run 行：newOrderId=null、dryRun=true（老格式同样没有 result 字段）
        String dryLine = "{\"sourceOrderId\":222,\"newOrderId\":null,"
                + "\"advertiserId\":1779106144470100,\"awemeId\":7446391238175474745,"
                + "\"productId\":3700528515221946525,\"amount\":200,\"deliveryTime\":24.0,"
                + "\"bidType\":\"MANUAL_BID\",\"qcpxMode\":\"QCPX_MODE_ON\",\"roiGoal\":1.7,"
                + "\"dryRun\":true,\"rebuiltAt\":\"2026-10-12 09:05:00\"}";
        Files.write(file, List.of(oldSuccessLine, dryLine), StandardCharsets.UTF_8);

        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(file);

        // 读回 2 条；真建条进幂等索引，dry-run 条不进
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.isRebuilt(111L)).isTrue();
        // 老成功行绝不能被误判为在投冲突
        SuixintuiRebuildRecord rec111 = store.findEffective(111L);
        assertThat(rec111.effectiveResult()).isEqualTo(SuixintuiRebuildRecord.RESULT_REBUILT);
        assertThat(rec111.isSettled()).isTrue();
        assertThat(rec111.getNewOrderId()).isEqualTo(999L);
        assertThat(store.isRebuilt(222L)).isFalse();
        // 今日计数：rebuiltAt 写的是 2026-10-12，用该日期查应为 1（只算真建，dry-run 不算）
        assertThat(store.effectiveCountOn(java.time.LocalDate.of(2026, 10, 12))).isEqualTo(1);
    }

    @Test
    @DisplayName("★证伪 A4b：真建记录落盘后，JSONL 里既不含 \"effective\" 也不含 \"settled\"/\"countsTowardDailyLimit\" 键")
    void a4b_derivedFieldsNeverLeakIntoFile() throws Exception {
        Path file = tempDir.resolve("leak.jsonl");
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(file);
        store.append(new SuixintuiRebuildRecord(111L, 999L, ACC, AWEME_UID, PRODUCT_ID,
                200L, 24.0, "MANUAL_BID", "QCPX_MODE_ON", 1.7, false));

        String content = Files.readString(file, StandardCharsets.UTF_8);
        // 若 @JsonIgnore 被去掉，Jackson 会把 isEffective()/isSettled()/countsTowardDailyLimit()
        // 序列化成 "effective"/"settled"/"countsTowardDailyLimit" 属性，
        // 下次启动 readValue 因无对应 setter 抛 UnrecognizedPropertyException → 拒绝启动。
        assertThat(content).doesNotContain("\"effective\"");
        assertThat(content).doesNotContain("\"settled\"");
        assertThat(content).doesNotContain("\"countsTowardDailyLimit\"");
        // 且这行必须是可被 Jackson 反序列化的（走对象往返验证）
        SuixintuiRebuildRecord roundTrip =
                new ObjectMapper().readValue(content.trim(), SuixintuiRebuildRecord.class);
        assertThat(roundTrip.getSourceOrderId()).isEqualTo(111L);
        assertThat(roundTrip.getResult()).isEqualTo(SuixintuiRebuildRecord.RESULT_REBUILT);
        assertThat(roundTrip.isEffective()).isTrue();
        assertThat(roundTrip.isSettled()).isTrue();
        assertThat(roundTrip.countsTowardDailyLimit()).isTrue();
    }

    @Test
    @DisplayName("★证伪 A4c：模拟「重启」——同文件重开 store，老成功行的幂等仍然生效（重启不失效）")
    void a4c_restartKeepsOldSuccessIdempotent() throws Exception {
        reinvestProps.setDryRun(false);
        Path file = tempDir.resolve("restart.jsonl");

        // 第一批：真建 111
        SuixintuiRebuildRecordStore s1 = new SuixintuiRebuildRecordStore(file);
        service(s1).rebuild(List.of(order(111L)));
        assertThat(s1.isRebuilt(111L)).isTrue();

        // 模拟进程重启：新 store 从磁盘加载
        SuixintuiRebuildRecordStore s2 = new SuixintuiRebuildRecordStore(file);
        assertThat(s2.isRebuilt(111L)).isTrue();

        // 重启后再跑 → 幂等挡住，不再调详情/建单
        detailCalls.set(0);
        createCalls.set(0);
        Map<Long, RebuildResult> status = service(s2).rebuild(List.of(order(111L)));
        assertThat(detailCalls.get()).isZero();
        assertThat(createCalls.get()).isZero();
        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.REBUILT);
    }

    @Test
    @DisplayName("★证伪 A4d（端到端向后兼容）：磁盘上老成功行（无 result 字段）→ rebuild() 必须回 REBUILT 而非 SKIPPED_INFLIGHT")
    void a4d_oldSuccessLineRebuildReturnsRebuiltNotConflict() throws Exception {
        reinvestProps.setDryRun(false);
        Path file = tempDir.resolve("old-success.jsonl");
        // 老格式真建行：newOrderId=999、dryRun=false、**没有 result 字段**
        String oldSuccessLine = "{\"sourceOrderId\":111,\"newOrderId\":999,"
                + "\"advertiserId\":1779106144470100,\"awemeId\":7446391238175474745,"
                + "\"productId\":3700528515221946525,\"amount\":200,\"deliveryTime\":24.0,"
                + "\"bidType\":\"MANUAL_BID\",\"qcpxMode\":\"QCPX_MODE_ON\",\"roiGoal\":1.7,"
                + "\"dryRun\":false,\"rebuiltAt\":\"2026-10-12 09:00:00\"}";
        Files.write(file, List.of(oldSuccessLine), StandardCharsets.UTF_8);

        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(file);
        // 再传同一原单 → 幂等命中，且必须走「真好单」分支（显示历史新单号 999），
        // 绝不能被 effectiveResult() 归一逻辑误判成 SKIPPED_INFLIGHT。
        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(111L)));

        RebuildResult r = status.get(111L);
        assertThat(r.status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(r.statusLabel()).isEqualTo("已复建");
        assertThat(r.orderIdLabel()).isEqualTo("999");
        assertThat(detailCalls.get()).isZero(); // 幂等，不碰接口
        assertThat(createCalls.get()).isZero();
    }

    // ==================================================================================
    // isSettled() vs isEffective()/effectiveCountOn() 的语义分离
    // ==================================================================================

    @Test
    @DisplayName("★证伪 A5：混合场景（3 真建 + 5 在投冲突）→ effectiveCountOn 只算 3、isRebuilt 认全部 8")
    void a5_settledVsEffectiveMixedScenario() throws Exception {
        Path file = tempDir.resolve("mixed.jsonl");
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(file);
        String today = java.time.LocalDate.now().toString();

        // 3 条真建（newOrderId 非空）
        for (int i = 1; i <= 3; i++) {
            SuixintuiRebuildRecord r = new SuixintuiRebuildRecord(
                    (long) (100 + i), 9000L + i, ACC, AWEME_UID, PRODUCT_ID,
                    200L, 24.0, "MANUAL_BID", "QCPX_MODE_ON", 1.7, false);
            r.setRebuiltAt(today + " 08:0" + i + ":00");
            store.append(r);
        }
        // 5 条在投冲突跳过（newOrderId=null，result=SKIPPED_INFLIGHT，带冲突单号）
        for (int i = 1; i <= 5; i++) {
            SuixintuiRebuildRecord r = SuixintuiRebuildRecord.skippedInflight(
                    (long) (200 + i), 5000L + i, ACC, AWEME_UID, PRODUCT_ID,
                    null, null, null, null, null);
            r.setRebuiltAt(today + " 09:0" + i + ":00");
            store.append(r);
        }

        // isSettled：8 条都算「已处理」→ isRebuilt 全 true
        for (int i = 1; i <= 3; i++) {
            assertThat(store.isRebuilt((long) (100 + i))).isTrue();
        }
        for (int i = 1; i <= 5; i++) {
            assertThat(store.isRebuilt((long) (200 + i))).isTrue();
        }
        // effectiveCountOn：只算真建 3 条，冲突 5 条不占额度
        assertThat(store.effectiveCountOn(java.time.LocalDate.now())).isEqualTo(3);

        // 逐条验证语义分离
        SuixintuiRebuildRecord rebuilt = store.findEffective(101L);
        assertThat(rebuilt.isEffective()).isTrue();
        assertThat(rebuilt.isSettled()).isTrue();
        assertThat(rebuilt.countsTowardDailyLimit()).isTrue();

        SuixintuiRebuildRecord conflict = store.findEffective(201L);
        assertThat(conflict.isEffective()).isFalse();          // 没真建
        assertThat(conflict.isSettled()).isTrue();             // 但已处理
        assertThat(conflict.countsTowardDailyLimit()).isFalse();// 不占额度
        assertThat(conflict.getConflictOrderId()).isEqualTo(5001L);
    }

    // ==================================================================================
    // B3 —— 每日上限：超限发生在「调详情之前」，且 11 单只对前 10 单调详情
    // ==================================================================================

    @Test
    @DisplayName("★证伪 B3：11 单上限 10 → 只对前 10 单调详情，第 11 单在详情之前就被拦（F=超每日上限、G 留空）")
    void b3_limitCheckPrecedesDetailCall() throws Exception {
        reinvestProps.setDryRun(false);
        rebuildProps.setMaxOrdersPerDay(10);
        SuixintuiRebuildRecordStore store = store();

        List<FinishedOrder> orders = new java.util.ArrayList<>();
        for (long i = 1; i <= 11; i++) {
            orders.add(order(100L + i)); // 101..111
        }
        Map<Long, RebuildResult> status = service(store).rebuild(orders);

        assertThat(createCalls.get()).isEqualTo(10);
        // 关键：详情也只调了 10 次。若实现把「上限判断」写在调详情之后，
        // 这里会是 11（白跑一次详情，且若详情接口有副作用/限流则更糟）。
        assertThat(detailCalls.get()).isEqualTo(10);
        // 第 11 单：超上限 → F 列「超每日上限」、G 列留空、不写流水、下轮可重试
        RebuildResult r111 = status.get(111L);
        assertThat(r111.status()).isEqualTo(RebuildResult.Status.SKIPPED_LIMIT);
        assertThat(r111.statusLabel()).isEqualTo("超每日上限");
        assertThat(r111.orderIdLabel()).isNull(); // G 列留空
        assertThat(store.isRebuilt(111L)).isFalse(); // 不写流水 → 可重试
        assertThat(store.effectiveCountOn(java.time.LocalDate.now())).isEqualTo(10);
    }

    @Test
    @DisplayName("★证伪 B3b：今日已真建 10（磁盘）→ 新一轮订单在详情之前就被拦（F=超每日上限）")
    void b3b_persistedCountBlocksBeforeDetail() throws Exception {
        reinvestProps.setDryRun(false);
        rebuildProps.setMaxOrdersPerDay(10);
        Path file = tempDir.resolve("persisted.jsonl");
        // 手工写 10 条「今日」真建记录
        SuixintuiRebuildRecordStore seed = new SuixintuiRebuildRecordStore(file);
        String today = java.time.LocalDate.now().toString();
        for (int i = 1; i <= 10; i++) {
            SuixintuiRebuildRecord r = new SuixintuiRebuildRecord(
                    (long) i, 9000L + i, ACC, AWEME_UID, PRODUCT_ID,
                    200L, 24.0, "MANUAL_BID", "QCPX_MODE_ON", 1.7, false);
            r.setRebuiltAt(today + " 08:00:00");
            seed.append(r);
        }
        assertThat(seed.effectiveCountOn(java.time.LocalDate.now())).isEqualTo(10);

        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(file);
        // 新订单 555
        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(555L)));

        assertThat(status).containsKey(555L);
        assertThat(status.get(555L).status()).isEqualTo(RebuildResult.Status.SKIPPED_LIMIT);
        assertThat(status.get(555L).orderIdLabel()).isNull();
        assertThat(detailCalls.get()).isZero(); // 详情根本没碰
        assertThat(createCalls.get()).isZero();
    }

    @Test
    @DisplayName("★证伪 B3c：在投冲突不占每日额度 —— 同一天 10 单冲突 + 第 11 单正常 → 第 11 单应能建")
    void b3c_inflightConflictsDoNotConsumeDailyLimit() throws Exception {
        reinvestProps.setDryRun(false);
        rebuildProps.setMaxOrdersPerDay(10);
        SuixintuiRebuildRecordStore store = store();

        // 直接往流水里塞 10 条「今日在投冲突跳过」记录（不占额度）
        String today = java.time.LocalDate.now().toString();
        for (int i = 1; i <= 10; i++) {
            SuixintuiRebuildRecord r = SuixintuiRebuildRecord.skippedInflight(
                    (long) i, 7000L + i, ACC, AWEME_UID, PRODUCT_ID,
                    null, null, null, null, null);
            r.setRebuiltAt(today + " 07:00:00");
            store.append(r);
        }
        // 冲突记录不占额度，今日真建计数应为 0
        assertThat(store.effectiveCountOn(java.time.LocalDate.now())).isZero();

        // 第 11 个是全新原单 → 不撞冲突 → 应能真建
        Map<Long, RebuildResult> status = service(store).rebuild(List.of(order(999L)));

        assertThat(status.get(999L).status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(createCalls.get()).isEqualTo(1);
        assertThat(store.effectiveCountOn(java.time.LocalDate.now())).isEqualTo(1);
    }

    // ==================================================================================
    // C2 —— dry-run 记录存在时，切真建仍能真建（dry-run 不阻挡）
    // ==================================================================================

    @Test
    @DisplayName("★证伪 C2：先 dry-run（写 dry-run 记录）→ 再真建同一单 → 能真建、F=已复建、占额度")
    void c2_dryRunRecordThenRealBuild() throws Exception {
        SuixintuiRebuildRecordStore store = store();

        // 第一步 dry-run
        reinvestProps.setDryRun(true);
        Map<Long, RebuildResult> dry = service(store).rebuild(List.of(order(111L)));
        assertThat(dry).isEmpty(); // dry-run 不进 status（日报不显示）
        assertThat(store.isRebuilt(111L)).isFalse();
        assertThat(store.effectiveCountOn(java.time.LocalDate.now())).isZero();
        assertThat(createCalls.get()).isEqualTo(1); // dry-run 也走了一遍组装

        // 第二步真建
        reinvestProps.setDryRun(false);
        Map<Long, RebuildResult> real = service(store).rebuild(List.of(order(111L)));
        RebuildResult r = real.get(111L);
        assertThat(r.status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(r.orderId()).isNotNull();
        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.size()).isEqualTo(2); // 一条 dry-run + 一条 effective
        assertThat(store.effectiveCountOn(java.time.LocalDate.now())).isEqualTo(1);
    }

    @Test
    @DisplayName("★证伪 C2b：dry-run 记录里的 newOrderId 必须是 null、result=DRY_RUN、isEffective=false")
    void c2b_dryRunRecordHasNoNewOrderId() throws Exception {
        reinvestProps.setDryRun(true);
        SuixintuiRebuildRecordStore store = store();
        service(store).rebuild(List.of(order(111L)));

        SuixintuiRebuildRecord rec = store.all().get(0);
        assertThat(rec.isDryRun()).isTrue();
        assertThat(rec.getNewOrderId()).isNull();
        assertThat(rec.getResult()).isEqualTo(SuixintuiRebuildRecord.RESULT_DRY_RUN);
        assertThat(rec.isEffective()).isFalse();
        assertThat(rec.isSettled()).isFalse(); // dry-run 也不算「已处理」
    }

    // ==================================================================================
    // D —— 参数照抄：qcpxMode 用详情的原值（QCPX_MODE_OFF 不能被改写）；Long→long 拆箱判空
    // ==================================================================================

    @Test
    @DisplayName("★证伪 D1：详情 qcpxMode=OFF → 复建参数必须是 QCPX_MODE_OFF（原样），不是 ON/DEFAULT")
    void d1_qcpxModeCopiedVerbatim() throws Exception {
        reinvestProps.setDryRun(false);
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData data =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseData();
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting ds =
                new QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting();
        ds.setAmount(300L);
        ds.setDeliveryTime(48.0);
        ds.setBidType(QianchuanAwemeUniPromotionOrderDetailV10DataDeliverySettingBidType.AUTO_BID);
        ds.setQcpxMode(QianchuanAwemeUniPromotionOrderDetailV10DataDeliverySettingQcpxMode.OFF);
        ds.setRoiGoal(null); // AUTO_BID 时可不填
        data.setDeliverySetting(ds);
        when(detail.getOrderDetail(anyLong(), anyLong())).thenReturn(data);

        final SuixintuiOrderParam[] captured = new SuixintuiOrderParam[1];
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean()))
                .thenAnswer(inv -> {
                    captured[0] = inv.getArgument(0);
                    return new SuixintuiCreateOutcome(false, 777L, "{}", List.of());
                });

        service(store()).rebuild(List.of(order(111L)));

        assertThat(captured[0].qcpxMode()).isEqualTo("QCPX_MODE_OFF");
        assertThat(captured[0].bidType()).isEqualTo("AUTO_BID");
        assertThat(captured[0].deliveryTime()).isEqualTo(48.0);
        assertThat(captured[0].amount()).isEqualTo(300L);
    }

    @Test
    @DisplayName("★证伪 D2：列表 advertiserId 为 null → 跳过（F=参数缺失、G 留空、不写流水、不调详情/建单）")
    void d2_nullAdvertiserIdSkipsWithoutNpe() throws Exception {
        reinvestProps.setDryRun(false);
        SuixintuiRebuildRecordStore store = store();
        FinishedOrder noAcc = new FinishedOrder(111L, null, "n", "s",
                AWEME_UID, PRODUCT_ID, 2.0, "FINISHED", "t", "ts");

        Map<Long, RebuildResult> status = service(store).rebuild(List.of(noAcc));

        RebuildResult r = status.get(111L);
        assertThat(r.status()).isEqualTo(RebuildResult.Status.SKIPPED_INVALID);
        assertThat(r.statusLabel()).isEqualTo("参数缺失");
        assertThat(r.orderIdLabel()).isNull();
        assertThat(detailCalls.get()).isZero();
        assertThat(createCalls.get()).isZero();
        assertThat(store.size()).isZero(); // 不写流水（可重试）
    }

    @Test
    @DisplayName("★证伪 D3：三字段中有两个为 null → 各自「参数缺失」，不误伤相邻的合法订单")
    void d3_invalidOrdersDoNotPoisonValidOnes() throws Exception {
        reinvestProps.setDryRun(false);
        SuixintuiRebuildRecordStore store = store();
        FinishedOrder badAweme = new FinishedOrder(111L, ACC, "n", "s",
                null, PRODUCT_ID, 2.0, "FINISHED", "t", "ts");
        FinishedOrder badProduct = new FinishedOrder(222L, ACC, "n", "s",
                AWEME_UID, null, 2.0, "FINISHED", "t", "ts");
        FinishedOrder good = order(333L);

        Map<Long, RebuildResult> status =
                service(store).rebuild(List.of(badAweme, badProduct, good));

        assertThat(status).containsOnlyKeys(111L, 222L, 333L);
        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.SKIPPED_INVALID);
        assertThat(status.get(222L).status()).isEqualTo(RebuildResult.Status.SKIPPED_INVALID);
        assertThat(status.get(333L).status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(store.isRebuilt(333L)).isTrue();
        assertThat(store.isRebuilt(111L)).isFalse();
        assertThat(store.isRebuilt(222L)).isFalse();
        // 只为合法订单调了详情/建单
        assertThat(detailCalls.get()).isEqualTo(1);
        assertThat(createCalls.get()).isEqualTo(1);
    }

    // ==================================================================================
    // E3 —— 旧的七日底表（无 awemeId/productId 键）仍能读回，不会让底表整份挂掉
    // ==================================================================================

    @Test
    @DisplayName("★证伪 E3：手写不含 awemeId/productId 的旧底表行 → 能读回，新字段为 null 但不抛")
    void e3_oldFinishedOrderJsonlStillReads() throws Exception {
        Path file = tempDir.resolve("finished.jsonl");
        // 旧格式：只有 8 个老字段，没有 awemeId / productId
        String oldLine = "{\"orderId\":111,\"advertiserId\":1779106144470100,"
                + "\"awemeName\":\"老单\",\"awemeShowId\":\"show_111\",\"roi\":2.0,"
                + "\"status\":\"FINISHED\",\"orderCreateTime\":\"2026-10-10 10:00\","
                + "\"lastSeenAt\":\"2026-10-11 00:01:00\"}";
        Files.write(file, List.of(oldLine), StandardCharsets.UTF_8);

        com.aolandai.oceanengine.storage.FinishedOrderStore store =
                new com.aolandai.oceanengine.storage.FinishedOrderStore(file);

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.getSkippedBadLines()).isZero();
        com.aolandai.oceanengine.storage.FinishedOrder o = store.find(111L);
        assertThat(o.getAwemeName()).isEqualTo("老单");
        assertThat(o.getAwemeId()).isNull();
        assertThat(o.getProductId()).isNull();
    }

    // ==================================================================================
    // F —— 限流立即停：详情限流 → break（后续订单不处理）；建单限流 → break 但保留已建成
    // ==================================================================================

    @Test
    @DisplayName("★证伪 F1：第 2 单详情限流 → 只处理到第 2 单，第 3 单不再碰（break）")
    void f1_detailRateLimitBreaksAfterSecondOrder() throws Exception {
        reinvestProps.setDryRun(false);
        AtomicInteger local = new AtomicInteger();
        when(detail.getOrderDetail(anyLong(), anyLong())).thenAnswer(inv -> {
            if (local.incrementAndGet() == 2) {
                throw new RateLimitException(RateLimitException.Scope.DEVELOPER, "详情被频控", 0L);
            }
            return okDetail();
        });
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status =
                service(store).rebuild(List.of(order(111L), order(222L), order(333L)));

        // 第 1 单建成；第 2 单详情限流 → 停；第 3 单从未尝试
        assertThat(status).containsKey(111L).doesNotContainKey(222L).doesNotContainKey(333L);
        assertThat(status.get(111L).status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(local.get()).isEqualTo(2); // 详情只调了 2 次，没对第 3 单再调
        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("★证伪 F2：第 2 单建单限流 → break，第 1 单已建成保留、第 3 单不处理")
    void f2_createRateLimitKeepsBuiltAndBreaks() throws Exception {
        reinvestProps.setDryRun(false);
        AtomicInteger c = new AtomicInteger();
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean())).thenAnswer(inv -> {
            if (c.incrementAndGet() == 1) {
                return new SuixintuiCreateOutcome(false, 999L, "{}", List.of());
            }
            throw new RateLimitException(RateLimitException.Scope.DEVELOPER, "建单被频控", 0L);
        });
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status =
                service(store).rebuild(List.of(order(111L), order(222L), order(333L)));

        assertThat(status).containsKey(111L).doesNotContainKey(222L).doesNotContainKey(333L);
        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.size()).isEqualTo(1);
        // 第 3 单不应被建单（break 生效）
        assertThat(c.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("★证伪 F3：建单业务错误（非限流）→ F=复建失败、G 留空、不写流水、继续下一单")
    void f3_nonRateLimitFailureContinues() throws Exception {
        reinvestProps.setDryRun(false);
        AtomicInteger c = new AtomicInteger();
        when(create.create(any(SuixintuiOrderParam.class), anyBoolean())).thenAnswer(inv -> {
            if (c.incrementAndGet() == 1) {
                throw new ApiException("建单业务被拒 code=40000");
            }
            return new SuixintuiCreateOutcome(false, 888L, "{}", List.of());
        });
        SuixintuiRebuildRecordStore store = store();

        Map<Long, RebuildResult> status =
                service(store).rebuild(List.of(order(111L), order(222L)));

        RebuildResult r111 = status.get(111L);
        assertThat(r111.status()).isEqualTo(RebuildResult.Status.FAILED);
        assertThat(r111.statusLabel()).isEqualTo("复建失败");
        assertThat(r111.orderIdLabel()).isNull(); // G 列留空
        assertThat(status.get(222L).status()).isEqualTo(RebuildResult.Status.REBUILT);
        assertThat(store.isRebuilt(111L)).isFalse(); // 不写流水 → 下轮可重试
        assertThat(store.isRebuilt(222L)).isTrue();
        assertThat(c.get()).isEqualTo(2); // 失败后仍继续处理第 2 单
    }

    // ==================================================================================
    // G —— RebuildResult 自身的边界契约（防止 status=null / label 漂移）
    // ==================================================================================

    @Test
    @DisplayName("★证伪 G1：RebuildResult 五种 Status 的 label 与会带号的 Status 都受约束")
    void g1_statusLabelsAndOrderIdRules() {
        assertThat(RebuildResult.rebuilt(123L).statusLabel()).isEqualTo("已复建");
        assertThat(RebuildResult.skippedInflight(456L).statusLabel()).isEqualTo("已有投放中订单");
        assertThat(RebuildResult.skippedLimit().statusLabel()).isEqualTo("超每日上限");
        assertThat(RebuildResult.skippedInvalid().statusLabel()).isEqualTo("参数缺失");
        assertThat(RebuildResult.failed().statusLabel()).isEqualTo("复建失败");

        // 只有 REBUILT / SKIPPED_INFLIGHT 带号（G 列），其余 orderIdLabel 必须为 null
        assertThat(RebuildResult.rebuilt(123L).orderIdLabel()).isEqualTo("123");
        assertThat(RebuildResult.skippedInflight(456L).orderIdLabel()).isEqualTo("456");
        assertThat(RebuildResult.skippedLimit().orderIdLabel()).isNull();
        assertThat(RebuildResult.skippedInvalid().orderIdLabel()).isNull();
        assertThat(RebuildResult.failed().orderIdLabel()).isNull();
    }

    @Test
    @DisplayName("★证伪 G2：rebuilt(null) 必须拒绝（没新单号的成功是非法状态）")
    void g2_rebuiltNullRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RebuildResult.rebuilt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================================
    // 额外：ApiErrors 限流识别（防止把限流误判成普通错误而继续轰炸）
    // ==================================================================================

    @Test
    @DisplayName("★证伪 H：40100/40110/40130 必须被 ApiErrors 识别为限流")
    void h_rateLimitCodesRecognized() {
        assertThat(ApiErrors.rateLimitOrNull(40100L, "t", "r")).isNotNull();
        assertThat(ApiErrors.rateLimitOrNull(40110L, "t", "r")).isNotNull();
        assertThat(ApiErrors.rateLimitOrNull(40130L, "t", "r")).isNotNull();
        assertThat(ApiErrors.rateLimitOrNull(40000L, "t", "r")).isNull();
        assertThat(ApiErrors.rateLimitOrNull(0L, "t", "r")).isNull();
    }
}
