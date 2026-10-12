package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>只读</b>探针：核实指定订单的<b>真实归属账户</b>，并打印完整原始返回。
 *
 * <p><b>背景</b>：老板给的新目标订单 {@code 7694463463581614099} 配的账户
 * {@code 182337954088039} 是 15 位（千川账户应为 16 位，疑似漏一位）。本探针扫全部生产账户，
 * 逐一调<b>订单详情</b>接口，找出这个订单<b>真正归属</b>哪个账户 —— <b>不假设</b>，
 * 用实测结果说话。</p>
 *
 * <p><b>绝对只读</b>：只调 {@link SuixintuiOrderDetailService#getOrderDetail}（GET 查询），
 * <b>不建单、不花钱、不改任何代码/配置</b>。</p>
 *
 * <h2>输出</h2>
 * <ul>
 *   <li>每个账户的命中情况（命中 / 报错 code+message）；</li>
 *   <li>命中账户的完整原始 JSON（{@code response.toJson()}）；</li>
 *   <li>关键字段提取：status / product_id / aweme_id / delivery_setting 全部字段（含 deep_external_action）。</li>
 * </ul>
 *
 * <h2>运行（PowerShell）</h2>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = (Get-Content target\cp.txt -Raw).Trim()
 * java "-Dfile.encoding=UTF-8" -cp "target\classes;target\test-classes;$cp" `
 *   com.aolandai.oceanengine.tools.OrderOwnerDiscoveryProbe
 * # 可覆盖：--orderId=... --out=...
 * </pre>
 */
public final class OrderOwnerDiscoveryProbe {

    /** 老板给的新目标订单 */
    private static final long ORDER_ID = 7694463463581614099L;

    /** 账户配置来源（优先从此文件解析 advertiser-tiers） */
    private static final String DEFAULT_CONFIG_PATH = "D:\\oceanengine\\config\\application.yml";

    private static final String DEFAULT_OUTPUT_PATH = "target/order-owner-discovery-probe.txt";

    private static final String API_BASE_URL = "https://api.oceanengine.com";

    /**
     * 兜底账户列表（config 读不到时用）：3 档共 6 个账户。
     *
     * <p>来源：{@code D:\oceanengine\config\application.yml} 的
     * {@code suixintui.reinvest.advertiser-tiers}（2026-10 快照）。</p>
     */
    private static final long[] FALLBACK_ADVERTISER_IDS = {
            1_779_106_144_470_100L,  // 10000档 婴童【青少年-商品】
            1_778_533_513_439_244L,  // 10000档 婴童【儿童-商品】
            1_776_098_375_039_040L,  // 10000档 官方【青少年-商品】
            1_771_298_760_893_511L,  // 10000档 官方【孕产-商品】
            1_795_656_569_312_393L,  // 10000档 官方【婴童-商品】
            1_823_379_540_880_396L,  // 20000档 官方【母婴商品】
    };

    /** 额外探测候选：老板给的 15 位补全成 16 位的猜测（去尾补0 / 加尾数等常见补法） */
    private static final long[] EXTRA_CANDIDATES = {
            1_823_379_540_880_396L,   // 1823379540880396（已知 20000 档账户）
            1_823_379_540_880_390L,   // 1823379540880390
            1_823_379_540_880_39L,    // 不合法位数，仅记录
    };

    private OrderOwnerDiscoveryProbe() {
    }

    public static void main(String[] args) {
        long orderId = ORDER_ID;
        String configPath = DEFAULT_CONFIG_PATH;
        String outputPath = DEFAULT_OUTPUT_PATH;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if (a.startsWith("--orderId=")) {
                orderId = Long.parseLong(a.substring("--orderId=".length()).trim());
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
        int exit = run(orderId, configPath, report);
        System.out.print(report);
        writeOutput(outputPath, report);
        System.exit(exit);
    }

    private static int run(long orderId, String configPath, StringBuilder report) {
        report.append("=== 只读探针：核实订单归属账户 ===\n");
        report.append("目标订单 : ").append(orderId).append("\n");
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

        // 汇总候选账户：配置文件 + 兜底 + 老板给的15位补全猜测（去重）
        List<Long> candidates = new ArrayList<>();
        for (long id : loadAdvertiserIds(configPath, report)) {
            addUnique(candidates, id);
        }
        for (long id : FALLBACK_ADVERTISER_IDS) {
            addUnique(candidates, id);
        }
        for (long id : EXTRA_CANDIDATES) {
            if (id >= 100_000_000_000_000L) {   // 只收 16 位以上，过滤荒谬值
                addUnique(candidates, id);
            }
        }

        report.append("候选账户数: ").append(candidates.size()).append("\n");
        for (long id : candidates) {
            report.append("  - ").append(id)
                    .append("（").append(String.valueOf(id).length()).append(" 位）\n");
        }
        report.append("提示: 老板给的 182337954088039 是 ").append("182337954088039".length())
                .append(" 位（缺一位），已按常见补法加入候选。\n\n");

        report.append("========== 逐账户调订单详情（只读）==========\n");
        Long hitAdvertiser = null;
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData hitData = null;

        for (long advId : candidates) {
            report.append("\n---- 账户 ").append(advId).append(" ----\n");
            try {
                QianchuanAwemeUniPromotionOrderDetailV10ResponseData data =
                        detailService.getOrderDetail(orderId, advId);
                report.append("✅ 命中！该订单归属账户 = ").append(advId).append("\n");
                if (hitAdvertiser == null) {
                    hitAdvertiser = advId;
                    hitData = data;
                }
                // 可能多个账户都"命中"（同集团？）——全部打印，但只把第一个作为结论
            } catch (ApiException e) {
                report.append("✗ 未命中/报错: ").append(e.getMessage()).append("\n");
            } catch (Exception e) {
                report.append("✗ 异常: ").append(e.getClass().getSimpleName())
                        .append(": ").append(e.getMessage()).append("\n");
            }
        }

        report.append("\n========== 归属结论 ==========\n");
        if (hitAdvertiser == null) {
            report.append("⚠️ 所有候选账户都未能查到该订单。可能原因：\n");
            report.append("   - 订单不属于这 6 个生产账户；\n");
            report.append("   - 订单号有误；\n");
            report.append("   - 账户 ID 仍是错的（需向老板确认完整 16 位）。\n");
            return 2;
        }
        report.append("★ 真实归属账户 = ").append(hitAdvertiser).append("\n");

        if (hitData != null) {
            printDetailFields(hitAdvertiser, hitData, report);
        }
        return 0;
    }

    /** 打印详情关键字段 + 完整原始 JSON */
    private static void printDetailFields(long advertiserId,
                                          QianchuanAwemeUniPromotionOrderDetailV10ResponseData data,
                                          StringBuilder report) {
        report.append("\n========== 订单关键字段（账户 ").append(advertiserId).append("）==========\n");

        // status：反射或直接 getter
        report.append("status           = ").append(safeGet(data, "getStatus")).append("\n");

        // product_info.product_id
        Object productInfo = safeGet(data, "getProductInfo");
        report.append("product_info.id  = ").append(safeGet(productInfo, "getId")).append("\n");
        report.append("product_info.name= ").append(safeGet(productInfo, "getName")).append("\n");

        // aweme_info.aweme_id
        Object awemeInfo = safeGet(data, "getAwemeInfo");
        report.append("aweme_info.aweme_id = ").append(safeGet(awemeInfo, "getAwemeId")).append("\n");
        report.append("aweme_info.aweme_show_id = ").append(safeGet(awemeInfo, "getAwemeShowId")).append("\n");
        report.append("aweme_info.aweme_name    = ").append(safeGet(awemeInfo, "getAwemeName")).append("\n");

        // delivery_setting 全部字段
        QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting s = data.getDeliverySetting();
        report.append("\n--- delivery_setting ---\n");
        if (s == null) {
            report.append("delivery_setting = null\n");
        } else {
            report.append("amount               = ").append(s.getAmount()).append("\n");
            report.append("bid_type             = ").append(s.getBidType()).append("\n");
            report.append("delivery_time        = ").append(s.getDeliveryTime()).append("\n");
            report.append("qcpx_mode            = ").append(s.getQcpxMode()).append("\n");
            report.append("roi_goal             = ").append(s.getRoiGoal()).append("\n");
            report.append("deep_external_action = ").append(safeGet(s, "getDeepExternalAction"))
                    .append("   ← ★关注：有没有值\n");
            report.append("support_add_budget   = ").append(safeGet(s, "getSupportAddBudget")).append("\n");
            // 反射列出该 DeliverySetting 的全部 getter，确保不漏字段
            report.append("（DeliverySetting 全部可读字段）:\n");
            for (Method m : s.getClass().getMethods()) {
                String name = m.getName();
                if (name.startsWith("get") && m.getParameterCount() == 0
                        && !name.equals("getClass") && !isObjectMethod(name)) {
                    report.append("   ").append(name).append(" = ")
                            .append(String.valueOf(safeInvoke(s, m))).append("\n");
                }
            }
        }

        report.append("\n--- 完整原始返回 JSON（response.toJson()）---\n");
        try {
            Method toJson = data.getClass().getMethod("toJson");
            report.append(String.valueOf(toJson.invoke(data))).append("\n");
        } catch (Exception e) {
            report.append("[取 toJson 失败] ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n");
        }
    }

    // ==================== 工具 ====================

    private static boolean isObjectMethod(String name) {
        return name.equals("getClass") || name.equals("getSERIALIZED_NAME")
                || name.startsWith("getOpenapi") || name.equals("getDeclaringClass");
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

    /** 从配置文件读 advertiser-tiers 展平；读不到返回空列表（上层再补兜底） */
    @SuppressWarnings("unchecked")
    private static List<Long> loadAdvertiserIds(String configPath, StringBuilder report) {
        List<Long> ids = new ArrayList<>();
        try {
            Path p = Paths.get(configPath);
            if (!Files.exists(p)) {
                report.append("[配置] 未找到 ").append(configPath).append("，用兜底账户列表\n");
                return ids;
            }
            try (InputStream in = Files.newInputStream(p)) {
                Yaml yaml = new Yaml();
                Map<String, Object> root = yaml.load(in);
                Map<String, Object> suixintui = (Map<String, Object>) root.get("suixintui");
                if (suixintui == null) {
                    return ids;
                }
                Map<String, Object> reinvest = (Map<String, Object>) suixintui.get("reinvest");
                if (reinvest == null) {
                    return ids;
                }
                List<Map<String, Object>> tiers = (List<Map<String, Object>>) reinvest.get("advertiser-tiers");
                if (tiers == null) {
                    return ids;
                }
                for (Map<String, Object> tier : tiers) {
                    List<Object> list = (List<Object>) tier.get("advertiser-ids");
                    if (list == null) {
                        continue;
                    }
                    for (Object o : list) {
                        if (o instanceof Number) {
                            ids.add(((Number) o).longValue());
                        } else if (o != null) {
                            try {
                                ids.add(Long.parseLong(o.toString().trim()));
                            } catch (NumberFormatException ignore) {
                                // 跳过非数字
                            }
                        }
                    }
                }
                report.append("[配置] 从 ").append(configPath).append(" 读到 ")
                        .append(ids.size()).append(" 个账户\n");
            }
        } catch (Exception e) {
            report.append("[配置] 解析失败（用兜底）: ")
                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
        }
        return ids;
    }

    private static void addUnique(List<Long> list, long id) {
        if (!list.contains(id)) {
            list.add(id);
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
        System.out.println("用法: OrderOwnerDiscoveryProbe [--orderId=...] [--config=...] [--out=...]");
        System.out.println();
        System.out.println("默认 orderId=" + ORDER_ID + "，config=" + DEFAULT_CONFIG_PATH);
        System.out.println("只读探针：扫全部生产账户调订单详情接口，找出订单真实归属账户。");
    }
}
