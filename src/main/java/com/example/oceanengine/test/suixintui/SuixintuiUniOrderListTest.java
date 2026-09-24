package com.example.oceanengine.test.suixintui;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.example.oceanengine.client.SuixintuiTokenClient;
import com.example.oceanengine.model.SuixintuiUniOrderListRequest;
import com.example.oceanengine.service.suixintui.SuixintuiOrderService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拉取指定千川账户的「随心推全域订单列表」（商品全域 VIDEO_PROM_GOODS）。
 *
 * <p>复用 {@link SuixintuiOrderService#fetchUniPromotionOrders(SuixintuiUniOrderListRequest)}：
 * 接口 /open_api/v1.0/qianchuan/aweme/uni_promotion/order/get/，cursor 分页拉全。</p>
 *
 * <p>运行前环境变量：</p>
 * <ul>
 *   <li>QIANCHUAN_SERVICE_BEARER_TOKEN —— 自建服务端认证密钥（必填，Access Token 由服务端下发）</li>
 *   <li>SUIXINTUI_ADVERTISER_ID —— 目标千川业务账户 ID（可选，默认 1779106144470100L）</li>
 *   <li>SUIXINTUI_START_DATE / SUIXINTUI_END_DATE —— 订单创建日期范围 yyyy-MM-dd（可选，用于收窄拉取）</li>
 * </ul>
 */
public class SuixintuiUniOrderListTest {

    private static final Long DEFAULT_ADVERTISER_ID = 1779106144470100L;

    /** 明细最多打印条数，避免大账户刷屏 */
    private static final int MAX_DETAIL_PRINT = 100;

    public static void main(String[] args) throws Exception {
        // ========== 1. 初始化 ==========
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        Long advertiserId = parseAdvertiserId();

        // 用 DTO 构造请求：advertiserId 必填，日期范围可选（未设置则拉全量）
        SuixintuiUniOrderListRequest request = new SuixintuiUniOrderListRequest(advertiserId)
                .orderCreateStartDate("2026-09-22")
                .orderCreateEndDate("2026-09-23")
                .status(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.DELIVERY_OK)
                .orderField(QianchuanAwemeUniPromotionOrderGetV10OrderField.STAT_COST_FOR_ROI2);

        System.out.println("=== 拉取随心推全域订单列表 ===");
        System.out.println("请求参数: " + request);

        // ========== 2. 拉取 ==========
        SuixintuiOrderService service = new SuixintuiOrderService(apiClient, token);

        long t0 = System.currentTimeMillis();
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                service.fetchUniPromotionOrders(request);
        long costMillis = System.currentTimeMillis() - t0;

        // ========== 3. 汇总 ==========
        System.out.println();
        System.out.println("=== 拉取完成 ===");
        System.out.println("随心推全域订单数: " + orders.size() + " 条（耗时 " + costMillis + " ms）");

        printStatusBreakdown(orders);

        // ========== 4. 明细 ==========
        System.out.println();
        System.out.println("=== 订单明细（前 " + Math.min(orders.size(), MAX_DETAIL_PRINT) + " 条） ===");
        for (int i = 0; i < orders.size() && i < MAX_DETAIL_PRINT; i++) {
            QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o = orders.get(i);
            System.out.printf("%d. order_id=%s ad_id=%s status=%s 创建=%s 抖音号=%s(%s) 商品=%s(%s) 整体消耗=%.2f 成交GMV=%.2f 净成交ROI=%.2f%n",
                    i + 1,
                    o.getOrderId(),
                    o.getAdId(),
                    o.getStatus(),
                    o.getOrderCreateTime(),
                    o.getAwemeInfo() == null ? "-" : o.getAwemeInfo().getAwemeShowId(),
                    o.getAwemeInfo() == null ? "-" : o.getAwemeInfo().getAwemeName(),
                    o.getProductInfo() == null ? "-" : o.getProductInfo().getProductName(),
                    o.getProductInfo() == null ? "-" : o.getProductInfo().getProductId(),
                    num(o.getStatsInfo() == null ? null : o.getStatsInfo().getStatCostForRoi2()),
                    num(o.getStatsInfo() == null ? null : o.getStatsInfo().getTotalPayOrderGmvForRoi2()),
                    num(o.getStatsInfo() == null ? null : o.getStatsInfo().getTotalPrepayAndPaySettleRoi21h()));
                     System.out.println();
        }
        if (orders.size() > MAX_DETAIL_PRINT) {
            System.out.println("  ... 共 " + orders.size() + " 条，仅打印前 " + MAX_DETAIL_PRINT + " 条");
        }
    }

    // ==================== 输出 ====================

    /** 按订单状态统计条数 */
    private static void printStatusBreakdown(
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : orders) {
            String status = String.valueOf(o.getStatus());
            byStatus.merge(status, 1, Integer::sum);
        }
        System.out.println("订单状态分布: " + byStatus);
    }

    private static double num(Double v) {
        return v == null ? 0.0 : v;
    }

    // ==================== 配置 ====================

    /** 读取环境变量，未设置/空白时返回 null */
    private static String envOrNull(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static Long parseAdvertiserId() {
        String v = System.getenv("SUIXINTUI_ADVERTISER_ID");
        if (v == null || v.isBlank()) {
            System.out.println("未设置 SUIXINTUI_ADVERTISER_ID，使用默认账户: " + DEFAULT_ADVERTISER_ID);
            return DEFAULT_ADVERTISER_ID;
        }
        return Long.parseLong(v.trim());
    }
}
