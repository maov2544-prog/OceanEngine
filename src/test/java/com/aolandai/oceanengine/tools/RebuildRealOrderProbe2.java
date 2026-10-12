package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.report.RebuildResult;
import com.aolandai.oceanengine.report.SuixintuiRebuildProperties;
import com.aolandai.oceanengine.report.SuixintuiRebuildService;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.storage.FinishedOrder;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecord;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecordStore;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderCreateService;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ⚠️⚠️ <b>真实花钱</b>探针：对订单 {@value #SOURCE_ORDER_ID} 执行<b>一次</b>真复建。
 *
 * <p>走正式 {@link SuixintuiRebuildService#rebuild} 逻辑 + 写幂等流水。只复建<b>一个</b>订单，
 * 不跑日报、不触发其它订单。</p>
 *
 * <h2>⚠️ 资金安全护栏（硬编码，不是配置）</h2>
 * <ol>
 *   <li><b>必须显式传 {@code --confirm-spend}</b> 才真正执行；否则只打印计划并退出。</li>
 *   <li>只喂<b>一个</b> {@link FinishedOrder}；{@code rebuild} 内部天然只处理这一单。</li>
 *   <li>dryRun 用 {@link SuixintuiReinvestProperties#setDryRun(boolean)} 在<b>内存</b>里设为 false，
 *       <b>不动 yml</b>；进程结束后生产配置原样不变。</li>
 *   <li>失败<b>不重试</b>：本探针只调一次 {@code rebuild}。</li>
 * </ol>
 *
 * <h2>运行（PowerShell）</h2>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = (Get-Content target\cp.txt -Raw).Trim()
 * # 先空跑看计划（不花钱）：
 * java "-Dfile.encoding=UTF-8" -cp "target\classes;target\test-classes;$cp" `
 *   com.aolandai.oceanengine.tools.RebuildRealOrderProbe2
 * # 确认无误后加 --confirm-spend 真建：
 * java "-Dfile.encoding=UTF-8" -cp "target\classes;target\test-classes;$cp" `
 *   com.aolandai.oceanengine.tools.RebuildRealOrderProbe2 --confirm-spend
 * </pre>
 */
public final class RebuildRealOrderProbe2 {

    /** 原订单号（老板点名） */
    private static final long SOURCE_ORDER_ID = 7694463463581614099L;

    /** 原单真实归属账户（官方【母婴商品】20000 档）—— 已由只读归属探针核实 */
    private static final long ADVERTISER_ID = 1823379540880396L;

    /** 原单达人 UID（aweme_info.aweme_id）—— 详情接口实测值 */
    private static final long AWEME_ID = 7478906606710572090L;

    /** 原单商品 ID（product_info.id）—— 详情接口实测值 */
    private static final long PRODUCT_ID = 3502541670170374206L;

    /** 生产复建幂等流水路径（与 application.yml 默认值一致） */
    private static final String RECORD_STORE_PATH = "D:/oceanengine/data/reinvest-rebuild-records.jsonl";

    private static final String DEFAULT_OUTPUT_PATH = "target/rebuild-real-7694463463581614099.txt";

    private static final String API_BASE_URL = "https://api.oceanengine.com";

    private RebuildRealOrderProbe2() {
    }

    public static void main(String[] args) {
        boolean confirmSpend = false;
        String outputPath = DEFAULT_OUTPUT_PATH;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if ("--confirm-spend".equals(a)) {
                confirmSpend = true;
            } else if (a.startsWith("--out=")) {
                outputPath = a.substring("--out=".length());
            } else if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                return;
            }
        }

        StringBuilder report = new StringBuilder();
        int exitCode = confirmSpend ? runRealRebuild(report) : printPlanOnly(report);

        System.out.print(report);
        writeOutput(outputPath, report);
        System.exit(exitCode);
    }

    /** 未传 --confirm-spend：只打印将要做的事，绝不花钱 */
    private static int printPlanOnly(StringBuilder report) {
        report.append("=== 真复建探针 v2（未传 --confirm-spend，仅打印计划，绝不花钱） ===\n");
        report.append("原订单号 : ").append(SOURCE_ORDER_ID).append("\n");
        report.append("账户     : ").append(ADVERTISER_ID).append("\n");
        report.append("awemeId  : ").append(AWEME_ID).append("\n");
        report.append("productId: ").append(PRODUCT_ID).append("\n");
        report.append("流水路径 : ").append(RECORD_STORE_PATH).append("\n");
        report.append("dryRun   : 将【临时设为 false】（仅本进程内存，不改 yml）\n\n");
        report.append("将要执行：SuixintuiRebuildService.rebuild(List.of(这一单)) —— 真建 1 个订单，花费 200 元。\n");
        report.append("\n确认无误后，重新运行并加上 --confirm-spend 参数。\n");
        return 0;
    }

    private static int runRealRebuild(StringBuilder report) {
        report.append("=== 真复建探针 v2（--confirm-spend 已确认，将真建 1 单，花费 200 元） ===\n");
        report.append("原订单号 : ").append(SOURCE_ORDER_ID).append("\n");
        report.append("账户     : ").append(ADVERTISER_ID).append("\n");
        report.append("awemeId  : ").append(AWEME_ID).append("\n");
        report.append("productId: ").append(PRODUCT_ID).append("\n\n");

        // ---- 1. 组装依赖（全部内存构造，不依赖 Spring / 不改 yml）----
        SuixintuiReinvestProperties reinvestProps = new SuixintuiReinvestProperties();
        reinvestProps.setDryRun(false);   // ⚠️ 仅本进程内存生效：真建。绝不写回 yml。
        reinvestProps.setApiBaseUrl(API_BASE_URL);

        SuixintuiRebuildProperties rebuildProps = new SuixintuiRebuildProperties();
        rebuildProps.setRecordStorePath(Paths.get(RECORD_STORE_PATH));
        rebuildProps.setMaxOrdersPerDay(10);

        report.append("生效配置（本进程内存）：\n");
        report.append("  dryRun           = ").append(reinvestProps.isDryRun()).append("\n");
        report.append("  流水路径          = ").append(rebuildProps.getRecordStorePath()).append("\n");
        report.append("  每日上限          = ").append(rebuildProps.getMaxOrdersPerDay()).append("\n\n");

        // ---- 2. 只读依赖：token + ApiClient ----
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
        SuixintuiOrderCreateService createService = new SuixintuiOrderCreateService(reinvestProps);

        // ---- 3. 原单详情（只读）——用于参数对比 ----
        report.append("==================== 原单详情（只读，用于参数对比）====================\n");
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData detail = null;
        try {
            detail = detailService.getOrderDetail(SOURCE_ORDER_ID, ADVERTISER_ID);
            QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting s = detail.getDeliverySetting();
            report.append("原始 JSON: ").append(detail.toJson()).append("\n\n");
            report.append("原单 delivery_setting: ");
            if (s == null) {
                report.append("null\n");
            } else {
                report.append("amount=").append(s.getAmount())
                        .append(", bid_type=").append(s.getBidType())
                        .append(", delivery_time=").append(s.getDeliveryTime())
                        .append(", qcpx_mode=").append(s.getQcpxMode())
                        .append(", roi_goal=").append(s.getRoiGoal())
                        .append(", deep_external_action=").append(safeGet(s, "getDeepExternalAction"))
                        .append("\n");
            }
        } catch (Exception e) {
            report.append("[原单详情失败] ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n");
        }

        // ---- 4. 幂等流水（读回已有记录，写新记录）----
        SuixintuiRebuildRecordStore recordStore;
        try {
            recordStore = new SuixintuiRebuildRecordStore(Paths.get(RECORD_STORE_PATH));
        } catch (Exception e) {
            report.append("[致命] 加载复建流水失败（拒绝继续，避免重复复建）: ")
                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
            return 1;
        }
        report.append("\n流水加载前记录数: ").append(recordStore.size())
                .append("，已真复建单数: ").append(recordStore.describe()).append("\n");
        if (recordStore.isRebuilt(SOURCE_ORDER_ID)) {
            SuixintuiRebuildRecord prev = recordStore.findEffective(SOURCE_ORDER_ID);
            report.append("\n[中止] 该原订单在流水里【已真复建过】→ 幂等生效，本次不再复建（不重复花钱）。\n");
            report.append("       已有记录 newOrderId = ")
                    .append(prev == null ? "null" : prev.getNewOrderId()).append("\n");
            return 0;
        }

        // ---- 5. 组装 FinishedOrder（只填 rebuild 需要的 4 个字段）----
        FinishedOrder order = new FinishedOrder(
                SOURCE_ORDER_ID, ADVERTISER_ID,
                null, null,
                AWEME_ID, PRODUCT_ID,
                null, "FINISHED", null, null);

        // ---- 6. 走正式编排：rebuild(List.of(这一单)) ----
        SuixintuiRebuildService rebuildService = new SuixintuiRebuildService(
                rebuildProps, reinvestProps, recordStore, detailService, createService);

        report.append("\n==================== 调用 SuixintuiRebuildService.rebuild(...) ====================\n");
        Map<Long, RebuildResult> result;
        try {
            result = rebuildService.rebuild(new ArrayList<>(List.of(order)));
        } catch (Throwable e) {
            report.append("[致命] rebuild 抛异常（不重试）: ")
                    .append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
            return 1;
        }

        RebuildResult rebuilt = result.get(SOURCE_ORDER_ID);
        report.append("\n==================== 结果 ====================\n");
        if (rebuilt == null || rebuilt.orderId() == null) {
            report.append("[结果] 未拿到新订单号（可能详情失败/建单失败/字段缺失）。\n");
            report.append("       本次未写入『真建成功』流水；如失败请看上面日志的 code/message。\n");
            report.append("       ⚠️ 不重试，避免重复扣费。\n");
            return 1;
        }

        String newOrderId = String.valueOf(rebuilt.orderId());
        report.append("状态文案     : ").append(rebuilt.statusLabel()).append("\n");
        report.append("★ 新订单号   : ").append(newOrderId)
                .append("（长度 ").append(newOrderId.length()).append("）\n\n");

        // ---- 7. 核对流水 ----
        report.append("==================== 复建流水 ====================\n");
        report.append("路径: ").append(RECORD_STORE_PATH).append("\n");
        try {
            List<String> lines = Files.readAllLines(Paths.get(RECORD_STORE_PATH), StandardCharsets.UTF_8);
            report.append("文件当前总行数: ").append(lines.size()).append("\n");
            report.append("最后一行内容:\n").append(lines.isEmpty() ? "(空)" : lines.get(lines.size() - 1)).append("\n");
        } catch (Exception e) {
            report.append("（读流水文件失败: ").append(e.getMessage() + "）\n");
        }
        SuixintuiRebuildRecord rec = recordStore.findEffective(SOURCE_ORDER_ID);
        if (rec != null) {
            report.append("\n内存索引里该单记录: newOrderId=").append(rec.getNewOrderId())
                    .append(", dryRun=").append(rec.isDryRun())
                    .append(", amount=").append(rec.getAmount())
                    .append(", deliveryTime=").append(rec.getDeliveryTime())
                    .append(", bidType=").append(rec.getBidType())
                    .append(", qcpxMode=").append(rec.getQcpxMode())
                    .append(", roiGoal=").append(rec.getRoiGoal())
                    .append(", rebuiltAt=").append(rec.getRebuiltAt()).append("\n");
        }

        report.append("\n[资金安全] 本次真实花费 200 元（1 单）；dryRun 仅内存临时关闭；"
                + "未改动任何 yml；未重试。\n");
        return 0;
    }

    private static Object safeGet(Object obj, String getter) {
        if (obj == null) {
            return null;
        }
        try {
            return obj.getClass().getMethod(getter).invoke(obj);
        } catch (Exception e) {
            return "[无 " + getter + "]";
        }
    }

    private static void writeOutput(String outputPath, StringBuilder report) {
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
    }

    private static void printUsage() {
        System.out.println("用法: RebuildRealOrderProbe2 [--confirm-spend] [--out=<path>]");
        System.out.println("  --confirm-spend  真正执行复建（真实花费 200 元）。不加则只打印计划。");
        System.out.println("  --out=<path>     报告输出文件（UTF-8），默认 " + DEFAULT_OUTPUT_PATH);
        System.out.println("⚠️ 真建 1 个随心推订单（200 元），走正式 SuixintuiRebuildService + 写幂等流水。");
    }
}
