package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.model.SuixintuiUniOrderListRequest;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderService;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>只读</b>预检探针：确认目标「商品 + 抖音号」组合当前<b>没有投放中</b>的订单。
 *
 * <p><b>为什么</b>：老板定位上一单 {@code 7695246925897072690} 真建失败
 * （{@code code=40000 商品校验不通过}）的根因是「<b>同一（商品+抖音号）组合已有投放中的订单</b>」，
 * 千川不允许重复建。因此真建前先只读确认：目标组合
 * （账户 {@value #ADVERTISER_ID} / 商品 {@value #PRODUCT_ID} / 达人 {@value #AWEME_ID}）
 * <b>当前没有在投订单</b>，否则新单也会撞同样的错、白白浪费一次调用。</p>
 *
 * <p><b>绝对只读</b>：只调订单列表接口 {@link SuixintuiOrderService#fetchUniPromotionOrders}
 * （GET 查询），<b>不建单、不花钱、不改任何代码/配置</b>。</p>
 *
 * <h2>判定规则</h2>
 * <p>「投放中」= 列表 {@code status} 不属于「终态」。终态集合：
 * FINISHED（已结束）/ OVER（超时结束）/ FAILED / TIMEOUT / UNPAIDCANCEL（未支付取消）。
 * 非终态（CREATING/AUDIT/DELIVERY_OK/OFFLINE_AUDIT/FROZEN/UNPAID/BOOK）视为「在投/处理中」，
 * 命中同组合即判为冲突。</p>
 *
 * <h2>运行（PowerShell）</h2>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = (Get-Content target\cp.txt -Raw).Trim()
 * java "-Dfile.encoding=UTF-8" -cp "target\classes;target\test-classes;$cp" `
 *   com.aolandai.oceanengine.tools.InflightConflictPrecheckProbe
 * </pre>
 */
public final class InflightConflictPrecheckProbe {

    private static final long ADVERTISER_ID = 1823379540880396L;
    private static final long PRODUCT_ID = 3502541670170374206L;
    private static final long AWEME_ID = 7478906606710572090L;

    /** 查询窗口：近 N 天创建的订单（覆盖在投单：投放时长最长 168h=7天，取 30 天足够） */
    private static final int WINDOW_DAYS = 30;

    private static final String API_BASE_URL = "https://api.oceanengine.com";
    private static final String DEFAULT_OUTPUT_PATH =
            "target/inflight-conflict-precheck-7694463463581614099.txt";

    private InflightConflictPrecheckProbe() {
    }

    public static void main(String[] args) {
        String outputPath = DEFAULT_OUTPUT_PATH;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if (a.startsWith("--out=")) {
                outputPath = a.substring("--out=".length());
            }
        }

        StringBuilder report = new StringBuilder();
        int exit = run(report);
        System.out.print(report);
        writeOutput(outputPath, report);
        System.exit(exit);
    }

    private static int run(StringBuilder report) {
        report.append("=== 只读预检：目标「商品+抖音号」组合是否已在投 ===\n");
        report.append("账户     : ").append(ADVERTISER_ID).append("\n");
        report.append("商品     : ").append(PRODUCT_ID).append("\n");
        report.append("达人UID  : ").append(AWEME_ID).append("（抖音号 90493031825）\n");
        report.append("窗口     : 近 ").append(WINDOW_DAYS).append(" 天创建\n");
        report.append("说明     : 全程只读，不建单、不花钱、不改代码。\n\n");

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

        String start = LocalDate.now().minusDays(WINDOW_DAYS).toString();
        // ⚠️ 结束日期「不能晚于今天」（接口校验），故取今天，不能 +1
        String end = LocalDate.now().toString();
        SuixintuiUniOrderListRequest req = new SuixintuiUniOrderListRequest(ADVERTISER_ID)
                .orderCreateStartDate(start)
                .orderCreateEndDate(end);

        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders;
        try {
            orders = orderService.fetchUniPromotionOrders(req);
        } catch (RateLimitException e) {
            report.append("[限流] 列表接口被频控，已停止（不重试）：").append(e.getMessage()).append("\n");
            return 1;
        } catch (ApiException e) {
            report.append("[接口异常] 列表接口失败：").append(e.getMessage()).append("\n");
            return 1;
        }

        report.append("拉取到订单数（账户 ").append(ADVERTISER_ID)
                .append("，" ).append(start).append(" ~ ").append(end).append("）= ")
                .append(orders.size()).append("\n\n");

        List<String> inflight = new ArrayList<>();
        List<String> sameCombo = new ArrayList<>();
        for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : orders) {
            Long pid = productIdOf(o);
            Long aid = awemeIdOf(o);
            boolean comboMatch = Long.valueOf(PRODUCT_ID).equals(pid) && Long.valueOf(AWEME_ID).equals(aid);
            if (!comboMatch) {
                continue;
            }
            String st = o.getStatus() == null ? "null" : o.getStatus().toString();
            sameCombo.add("  order_id=" + o.getOrderId() + ", status=" + st
                    + ", create=" + o.getOrderCreateTime());
            if (!isFinal(st)) {
                inflight.add("  ★ order_id=" + o.getOrderId() + ", status=" + st
                        + ", create=" + o.getOrderCreateTime());
            }
        }

        report.append("========== 同（商品+达人）组合的订单 ==========\n");
        if (sameCombo.isEmpty()) {
            report.append("（近 ").append(WINDOW_DAYS).append(" 天内无同组合订单）\n");
        } else {
            for (String s : sameCombo) {
                report.append(s).append("\n");
            }
        }

        report.append("\n========== 结论 ==========\n");
        if (inflight.isEmpty()) {
            report.append("✅ 未发现该「商品+达人」组合的【在投】订单。\n");
            report.append("   → 预检通过，可以真建（无重复建单冲突）。\n");
            return 0;
        }
        report.append("🛑 发现该「商品+达人」组合【仍在投放/处理中】的订单 ").append(inflight.size()).append(" 条：\n");
        for (String s : inflight) {
            report.append(s).append("\n");
        }
        report.append("\n   → 预检不通过：真建很可能再次撞『商品校验不通过』。建议先停掉/等结束后再建。\n");
        return 3;
    }

    private static Long productIdOf(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo p = o.getProductInfo();
        return p == null ? null : p.getProductId();
    }

    private static Long awemeIdOf(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerAwemeInfo a = o.getAwemeInfo();
        return a == null ? null : a.getAwemeId();
    }

    /** 终态（已结束类）：不算在投 */
    private static boolean isFinal(String status) {
        if (status == null) {
            return false;
        }
        switch (status) {
            case "FINISHED":
            case "OVER":
            case "FAILED":
            case "TIMEOUT":
            case "UNPAIDCANCEL":
                return true;
            default:
                return false;
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
}
