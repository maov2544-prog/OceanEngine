package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.model.SuixintuiUniOrderListRequest;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderService;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo;
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
 * 只读诊断探针：实测「随心推订单复建」所需字段的实际有值率。
 *
 * <p><b>背景</b>：日报抓完「已结束且 ROI 达标」的订单后要自动复建（重新建一个参数相同的
 * 随心推订单）。建单需要若干字段，SDK 1.1.96 的<b>字段声明</b>是齐的，但本项目最大的教训是
 * 「SDK 声明 ≠ 接口实际返回」（例如订单列表 {@code stats_info} 声明了 8 个字段、实测只返回 4 个，
 * {@code total_prepay_and_pay_order_roi2} 在 426 单上全为 null）。因此本探针拉一批
 * <b>已结束（FINISHED）</b>订单，统计各字段的<b>实际有值率</b>。</p>
 *
 * <p><b>资金安全红线</b>：本类<b>只调用两个只读接口</b>——订单列表（
 * {@link SuixintuiOrderService}）+ 订单详情（{@link SuixintuiOrderDetailService}），
 * <b>绝不</b>调用任何写接口 / 建单接口，不花钱、不改动任何生产状态。</p>
 *
 * <p><b>为什么要它</b>：字段有值率直接决定后续要不要为某个缺失字段加「固定配置兜底」。</p>
 *
 * <p><b>为什么不用 {@code @Test}</b>：本项目约定 —— 会调真实接口的工具类靠手动指定类名运行，
 * 不能让 surefire 自动跑（会误触发真实请求）。故本类只有 {@code public static void main}，
 * 无 {@code @Test} 注解，与 {@link RoiMetricCompareProbe} / {@link WebhookProbe} 保持一致。</p>
 *
 * <p><b>运行方式（PowerShell / cmd，不能在 Git Bash 跑 Maven）</b>：</p>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = Get-Content target\cp.txt -Raw
 * java -cp "target\classes;target\test-classes;$cp" com.aolandai.oceanengine.tools.RebuildFieldProbe
 * </pre>
 *
 * <p>输出同时打印到控制台，并<b>以 UTF-8 写入</b> {@code target/rebuild-field-probe.txt}
 * （避免 PowerShell 中文乱码 / 吞输出；文件路径可用 {@code --out=<path>} 覆盖）。
 * 只跑一次，不循环重试；若列表接口报限流（40100/40110）立即停止并如实报告。</p>
 */
public final class RebuildFieldProbe {

    /** 账户配置来源，优先从此文件解析 advertiser-tiers */
    private static final String DEFAULT_CONFIG_PATH = "D:\\oceanengine\\config\\application.yml";

    /** 默认输出文件（UTF-8） */
    private static final String DEFAULT_OUTPUT_PATH = "target/rebuild-field-probe.txt";

    /** 查询窗口天数：近 N 天创建（含今天） */
    private static final int WINDOW_DAYS = 7;

    /** 取样订单上限（详情接口一单一请求，避免打爆限流） */
    private static final int SAMPLE_LIMIT = 30;

    /** 每个账户最多取多少单，凑够样本即止 */
    private static final int PER_ACCOUNT_TAKE = 10;

    private static final String API_BASE_URL = "https://api.oceanengine.com";

    /**
     * 兜底账户列表：当 {@link #DEFAULT_CONFIG_PATH} 无法读取/解析时使用。
     *
     * <p>来源：{@code D:\oceanengine\config\application.yml} 的
     * {@code suixintui.reinvest.advertiser-tiers}（2026-10 快照，3 档共 6 个账户）。</p>
     */
    private static final long[] FALLBACK_ADVERTISER_IDS = {
            1_779_106_144_470_100L,  // 10000档 婴童【青少年-商品】
            1_778_533_513_439_244L,  // 10000档 婴童【儿童-商品】
            1_776_098_375_039_040L,  // 10000档 官方【青少年-商品】
            1_771_298_760_893_511L,  // 10000档 官方【孕产-商品】
            1_795_656_569_312_393L,  // 10000档 官方【婴童-商品】
            1_823_379_540_880_396L,  // 20000档 官方【母婴商品】
    };

    private RebuildFieldProbe() {
    }

    // ==================== 每单统计行 ====================

    /** 单订单的字段核对行 */
    private static final class Row {
        final long advertiserId;
        final Long orderId;
        final Long awemeId;         // 列表 awemeInfo.awemeId（建单要的数值 UID）
        final String awemeShowId;   // 列表 awemeInfo.awemeShowId（抖音号字符串）
        final Long productId;       // 列表 productInfo.productId

        // 详情接口 delivery_setting 5 参数
        boolean detailFetched;      // 详情是否成功取到
        String detailError;         // 详情失败原因
        boolean deliverySettingNull;// delivery_setting 是否为 null
        Long amount;
        Double deliveryTime;
        String bidType;
        Double roiGoal;
        String qcpxMode;

        Row(long advertiserId, Long orderId, Long awemeId, String awemeShowId, Long productId) {
            this.advertiserId = advertiserId;
            this.orderId = orderId;
            this.awemeId = awemeId;
            this.awemeShowId = awemeShowId;
            this.productId = productId;
        }
    }

    // ==================== main ====================

    public static void main(String[] args) {
        String configPath = DEFAULT_CONFIG_PATH;
        String outputPath = DEFAULT_OUTPUT_PATH;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if (a.startsWith("--config=")) {
                configPath = a.substring("--config=".length());
            } else if (a.startsWith("--out=")) {
                outputPath = a.substring("--out=".length());
            } else if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                return;
            }
        }

        StringBuilder report = new StringBuilder();
        int exitCode = run(report, configPath);

        System.out.print(report);
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

    private static int run(StringBuilder report, String configPath) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(WINDOW_DAYS - 1L);

        List<Long> advertiserIds = loadAdvertiserIds(configPath, report);

        report.append("=== 随心推订单复建字段有值率探针（只读，绝不建单） ===\n");
        report.append("账户配置来源: ").append(configPath)
                .append("（suixintui.reinvest.advertiser-tiers）\n");
        report.append("账户数: ").append(advertiserIds.size())
                .append(" 个 -> ").append(advertiserIds).append("\n");
        report.append("查询条件: status=FINISHED（投放完成）")
                .append(", orderCreateStartDate=").append(startDate)
                .append(", orderCreateEndDate=").append(endDate)
                .append("，每账户最多取 ").append(PER_ACCOUNT_TAKE)
                .append(" 单，总计上限 ").append(SAMPLE_LIMIT).append(" 单\n\n");

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

        // ---- 2. 逐账户拉取已结束订单（只读列表） ----
        List<Row> rows = new ArrayList<>();
        boolean aborted = false;
        StringBuilder listRawSample = new StringBuilder();

        for (Long advertiserId : advertiserIds) {
            if (rows.size() >= SAMPLE_LIMIT) {
                break;
            }
            report.append("--- 账户 ").append(advertiserId).append(" ---\n");
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders;
            try {
                SuixintuiUniOrderListRequest request = new SuixintuiUniOrderListRequest(advertiserId)
                        .status(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.FINISHED)
                        .orderCreateStartDate(startDate.toString())
                        .orderCreateEndDate(endDate.toString());
                orders = orderService.fetchUniPromotionOrders(request);
            } catch (RateLimitException e) {
                report.append("  [限流").append(e.isAccountScoped() ? "跳过" : "中止").append("] ")
                        .append(e.describe()).append("\n");
                if (!e.isAccountScoped()) {
                    report.append("\n[中止] 列表接口被开发者/总频控拦截，已停止本次探针，不重试。\n");
                    aborted = true;
                    break;
                }
                continue;
            } catch (Exception e) {
                report.append("  [失败] 拉取订单列表异常: ")
                        .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
                continue;
            }

            report.append("  已结束订单数: ").append(orders.size()).append("\n");

            // 列表第一单原始 JSON 也留一份（证明列表返回哪些 key）
            if (listRawSample.length() == 0 && !orders.isEmpty()) {
                listRawSample.append("=== 列表接口返回的『第一单』原始 JSON 样例 ===\n");
                listRawSample.append(orders.get(0).toJson()).append("\n\n");
            }

            int taken = 0;
            for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : orders) {
                if (rows.size() >= SAMPLE_LIMIT || taken >= PER_ACCOUNT_TAKE) {
                    break;
                }
                Row row = new Row(advertiserId, o.getOrderId(),
                        awemeIdOf(o), awemeShowIdOf(o), productIdOf(o));

                // 详情接口（只读，一单一请求，服务内已有 100ms 节流 + 瞬时重试）
                try {
                    QianchuanAwemeUniPromotionOrderDetailV10ResponseData detail =
                            detailService.getOrderDetail(o.getOrderId(), advertiserId);
                    row.detailFetched = true;
                    QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting ds =
                            detail.getDeliverySetting();
                    row.deliverySettingNull = (ds == null);
                    if (ds != null) {
                        row.amount = ds.getAmount();
                        row.deliveryTime = ds.getDeliveryTime();
                        row.bidType = ds.getBidType() == null ? null : ds.getBidType().getValue();
                        row.roiGoal = ds.getRoiGoal();
                        row.qcpxMode = ds.getQcpxMode() == null ? null : ds.getQcpxMode().getValue();
                    }
                    // 只打印一单详情原始 JSON，作为「接口实际返回哪些 key」的关键证据
                    if (listRawSample.indexOf("详情接口返回的『第一单』原始 JSON") < 0) {
                        listRawSample.append("=== 详情接口返回的『第一单』原始 JSON 样例 ===\n");
                        listRawSample.append("（order_id=").append(o.getOrderId())
                                .append(", advertiser_id=").append(advertiserId).append("）\n");
                        listRawSample.append(detail.toJson()).append("\n\n");
                    }
                } catch (RateLimitException e) {
                    row.detailError = "限流: " + e.describe();
                } catch (Exception e) {
                    row.detailError = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                rows.add(row);
                taken++;
            }
            report.append("  本账户取样: ").append(taken)
                    .append(" 单（累计 ").append(rows.size()).append("）\n\n");
        }

        // ---- 3. 统计 ----
        appendStats(report, rows, aborted);

        // ---- 4. 原始 JSON 证据 ----
        if (listRawSample.length() > 0) {
            report.append("\n").append(listRawSample);
        }

        return aborted ? 1 : 0;
    }

    // ==================== 统计 ====================

    private static void appendStats(StringBuilder report, List<Row> rows, boolean aborted) {
        int total = rows.size();

        int awemeIdOk = 0, awemeShowIdOk = 0, productIdOk = 0, orderIdOk = 0;
        int detailFetched = 0, detailFailed = 0;
        int dsNull = 0;
        int amountOk = 0, deliveryTimeOk = 0, bidTypeOk = 0, roiGoalOk = 0, qcpxModeOk = 0;

        for (Row r : rows) {
            if (r.awemeId != null) awemeIdOk++;
            if (r.awemeShowId != null && !r.awemeShowId.isBlank()) awemeShowIdOk++;
            if (r.productId != null) productIdOk++;
            if (r.orderId != null) orderIdOk++;

            if (r.detailFetched) {
                detailFetched++;
                if (r.deliverySettingNull) {
                    dsNull++;
                }
                if (r.amount != null) amountOk++;
                if (r.deliveryTime != null) deliveryTimeOk++;
                if (r.bidType != null && !r.bidType.isBlank()) bidTypeOk++;
                if (r.roiGoal != null) roiGoalOk++;
                if (r.qcpxMode != null && !r.qcpxMode.isBlank()) qcpxModeOk++;
            } else {
                detailFailed++;
            }
        }

        report.append("==================== 有值率统计 ====================\n");
        if (aborted) {
            report.append("⚠️ 本次探针被限流中止，以下统计仅覆盖已成功拉取的账户，非全量。\n");
        }
        report.append("取样订单总数: ").append(total).append(" 单\n\n");

        report.append("【订单列表接口】\n");
        report.append(pctLine("orderId        非空", orderIdOk, total));
        report.append(pctLine("awemeInfo.awemeId（建单要的数值 UID）", awemeIdOk, total));
        report.append(pctLine("awemeInfo.awemeShowId（抖音号字符串）", awemeShowIdOk, total));
        report.append(pctLine("productInfo.productId", productIdOk, total));
        report.append("\n");

        report.append("【订单详情接口】\n");
        report.append("详情成功取到: ").append(detailFetched)
                .append(" / ").append(total).append(" 单")
                .append(detailFailed > 0 ? ("，失败 " + detailFailed + " 单") : "").append("\n");
        report.append("deliverySetting 为 null 的单数: ").append(dsNull).append("\n");
        int dBase = detailFetched;
        report.append(pctLine("deliverySetting.amount", amountOk, dBase));
        report.append(pctLine("deliverySetting.deliveryTime", deliveryTimeOk, dBase));
        report.append(pctLine("deliverySetting.bidType", bidTypeOk, dBase));
        report.append(pctLine("deliverySetting.roiGoal", roiGoalOk, dBase));
        report.append(pctLine("deliverySetting.qcpxMode", qcpxModeOk, dBase));
        report.append("（注：详情 5 参数的百分比分母为『详情成功取到的单数』=")
                .append(dBase).append("）\n\n");

        // ---- 明细表 ----
        report.append("==================== 每单明细 ====================\n");
        report.append("advertiserId | orderId | awemeId | awemeShowId | productId | amount | deliveryTime | bidType | roiGoal | qcpxMode | 详情\n");
        for (Row r : rows) {
            if (r.detailFetched) {
                report.append(r.advertiserId)
                        .append(" | ").append(nz(r.orderId))
                        .append(" | ").append(nz(r.awemeId))
                        .append(" | ").append(nz(r.awemeShowId))
                        .append(" | ").append(nz(r.productId))
                        .append(" | ").append(nz(r.amount))
                        .append(" | ").append(nz(r.deliveryTime))
                        .append(" | ").append(nz(r.bidType))
                        .append(" | ").append(nz(r.roiGoal))
                        .append(" | ").append(nz(r.qcpxMode))
                        .append(" | ok\n");
            } else {
                report.append(r.advertiserId)
                        .append(" | ").append(nz(r.orderId))
                        .append(" | ").append(nz(r.awemeId))
                        .append(" | ").append(nz(r.awemeShowId))
                        .append(" | ").append(nz(r.productId))
                        .append(" | - | - | - | - | - | 失败: ").append(nz(r.detailError)).append("\n");
            }
        }
        report.append("=================================================\n");
    }

    private static String pctLine(String label, int ok, int total) {
        String pct = total == 0 ? "—" : String.format("%.1f%%", 100.0 * ok / total);
        return "  " + label + ": " + ok + " / " + total + " （" + pct + "）\n";
    }

    private static String nz(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }

    // ==================== 取字段 ====================

    private static Long awemeIdOf(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo a = o.getAwemeInfo();
        return a == null ? null : a.getAwemeId();
    }

    private static String awemeShowIdOf(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo a = o.getAwemeInfo();
        return a == null ? null : a.getAwemeShowId();
    }

    private static Long productIdOf(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo p = o.getProductInfo();
        return p == null ? null : p.getProductId();
    }

    // ==================== 配置 ====================

    @SuppressWarnings("unchecked")
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
        System.out.println("用法: RebuildFieldProbe [--config=<path>] [--out=<path>]");
        System.out.println();
        System.out.println("  --config=<path>   账户配置文件，默认 " + DEFAULT_CONFIG_PATH);
        System.out.println("  --out=<path>      报告输出文件（UTF-8），默认 " + DEFAULT_OUTPUT_PATH);
        System.out.println();
        System.out.println("只读探针：仅调用订单列表 + 订单详情接口，绝不建单、不花钱。");
    }
}
