package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting;

import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * <b>只读</b>探针：拉取单个订单的详情，打印<b>完整原始 JSON</b> 与全部关键字段。
 *
 * <p><b>背景</b>：今天真建成功的复建订单 {@code 7695651629202407460}（源单
 * {@code 7694463463581614099}，账户 {@code 1823379540880396}）。老板要核验它当前详情。</p>
 *
 * <p><b>绝对只读</b>：只调 {@link SuixintuiOrderDetailService#getOrderDetail}（GET 查询），
 * <b>不建单、不花钱、不改任何代码/配置</b>。</p>
 *
 * <h2>输出（尽量原样，不过滤）</h2>
 * <ol>
 *   <li>status 当前值</li>
 *   <li>delivery_setting 全部字段（amount/bid_type/delivery_time/qcpx_mode/roi_goal/
 *       deep_external_action/support_add_budget）</li>
 *   <li>audit_record（含 status_attach_desp）</li>
 *   <li>aweme_info（aweme_id / aweme_name / aweme_show_id）</li>
 *   <li>product_info（id / name）</li>
 *   <li>order_create_time / ad_id</li>
 *   <li>完整原始 JSON 全文（{@code response.toJson()}）</li>
 * </ol>
 *
 * <h2>运行（PowerShell）</h2>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = (Get-Content target\cp.txt -Raw).Trim()
 * java "-Dfile.encoding=UTF-8" -cp "target\classes;target\test-classes;$cp" `
 *   com.aolandai.oceanengine.tools.OrderDetailDumpProbe
 * # 可覆盖：--orderId=... --advertiserId=... --out=...
 * </pre>
 */
public final class OrderDetailDumpProbe {

    /** 今天真建成功的复建订单 */
    private static final long ORDER_ID = 7695651629202407460L;

    /** 该订单所属账户 */
    private static final long ADVERTISER_ID = 1823379540880396L;

    private static final String DEFAULT_OUTPUT_PATH =
            "target/order-7695651629202407460-detail.txt";

    private static final String API_BASE_URL = "https://api.oceanengine.com";

    private OrderDetailDumpProbe() {
    }

    public static void main(String[] args) {
        long orderId = ORDER_ID;
        long advertiserId = ADVERTISER_ID;
        String outputPath = DEFAULT_OUTPUT_PATH;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if (a.startsWith("--orderId=")) {
                orderId = Long.parseLong(a.substring("--orderId=".length()).trim());
            } else if (a.startsWith("--advertiserId=")) {
                advertiserId = Long.parseLong(a.substring("--advertiserId=".length()).trim());
            } else if (a.startsWith("--out=")) {
                outputPath = a.substring("--out=".length());
            } else if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                return;
            }
        }

        StringBuilder report = new StringBuilder();
        int exit = run(orderId, advertiserId, report);
        System.out.print(report);
        writeOutput(outputPath, report);
        System.exit(exit);
    }

    private static int run(long orderId, long advertiserId, StringBuilder report) {
        report.append("=== 只读探针：拉取单订单详情 ===\n");
        report.append("订单号   : ").append(orderId).append("\n");
        report.append("账户     : ").append(advertiserId)
                .append("（").append(String.valueOf(advertiserId).length()).append(" 位）\n");
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

        SuixintuiOrderDetailService detailService = new SuixintuiOrderDetailService(apiClient, token);

        report.append("========== 调订单详情接口（只读）==========\n");
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData data;
        try {
            data = detailService.getOrderDetail(orderId, advertiserId);
            report.append("✅ 调用成功\n");
        } catch (ApiException e) {
            report.append("✗ 接口报错 code/message: ").append(e.getMessage()).append("\n");
            report.append("  （请核对订单号与账户是否匹配）\n");
            return 2;
        } catch (Exception e) {
            report.append("✗ 异常: ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n");
            return 3;
        }

        if (data == null) {
            report.append("⚠️ 接口返回 data 为 null（订单不存在或账户不匹配）\n");
            return 4;
        }

        printDetailFields(advertiserId, data, report);
        return 0;
    }

    /** 打印详情关键字段 + 完整原始 JSON */
    private static void printDetailFields(long advertiserId,
                                          QianchuanAwemeUniPromotionOrderDetailV10ResponseData data,
                                          StringBuilder report) {
        report.append("\n========== 订单关键字段（账户 ").append(advertiserId).append("）==========\n");

        // 1. status 当前值
        report.append("【1】status           = ").append(safeGet(data, "getStatus"))
                .append("   ← 上次为 DELIVERY_OK，确认是否变化\n");

        // 6. order_create_time / ad_id
        report.append("【6】order_create_time = ").append(safeGet(data, "getOrderCreateTime")).append("\n");
        report.append("【6】ad_id             = ").append(safeGet(data, "getAdId")).append("\n");

        // 5. product_info
        Object productInfo = safeGet(data, "getProductInfo");
        report.append("【5】product_info.id   = ").append(safeGet(productInfo, "getId")).append("\n");
        report.append("【5】product_info.name = ").append(safeGet(productInfo, "getName")).append("\n");

        // 4. aweme_info
        Object awemeInfo = safeGet(data, "getAwemeInfo");
        report.append("【4】aweme_info.aweme_id      = ").append(safeGet(awemeInfo, "getAwemeId")).append("\n");
        report.append("【4】aweme_info.aweme_show_id = ").append(safeGet(awemeInfo, "getAwemeShowId")).append("\n");
        report.append("【4】aweme_info.aweme_name    = ").append(safeGet(awemeInfo, "getAwemeName")).append("\n");

        // 3. audit_record
        Object auditRecord = safeGet(data, "getAuditRecord");
        report.append("\n--- 【3】audit_record ---\n");
        report.append("audit_record        = ").append(auditRecord).append("\n");
        if (auditRecord != null) {
            report.append("audit_record.status_attach_desp = ")
                    .append(safeGet(auditRecord, "getStatusAttachDesp"))
                    .append("   ← 投放状态说明\n");
            report.append("（audit_record 全部可读字段）:\n");
            dumpGetters(auditRecord, report, "   ");
        }

        // 2. delivery_setting 全部字段
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting s = data.getDeliverySetting();
        report.append("\n--- 【2】delivery_setting ---\n");
        if (s == null) {
            report.append("delivery_setting = null\n");
        } else {
            report.append("amount               = ").append(s.getAmount()).append("\n");
            report.append("bid_type             = ").append(s.getBidType()).append("\n");
            report.append("delivery_time        = ").append(s.getDeliveryTime()).append("\n");
            report.append("qcpx_mode            = ").append(s.getQcpxMode()).append("\n");
            report.append("roi_goal             = ").append(s.getRoiGoal()).append("\n");
            report.append("deep_external_action = ").append(safeGet(s, "getDeepExternalAction")).append("\n");
            report.append("support_add_budget   = ").append(safeGet(s, "getSupportAddBudget")).append("\n");
            report.append("（DeliverySetting 全部可读字段）:\n");
            dumpGetters(s, report, "   ");
        }

        // 顶层全部 getter，确保不漏字段
        report.append("\n--- 顶层对象（data）全部可读字段 ---\n");
        dumpGetters(data, report, "   ");

        // 7. 完整原始 JSON
        report.append("\n--- 【7】完整原始返回 JSON（response.toJson()）---\n");
        try {
            Method toJson = data.getClass().getMethod("toJson");
            report.append(String.valueOf(toJson.invoke(data))).append("\n");
        } catch (Exception e) {
            report.append("[取 toJson 失败] ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n");
        }
    }

    /** 反射打印一个对象的全部无参 getXxx() */
    private static void dumpGetters(Object obj, StringBuilder report, String indent) {
        if (obj == null) {
            return;
        }
        for (Method m : obj.getClass().getMethods()) {
            String name = m.getName();
            if (name.startsWith("get") && m.getParameterCount() == 0 && !isObjectMethod(name)) {
                report.append(indent).append(name).append(" = ")
                        .append(String.valueOf(safeInvoke(obj, m))).append("\n");
            }
        }
    }

    private static boolean isObjectMethod(String name) {
        return name.equals("getClass") || name.equals("getSERIALIZED_NAME")
                || name.startsWith("getOpenapi") || name.equals("getDeclaringClass")
                || name.equals("getDeclaredClass");
    }

    private static Object safeGet(Object obj, String getter) {
        if (obj == null) {
            return null;
        }
        try {
            Method m = obj.getClass().getMethod(getter);
            return m.invoke(obj);
        } catch (Exception e) {
            return "[无 " + getter + "]";
        }
    }

    private static Object safeInvoke(Object obj, Method m) {
        try {
            return m.invoke(obj);
        } catch (Exception e) {
            return "[异常]";
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
        System.out.println("用法: OrderDetailDumpProbe [--orderId=...] [--advertiserId=...] [--out=...]");
        System.out.println();
        System.out.println("默认 orderId=" + ORDER_ID + "，advertiserId=" + ADVERTISER_ID);
        System.out.println("只读探针：拉取单订单详情，打印全部关键字段 + 原始 JSON。");
    }
}
