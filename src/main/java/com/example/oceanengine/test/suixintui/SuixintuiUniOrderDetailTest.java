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

import java.util.List;

/**
 * 追投候选筛选：先拉一次「随心推全域订单列表」，内存筛选 ROI ≥ 2（投放中），
 * 再仅对筛选结果调用「获取随心推全域订单详情」接口取投放金额（预算 delivery_setting.amount），
 * 最终筛出「整体消耗 / 投放金额 ≥ 80%」的订单列表（用于后续追投，追投动作暂不实现）。
 *
 * <p>效率说明：列表只拉一次；详情接口只对 ROI 达标的订单调用，
 * 避免对无关订单（ROI 不达标/非投放中）浪费详情请求。</p>
 *
 * <p>链路：</p>
 * <ol>
 *   <li>列表：GET /open_api/v1.0/qianchuan/aweme/uni_promotion/order/get/
 *       （{@link SuixintuiOrderService#fetchUniPromotionOrders(SuixintuiUniOrderListRequest)}）</li>
 *   <li>筛选+详情：{@link SuixintuiReinvestCandidateSelector#findReinvestCandidates(
 *       List, Long, double, double, RoiMetric)}</li>
 * </ol>
 *
 * <p>运行前环境变量：</p>
 * <ul>
 *   <li>QIANCHUAN_SERVICE_BEARER_TOKEN —— 自建服务端认证密钥（必填，Access Token 由服务端下发）</li>
 *   <li>SUIXINTUI_ADVERTISER_ID —— 目标千川业务账户 ID（可选，默认 1779106144470100L）</li>
 * </ul>
 */
public class SuixintuiUniOrderDetailTest {

    private static final Long DEFAULT_ADVERTISER_ID = 1779106144470100L;

    /** 追投候选筛选阈值：投放中 + ROI ≥ 2 + 整体消耗/投放金额 ≥ 80% */
    private static final double MIN_ROI = 2.0;
    private static final double MIN_BUDGET_USED_RATIO = 0.8;

    public static void main(String[] args) throws Exception {
        // ========== 1. 初始化 ==========
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        Long advertiserId = parseAdvertiserId();

        System.out.println("=== 随心推全域订单 -> 追投候选 ===");
        System.out.println("advertiser_id: " + advertiserId
                + "，筛选条件: 投放中 + ROI≥" + MIN_ROI + " + 整体消耗/投放金额≥" + (MIN_BUDGET_USED_RATIO * 100) + "%");

        // ========== 2. 拉取订单列表（只拉一次） ==========
        SuixintuiUniOrderListRequest request = new SuixintuiUniOrderListRequest(advertiserId)
                .orderCreateStartDate("2026-09-22")
                .orderCreateEndDate("2026-09-23")
                .status(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.DELIVERY_OK)
                .orderField(QianchuanAwemeUniPromotionOrderGetV10OrderField.STAT_COST_FOR_ROI2);

        SuixintuiOrderService service = new SuixintuiOrderService(apiClient, token);
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                service.fetchUniPromotionOrders(request);
        System.out.println("订单列表返回: " + orders.size() + " 条");

        // ========== 3. 追投候选筛选（列表版，详情只对 ROI 达标订单调用） ==========
        SuixintuiOrderDetailService detailService = new SuixintuiOrderDetailService(apiClient, token);
        SuixintuiReinvestCandidateSelector selector =
                new SuixintuiReinvestCandidateSelector(service, detailService);

        System.out.println();
        List<CandidateOrder> candidates = selector.findReinvestCandidates(
                orders, advertiserId, MIN_ROI, MIN_BUDGET_USED_RATIO, RoiMetric.OVERALL_PAY_ROI);

        // ========== 4. 输出候选 ==========
        System.out.println();
        System.out.println("=== 追投候选（" + candidates.size() + " 条，按预算使用率降序） ===");
        for (int i = 0; i < candidates.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, candidates.get(i));
        }
    }

    // ==================== 配置 ====================

    private static Long parseAdvertiserId() {
        String v = System.getenv("SUIXINTUI_ADVERTISER_ID");
        if (v == null || v.isBlank()) {
            System.out.println("未设置 SUIXINTUI_ADVERTISER_ID，使用默认账户: " + DEFAULT_ADVERTISER_ID);
            return DEFAULT_ADVERTISER_ID;
        }
        return Long.parseLong(v.trim());
    }
}
