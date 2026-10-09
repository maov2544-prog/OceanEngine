package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.domain.SuixintuiReinvestCandidateSelector;
import com.aolandai.oceanengine.domain.SuixintuiReinvestCandidateSelector.RoiMetric;
import com.aolandai.oceanengine.model.SuixintuiUniOrderListRequest;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderService;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 只读诊断探针：并排对比同一批「投放中」随心推全域订单在两种 ROI 口径下的达标情况。
 *
 * <p><b>背景</b>：追投筛选当前用 {@code SETTLE_ROI}（净成交 ROI，字段
 * {@code stats_info.total_prepay_and_pay_settle_roi2_1h}，带 1 小时归因窗口 + 结算口径），
 * 该口径保守且滞后，可能把实际 ROI 不错的订单误判为不达标 → 漏投。
 * 另一个口径 {@code OVERALL_PAY_ROI}（整体支付 ROI，字段
 * {@code stats_info.total_prepay_and_pay_order_roi2}，无 {@code _1h} 后缀、不要求结算）
 * 更即时。本探针量化：若换成 {@code OVERALL_PAY_ROI}，有多少单会从「不达标」变成「达标」。</p>
 *
 * <p><b>资金安全红线（务必遵守）</b>：本类<b>只调用订单列表接口</b>（只读），
 * 通过 {@link SuixintuiOrderService} 完成；<b>绝不</b>调用任何加预算/写接口。
 * 另外为补齐「投放总金额」列，会以<b>尽力而为</b>的方式调用订单详情接口
 * （也是只读），一旦详情接口被限流即停止补齐、不再继续，不影响已采集的 ROI 数据。
 * 可用 {@code --no-budget} 完全跳过详情调用（此时只发列表请求）。</p>
 *
 * <p><b>为什么不用 {@code @Test}</b>：本项目约定 —— 会调真实接口的工具类靠手动指定类名运行，
 * 不能让 surefire 自动跑（会误触发真实请求）。故本类只有 {@code public static void main}，
 * 无 {@code @Test} 注解，与 {@link WebhookProbe} 保持一致。</p>
 *
 * <p><b>术语提醒</b>：SDK 方法名 {@code getTotalPrepayAndPaySettleRoi21h()} 里的
 * {@code Roi21h} 是 JSON 名 {@code roi2_1h} 去掉下划线后的机械驼峰化（{@code roi2} + {@code 1h}），
 * <b>不是 21 小时</b>。</p>
 *
 * <p><b>运行方式（PowerShell / cmd，不能在 Git Bash 跑 Maven）</b>：</p>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = Get-Content target\cp.txt -Raw
 * java -cp "target\classes;target\test-classes;$cp" com.aolandai.oceanengine.tools.RoiMetricCompareProbe
 * </pre>
 *
 * <p>输出同时打印到控制台，并<b>以 UTF-8 写入</b> {@code target/roi-compare.txt}
 * （避免 PowerShell 中文乱码 / 吞输出；文件路径可用 {@code --out=<path>} 覆盖）。
 * 只跑一次，不循环重试；若列表接口报限流（40100/40110）立即停止并如实报告。</p>
 */
public final class RoiMetricCompareProbe {

    /** ROI 达标阈值（与 config 的 suixintui.reinvest.min-roi 一致，2026 当前为 1.8） */
    private static final double ROI_THRESHOLD = 1.8;

    /** 账户配置来源（当前 6 个账户 / 3 档），优先从此文件解析 advertiser-tiers */
    private static final String DEFAULT_CONFIG_PATH = "D:\\oceanengine\\config\\application.yml";

    /** 默认输出文件（UTF-8） */
    private static final String DEFAULT_OUTPUT_PATH = "target/roi-compare.txt";

    /** 查询窗口天数（与追投服务一致：近 N 天，含今天）。config window-days=7 → 今天-6 天 ~ 今天 */
    private static final int WINDOW_DAYS = 7;

    private static final String API_BASE_URL = "https://api.oceanengine.com";

    /**
     * 兜底账户列表：当 {@link #DEFAULT_CONFIG_PATH} 无法读取/解析时使用。
     *
     * <p>来源：{@code D:\oceanengine\config\application.yml} 的
     * {@code suixintui.reinvest.advertiser-tiers}（2026-10 快照，3 档共 6 个账户）。
     * 硬编码只是兜底，正常情况下以解析到的配置为准。</p>
     */
    private static final long[] FALLBACK_ADVERTISER_IDS = {
            1_779_106_144_470_100L,  // 10000档 婴童【青少年-商品】
            1_778_533_513_439_244L,  // 10000档 婴童【儿童-商品】
            1_776_098_375_039_040L,  // 10000档 官方【青少年-商品】
            1_771_298_760_893_511L,  // 10000档 官方【孕产-商品】
            1_795_656_569_312_393L,  // 10000档 官方【婴童-商品】
            1_823_379_540_880_396L,  // 20000档 官方【母婴商品】
    };

    private RoiMetricCompareProbe() {
    }

    // ==================== 每行数据 ====================

    /** 单订单在两个口径下的对比行 */
    private static final class Row {
        final long advertiserId;
        final Long orderId;
        final Long productId;
        final Double statCost;          // 整体消耗 stats_info.stat_cost_for_roi2
        Double budget;                  // 投放总金额（详情接口；尽力而为，可能为 null）
        boolean budgetFetchFailed;      // 详情接口取失败（限流/异常），budget 不可信
        final Double settleRoi;         // SETTLE_ROI（net，roi2_1h）
        final Double overallRoi;        // OVERALL_PAY_ROI（roi2）

        Row(long advertiserId, Long orderId, Long productId, Double statCost,
            Double settleRoi, Double overallRoi) {
            this.advertiserId = advertiserId;
            this.orderId = orderId;
            this.productId = productId;
            this.statCost = statCost;
            this.settleRoi = settleRoi;
            this.overallRoi = overallRoi;
        }
    }

    // ==================== main ====================

    public static void main(String[] args) {
        // ---- 参数解析 ----
        String configPath = DEFAULT_CONFIG_PATH;
        String outputPath = DEFAULT_OUTPUT_PATH;
        boolean fetchBudget = true;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if ("--no-budget".equals(a)) {
                fetchBudget = false;
            } else if (a.startsWith("--config=")) {
                configPath = a.substring("--config=".length());
            } else if (a.startsWith("--out=")) {
                outputPath = a.substring("--out=".length());
            } else if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                return;
            }
        }

        StringBuilder report = new StringBuilder();
        int exitCode = run(report, configPath, fetchBudget);

        // 打印到控制台（可能因终端编码乱码，以文件为准）
        System.out.print(report);
        // 同时写 UTF-8 文件，保证中文可靠可读
        try {
            Path out = Paths.get(outputPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                w.write(report.toString());
            }
            System.out.println();
            System.out.println("（完整报告已写入 UTF-8 文件：" + out.toAbsolutePath() + "）");
        } catch (Exception e) {
            System.out.println();
            System.out.println("（写输出文件失败，忽略："
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "）");
        }

        System.exit(exitCode);
    }

    /**
     * 执行探针主流程，把报告写进 {@code report}。
     *
     * @return 退出码：0 正常；1 中途失败（如 token/列表接口异常）
     */
    private static int run(StringBuilder report, String configPath, boolean fetchBudget) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(WINDOW_DAYS - 1L);

        List<Long> advertiserIds = loadAdvertiserIds(configPath, report);

        report.append("=== 随心推追投 ROI 口径对比探针（只读，不产生任何追投） ===\n");
        report.append("账户配置来源: ").append(configPath)
                .append("（suixintui.reinvest.advertiser-tiers）\n");
        report.append("账户数: ").append(advertiserIds.size())
                .append(" 个 -> ").append(advertiserIds).append("\n");
        report.append("查询条件: status=DELIVERY_OK（投放中）"
                + ", orderCreateStartDate=").append(startDate)
                .append(", orderCreateEndDate=").append(endDate)
                .append(", orderField=STAT_COST_FOR_ROI2\n");
        report.append("ROI 达标阈值: ").append(ROI_THRESHOLD)
                .append("（SETTLE_ROI=净成交 roi2_1h；OVERALL_PAY_ROI=整体支付 roi2）\n");
        report.append("投放总金额: ").append(fetchBudget
                ? "尽力而为调用订单详情接口补齐（只读；限流即停止补齐）"
                : "已跳过（--no-budget）").append("\n");
        report.append("\n");

        // ---- 1. Token + 客户端 ----
        String token;
        ApiClient apiClient;
        try {
            token = SuixintuiTokenClient.getAccessToken();
            apiClient = new ApiClient();
            apiClient.setBasePath(API_BASE_URL);
            apiClient.addDefaultHeader("Access-Token", token);
        } catch (Exception e) {
            report.append("[致命] 获取 Access Token 失败: ")
                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
            return 1;
        }

        SuixintuiOrderService orderService = new SuixintuiOrderService(apiClient, token);
        SuixintuiOrderDetailService detailService = new SuixintuiOrderDetailService(apiClient, token);

        // ---- 2. 逐账户拉取订单列表（只读） ----
        List<Row> rows = new ArrayList<>();
        boolean budgetFetchAborted = false;  // 详情接口限流后，后续不再补 budget

        for (Long advertiserId : advertiserIds) {
            report.append("--- 账户 ").append(advertiserId).append(" ---\n");
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders;
            try {
                SuixintuiUniOrderListRequest request = new SuixintuiUniOrderListRequest(advertiserId)
                        .status(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.DELIVERY_OK)
                        .orderField(QianchuanAwemeUniPromotionOrderGetV10OrderField.STAT_COST_FOR_ROI2)
                        .orderCreateStartDate(startDate.toString())
                        .orderCreateEndDate(endDate.toString());
                orders = orderService.fetchUniPromotionOrders(request);
            } catch (RateLimitException e) {
                report.append("  [限流").append(e.isAccountScoped() ? "跳过" : "中止").append("] ")
                        .append(e.describe()).append("\n");
                if (!e.isAccountScoped()) {
                    // 40100/40110：立即停止，不再请求任何账户（避免加重惩罚）
                    report.append("\n[中止] 列表接口被开发者/总频控拦截，已停止本次探针，不重试。\n");
                    appendSummary(report, rows, budgetFetchAborted, true);
                    return 1;
                }
                continue;
            } catch (Exception e) {
                report.append("  [失败] 拉取订单列表异常: ")
                        .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
                continue;
            }

            report.append("  订单数: ").append(orders.size()).append("\n");
            report.append("  orderId | 商品ID | 整体消耗 | 投放总金额 | SETTLE_ROI | OVERALL_PAY_ROI | 差值\n");

            for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : orders) {
                Double statCost = statCostOf(o);
                Double settleRoi = SuixintuiReinvestCandidateSelector.roiOf(o, RoiMetric.SETTLE_ROI);
                Double overallRoi = SuixintuiReinvestCandidateSelector.roiOf(o, RoiMetric.OVERALL_PAY_ROI);

                Row row = new Row(advertiserId, o.getOrderId(),
                        SuixintuiReinvestCandidateSelector.productIdOf(o),
                        statCost, settleRoi, overallRoi);

                // 尽力而为补「投放总金额」；限流后不再补
                if (fetchBudget && !budgetFetchAborted) {
                    try {
                        Long totalBudget = detailService.getTotalBudget(o.getOrderId(), advertiserId);
                        row.budget = totalBudget == null ? null : totalBudget.doubleValue();
                    } catch (RateLimitException e) {
                        row.budgetFetchFailed = true;
                        budgetFetchAborted = true;
                        report.append("  [限流中止补齐] 详情接口被拦（")
                                .append(e.describe()).append("），后续订单不再补「投放总金额」。\n");
                    } catch (ApiException e) {
                        row.budgetFetchFailed = true;
                    }
                } else if (fetchBudget) {
                    row.budgetFetchFailed = true;
                }

                rows.add(row);
                report.append("  ").append(formatRow(row)).append("\n");
            }
            report.append("\n");
        }

        // ---- 3. 汇总统计 ----
        appendSummary(report, rows, budgetFetchAborted, false);
        return 0;
    }

    // ==================== 汇总 ====================

    private static void appendSummary(StringBuilder report, List<Row> rows,
                                      boolean budgetFetchAborted, boolean aborted) {
        int total = rows.size();
        int settleOk = 0, overallOk = 0, rescue = 0;
        int settleNull = 0, overallNull = 0;
        double settleSum = 0, overallSum = 0;
        int settleValid = 0, overallValid = 0;
        int bothNull = 0;

        for (Row r : rows) {
            boolean sOk = r.settleRoi != null && r.settleRoi >= ROI_THRESHOLD;
            boolean oOk = r.overallRoi != null && r.overallRoi >= ROI_THRESHOLD;
            if (sOk) {
                settleOk++;
            }
            if (oOk) {
                overallOk++;
            }
            // 核心指标：SETTLE_ROI < 1.8（含 null 视为不达标）且 OVERALL_PAY_ROI >= 1.8
            if (!sOk && oOk) {
                rescue++;
            }
            if (r.settleRoi == null) {
                settleNull++;
            } else {
                settleSum += r.settleRoi;
                settleValid++;
            }
            if (r.overallRoi == null) {
                overallNull++;
            } else {
                overallSum += r.overallRoi;
                overallValid++;
            }
            if (r.settleRoi == null && r.overallRoi == null) {
                bothNull++;
            }
        }

        report.append("==================== 汇总统计 ====================\n");
        if (aborted) {
            report.append("⚠️ 本次探针被限流中止，以下统计仅覆盖已成功拉取的账户，非全量。\n");
        }
        report.append("订单总数: ").append(total).append("\n");
        report.append("SETTLE_ROI ≥ ").append(ROI_THRESHOLD).append(" 的单数: ").append(settleOk).append("\n");
        report.append("OVERALL_PAY_ROI ≥ ").append(ROI_THRESHOLD).append(" 的单数: ").append(overallOk).append("\n");
        report.append("\n");
        report.append("★ 核心指标 —— SETTLE_ROI < ").append(ROI_THRESHOLD)
                .append(" 且 OVERALL_PAY_ROI ≥ ").append(ROI_THRESHOLD)
                .append("（被 SETTLE 口径误杀、换 OVERALL 后可救回）的单数: ")
                .append(rescue).append(" 单\n");
        report.append("\n");
        report.append("SETTLE_ROI 平均值: ")
                .append(settleValid == 0 ? "无有效值" : String.format("%.4f", settleSum / settleValid))
                .append("（有效样本 ").append(settleValid).append(" 单）\n");
        report.append("OVERALL_PAY_ROI 平均值: ")
                .append(overallValid == 0 ? "无有效值" : String.format("%.4f", overallSum / overallValid))
                .append("（有效样本 ").append(overallValid).append(" 单）\n");
        report.append("\n");
        report.append("SETTLE_ROI 为 null 的订单数: ").append(settleNull).append("\n");
        report.append("OVERALL_PAY_ROI 为 null 的订单数: ").append(overallNull).append("\n");
        report.append("两个口径都为 null 的订单数: ").append(bothNull).append("\n");
        if (budgetFetchAborted) {
            report.append("（注：投放总金额因详情接口限流未能全部补齐，相关列为空属正常）\n");
        }
        report.append("=================================================\n");
    }

    // ==================== 格式化 ====================

    private static String formatRow(Row r) {
        String settle = fmtRoi(r.settleRoi);
        String overall = fmtRoi(r.overallRoi);
        String diff;
        if (r.settleRoi == null || r.overallRoi == null) {
            diff = "—";
        } else {
            diff = String.format("%+.4f", r.overallRoi - r.settleRoi);
        }
        String budget;
        if (r.budget != null) {
            budget = String.valueOf(r.budget);
        } else if (r.budgetFetchFailed) {
            budget = "(取失败)";
        } else {
            budget = "(未取)";
        }
        return r.orderId
                + " | " + (r.productId == null ? "null" : r.productId)
                + " | " + fmtCost(r.statCost)
                + " | " + budget
                + " | " + settle
                + " | " + overall
                + " | " + diff;
    }

    private static String fmtRoi(Double v) {
        return v == null ? "null" : String.format("%.4f", v);
    }

    private static String fmtCost(Double v) {
        return v == null ? "null" : String.format("%.2f", v);
    }

    // ==================== 工具 ====================

    /** 取整体消耗 stats_info.stat_cost_for_roi2；stats_info 为空返回 null */
    private static Double statCostOf(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo s = o.getStatsInfo();
        return s == null ? null : s.getStatCostForRoi2();
    }

    /**
     * 读取账户列表：优先解析 {@code configPath} 的
     * {@code suixintui.reinvest.advertiser-tiers[*].advertiser-ids}（按档次顺序展平）；
     * 解析失败时回退 {@link #FALLBACK_ADVERTISER_IDS}，并在报告中说明。
     */
    private static List<Long> loadAdvertiserIds(String configPath, StringBuilder report) {
        try {
            Path path = Paths.get(configPath);
            if (!Files.isReadable(path)) {
                report.append("[提示] 配置文件不可读，改用内置兜底账户列表: ")
                        .append(configPath).append("\n");
                return fallbackIds();
            }
            Object loaded;
            try (InputStream in = Files.newInputStream(path)) {
                loaded = new Yaml().load(in);
            }
            List<Long> ids = extractTierAdvertiserIds(loaded);
            if (ids.isEmpty()) {
                report.append("[提示] 配置里 advertiser-tiers 为空，改用内置兜底账户列表\n");
                return fallbackIds();
            }
            return ids;
        } catch (Exception e) {
            report.append("[提示] 解析配置失败（")
                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage())
                    .append("），改用内置兜底账户列表\n");
            return fallbackIds();
        }
    }

    /** 从已解析的 YAML 根对象里按 suixintui.reinvest.advertiser-tiers 顺序提取 advertiser-ids */
    @SuppressWarnings("unchecked")
    private static List<Long> extractTierAdvertiserIds(Object root) {
        List<Long> ids = new ArrayList<>();
        if (!(root instanceof Map)) {
            return ids;
        }
        Map<String, Object> suixintui = (Map<String, Object>) ((Map<String, Object>) root).get("suixintui");
        if (suixintui == null) {
            return ids;
        }
        Map<String, Object> reinvest = (Map<String, Object>) suixintui.get("reinvest");
        if (reinvest == null) {
            return ids;
        }
        Object tiersObj = reinvest.get("advertiser-tiers");
        if (!(tiersObj instanceof List)) {
            return ids;
        }
        for (Object tierObj : (List<Object>) tiersObj) {
            if (!(tierObj instanceof Map)) {
                continue;
            }
            Object advIds = ((Map<String, Object>) tierObj).get("advertiser-ids");
            if (!(advIds instanceof List)) {
                continue;
            }
            for (Object idObj : (List<Object>) advIds) {
                if (idObj instanceof Number) {
                    long v = ((Number) idObj).longValue();
                    if (!ids.contains(v)) {
                        ids.add(v);
                    }
                }
            }
        }
        return ids;
    }

    private static List<Long> fallbackIds() {
        List<Long> ids = new ArrayList<>(FALLBACK_ADVERTISER_IDS.length);
        for (long id : FALLBACK_ADVERTISER_IDS) {
            ids.add(id);
        }
        return ids;
    }

    private static void printUsage() {
        System.out.println("用法: RoiMetricCompareProbe [--no-budget] [--config=<path>] [--out=<path>]");
        System.out.println();
        System.out.println("  --no-budget       不调用订单详情接口补「投放总金额」（只发列表请求）");
        System.out.println("  --config=<path>   账户配置文件，默认 " + DEFAULT_CONFIG_PATH);
        System.out.println("  --out=<path>      报告输出文件（UTF-8），默认 " + DEFAULT_OUTPUT_PATH);
        System.out.println();
        System.out.println("只读探针：仅调用订单列表（+可选订单详情）接口，绝不产生任何追投。");
    }
}
