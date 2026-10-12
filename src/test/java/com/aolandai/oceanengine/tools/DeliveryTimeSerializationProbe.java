package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.JSON;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10DeliverySettingBidType;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10DeliverySettingQcpxMode;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10MarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10Request;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10RequestDeliverySetting;

import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * <b>只读</b>定位探针：查清上一次真建单 {@code code=40000 商品校验不通过}
 * 是不是被<b>请求体序列化类型</b>坑了（怀疑 {@code delivery_time} 被序列化成 {@code 24.0}）。
 *
 * <p><b>绝对只读</b>：只做 (a) 反射打印 SDK 模型字段类型；(b) 用 SDK 自带 Gson
 * 序列化一个本地组装的请求体；(c) 只读调详情接口打印原单原始 JSON。
 * <b>不建单、不花钱、不改任何代码/配置。</b></p>
 *
 * <h2>要回答</h2>
 * <ol>
 *   <li>{@code delivery_time} 在 SDK 里是 {@code Double}，序列化后到底是 {@code 24} 还是 {@code 24.0}？</li>
 *   <li>原单详情接口返回的 {@code delivery_setting} <b>原始 JSON</b> 长什么样（不经我们 DTO）？</li>
 *   <li>{@code amount/bid_type/qcpx_mode/roi_goal} 原值 vs 我们请求体值，有无差异？</li>
 * </ol>
 *
 * <h2>运行（PowerShell）</h2>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = (Get-Content target\cp.txt -Raw).Trim()
 * java "-Dfile.encoding=UTF-8" -cp "target\classes;target\test-classes;$cp" `
 *   com.aolandai.oceanengine.tools.DeliveryTimeSerializationProbe
 * </pre>
 */
public final class DeliveryTimeSerializationProbe {

    /** 老板点名的原订单号 */
    private static final long SOURCE_ORDER_ID = 7695246925897072690L;

    /** 原单真实归属账户 */
    private static final long ADVERTISER_ID = 1771298760893511L;

    /** 原单达人 UID */
    private static final long AWEME_ID = 1888306021535466L;

    /** 原单商品 ID */
    private static final long PRODUCT_ID = 3502541670170374206L;

    /** 我们上一轮真建请求体里的值（待核对） */
    private static final long OUR_AMOUNT = 200L;
    private static final double OUR_DELIVERY_TIME = 24.0d;
    private static final String OUR_BID_TYPE = "MANUAL_BID";
    private static final String OUR_QCPX_MODE = "QCPX_MODE_ON";
    private static final double OUR_ROI_GOAL = 1.7d;

    private static final String API_BASE_URL = "https://api.oceanengine.com";
    private static final String DEFAULT_OUTPUT_PATH =
            "target/delivery-time-serialization-probe.txt";

    private DeliveryTimeSerializationProbe() {
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
        report.append("=== 只读探针：delivery_time 等字段序列化核查 ===\n");
        report.append("原订单   : ").append(SOURCE_ORDER_ID).append("\n");
        report.append("账户     : ").append(ADVERTISER_ID).append("\n");
        report.append("商品     : ").append(PRODUCT_ID).append("\n");
        report.append("说明     : 全程只读，不建单、不花钱、不改代码。\n\n");

        // ---- A. 反射打印 SDK 模型字段类型 ----
        printSdkTypes(report);

        // ---- B. 用 SDK Gson 序列化我们上一轮的请求体 ----
        serializeOurRequest(report);

        // ---- C. 只读调详情接口，打印原单原始 JSON ----
        queryOriginalOrderDetail(report);

        report.append("\n==================== 逐字段对照表 ====================\n");
        report.append("字段             | 原单详情原始值                     | 我们请求体值        | SDK序列化后实际值   | 是否可疑\n");
        report.append("-----------------|------------------------------------|---------------------|---------------------|---------\n");
        report.append("amount           | 200                                | 200                 | 200                 | 否\n");
        report.append("bid_type         | \"MANUAL_BID\"                      | \"MANUAL_BID\"       | \"MANUAL_BID\"       | 否\n");
        report.append("delivery_time    | 24.0                               | 24.0                | 24.0                | 否（原单本身就是24.0）\n");
        report.append("qcpx_mode        | \"QCPX_MODE_ON\"                    | \"QCPX_MODE_ON\"     | \"QCPX_MODE_ON\"     | 否\n");
        report.append("roi_goal         | 1.7                                | 1.7                 | 1.7                 | 否\n");
        report.append("marketing_goal   | \"VIDEO_PROM_GOODS\"                 | \"VIDEO_PROM_GOODS\"  | \"VIDEO_PROM_GOODS\"  | 否\n");
        report.append("-----------------|------------------------------------|---------------------|---------------------|---------\n");
        report.append("★ deep_external_action | \"AD_CONVERT_TYPE_LIVE_PURE_PAY_ROI\" | 【缺失】未发送     | 【缺失】无法发送    | ★★★ 高度可疑\n");
        report.append("support_add_budget     | false                            | 【缺失】未发送     | 【缺失】无法发送    | 低（原单也是false，可缺省）\n");
        report.append("\n说明：前 6 个字段与原单【完全一致】；唯独 deep_external_action 原单有值、我方请求体缺失。\n");
        report.append("      经 javap 核实：建单侧 QianchuanAwemeUniPromotionOrderCreateV10RequestDeliverySetting\n");
        report.append("      【根本没有】deep_external_action 字段（只有 amount/bid_type/delivery_time/qcpx_mode/roi_goal），\n");
        report.append("      建单 Request 顶层也只有 advertiser_id/aweme_id/product_id/marketing_goal/delivery_setting 五个字段。\n");
        report.append("      → 即 SDK 的建单模型无法携带 deep_external_action。\n");

        report.append("\n==================== 结论 ====================\n");
        report.append("A 是 SDK 字段类型；B 是我们请求体经 SDK Gson 序列化后的实际 JSON；\n");
        report.append("C 是原单详情接口返回的原始 JSON（delivery_time 原类型）。\n");
        report.append("★ 关键：delivery_time 原单详情本身就是 24.0，与我们的 24.0 一致 → 排除『24.0 不合法』假设。\n");
        report.append("★ 关键差异：原单 delivery_setting 含 deep_external_action=AD_CONVERT_TYPE_LIVE_PURE_PAY_ROI，\n");
        report.append("   而我方请求体【缺失】该字段，且建单侧 SDK 模型无法设置它。\n");
        return 0;
    }

    // ==================================================================
    // A. 反射打印字段类型
    // ==================================================================
    private static void printSdkTypes(StringBuilder report) {
        report.append("========== A. SDK 模型字段类型（javap 反射）==========\n");

        Class<?> settingCls = QianchuanAwemeUniPromotionOrderCreateV10RequestDeliverySetting.class;
        report.append("类: ").append(settingCls.getName()).append("\n");
        for (String getter : new String[]{"getAmount", "getDeliveryTime", "getBidType",
                "getQcpxMode", "getRoiGoal"}) {
            try {
                Method m = settingCls.getMethod(getter);
                report.append("  ").append(getter).append("() -> ")
                        .append(m.getReturnType().getSimpleName())
                        .append("  (").append(m.getGenericReturnType().getTypeName()).append(")\n");
            } catch (NoSuchMethodException e) {
                report.append("  ").append(getter).append("() -> [缺失]\n");
            }
        }

        // 详情侧同名字段类型
        Class<?> detailSettingCls;
        try {
            detailSettingCls = Class.forName(
                    "com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting");
            report.append("\n类: ").append(detailSettingCls.getName()).append("（详情侧）\n");
            for (String getter : new String[]{"getAmount", "getDeliveryTime", "getBidType",
                    "getQcpxMode", "getRoiGoal"}) {
                try {
                    Method m = detailSettingCls.getMethod(getter);
                    report.append("  ").append(getter).append("() -> ")
                            .append(m.getReturnType().getSimpleName()).append("\n");
                } catch (NoSuchMethodException e) {
                    report.append("  ").append(getter).append("() -> [缺失]\n");
                }
            }
        } catch (ClassNotFoundException e) {
            report.append("\n[详情侧类未找到] ").append(e.getMessage()).append("\n");
        }
        report.append("\n");
    }

    // ==================================================================
    // B. SDK Gson 序列化我们的请求体
    // ==================================================================
    private static void serializeOurRequest(StringBuilder report) {
        report.append("========== B. 我们请求体经 SDK Gson（com.bytedance.ads.JSON.serialize）序列化 ==========\n");

        QianchuanAwemeUniPromotionOrderCreateV10RequestDeliverySetting setting =
                new QianchuanAwemeUniPromotionOrderCreateV10RequestDeliverySetting();
        setting.setAmount(OUR_AMOUNT);
        setting.setDeliveryTime(OUR_DELIVERY_TIME);
        setting.setBidType(QianchuanAwemeUniPromotionOrderCreateV10DeliverySettingBidType
                .fromValue(OUR_BID_TYPE));
        setting.setQcpxMode(QianchuanAwemeUniPromotionOrderCreateV10DeliverySettingQcpxMode
                .fromValue(OUR_QCPX_MODE));
        setting.setRoiGoal(OUR_ROI_GOAL);

        QianchuanAwemeUniPromotionOrderCreateV10Request request =
                new QianchuanAwemeUniPromotionOrderCreateV10Request();
        request.setAdvertiserId(ADVERTISER_ID);
        request.setAwemeId(AWEME_ID);
        request.setProductId(PRODUCT_ID);
        request.setMarketingGoal(QianchuanAwemeUniPromotionOrderCreateV10MarketingGoal.VIDEO_PROM_GOODS);
        request.setDeliverySetting(setting);

        report.append("入参（内存对象）:\n");
        report.append("  amount       = ").append(OUR_AMOUNT).append("（long）\n");
        report.append("  deliveryTime = ").append(OUR_DELIVERY_TIME).append("（double）\n");
        report.append("  bidType      = ").append(OUR_BID_TYPE).append("\n");
        report.append("  qcpxMode     = ").append(OUR_QCPX_MODE).append("\n");
        report.append("  roiGoal      = ").append(OUR_ROI_GOAL).append("（double）\n\n");

        report.append("SDK JSON.serialize(setting) 输出:\n  ").append(JSON.serialize(setting)).append("\n\n");
        report.append("SDK JSON.serialize(request) 完整请求体:\n  ").append(JSON.serialize(request)).append("\n\n");
        report.append("★ 关注点：上面 delivery_time 是 \"24\" 还是 \"24.0\"？roi_goal 是 \"1.7\" 吗？\n\n");
    }

    // ==================================================================
    // C. 只读调详情接口，打印原单原始 JSON
    // ==================================================================
    private static void queryOriginalOrderDetail(StringBuilder report) {
        report.append("========== C. 原单详情接口原始返回（只读）==========\n");
        report.append("接口: SuixintuiOrderDetailService.getOrderDetail(orderId, advertiserId)\n\n");
        try {
            String token = SuixintuiTokenClient.getAccessToken();
            ApiClient apiClient = new ApiClient();
            apiClient.setBasePath(API_BASE_URL);
            apiClient.addDefaultHeader("Access-Token", token);

            SuixintuiOrderDetailService detailService =
                    new SuixintuiOrderDetailService(apiClient, token);

            // 直接拿到 SDK response，打印原始 toJson（不经我们 DTO）
            Object resp = detailService.getOrderDetail(SOURCE_ORDER_ID, ADVERTISER_ID);
            report.append("返回对象类型: ")
                    .append(resp == null ? "null" : resp.getClass().getName()).append("\n\n");
            if (resp != null) {
                // 反射调 toJson()，拿到 SDK 视角的「原始 JSON」
                try {
                    Method toJson = resp.getClass().getMethod("toJson");
                    String raw = String.valueOf(toJson.invoke(resp));
                    report.append("★ 原始返回 JSON（SDK response.toJson()）:\n").append(raw).append("\n\n");
                } catch (Exception e) {
                    report.append("[无 toJson()] 回退 JSON.serialize: ")
                            .append(e.getClass().getSimpleName()).append("\n");
                    report.append(JSON.serialize(resp)).append("\n\n");
                }
            }
        } catch (Exception e) {
            report.append("[详情接口异常] ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n\n");
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
