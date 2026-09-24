package com.example.oceanengine.domain;

import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo;
import com.example.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.example.oceanengine.service.suixintui.SuixintuiOrderService;

import java.util.ArrayList;
import java.util.List;

/**
 * 追投候选订单筛选业务类（领域层）。
 *
 * <p>业务链路（追投动作本身暂不实现）：</p>
 * <ol>
 *   <li>拉取某千川账户下全部「随心推全域-商品订单」；
 *       筛选出 <b>投放中（DELIVERY_OK）且 ROI ≥ 阈值</b>（默认 2.0）的订单；</li>
 *   <li>对筛选结果逐单调用订单详情接口，取创建时设定的投放金额（预算 delivery_setting.amount）；</li>
 *   <li>计算「整体消耗 / 投放金额」使用率，筛出 <b>≥ 阈值</b>（默认 80%）的订单，
 *       作为后续追投候选（预算快消耗完且 ROI 达标，适合加预算）。</li>
 * </ol>
 *
 * <p>依赖底层服务：{@link SuixintuiOrderService}（订单列表）、
 * {@link SuixintuiOrderDetailService}（订单详情/预算）。
 * 本类只做业务筛选编排，不直接调 SDK 接口。</p>
 *
 * <p>注意：ROI 口径可通过 {@link RoiMetric} 选择；详情接口为「一单一请求」，
 * 订单量大时耗时较长，请自行评估。预算单位为分（以官方文档为准）。</p>
 */
public class SuixintuiReinvestCandidateSelector {

    /** ROI 口径 */
    public enum RoiMetric {
        /** 整体支付 ROI：total_prepay_and_pay_order_roi2 */
        OVERALL_PAY_ROI,
        /** 净成交 ROI：total_prepay_and_pay_settle_roi2_1h */
        SETTLE_ROI
    }

    /** 追投候选订单（含筛选所需字段） */
    public static class CandidateOrder {
        public final Long orderId;
        public final Long adId;
        public final String awemeShowId;
        public final Double roi;             // 按所选口径的 ROI
        public final Double statCost;        // 整体消耗（stats_info.stat_cost_for_roi2）
        public final Long budget;            // 投放金额（创建时设定，delivery_setting.amount）
        public final double budgetUsedRatio; // 整体消耗 / 投放金额

        public CandidateOrder(Long orderId, Long adId, String awemeShowId,
                              Double roi, Double statCost, Long budget, double budgetUsedRatio) {
            this.orderId = orderId;
            this.adId = adId;
            this.awemeShowId = awemeShowId;
            this.roi = roi;
            this.statCost = statCost;
            this.budget = budget;
            this.budgetUsedRatio = budgetUsedRatio;
        }

        @Override
        public String toString() {
            return String.format(
                    "CandidateOrder{orderId=%s, adId=%s, awemeShowId=%s, roi=%.2f, "
                            + "statCost=%.2f, budget=%s, budgetUsedRatio=%.2f%%}",
                    orderId, adId, awemeShowId,
                    roi == null ? 0.0 : roi,
                    statCost == null ? 0.0 : statCost,
                    budget,
                    budgetUsedRatio * 100);
        }
    }

    private final SuixintuiOrderService orderService;
    private final SuixintuiOrderDetailService detailService;

    public SuixintuiReinvestCandidateSelector(SuixintuiOrderService orderService,
                                              SuixintuiOrderDetailService detailService) {
        this.orderService = orderService;
        this.detailService = detailService;
    }

    // ==================== 入口 ====================

    /** 默认筛选：投放中 + 整体支付 ROI ≥ 2.0 + 整体消耗/投放金额 ≥ 80% */
    public List<CandidateOrder> findReinvestCandidates(Long advertiserId) throws ApiException {
        return findReinvestCandidates(advertiserId, 2.0, 0.8, RoiMetric.OVERALL_PAY_ROI);
    }

    /** 指定 ROI 与消耗使用率阈值，ROI 口径默认整体支付 ROI */
    public List<CandidateOrder> findReinvestCandidates(Long advertiserId,
                                                       double minRoi,
                                                       double minBudgetUsedRatio) throws ApiException {
        return findReinvestCandidates(advertiserId, minRoi, minBudgetUsedRatio, RoiMetric.OVERALL_PAY_ROI);
    }

    /**
     * 完整参数版（内部先拉订单列表，再走列表版筛选）。
     *
     * @param advertiserId       千川业务账户 ID
     * @param minRoi             ROI 下限（如 2.0 表示 ROI ≥ 2）
     * @param minBudgetUsedRatio 整体消耗/投放金额 下限（如 0.8 表示 ≥ 80%）
     * @param metric             ROI 口径（整体支付 ROI 或 净成交 ROI）
     * @return 追投候选订单列表（按预算使用率降序）
     */
    public List<CandidateOrder> findReinvestCandidates(Long advertiserId,
                                                       double minRoi,
                                                       double minBudgetUsedRatio,
                                                       RoiMetric metric) throws ApiException {
        // 先拉全部商品全域订单，再走列表版筛选（详情只对 ROI 达标订单调用）
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                orderService.fetchUniPromotionOrders(advertiserId);
        System.out.println("订单列表返回: " + orders.size() + " 条");
        return findReinvestCandidates(orders, advertiserId, minRoi, minBudgetUsedRatio, metric);
    }

    /**
     * 列表版：调用方已拉取订单列表时复用，避免重复拉列表。
     *
     * <p>流程：① 内存筛选「投放中 + ROI ≥ 阈值」；② 仅对筛选结果逐单调详情接口取预算；
     * ③ 计算整体消耗/投放金额，筛出 ≥ 阈值的订单。</p>
     *
     * @param orders             已拉取的全量商品全域订单列表
     * @param advertiserId       千川业务账户 ID（详情接口必填）
     * @param minRoi             ROI 下限
     * @param minBudgetUsedRatio 整体消耗/投放金额 下限
     * @param metric             ROI 口径
     * @return 追投候选订单列表（按预算使用率降序）
     */
    public List<CandidateOrder> findReinvestCandidates(
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders,
            Long advertiserId,
            double minRoi,
            double minBudgetUsedRatio,
            RoiMetric metric) throws ApiException {

        // 1. 内存筛选：投放中 + ROI ≥ 阈值
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> roiOk =
                new ArrayList<>();
        for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : orders) {
            if (o.getStatus() != QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.DELIVERY_OK) {
                continue;
            }
            Double roi = roiOf(o, metric);
            if (roi != null && roi >= minRoi) {
                roiOk.add(o);
            }
        }
        System.out.println("投放中且 ROI ≥ " + minRoi + "（口径 " + metric + "）: " + roiOk.size()
                + " 条，将对这 " + roiOk.size() + " 条调用详情接口");

        // 3. 逐单取投放金额（预算），计算整体消耗/投放金额，筛选使用率 ≥ 阈值
        List<CandidateOrder> candidates = new ArrayList<>();
        int detailFailed = 0;
        for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : roiOk) {
            try {
                Long budget = detailService.getBudget(o.getOrderId(), advertiserId);

                Double statCost = o.getStatsInfo() == null
                        ? null : o.getStatsInfo().getStatCostForRoi2();

                double usedRatio = calcBudgetUsedRatio(statCost, budget);
                if (usedRatio < minBudgetUsedRatio) {
                    continue;
                }

                candidates.add(new CandidateOrder(
                        o.getOrderId(),
                        o.getAdId(),
                        o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeShowId(),
                        roiOf(o, metric),
                        statCost,
                        budget,
                        usedRatio));
            } catch (ApiException e) {
                detailFailed++;
                System.out.println("  [WARN] order_id=" + o.getOrderId()
                        + " 详情查询失败: " + e.getMessage());
            }
        }

        // 按预算使用率降序（最需要追投的排前面）
        candidates.sort((a, b) -> Double.compare(b.budgetUsedRatio, a.budgetUsedRatio));

        System.out.println("整体消耗/投放金额 ≥ " + (minBudgetUsedRatio * 100)
                + "%: " + candidates.size() + " 条（详情失败跳过 " + detailFailed + " 条）");
        return candidates;
    }

    // ==================== 工具 ====================

    /** 按口径取订单 ROI */
    private static Double roiOf(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o,
                                RoiMetric metric) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo s = o.getStatsInfo();
        if (s == null) {
            return null;
        }
        return metric == RoiMetric.SETTLE_ROI
                ? s.getTotalPrepayAndPaySettleRoi21h()
                : s.getTotalPrepayAndPayOrderRoi2();
    }

    /**
     * 计算整体消耗/投放金额。
     * 预算缺失、为 0 或消耗缺失时返回 0（视为未达标，由调用方阈值过滤）。
     */
    private static double calcBudgetUsedRatio(Double statCost, Long budget) {
        if (statCost == null || budget == null || budget <= 0) {
            return 0.0;
        }
        return statCost / budget;
    }
}
