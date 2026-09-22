package com.example.oceanengine.test;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanAwemeOrderGetV10Api;
import com.bytedance.ads.api.QianchuanAwemeOrderDetailGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10Filtering;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10FilteringMarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeOrderDetailGetV10Response;

/**
 * 千川随心推订单查询测试
 * 用法：
 *   1. 修改下面的 ACCESS_TOKEN 和 ADVERTISER_ID
 *   2. 右键运行 main 方法
 */
public class QianchuanOrderTest {

    // ====== 需要你改的配置 ======
    // 从 Cloudflare KV 里复制的 access_token
    private static final String ACCESS_TOKEN = "856448aced4b750ddd4c76aaebdcca9568ce5edf";
    // 千川客户投放账户ID（advertiser_id），不是抖店店铺ID
    private static final Long ADVERTISER_ID = 1823379540880396L;
    // ============================

    public static void main(String[] args) {
        // 1. 初始化 ApiClient
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", ACCESS_TOKEN);

        System.out.println("=== 开始查询千川订单 ===");
        System.out.println("advertiser_id: " + ADVERTISER_ID);
        System.out.println();

        // 2. 查询订单列表（近30天）
        queryOrderList(apiClient);
        
        // 3. 查询订单详情（需要先从列表里拿到 order_id，取消注释并填入）
        // queryOrderDetail(apiClient, 1234567890L);
    }

    /**
     * 查询随心推订单列表
     */
    private static void queryOrderList(ApiClient apiClient) {
        try {
            QianchuanAwemeOrderGetV10Api orderApi = new QianchuanAwemeOrderGetV10Api();
            orderApi.setApiClient(apiClient);

            // 查询近30天的订单
            String startDate = "2026-08-22";
            String endDate = "2026-09-21";
            QianchuanAwemeOrderGetV10Filtering filtering = new QianchuanAwemeOrderGetV10Filtering();
            filtering.setMarketingGoal(QianchuanAwemeOrderGetV10FilteringMarketingGoal.VIDEO_PROM_GOODS);

            System.out.println("查询日期范围: " + startDate + " ~ " + endDate);

            QianchuanAwemeOrderGetV10Response response = orderApi.openApiV10QianchuanAwemeOrderGetGet(
                ADVERTISER_ID,
                filtering,
                null,    // cursor 分页游标（null = 第一页）
                null,    // count 每页数量
                null,    // orderField 排序字段
                startDate,
                endDate
            );

            System.out.println();
            System.out.println("=== 订单列表查询成功 ===");
            System.out.println(response);

        } catch (Exception e) {
            System.err.println();
            System.err.println("=== 订单列表查询失败 ===");
            System.err.println("错误信息: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 查询随心推订单详情
     * @param orderId 从订单列表里拿到的 order_id
     */
    private static void queryOrderDetail(ApiClient apiClient, Long orderId) {
        try {
            QianchuanAwemeOrderDetailGetV10Api detailApi = new QianchuanAwemeOrderDetailGetV10Api();
            detailApi.setApiClient(apiClient);

            System.out.println("查询订单详情: order_id=" + orderId);

            QianchuanAwemeOrderDetailGetV10Response response = detailApi.openApiV10QianchuanAwemeOrderDetailGetGet(
                orderId,
                ADVERTISER_ID
            );

            System.out.println();
            System.out.println("=== 订单详情查询成功 ===");
            System.out.println(response);

        } catch (Exception e) {
            System.err.println();
            System.err.println("=== 订单详情查询失败 ===");
            System.err.println("错误信息: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
