package com.aolandai.oceanengine.domain;

import com.aolandai.oceanengine.domain.SuixintuiReinvestCandidateSelector.CandidateOrder;
import com.aolandai.oceanengine.domain.SuixintuiReinvestCandidateSelector.RoiMetric;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【QA 独立验证，临时】ROAS 第二条路径的「差分等价」测试。
 *
 * <p>不信任工程师「roasHit = enabled && ...」这句话，而是把同一个订单集合分别喂给
 * {@code roasRuleEnabled=false} 与 {@code =true} 的选择器，并把结果与「用公开纯函数手算的参考集合」
 * 逐单比对：</p>
 * <ul>
 *   <li>开关关 → 必须 == 纯 {@link UsageRatioRule} 判定结果（等价性红线）；</li>
 *   <li>开关开 → 必须 == 使用率 OR ROAS 的结果；</li>
 *   <li>关时结果必须与 netSettleAmount 无关（改 netSettle 不影响关的结果）；</li>
 *   <li>开时「新纳入」的订单必须恰好是 usageHit=false & roasHit=true 的那些（admittedByRoas=true）。</li>
 * </ul>
 */
class RoasQaSelectorDiffTest {

    private static final long ADVID = 1000L;
    private static final long PRODUCT_HIGH = 3686032876161335490L; // 生产里覆盖成 6.0
    private static final long PRODUCT_OTHER = 1111111111111111111L;
    private static final double MIN_STAT_COST = 100.0;
    private static final double MIN_BUDGET_USED_RATIO = 0.8;
    private static final double ROAS_MIN_USAGE = 0.5;

    /** 生产口径：全局门槛 1.8，PRODUCT_HIGH 覆盖成 6.0 */
    private static ProductRoiRule prodRule() {
        Map<Long, Double> m = new LinkedHashMap<>();
        m.put(PRODUCT_HIGH, 6.0);
        return new ProductRoiRule(m, 1.8);
    }

    // ==================== 订单构造 ====================

    private static QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner order(
            long orderId, Long productId, Double roi, Double statCost, Double netSettle) {
        return new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner()
                .orderId(orderId)
                .adId(orderId + 1)
                .status(QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.DELIVERY_OK)
                .supportAddBudget(true)
                .productInfo(productId == null ? null
                        : new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo()
                                .productId(productId))
                .statsInfo(new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo()
                        .statCostForRoi2(statCost)
                        .totalPrepayAndPaySettleRoi21h(roi)
                        .totalOrderSettleAmountForRoi21h(netSettle));
    }

    /**
     * 一批覆盖各种组合的订单（budget 见返回的 map）：
     * 1 使用率过、ROAS 不过；2 使用率不过、ROAS 过；3 都过；4 都不过；
     * 5 高门槛商品(6.0) 净成交率 2.0 → ROAS 必须被拒；6 普通商品同数据 → ROAS 过；
     * 7 净成交 null；8 预算 0；9 消耗 null（被 minStatCost 过滤）；10 roi null（被 ROI 过滤）。
     */
    private static List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders() {
        return List.of(
                order(1L, PRODUCT_OTHER, 2.5, 170.0, 200.0),  // 使用率 0.85 过；净成交率 1.0 < 1.8 ROAS 不过
                order(2L, PRODUCT_OTHER, 2.5, 120.0, 500.0),  // 使用率 0.60 不过；净成交率 2.5 ≥ 1.8 ROAS 过
                order(3L, PRODUCT_OTHER, 2.5, 180.0, 500.0),  // 0.90 过 且 2.5 过
                order(4L, PRODUCT_OTHER, 2.5, 120.0, 200.0),  // 0.60 不过 且 1.0 不过
                order(5L, PRODUCT_HIGH, 6.5, 150.0, 400.0),   // 使用率 0.75 不过；净成交率 2.0 < 6.0 高门槛 → ROAS 不过
                order(6L, PRODUCT_OTHER, 2.0, 150.0, 400.0),  // 同数据但门槛 1.8 → 净成交率 2.0 ≥ 1.8 ROAS 过
                order(7L, PRODUCT_OTHER, 2.5, 150.0, null),   // 净成交 null → ROAS 不过
                order(8L, PRODUCT_OTHER, 2.5, 150.0, 400.0),  // budget=0 → 两条都不过
                order(9L, PRODUCT_OTHER, 2.5, null, 400.0),   // 消耗 null → 被 minStatCost 过滤
                order(10L, PRODUCT_OTHER, null, 150.0, 400.0)); // roi null → 被 ROI 过滤
    }

    private static Map<Long, Long> budgets() {
        Map<Long, Long> m = new HashMap<>();
        m.put(1L, 200L);
        m.put(2L, 200L);
        m.put(3L, 200L);
        m.put(4L, 200L);
        m.put(5L, 200L);
        m.put(6L, 200L);
        m.put(7L, 200L);
        m.put(8L, 0L);      // 预算 0：不能除零异常
        m.put(9L, 200L);
        m.put(10L, 200L);
        return m;
    }

    private static SuixintuiOrderDetailService mockDetail(Map<Long, Long> budgets) throws Exception {
        SuixintuiOrderDetailService detail = mock(SuixintuiOrderDetailService.class);
        for (Map.Entry<Long, Long> e : budgets.entrySet()) {
            when(detail.getTotalBudget(e.getKey(), ADVID)).thenReturn(e.getValue());
        }
        return detail;
    }

    private static Set<Long> ids(List<CandidateOrder> cs) {
        return cs.stream().map(c -> c.orderId).collect(Collectors.toSet());
    }

    /** 用公开纯函数手算「开关关」时的参考候选集合（完全不含任何 ROAS 逻辑）。 */
    private static Set<Long> referenceUsageOnly(
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders,
            ProductRoiRule rule, Map<Long, Long> budgets) {
        return orders.stream().filter(o -> {
            if (o.getStatus() != QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.DELIVERY_OK) {
                return false;
            }
            if (o.getSupportAddBudget() == null || !o.getSupportAddBudget()) {
                return false;
            }
            Double statCost = o.getStatsInfo() == null ? null : o.getStatsInfo().getStatCostForRoi2();
            if (!SuixintuiReinvestCandidateSelector.statCostOk(statCost, MIN_STAT_COST)) {
                return false;
            }
            Long pid = SuixintuiReinvestCandidateSelector.productIdOf(o);
            Double roi = SuixintuiReinvestCandidateSelector.roiOf(o, RoiMetric.SETTLE_ROI);
            if (!rule.roiOk(pid, roi)) {
                return false;
            }
            Long budget = budgets.get(o.getOrderId());
            return UsageRatioRule.hit(statCost, null, budget, MIN_BUDGET_USED_RATIO);
        }).map(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner::getOrderId)
                .collect(Collectors.toSet());
    }

    /** 手算「开关开」时的参考集合：使用率 OR ROAS。 */
    private static Set<Long> referenceUsageOrRoas(
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders,
            ProductRoiRule rule, Map<Long, Long> budgets) {
        return orders.stream().filter(o -> {
            if (o.getStatus() != QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.DELIVERY_OK) {
                return false;
            }
            if (o.getSupportAddBudget() == null || !o.getSupportAddBudget()) {
                return false;
            }
            Double statCost = o.getStatsInfo() == null ? null : o.getStatsInfo().getStatCostForRoi2();
            if (!SuixintuiReinvestCandidateSelector.statCostOk(statCost, MIN_STAT_COST)) {
                return false;
            }
            Long pid = SuixintuiReinvestCandidateSelector.productIdOf(o);
            Double roi = SuixintuiReinvestCandidateSelector.roiOf(o, RoiMetric.SETTLE_ROI);
            if (!rule.roiOk(pid, roi)) {
                return false;
            }
            Long budget = budgets.get(o.getOrderId());
            Double netSettle = o.getStatsInfo() == null
                    ? null : o.getStatsInfo().getTotalOrderSettleAmountForRoi21h();
            boolean usageHit = UsageRatioRule.hit(statCost, null, budget, MIN_BUDGET_USED_RATIO);
            boolean roasHit = RoasRule.hit(statCost, netSettle, budget,
                    ROAS_MIN_USAGE, rule.minRoiFor(pid));
            return usageHit || roasHit;
        }).map(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner::getOrderId)
                .collect(Collectors.toSet());
    }

    // ==================== ① 开关关闭 == 纯使用率 ====================

    @Test
    @DisplayName("① 开关关闭：候选集合逐单等于「纯使用率」参考结果（等价性红线）")
    void flagOff_equalsPureUsageRatio() throws Exception {
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders = orders();
        Map<Long, Long> budgets = budgets();
        ProductRoiRule rule = prodRule();

        SuixintuiReinvestCandidateSelector off = new SuixintuiReinvestCandidateSelector(
                null, mockDetail(budgets), null, MIN_STAT_COST, rule, false, ROAS_MIN_USAGE);

        List<CandidateOrder> got = off.findReinvestCandidates(
                orders, ADVID, 1.8, MIN_BUDGET_USED_RATIO, RoiMetric.SETTLE_ROI);

        Set<Long> expected = referenceUsageOnly(orders, rule, budgets);
        assertThat(ids(got))
                .as("开关关闭时候选集合必须与纯使用率判定完全一致")
                .isEqualTo(expected);
        assertThat(got).allMatch(c -> !c.admittedByRoas,
                "开关关闭时 admittedByRoas 必须恒为 false");
    }

    @Test
    @DisplayName("① 开关关闭：结果与 netSettleAmount 无关（改净成交值不改变任何候选）")
    void flagOff_isInvariantToNetSettle() throws Exception {
        Map<Long, Long> budgets = budgets();
        ProductRoiRule rule = prodRule();

        // 同一批订单，净成交全部塞成 999999（若误把 ROAS 生效，集合会变大）
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> hugeNet = orders().stream()
                .map(o -> order(o.getOrderId(),
                        SuixintuiReinvestCandidateSelector.productIdOf(o),
                        SuixintuiReinvestCandidateSelector.roiOf(o, RoiMetric.SETTLE_ROI),
                        o.getStatsInfo() == null ? null : o.getStatsInfo().getStatCostForRoi2(),
                        999999.0))
                .collect(Collectors.toList());

        SuixintuiReinvestCandidateSelector off = new SuixintuiReinvestCandidateSelector(
                null, mockDetail(budgets), null, MIN_STAT_COST, rule, false, ROAS_MIN_USAGE);

        Set<Long> a = ids(off.findReinvestCandidates(
                orders(), ADVID, 1.8, MIN_BUDGET_USED_RATIO, RoiMetric.SETTLE_ROI));
        Set<Long> b = ids(off.findReinvestCandidates(
                hugeNet, ADVID, 1.8, MIN_BUDGET_USED_RATIO, RoiMetric.SETTLE_ROI));

        assertThat(b).as("开关关闭时净成交值不应影响候选集合").isEqualTo(a);
    }

    // ==================== ② 开关打开 == 使用率 OR ROAS ====================

    @Test
    @DisplayName("② 开关打开：候选集合 == 使用率 OR ROAS；新增的恰好是「仅 ROAS」")
    void flagOn_equalsUsageOrRoas_andNewOnesAreRoasOnly() throws Exception {
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders = orders();
        Map<Long, Long> budgets = budgets();
        ProductRoiRule rule = prodRule();

        SuixintuiReinvestCandidateSelector off = new SuixintuiReinvestCandidateSelector(
                null, mockDetail(budgets), null, MIN_STAT_COST, rule, false, ROAS_MIN_USAGE);
        SuixintuiReinvestCandidateSelector on = new SuixintuiReinvestCandidateSelector(
                null, mockDetail(budgets), null, MIN_STAT_COST, rule, true, ROAS_MIN_USAGE);

        List<CandidateOrder> gotOff = off.findReinvestCandidates(
                orders, ADVID, 1.8, MIN_BUDGET_USED_RATIO, RoiMetric.SETTLE_ROI);
        List<CandidateOrder> gotOn = on.findReinvestCandidates(
                orders, ADVID, 1.8, MIN_BUDGET_USED_RATIO, RoiMetric.SETTLE_ROI);

        assertThat(ids(gotOn)).isEqualTo(referenceUsageOrRoas(orders, rule, budgets));

        // 新增的单必须是「仅 ROAS」；原有单 admittedByRoas=false
        Set<Long> offIds = ids(gotOff);
        Set<Long> onIds = ids(gotOn);
        assertThat(onIds).containsAll(offIds);
        assertThat(gotOn).filteredOn(c -> c.admittedByRoas)
                .extracting(c -> c.orderId)
                .containsExactlyInAnyOrderElementsOf(
                        onIds.stream().filter(id -> !offIds.contains(id)).collect(Collectors.toSet()));
        // 不变量：关的结果 == 开的结果里「非仅 ROAS」的那些
        assertThat(offIds).isEqualTo(gotOn.stream()
                .filter(c -> !c.admittedByRoas)
                .map(c -> c.orderId)
                .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("③ 门槛复用：高门槛商品(6.0) 净成交率 2.0 被 ROAS 拒绝；普通商品同数据被纳入")
    void roas_reusesProductLevelThreshold() throws Exception {
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders = orders();
        Map<Long, Long> budgets = budgets();
        ProductRoiRule rule = prodRule();

        SuixintuiReinvestCandidateSelector on = new SuixintuiReinvestCandidateSelector(
                null, mockDetail(budgets), null, MIN_STAT_COST, rule, true, ROAS_MIN_USAGE);
        Set<Long> onIds = ids(on.findReinvestCandidates(
                orders, ADVID, 1.8, MIN_BUDGET_USED_RATIO, RoiMetric.SETTLE_ROI));

        assertThat(onIds).as("高门槛商品(6.0) 净成交率 2.0 < 6.0 → ROAS 不应纳入")
                .doesNotContain(5L);
        assertThat(onIds).as("普通商品(1.8) 净成交率 2.0 ≥ 1.8 → ROAS 应纳入")
                .contains(6L);
    }

    // ==================== ④ ROAS 规则边界（独立补测） ====================

    @Test
    @DisplayName("④ 边界：消耗比正好 0.5 → true；0.4999 → false")
    void boundary_usage() {
        // B=200；0.5 → 100；0.4999 → 99.98
        assertThat(RoasRule.hit(100.0, 400.0, 200L, 0.5, 1.8)).isTrue();
        assertThat(RoasRule.hit(99.98, 400.0, 200L, 0.5, 1.8)).isFalse();
    }

    @Test
    @DisplayName("④ 边界：净成交比正好等于门槛 → true；略低 → false")
    void boundary_netSettle() {
        // 门槛 1.8，B=200 → 净成交 360 正好 1.8；359.8 → 1.799
        assertThat(RoasRule.hit(150.0, 360.0, 200L, 0.5, 1.8)).isTrue();
        assertThat(RoasRule.hit(150.0, 359.8, 200L, 0.5, 1.8)).isFalse();
    }

    @Test
    @DisplayName("④ 边界：商品级高门槛 6.0 时净成交比 1.8 → false")
    void boundary_highThreshold() {
        // B=200；净成交 360 → 1.8 < 6.0
        assertThat(RoasRule.hit(150.0, 360.0, 200L, 0.5, 6.0)).isFalse();
        // 净成交 1200 → 6.0 正好 → true
        assertThat(RoasRule.hit(150.0, 1200.0, 200L, 0.5, 6.0)).isTrue();
    }

    @Test
    @DisplayName("④ 边界：netSettle=null → false；budget=0 → false（不抛异常）")
    void boundary_nullAndZero() {
        assertThat(RoasRule.hit(150.0, null, 200L, 0.5, 1.8)).isFalse();
        assertThat(RoasRule.hit(150.0, 400.0, 0L, 0.5, 1.8)).isFalse();
        assertThat(RoasRule.hit(null, 400.0, 200L, 0.5, 1.8)).isFalse();
        assertThat(RoasRule.hit(150.0, 400.0, null, 0.5, 1.8)).isFalse();
    }

    // ==================== ⑥ 日志可读性 ====================

    @Test
    @DisplayName("⑥ 日志：三条路径可区分 + 轮次汇总含「仅靠第二条路径纳入 N 单」")
    void logs_distinguishThreePaths() throws Exception {
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders = orders();
        Map<Long, Long> budgets = budgets();
        ProductRoiRule rule = prodRule();

        SuixintuiReinvestCandidateSelector on = new SuixintuiReinvestCandidateSelector(
                null, mockDetail(budgets), null, MIN_STAT_COST, rule, true, ROAS_MIN_USAGE);

        String out = captureStdout(() -> on.findReinvestCandidates(
                orders, ADVID, 1.8, MIN_BUDGET_USED_RATIO, RoiMetric.SETTLE_ROI));

        assertThat(out).contains("纳入路径=使用率");
        assertThat(out).contains("纳入路径=ROAS(仅第二条)");
        assertThat(out).contains("纳入路径=使用率+ROAS");
        assertThat(out).contains("其中仅靠第二条路径(ROAS)纳入");
        assertThat(out).contains("净成交金额=");
        assertThat(out).contains("净成交率=");
    }

    // ==================== 工具 ====================

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String captureStdout(ThrowingRunnable r) throws Exception {
        PrintStream orig = System.out;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bos, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setOut(orig);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }
}
