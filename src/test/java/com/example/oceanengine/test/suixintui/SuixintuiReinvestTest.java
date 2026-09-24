package com.example.oceanengine.test.suixintui;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.example.oceanengine.client.SuixintuiTokenClient;
import com.example.oceanengine.domain.SuixintuiReinvestCandidateSelector;
import com.example.oceanengine.domain.SuixintuiReinvestCandidateSelector.CandidateOrder;
import com.example.oceanengine.domain.SuixintuiReinvestCandidateSelector.RoiMetric;
import com.example.oceanengine.model.SuixintuiUniOrderListRequest;
import com.example.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.example.oceanengine.service.suixintui.SuixintuiOrderService;
import com.example.oceanengine.service.suixintui.SuixintuiReinvestService;

import java.util.List;

/**
 * 随心推追投测试：筛出追投候选（投放中 + 净成交 ROI ≥ 2 + 整体消耗/投放总金额 ≥ 阈值），
 * 再对候选订单逐个追加预算。
 *
 * <p>⚠️ 追投 = 真实资金操作（增加订单预算上限）。默认 <b>dry-run</b> 只打印计划不执行；
 * 确认真实执行需设置环境变量 <code>SUIXINTUI_REINVEST_DRY_RUN=false</code>。</p>
 *
 * <p>链路：</p>
 * <ol>
 *   <li>列表：{@link SuixintuiOrderService#fetchUniPromotionOrders(SuixintuiUniOrderListRequest)}</li>
 *   <li>筛选：{@link SuixintuiReinvestCandidateSelector#findReinvestCandidates(
 *       List, Long, double, double, RoiMetric)}</li>
 *   <li>追投：{@link SuixintuiReinvestService#addBudget(Long, Long, Long, Double)}
 *       （add_amount 单位元，add_delivery_time 必填）</li>
 * </ol>
 *
 * <p>环境变量：</p>
 * <ul>
 *   <li>QIANCHUAN_SERVICE_BEARER_TOKEN —— 必填，Access Token 由服务端下发</li>
 *   <li>SUIXINTUI_ADVERTISER_ID —— 目标千川业务账户（可选，默认 1779106144470100L）</li>
 *   <li>SUIXINTUI_REINVEST_AMOUNT —— 每单追加金额，单位元（可选，默认 100）</li>
 *   <li>SUIXINTUI_REINVEST_DELIVERY_TIME —— 追加投放时长，小时，须为
 *       24/48/72/96/120/144/168 之一（可选，默认 24）</li>
 *   <li>SUIXINTUI_REINVEST_DRY_RUN —— 是否仅演练（可选，默认 true；设 false 才真实追投）</li>
 * </ul>
 */
public class SuixintuiReinvestTest {

    private static final Long DEFAULT_ADVERTISER_ID = 1779106144470100L;

    /** 筛选阈值：投放中 + 净成交 ROI ≥ 2 + 整体消耗/投放总金额 ≥ 50% */
    private static final double MIN_ROI = 2.0;
    private static final double MIN_BUDGET_USED_RATIO = 0.5;

    public static void main(String[] args) throws Exception {
        // ========== 1. 初始化 ==========
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        Long advertiserId = parseEnvLong("SUIXINTUI_ADVERTISER_ID", DEFAULT_ADVERTISER_ID);

        boolean dryRun = parseEnvBoolean("SUIXINTUI_REINVEST_DRY_RUN", true);
        long addAmount = parseEnvLong("SUIXINTUI_REINVEST_AMOUNT", 100L);            // 单位：元
        double addDeliveryTime = parseEnvDouble("SUIXINTUI_REINVEST_DELIVERY_TIME", 24.0); // 单位：小时

        System.out.println("=== 随心推追投 ===");
        System.out.println("advertiser_id: " + advertiserId
                + "，筛选: 投放中 + 净成交ROI≥" + MIN_ROI + " + 消耗/投放总金额≥" + (MIN_BUDGET_USED_RATIO * 100) + "%");
        System.out.println("追投配置: 每单追加 " + addAmount + " 元，延长投放 " + addDeliveryTime + " 小时"
                + (dryRun ? "，DRY-RUN 模式（不真实执行）" : "，真实执行！"));

        // ========== 2. 拉取订单列表 ==========
        SuixintuiUniOrderListRequest request = new SuixintuiUniOrderListRequest(advertiserId)
                .orderCreateStartDate("2026-09-22")
                .orderCreateEndDate("2026-09-23")
                .status(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.DELIVERY_OK)
                .orderField(QianchuanAwemeUniPromotionOrderGetV10OrderField.STAT_COST_FOR_ROI2);

        SuixintuiOrderService orderService = new SuixintuiOrderService(apiClient, token);
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                orderService.fetchUniPromotionOrders(request);
        System.out.println("订单列表返回: " + orders.size() + " 条");

        // ========== 3. 筛选追投候选 ==========
        SuixintuiOrderDetailService detailService = new SuixintuiOrderDetailService(apiClient, token);
        SuixintuiReinvestCandidateSelector selector =
                new SuixintuiReinvestCandidateSelector(orderService, detailService);

        System.out.println();
        List<CandidateOrder> candidates = selector.findReinvestCandidates(
                orders, advertiserId, MIN_ROI, MIN_BUDGET_USED_RATIO, RoiMetric.SETTLE_ROI);

        // ========== 4. 输出候选 + 追投 ==========
        System.out.println();
        System.out.println("=== 追投候选（" + candidates.size() + " 条） ===");
        for (int i = 0; i < candidates.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, candidates.get(i));
        }

        if (candidates.isEmpty()) {
            System.out.println("无追投候选，结束。");
            return;
        }

        long totalAdd = addAmount * candidates.size();
        System.out.println();
        System.out.println("计划追投 " + candidates.size() + " 单，每单 +" + addAmount
                + " 元，合计 +" + totalAdd + " 元");

        if (dryRun) {
            System.out.println("[DRY-RUN] 未调用加预算接口。确认无误后设置 SUIXINTUI_REINVEST_DRY_RUN=false 真实执行。");
            return;
        }

        SuixintuiReinvestService reinvestService = new SuixintuiReinvestService(apiClient, token);
        int success = 0;
        for (CandidateOrder c : candidates) {
            try {
                reinvestService.addBudget(advertiserId, c.orderId, addAmount, addDeliveryTime);
                success++;
                System.out.println("  [追投成功] order_id=" + c.orderId + " +" + addAmount + " 元，+" + addDeliveryTime + " 小时");
            } catch (Exception e) {
                System.out.println("  [追投失败] order_id=" + c.orderId + " 原因: " + e.getMessage());
            }
        }
        System.out.println("追投完成: 成功 " + success + " / " + candidates.size());
    }

    // ==================== 配置 ====================

    private static Long parseEnvLong(String name, Long defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(v.trim());
    }

    private static boolean parseEnvBoolean(String name, boolean defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v.trim());
    }

    private static double parseEnvDouble(String name, double defaultValue) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(v.trim());
    }
}
