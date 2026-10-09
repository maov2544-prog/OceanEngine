package com.aolandai.oceanengine.suixintui;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderBudgetAddV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderBudgetAddV10Request;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderBudgetAddV10Response;
import com.aolandai.oceanengine.client.SuixintuiTokenClient;

import java.util.Set;

/**
 * 追投接口测试：追加随心推全域订单预算。
 *
 * <p>接口：POST /open_api/v1.0/qianchuan/aweme/uni_promotion/order/budget/add/
 * SDK：{@link QianchuanAwemeUniPromotionOrderBudgetAddV10Api}</p>
 *
 * <p>【重要】调用本接口会真实追加订单预算（扣减账户资金），执行前请确认订单 ID 与金额。</p>
 *
 * <p>参数配置（环境变量）：</p>
 * <ul>
 *   <li>QIANCHUAN_SERVICE_BEARER_TOKEN —— 自建服务端认证密钥（必填，Access Token 由服务端下发）</li>
 *   <li>SUIXINTUI_ADVERTISER_ID —— 随心推投放账户 id（必填，无默认；可从订单列表 CSV 的 advertiser_id 列取）</li>
 *   <li>SUIXINTUI_ORDER_ID —— 需要追投的订单 id（必填，无默认；需为投放中的订单，可从订单列表 JSON 取）</li>
 *   <li>SUIXINTUI_ADD_AMOUNT —— 追加预算金额，单位元（可选，默认 100）</li>
 *   <li>SUIXINTUI_ADD_DELIVERY_TIME —— 延长投放时长，单位小时（可选，默认 24；
 *       允许值仅 24、48、72、96、120、144、168）</li>
 * </ul>
 *
 * <p>示例：</p>
 * <pre>
 * set SUIXINTUI_ADVERTISER_ID=1771298760893511
 * set SUIXINTUI_ORDER_ID=7688250099981402148
 * set SUIXINTUI_ADD_AMOUNT=100
 * set SUIXINTUI_ADD_DELIVERY_TIME=24
 * </pre>
 */
public class SuixintuiBudgetAddTest {

    /** 追加投放时长允许值（官方枚举：24、48、72、96、120、144、168 小时） */
    private static final Set<Double> ALLOWED_DELIVERY_TIMES =
            Set.of(24d, 48d, 72d, 96d, 120d, 144d, 168d);

    public static void main(String[] args) throws Exception {
        // ========== 1. 读取追投参数 ==========
        Long advertiserId = readRequiredLong("SUIXINTUI_ADVERTISER_ID",
                "请设置环境变量 SUIXINTUI_ADVERTISER_ID（随心推投放账户 id，从订单 CSV 的 advertiser_id 列取）");
        Long orderId = readRequiredLong("SUIXINTUI_ORDER_ID",
                "请设置环境变量 SUIXINTUI_ORDER_ID（要追投的订单 id，需为投放中的订单，从订单 JSON 取）");
        long addAmount = readLongEnv("SUIXINTUI_ADD_AMOUNT", 100L);
        double addDeliveryTime = readDoubleEnv("SUIXINTUI_ADD_DELIVERY_TIME", 24d);

        if (!ALLOWED_DELIVERY_TIMES.contains(addDeliveryTime)) {
            throw new IllegalArgumentException("add_delivery_time 仅允许取值 "
                    + ALLOWED_DELIVERY_TIMES + "，当前为 " + addDeliveryTime);
        }

        // ========== 2. 初始化 SDK 客户端 ==========
        String token = SuixintuiTokenClient.getAccessToken();
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        // ========== 3. 组装追投请求 ==========
        QianchuanAwemeUniPromotionOrderBudgetAddV10Request request =
                new QianchuanAwemeUniPromotionOrderBudgetAddV10Request()
                        .advertiserId(advertiserId)
                        .orderId(orderId)
                        .addAmount(addAmount)
                        .addDeliveryTime(addDeliveryTime);

        System.out.println("=== 追加随心推全域订单预算 ===");
        System.out.println("advertiser_id: " + advertiserId);
        System.out.println("order_id: " + orderId);
        System.out.println("add_amount: " + addAmount + " 元");
        System.out.println("add_delivery_time: " + addDeliveryTime + " 小时");
        System.out.println("【提示】该操作将真实追加订单预算！");

        // ========== 4. 调用追投接口 ==========
        QianchuanAwemeUniPromotionOrderBudgetAddV10Api api =
                new QianchuanAwemeUniPromotionOrderBudgetAddV10Api();
        api.setApiClient(apiClient);

        QianchuanAwemeUniPromotionOrderBudgetAddV10Response response =
                api.openApiV10QianchuanAwemeUniPromotionOrderBudgetAddPost(request);

        // ========== 5. 打印结果 ==========
        if (response.getCode() == null || response.getCode() != 0L) {
            System.out.println("追投失败 code=" + response.getCode()
                    + " message=" + response.getMessage()
                    + " request_id=" + response.getRequestId());
        } else {
            System.out.println("追投成功 code=0 message=" + response.getMessage()
                    + " request_id=" + response.getRequestId());
        }
    }

    // ==================== 配置读取 ====================

    private static Long readRequiredLong(String name, String hint) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(hint);
        }
        return Long.parseLong(v.trim());
    }

    private static long readLongEnv(String name, long defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : Long.parseLong(v.trim());
    }

    private static double readDoubleEnv(String name, double defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : Double.parseDouble(v.trim());
    }
}
