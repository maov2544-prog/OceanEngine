package com.aolandai.oceanengine.tools;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderDetailV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;
import com.aolandai.oceanengine.client.SuixintuiTokenClient;

/**
 * 诊断工具：查询随心推全域订单详情，用于追投前确认订单归属与状态。
 * 参数直接在下方修改。
 */
public class SuixintuiOrderDetailTest {

    /** 随心推投放账户 id */
    private static final Long ADVERTISER_ID = 1823379540880396L;

    /** 待追投订单 id（投放订单号） */
    private static final Long ORDER_ID = 7688654520687149099L;

    public static void main(String[] args) throws Exception {
        String token = SuixintuiTokenClient.getAccessToken();
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        QianchuanAwemeUniPromotionOrderDetailV10Api api =
                new QianchuanAwemeUniPromotionOrderDetailV10Api();
        api.setApiClient(apiClient);

        QianchuanAwemeUniPromotionOrderDetailV10Response response =
                api.openApiV10QianchuanAwemeUniPromotionOrderDetailGet(
                        ADVERTISER_ID, ORDER_ID);

        if (response.getCode() == null || response.getCode() != 0L) {
            System.out.println("查询失败 code=" + response.getCode()
                    + " message=" + response.getMessage()
                    + " request_id=" + response.getRequestId());
            return;
        }

        QianchuanAwemeUniPromotionOrderDetailV10ResponseData data = response.getData();
        if (data == null) {
            System.out.println("data 为空");
            return;
        }

        System.out.println("=== 订单详情 ===");
        System.out.println("order_id: " + data.getOrderId());
        System.out.println("ad_id: " + data.getAdId());
        System.out.println("status: " + data.getStatus()
                + "（DELIVERY_OK=投放中，可追投）");
        System.out.println("marketing_goal: " + data.getMarketingGoal());
        System.out.println("order_create_time: " + data.getOrderCreateTime());
        System.out.println("aweme: " + (data.getAwemeInfo() == null ? null :
                data.getAwemeInfo().getAwemeName() + "(" + data.getAwemeInfo().getAwemeId() + ")"));
        System.out.println("product: " + (data.getProductInfo() == null ? null :
                data.getProductInfo().getName() + "(" + data.getProductInfo().getId() + ")"));
        if (data.getDeliverySetting() != null) {
            System.out.println("delivery_setting: " + data.getDeliverySetting().toJson());
        }
        if (data.getAddAmountInfo() != null) {
            System.out.println("add_amount_info: " + data.getAddAmountInfo().toJson());
        }
    }
}
