package com.example.oceanengine.test;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10ResponseDataListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.example.oceanengine.client.SuixintuiTokenClient;
import com.example.oceanengine.service.SuixintuiOrderService;
import com.example.oceanengine.service.SuixintuiOrderService.FetchResult;

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
 * 需求一：获取抖音号（千川随心推账户）的所有随心推订单。
 *
 * <p>运行前环境变量：</p>
 * <ul>
 *   <li>QIANCHUAN_SERVICE_BEARER_TOKEN —— 自建服务端认证密钥（必填，Access Token 由服务端下发）</li>
 *   <li>SUIXINTUI_ADVERTISER_ID —— 千川业务账户 ID，多个用英文逗号分隔（可选，默认自动发现全部账户）</li>
 *   <li>SUIXINTUI_ORDERS_START_DATE —— 小店随心推查询起始日期 yyyy-MM-dd（可选，默认今天-179天，
 *       接口限制最早只能查最近 180 天）</li>
 * </ul>
 *
 * <p>说明：实测当前随心推 Token 下「小店随心推订单列表」近 179 天无订单，
 * 真实订单在「随心推全域订单列表」中（商品全域 VIDEO_PROM_GOODS）。
 * 本入口同时拉取两类订单并统一去重，导出 CSV 与 JSON 到项目 output/ 目录。</p>
 */
public class SuixintuiOrderTest {

    public static void main(String[] args) throws Exception {
        // ========== 1. 初始化 ==========
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        SuixintuiOrderService service = new SuixintuiOrderService(apiClient, token);

        List<Long> advertiserIds = parseAdvertiserIds();
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = envOr("SUIXINTUI_ORDERS_START_DATE", null) == null
                ? endDate.minusDays(SuixintuiOrderService.MAX_LOOKBACK_DAYS)
                : LocalDate.parse(envOr("SUIXINTUI_ORDERS_START_DATE", null));

        System.out.println("=== 开始拉取随心推全部订单 ===");
        System.out.println("账户: " + (advertiserIds.isEmpty() ? "自动发现全部" : advertiserIds));
        System.out.println("小店随心推日期范围: " + startDate + " ~ " + endDate
                + "（接口限制最早只能查最近 180 天）");

        // ========== 2. 拉取 ==========
        long t0 = System.currentTimeMillis();
        FetchResult result = service.fetchAllOrders(advertiserIds, startDate, endDate);
        long costMillis = System.currentTimeMillis() - t0;

        // ========== 3. 导出 ==========
        Path outputDir = Path.of("output");
        Files.createDirectories(outputDir);

        String stamp = LocalDate.now().toString();
        Path csvFile = outputDir.resolve("suixintui_orders_" + stamp + ".csv");
        Path jsonFile = outputDir.resolve("suixintui_orders_" + stamp + ".json");

        writeCsv(result, csvFile);
        writeJson(result, jsonFile);

        // ========== 4. 汇总 ==========
        System.out.println();
        System.out.println("=== 拉取完成 ===");
        System.out.println("小店随心推订单: " + result.classicTotal() + " 条"
                + "（" + result.classicByAccount.size() + " 个账户有数据）");
        System.out.println("随心推全域订单: " + result.uniTotal() + " 条"
                + "（" + result.uniByAccount.size() + " 个账户有数据）");
        System.out.println("订单合计: " + result.total() + " 条");
        System.out.println("失败账户: " + result.failedAccounts.size() + " 个 "
                + result.failedAccounts.keySet());
        System.out.println("耗时: " + costMillis + " ms");
        System.out.println("CSV 文件: " + csvFile.toAbsolutePath());
        System.out.println("JSON 文件: " + jsonFile.toAbsolutePath());

        // 分账户明细
        System.out.println();
        System.out.println("=== 分账户明细 ===");
        for (Map.Entry<Long, List<QianchuanAwemeOrderGetV10ResponseDataListInner>> e :
                result.classicByAccount.entrySet()) {
            System.out.printf("  账户 %d 小店随心推: %d 条%n", e.getKey(), e.getValue().size());
        }
        for (Map.Entry<Long, List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>> e :
                result.uniByAccount.entrySet()) {
            System.out.printf("  账户 %d 随心推全域: %d 条%n", e.getKey(), e.getValue().size());
        }
    }

    // ==================== 导出 ====================

    private static void writeCsv(FetchResult result, Path file) throws IOException {
        String[] headers = {
                "channel", "advertiser_id", "order_id", "ad_id",
                "marketing_goal", "status", "order_create_time",
                "aweme_id", "aweme_name", "aweme_show_id",
                "product_id", "product_name",
                "stat_cost", "pay_order_gmv", "roi", "pay_order_count"
        };

        try (PrintWriter out = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            out.println(String.join(",", headers));

            for (Map.Entry<Long, List<QianchuanAwemeOrderGetV10ResponseDataListInner>> e :
                    result.classicByAccount.entrySet()) {
                for (QianchuanAwemeOrderGetV10ResponseDataListInner o : e.getValue()) {
                    out.println(String.join(",",
                            csv("小店随心推"),
                            csv(e.getKey()),
                            csv(o.getOrderId()),
                            csv(o.getAdId()),
                            csv(o.getMarketingGoal()),
                            csv(o.getStatus()),
                            csv(o.getOrderCreateTime()),
                            csv(o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeId()),
                            csv(o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeName()),
                            csv(o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeShowId()),
                            "", // product_id
                            "", // product_name
                            "", // stat_cost
                            "", // pay_order_gmv
                            "", // roi
                            ""  // pay_order_count
                    ));
                }
            }

            for (Map.Entry<Long, List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>> e :
                    result.uniByAccount.entrySet()) {
                for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : e.getValue()) {
                    out.println(String.join(",",
                            csv("随心推全域"),
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

    private static void writeJson(FetchResult result, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"fetched_at\": \"").append(LocalDate.now()).append("\",\n");
        sb.append("  \"小店随心推\": [\n");
        boolean first = true;
        for (Map.Entry<Long, List<QianchuanAwemeOrderGetV10ResponseDataListInner>> e :
                result.classicByAccount.entrySet()) {
            for (QianchuanAwemeOrderGetV10ResponseDataListInner o : e.getValue()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("    ").append(o.toJson());
            }
        }
        sb.append("\n  ],\n");
        sb.append("  \"随心推全域\": [\n");
        first = true;
        for (Map.Entry<Long, List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>> e :
                result.uniByAccount.entrySet()) {
            for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : e.getValue()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("    ").append(o.toJson());
            }
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
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

    private static String envOr(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }
}
