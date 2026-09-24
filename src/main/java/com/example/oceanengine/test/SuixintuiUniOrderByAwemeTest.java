package com.example.oceanengine.test;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.example.oceanengine.client.SuixintuiTokenClient;
import com.example.oceanengine.service.suixintui.SuixintuiUniOrderByAwemeService;
import com.example.oceanengine.service.suixintui.SuixintuiUniOrderByAwemeService.AwemeOrderFetchResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 需求：通过抖音ID获取该抖音号名下所有「随心推商品全域订单」。
 *
 * <p>运行前环境变量：</p>
 * <ul>
 *   <li>QIANCHUAN_SERVICE_BEARER_TOKEN —— 自建服务端认证密钥（必填，Access Token 由服务端下发）</li>
 *   <li>SUIXINTUI_AWEME_ID —— 目标抖音ID（aweme_id，数字 UID，必填）</li>
 *   <li>SUIXINTUI_ADVERTISER_ID —— 千川业务账户 ID，多个用英文逗号分隔（可选，默认自动发现全部账户）</li>
 *   <li>SUIXINTUI_ORDERS_START_DATE / SUIXINTUI_ORDERS_END_DATE —— 订单创建时间范围 yyyy-MM-dd（可选）</li>
 * </ul>
 *
 * <p>说明：订单列表接口不支持按抖音号过滤，本程序先定位有该抖音号授权的账户，
 * 再拉取商品全域订单并按 aweme_info.aweme_id 匹配，结果导出 CSV 到项目 output/ 目录。</p>
 */
public class SuixintuiUniOrderByAwemeTest {

    public static void main(String[] args) throws Exception {
        // ========== 1. 初始化 ==========
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        Long awemeId = parseAwemeId();
        List<Long> advertiserIds = parseAdvertiserIds();
        LocalDate endDate = parseEnvDate("SUIXINTUI_ORDERS_END_DATE");
        LocalDate startDate = parseEnvDate("SUIXINTUI_ORDERS_START_DATE");

        SuixintuiUniOrderByAwemeService service =
                new SuixintuiUniOrderByAwemeService(apiClient, token);

        System.out.println("=== 按抖音ID拉取随心推商品全域订单 ===");
        System.out.println("抖音ID(aweme_id): " + awemeId);
        System.out.println("账户: " + (advertiserIds.isEmpty() ? "自动发现全部" : advertiserIds));
        System.out.println("订单创建时间范围: "
                + (startDate == null ? "不限" : startDate) + " ~ " + (endDate == null ? "不限" : endDate));

        // ========== 2. 拉取 ==========
        long t0 = System.currentTimeMillis();
        AwemeOrderFetchResult result =
                service.fetchAllByAweme(awemeId, advertiserIds, startDate, endDate);
        long costMillis = System.currentTimeMillis() - t0;

        // ========== 3. 导出 ==========
        Path outputDir = Path.of("output");
        Files.createDirectories(outputDir);
        String stamp = LocalDate.now().toString();
        Path csvFile = outputDir.resolve("suixintui_uni_orders_aweme_"
                + awemeId + "_" + stamp + ".csv");
        writeCsv(result, csvFile);

        // ========== 4. 汇总 ==========
        System.out.println();
        System.out.println("=== 拉取完成 ===");
        System.out.println("命中账户: " + result.matchedAccountCount() + " 个 "
                + result.ordersByAccount.keySet());
        System.out.println("该抖音号商品全域订单: " + result.total() + " 条");
        System.out.println("跳过账户: " + result.skippedAccounts.size() + " 个 "
                + result.skippedAccounts.keySet());
        System.out.println("失败账户: " + result.failedAccounts.size() + " 个 "
                + result.failedAccounts.keySet());
        System.out.println("耗时: " + costMillis + " ms");
        System.out.println("CSV 文件: " + csvFile.toAbsolutePath());

        System.out.println();
        System.out.println("=== 分账户明细 ===");
        for (Map.Entry<Long, List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>> e :
                result.ordersByAccount.entrySet()) {
            System.out.printf("  账户 %d (%s): %d 条%n",
                    e.getKey(), result.matchedAwemesByAccount.get(e.getKey()), e.getValue().size());
        }
    }

    // ==================== 导出 ====================

    private static void writeCsv(AwemeOrderFetchResult result, Path file) throws IOException {
        String[] headers = {
                "advertiser_id", "order_id", "ad_id",
                "marketing_goal", "status", "order_create_time",
                "aweme_id", "aweme_name", "aweme_show_id",
                "product_id", "product_name",
                "stat_cost", "pay_order_gmv", "roi", "pay_order_count"
        };

        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            out.println(String.join(",", headers));

            for (Map.Entry<Long, List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>> e :
                    result.ordersByAccount.entrySet()) {
                for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : e.getValue()) {
                    out.println(String.join(",",
                            csv(e.getKey()),
                            csv(o.getOrderId()),
                            csv(o.getAdId()),
                            csv(o.getMarketingGoal()),
                            csv(o.getStatus()),
                            csv(o.getOrderCreateTime()),
                            csv(o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeId()),
                            csv(o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeName()),
                            csv(o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeShowId()),
                            csv(o.getProductInfo() == null ? null : o.getProductInfo().getProductId()),
                            csv(o.getProductInfo() == null ? null : o.getProductInfo().getProductName()),
                            csv(o.getStatsInfo() == null ? null : o.getStatsInfo().getStatCostForRoi2()),
                            csv(o.getStatsInfo() == null ? null : o.getStatsInfo().getTotalPayOrderGmvForRoi2()),
                            csv(o.getStatsInfo() == null ? null : o.getStatsInfo().getTotalPrepayAndPayOrderRoi2()),
                            csv(o.getStatsInfo() == null ? null : o.getStatsInfo().getTotalPayOrderCountForRoi2())
                    ));
                }
            }
        }
    }

    /** CSV 字段转义：含逗号/引号/换行时加引号包裹 */
    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ==================== 配置 ====================

    private static Long parseAwemeId() {
        String v = System.getenv("SUIXINTUI_AWEME_ID");
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                    "请设置环境变量 SUIXINTUI_AWEME_ID（目标抖音ID/aweme_id，数字UID）");
        }
        return Long.parseLong(v.trim());
    }

    private static List<Long> parseAdvertiserIds() {
        String v = System.getenv("SUIXINTUI_ADVERTISER_ID");
        if (v == null || v.isBlank()) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : v.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                ids.add(Long.parseLong(t));
            }
        }
        return ids;
    }

    private static LocalDate parseEnvDate(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        return LocalDate.parse(v.trim());
    }
}
