package com.aolandai.oceanengine.scheduler;

import com.bytedance.ads.ApiClient;
import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.service.suixintui.SuixintuiReinvestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实追投冒烟测试 —— 会花真钱。
 *
 * <p>下面几个常量都是真实的千川账户 / 订单，调用 {@code addBudget} 会真实扣费。
 * 所以本类默认<b>不执行</b>：只有显式设置环境变量 {@code RUN_REAL_REINVEST_TEST=true} 时才运行。
 * 这样 {@code mvn clean package}（即使不带 {@code -DskipTests}）也不会误扣费。
 *
 * <p>确认要真跑一次时：
 * <pre>
 *   set RUN_REAL_REINVEST_TEST=true
 *   mvn test -Dtest=ReinvestApiSmokeTest
 * </pre>
 *
 * <p>⚠️ 新增会真实扣费的测试时，务必照抄这个注解，不要让钱测试进入默认执行集。
 */
@EnabledIfEnvironmentVariable(named = "RUN_REAL_REINVEST_TEST", matches = "true")
public class ReinvestApiSmokeTest {

    // 1) 目标千川账户 ID（必须是你自己可操作的 advertiser_id）
    private static final Long ADVERTISER_ID = 1823379540880396L;

    // 2) 追加预算（单位：元）
    // 先不要太大，建议 20 / 50 / 100
    private static final Long ADD_AMOUNT = 10L;

    // 3) 追加投放时长（小时）
    // 官方允许：24 / 48 / 72 / 96 / 120 / 144 / 168
    private static final Double ADD_DELIVERY_TIME = 24.0;

    // 4) 真实订单 ID
    // 这里必须是你已存在的订单ID，不能随便填
    // 先从订单列表接口拿到真实 order_id，再写进这里
    private static final Long ORDER_ID = 1877113335597299L;

    @Test
    void testAddBudget() throws Exception {
        // 先获取 token（动态获取）
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        SuixintuiReinvestService reinvestService =
                new SuixintuiReinvestService(apiClient, token);

        System.out.println("=== 测试追投接口 ===");
        System.out.println("advertiserId=" + ADVERTISER_ID);
        System.out.println("orderId=" + ORDER_ID);
        System.out.println("addAmount=" + ADD_AMOUNT);
        System.out.println("addDeliveryTime=" + ADD_DELIVERY_TIME);

        reinvestService.addBudget(ADVERTISER_ID, ORDER_ID, ADD_AMOUNT, ADD_DELIVERY_TIME);

        System.out.println("追投接口调用成功，未抛异常");
    }
}