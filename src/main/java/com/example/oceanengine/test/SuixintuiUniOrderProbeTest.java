package com.example.oceanengine.test;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.Oauth2AdvertiserGetApi;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderGetV10Api;
import com.bytedance.ads.api.QianchuanShopAdvertiserListV10Api;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponse;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseData;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseDataListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Count;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10MarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10Response;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10ResponseData;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10ResponseDataAdvIdListInner;
import com.example.oceanengine.client.SuixintuiTokenClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断工具 v3：探测「随心推全域订单列表」接口，
 * 定位存在全域随心推订单的 advertiser_id。
 */
public class SuixintuiUniOrderProbeTest {

    public static void main(String[] args) throws Exception {
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        // 1. 已授权账户 -> 店铺账户
        Oauth2AdvertiserGetApi oauthApi = new Oauth2AdvertiserGetApi();
        oauthApi.setApiClient(apiClient);
        Oauth2AdvertiserGetResponse oauthResp = oauthApi.openApiOauth2AdvertiserGetGet(token);
        List<Oauth2AdvertiserGetResponseDataListInner> accounts =
                oauthResp.getData() == null ? null : oauthResp.getData().getList();
        if (accounts == null || accounts.isEmpty()) {
            System.out.println("已授权账户列表为空");
            return;
        }

        // 2. 店铺账户 -> 千川业务账户
        Map<Long, String> candidates = new LinkedHashMap<>();
        QianchuanShopAdvertiserListV10Api shopApi = new QianchuanShopAdvertiserListV10Api();
        shopApi.setApiClient(apiClient);
        for (Oauth2AdvertiserGetResponseDataListInner account : accounts) {
            if (!"PLATFORM_ROLE_SHOP_ACCOUNT".equals(String.valueOf(account.getAccountRole()))) {
                continue;
            }
            try {
                QianchuanShopAdvertiserListV10Response resp = shopApi
                        .openApiV10QianchuanShopAdvertiserListGet(
                                account.getAdvertiserId(), null, 1L, 100L);
                if (resp.getCode() == null || resp.getCode() != 0L) {
                    continue;
                }
                QianchuanShopAdvertiserListV10ResponseData data = resp.getData();
                List<QianchuanShopAdvertiserListV10ResponseDataAdvIdListInner> advList =
                        data == null ? null : data.getAdvIdList();
                if (advList == null) {
                    continue;
                }
                for (QianchuanShopAdvertiserListV10ResponseDataAdvIdListInner adv : advList) {
                    candidates.putIfAbsent(adv.getAdvId(), adv.getAdvName());
                }
            } catch (Exception e) {
                System.out.println("  店铺账户查询异常: " + e.getMessage());
            }
        }

        System.out.println("候选千川业务账户数: " + candidates.size());
        System.out.println("=== 随心推全域订单探测 ===");
        for (Map.Entry<Long, String> entry : candidates.entrySet()) {
            probe(apiClient, entry.getKey(), entry.getValue());
        }
    }

    private static void probe(ApiClient apiClient, Long advertiserId, String name) {
        StringBuilder sb = new StringBuilder();
        for (QianchuanAwemeUniPromotionOrderGetV10MarketingGoal goal :
                new QianchuanAwemeUniPromotionOrderGetV10MarketingGoal[]{
                        QianchuanAwemeUniPromotionOrderGetV10MarketingGoal.VIDEO_PROM_GOODS,
                        QianchuanAwemeUniPromotionOrderGetV10MarketingGoal.LIVE_PROM_GOODS}) {
            try {
                QianchuanAwemeUniPromotionOrderGetV10Api api =
                        new QianchuanAwemeUniPromotionOrderGetV10Api();
                api.setApiClient(apiClient);
                QianchuanAwemeUniPromotionOrderGetV10Response resp =
                        api.openApiV10QianchuanAwemeUniPromotionOrderGetGet(
                                advertiserId,
                                goal,
                                null, // filtering.status
                                QianchuanAwemeUniPromotionOrderGetV10OrderField.ORDER_CREATE_TIME,
                                null, // cursor
                                QianchuanAwemeUniPromotionOrderGetV10Count.NUMBER_10);
                if (resp.getCode() == null || resp.getCode() != 0L) {
                    sb.append(String.format("  [%s] code=%s %s%n",
                            goal, resp.getCode(), resp.getMessage()));
                    continue;
                }
                QianchuanAwemeUniPromotionOrderGetV10ResponseData data = resp.getData();
                List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> list =
                        data == null ? null : data.getOrderList();
                long count = list == null ? 0 : list.size();
                String sample = "";
                if (list != null && !list.isEmpty()) {
                    sample = " 样例 order_id: "
                            + list.get(0).getOrderId()
                            + " ... " + list.get(list.size() - 1).getOrderId();
                }
                sb.append(String.format("  [%s] 第1页订单数=%d%s%n", goal, count, sample));
            } catch (Exception e) {
                sb.append(String.format("  [%s] 异常: %s%n", goal, e.getMessage()));
            }
        }
        System.out.printf("advertiser_id=%d (%s):%n%s", advertiserId, name, sb);
    }
}
