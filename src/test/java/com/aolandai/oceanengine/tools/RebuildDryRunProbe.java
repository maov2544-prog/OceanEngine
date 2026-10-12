package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.domain.SuixintuiConstraints;
import com.aolandai.oceanengine.model.SuixintuiUniOrderListRequest;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderService;
import com.aolandai.oceanengine.suixintui.SuixintuiCreateOutcome;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderCreateService;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderParam;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataAwemeInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataProductInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复建 <b>dry-run</b> 探针：对指定原订单，走一遍「详情取参 → 组装建单请求体」，
 * 把<b>即将真实发出的建单请求体</b>打印出来，供人工与原单参数并排核对。
 *
 * <p><b>背景</b>：老板点名要用订单 {@code 7695246925897072690}（账户 {@code 1823379540880396}，
 * 已结束且 ROI 达标）验证复建功能。复建最终要调建单接口 —— 那是<b>花钱</b>操作，
 * 所以本探针<b>只走 dry-run 分支</b>，绝不真建。</p>
 *
 * <h2>⚠️⚠️ 资金安全红线（硬编码，不是配置）</h2>
 * <ol>
 *   <li>{@link SuixintuiOrderCreateService#create(SuixintuiOrderParam, boolean)} 的 {@code dryRun}
 *       <b>写死 {@code true}</b>，不做成可配置 —— 防止被误传 false 而真建单。</li>
 *   <li>dry-run 分支在 {@code create()} 里<b>先 return、后取 token/fetcher</b>：
 *       即 dry-run 时<b>不会换取建单 token、不会调用建单接口</b>（已在代码里核对，
 *       见 {@code SuixintuiOrderCreateService#create} 第 146-150 行）。</li>
 *   <li>本探针<b>不写复建流水</b>（不碰 {@code SuixintuiRebuildRecordStore}），
 *       也不经过 {@code SuixintuiRebuildService} —— 避免污染生产幂等状态。</li>
 *   <li>只读调用：订单详情接口（{@link SuixintuiOrderDetailService}）。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@code @Test}</b>：本项目约定 —— 会调真实接口的工具类靠手动指定类名运行，
 * 不能让 surefire 自动跑。故本类只有 {@code public static void main}，无 {@code @Test}，
 * 与 {@link RebuildFieldProbe} / {@link RoiMetricCompareProbe} 保持一致。</p>
 *
 * <p><b>运行方式（PowerShell / cmd，不能在 Git Bash 跑 Maven）</b>：</p>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = Get-Content target\cp.txt -Raw
 * java -cp "target\classes;target\test-classes;$cp" com.aolandai.oceanengine.tools.RebuildDryRunProbe
 * </pre>
 *
 * <p>输出同时打印到控制台，并<b>以 UTF-8 写入</b>
 * {@code target/rebuild-dryrun-7695246925897072690.txt}（避免 PowerShell 中文乱码 / 吞输出；
 * 路径可用 {@code --out=<path>} 覆盖）。</p>
 */
public final class RebuildDryRunProbe {

    /** 老板点名的原订单号（已结束且 ROI 达标）—— 探针固定测试对象 */
    private static final long SOURCE_ORDER_ID = 7695246925897072690L;

    /** 老板给的账户（默认值，可用 --advertiser= 覆盖；实测此账户与订单不匹配，探针会自动改判） */
    private static final long DEFAULT_ADVERTISER_ID = 1823379540880396L;

    /** 默认输出文件（UTF-8） */
    private static final String DEFAULT_OUTPUT_PATH =
            "target/rebuild-dryrun-7695246925897072690.txt";

    /** 账户配置来源（自动定位订单归属账户时从这里读账户列表） */
    private static final String DEFAULT_CONFIG_PATH = "D:\\oceanengine\\config\\application.yml";

    private static final String API_BASE_URL = "https://api.oceanengine.com";

    /** 自动定位账户时的列表查询窗口（订单太老可能落在更早，故放宽到 60 天） */
    private static final int DISCOVERY_WINDOW_DAYS = 60;

    /** 兜底账户列表（配置读不到时用；同 RebuildFieldProbe 快照） */
    private static final long[] FALLBACK_ADVERTISER_IDS = {
            1_779_106_144_470_100L,
            1_778_533_513_439_244L,
            1_776_098_375_039_040L,
            1_771_298_760_893_511L,
            1_795_656_569_312_393L,
            1_823_379_540_880_396L,
    };

    private RebuildDryRunProbe() {
    }

    public static void main(String[] args) {
        String outputPath = DEFAULT_OUTPUT_PATH;
        String configPath = DEFAULT_CONFIG_PATH;
        Long advertiserId = DEFAULT_ADVERTISER_ID;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if (a.startsWith("--out=")) {
                outputPath = a.substring("--out=".length());
            } else if (a.startsWith("--config=")) {
                configPath = a.substring("--config=".length());
            } else if (a.startsWith("--advertiser=")) {
                try {
                    advertiserId = Long.parseLong(a.substring("--advertiser=".length()).trim());
                } catch (NumberFormatException e) {
                    System.out.println("[致命] --advertiser 不是合法数字: " + a);
                    System.exit(2);
                }
            } else if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                return;
            }
        }

        StringBuilder report = new StringBuilder();
        int exitCode = run(report, advertiserId, configPath);

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

    private static int run(StringBuilder report, Long advertiserId, String configPath) {
        report.append("=== 复建 dry-run 探针（只组装请求体，绝不真建单） ===\n");
        report.append("原订单号: ").append(SOURCE_ORDER_ID).append("\n");
        report.append("账户(入参): ").append(advertiserId).append("\n");
        report.append("dryRun:   true（硬编码）\n\n");

        // ---- 1. Token + 只读客户端 ----
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

        SuixintuiOrderDetailService detailService = new SuixintuiOrderDetailService(apiClient, token);
        SuixintuiOrderService orderService = new SuixintuiOrderService(apiClient, token);

        // ---- 2. 只读调用订单详情（入参账户若与订单不匹配，自动扫描配置账户定位真实归属）----
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData detail = null;
        String detailError = null;
        try {
            detail = detailService.getOrderDetail(SOURCE_ORDER_ID, advertiserId);
        } catch (Exception e) {
            detailError = e.getMessage();
        }

        if (detail == null) {
            report.append("[入参账户取详情失败] ").append(nz(detailError)).append("\n");
            report.append("（订单可能不属于入参账户 ").append(advertiserId)
                    .append("，尝试扫描配置账户自动定位真实归属…）\n\n");
            Long found = discoverOwningAdvertiser(orderService, report, advertiserId, configPath);
            if (found == null) {
                report.append("[致命] 扫描完配置账户仍未找到该订单，无法继续。如实报告，不猜测。\n");
                return 1;
            }
            advertiserId = found;
            report.append("=> 定位到真实归属账户: ").append(advertiserId).append("\n\n");
            try {
                detail = detailService.getOrderDetail(SOURCE_ORDER_ID, advertiserId);
            } catch (Exception e) {
                report.append("[致命] 用定位到的账户仍取详情失败: ")
                        .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
                return 1;
            }
        }

        if (detail == null) {
            report.append("[致命] 详情返回 null。\n");
            return 1;
        }

        // ---- 3. 打印整单详情原始 JSON ----
        report.append("==================== 原订单详情原始 JSON ====================\n");
        report.append(detail.toJson()).append("\n\n");

        // ---- 4. 提取 delivery_setting 5 参数 ----
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting ds =
                detail.getDeliverySetting();
        if (ds == null) {
            report.append("[致命] 详情 delivery_setting 为空，无法取建单 5 参数。\n");
            return 1;
        }
        Long amount = ds.getAmount();
        Double deliveryTime = ds.getDeliveryTime();
        String bidType = ds.getBidType() == null ? null : ds.getBidType().getValue();
        Double roiGoal = ds.getRoiGoal();
        String qcpxMode = ds.getQcpxMode() == null ? null : ds.getQcpxMode().getValue();

        // ---- 5. 提取 awemeId / productId（详情结构：aweme_info.aweme_id / product_info.id）----
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataAwemeInfo awemeInfo = detail.getAwemeInfo();
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataProductInfo productInfo =
                detail.getProductInfo();
        Long awemeId = awemeInfo == null ? null : awemeInfo.getAwemeId();
        Long productId = productInfo == null ? null : productInfo.getId();
        final long ownerAdvertiserId = advertiserId;

        report.append("==================== 原单 8 个复建参数 ====================\n");
        report.append("来源：详情接口（awemeId=aweme_info.aweme_id, productId=product_info.id）\n");
        report.append("  advertiserId : ").append(ownerAdvertiserId).append("（真实归属账户）\n");
        report.append("  awemeId      : ").append(nz(awemeId)).append("\n");
        report.append("  productId    : ").append(nz(productId)).append("\n");
        report.append("  amount       : ").append(nz(amount)).append("\n");
        report.append("  deliveryTime : ").append(nz(deliveryTime)).append("\n");
        report.append("  bidType      : ").append(nz(bidType)).append("\n");
        report.append("  qcpxMode     : ").append(nz(qcpxMode)).append("\n");
        report.append("  roiGoal      : ").append(nz(roiGoal)).append("\n\n");

        // ---- 6. 必须先有完整参数才能组装（缺字段如实报，不瞎传 0）----
        if (awemeId == null || productId == null || amount == null || deliveryTime == null) {
            report.append("[致命] 关键参数缺失（awemeId/productId/amount/deliveryTime 任一为 null），"
                    + "无法组装请求体。\n");
            return 1;
        }

        SuixintuiOrderParam param = new SuixintuiOrderParam(
                ownerAdvertiserId, awemeId, productId, amount, deliveryTime, bidType, qcpxMode, roiGoal);

        // 顺带把本地硬约束校验结果打出来，便于判断「请求体是否会被官方拒绝」
        report.append("==================== 本地硬约束校验（SuixintuiConstraints） ====================\n");
        report.append("  amount     : ").append(describe(SuixintuiConstraints.amountProblem(amount))).append("\n");
        report.append("  deliveryTime: ").append(describe(SuixintuiConstraints.deliveryTimeProblem(deliveryTime))).append("\n");
        report.append("  roiGoal    : ").append(describe(SuixintuiConstraints.roiGoalProblem(roiGoal, bidType))).append("\n\n");

        // ---- 7. 调 create(param, true) —— ⚠️ dryRun 写死 true ----
        report.append("==================== 即将发出的建单请求体（dry-run，不会真发） ====================\n");
        SuixintuiCreateOutcome outcome;
        try {
            SuixintuiOrderCreateService createService = new SuixintuiOrderCreateService(
                    new com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties());
            // ⚠️ 第二个参数硬编码 true：只组装 + 打印，不取 token、不调建单接口
            outcome = createService.create(param, true);
        } catch (Exception e) {
            report.append("[致命] 组装请求体失败: ")
                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
            return 1;
        }

        report.append(outcome.requestBody()).append("\n\n");

        report.append("==================== 核对结论 ====================\n");
        report.append("  dryRun 标记        : ").append(outcome.dryRun()).append("\n");
        report.append("  返回 orderId       : ").append(nz(outcome.orderId()))
                .append(outcome.orderId() == null ? "（null → 确认没有真建单）" : "（⚠️ 非 null，异常！）").append("\n");
        report.append("  warnings           : ").append(outcome.warnings()).append("\n\n");

        report.append("对照要点（请求体里的值应等于上面『原单 8 个复建参数』）：\n");
        report.append("  - advertiser_id == ").append(ownerAdvertiserId).append("\n");
        report.append("  - aweme_id      == ").append(awemeId).append("\n");
        report.append("  - product_id    == ").append(productId).append("\n");
        report.append("  - delivery_setting.amount        == ").append(amount).append("\n");
        report.append("  - delivery_setting.delivery_time == ").append(deliveryTime).append("\n");
        report.append("  - delivery_setting.bid_type      == ").append(nz(bidType)).append("\n");
        report.append("  - delivery_setting.qcpx_mode     == ").append(nz(qcpxMode)).append("\n");
        report.append("  - delivery_setting.roi_goal      == ").append(nz(roiGoal)).append("\n");
        report.append("  - marketing_goal 恒为 VIDEO_PROM_GOODS\n");
        report.append("\n[资金安全] 本探针 dryRun=true（硬编码），未换取建单 token、未调用建单接口、"
                + "未写复建流水。\n");

        return 0;
    }

    private static String describe(String problem) {
        return problem == null ? "OK" : "不合法 -> " + problem;
    }

    private static String nz(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }

    // ==================== 账户自动定位（只读列表，绝不建单） ====================

    /**
     * 扫描配置账户，找出「订单列表里含 {@link #SOURCE_ORDER_ID}」的那个真实归属账户。
     *
     * <p>只读调用订单列表接口（status=FINISHED，近 {@link #DISCOVERY_WINDOW_DAYS} 天），
     * 不调任何写接口。入参账户先跳过（已知不匹配）。</p>
     *
     * @return 真实归属账户；找不到返回 {@code null}
     */
    private static Long discoverOwningAdvertiser(SuixintuiOrderService orderService,
                                                 StringBuilder report,
                                                 long triedAdvertiser,
                                                 String configPath) {
        List<Long> candidates = loadAdvertiserIds(configPath, report);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(DISCOVERY_WINDOW_DAYS - 1L);
        report.append("扫描账户数: ").append(candidates.size())
                .append("，列表窗口: ").append(start).append(" ~ ").append(end)
                .append("（status=FINISHED）\n");

        for (Long candidate : candidates) {
            if (candidate == triedAdvertiser) {
                continue;
            }
            try {
                List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                        orderService.fetchUniPromotionOrders(new SuixintuiUniOrderListRequest(candidate)
                                .status(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.FINISHED)
                                .orderCreateStartDate(start.toString())
                                .orderCreateEndDate(end.toString()));
                boolean hit = orders.stream()
                        .anyMatch(o -> o.getOrderId() != null
                                && o.getOrderId() == SOURCE_ORDER_ID);
                report.append("  账户 ").append(candidate).append(": ")
                        .append(orders.size()).append(" 单")
                        .append(hit ? "  → ★ 命中！" : "").append("\n");
                if (hit) {
                    return candidate;
                }
            } catch (Exception e) {
                report.append("  账户 ").append(candidate).append(": 查询失败（")
                        .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage())
                        .append("）\n");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> loadAdvertiserIds(String configPath, StringBuilder report) {
        try {
            Path path = Paths.get(configPath);
            if (!Files.isReadable(path)) {
                report.append("[提示] 配置文件不可读，改用内置兜底账户列表: ").append(configPath).append("\n");
                return fallbackIds();
            }
            Object loaded;
            try (InputStream in = Files.newInputStream(path)) {
                loaded = new Yaml().load(in);
            }
            Set<Long> ids = new LinkedHashSet<>();
            if (loaded instanceof Map) {
                Object suixintui = ((Map<String, Object>) loaded).get("suixintui");
                if (suixintui instanceof Map) {
                    Object reinvest = ((Map<String, Object>) suixintui).get("reinvest");
                    if (reinvest instanceof Map) {
                        Object tiersObj = ((Map<String, Object>) reinvest).get("advertiser-tiers");
                        if (tiersObj instanceof List) {
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
                                        ids.add(((Number) idObj).longValue());
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                report.append("[提示] 配置里 advertiser-tiers 为空，改用内置兜底账户列表\n");
                return fallbackIds();
            }
            return new ArrayList<>(ids);
        } catch (Exception e) {
            report.append("[提示] 解析配置失败（")
                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage())
                    .append("），改用内置兜底账户列表\n");
            return fallbackIds();
        }
    }

    private static List<Long> fallbackIds() {
        List<Long> ids = new ArrayList<>(FALLBACK_ADVERTISER_IDS.length);
        for (long id : FALLBACK_ADVERTISER_IDS) {
            ids.add(id);
        }
        return ids;
    }

    private static void printUsage() {
        System.out.println("用法: RebuildDryRunProbe [--out=<path>] [--advertiser=<id>] [--config=<path>]");
        System.out.println();
        System.out.println("  --out=<path>         报告输出文件（UTF-8），默认 " + DEFAULT_OUTPUT_PATH);
        System.out.println("  --advertiser=<id>    原单归属账户，默认 " + DEFAULT_ADVERTISER_ID
                + "（若与订单不匹配会自动扫描配置账户定位）");
        System.out.println("  --config=<path>      账户配置（定位归属账户用），默认 " + DEFAULT_CONFIG_PATH);
        System.out.println();
        System.out.println("对订单 " + SOURCE_ORDER_ID + " 做复建 dry-run：");
        System.out.println("只读详情接口 + 组装建单请求体打印，dryRun 硬编码 true，绝不真建单。");
    }
}
