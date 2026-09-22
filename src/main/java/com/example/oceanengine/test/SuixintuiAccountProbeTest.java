package com.example.oceanengine.test;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.Oauth2AdvertiserGetApi;
import com.bytedance.ads.api.QianchuanAwemeOrderGetV10Api;
import com.bytedance.ads.api.QianchuanShopAdvertiserListV10Api;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponse;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseData;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseDataListInner;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10Filtering;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10FilteringMarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10ResponseData;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10Response;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10ResponseData;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10ResponseDataAdvIdListInner;
import com.example.oceanengine.client.SuixintuiTokenClient;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断工具 v2：对当前随心推 Token 下所有已授权账户做两层探测——
 * ① 抖店店铺账户 → 「查询店铺下管理的千川业务账户」找到真正的千川业务账户；
 * ② 对所有候选千川账户调用「获取随心推订单列表」，定位有订单的 advertiser_id。
 */
public class SuixintuiAccountProbeTest {

    public static void main(String[] args) throws Exception {
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        LocalDate start = LocalDate.now().minusDays(179);
        LocalDate end = LocalDate.now();

        // 1. 已授权账户
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
        Map<Long, String> candidates = new LinkedHashMap<>(); // advId -> 名称
        System.out.println("=== 店铺账户 -> 千川业务账户 ===");
        QianchuanShopAdvertiserListV10Api shopApi = new QianchuanShopAdvertiserListV10Api();
        shopApi.setApiClient(apiClient);

        for (Oauth2AdvertiserGetResponseDataListInner account : accounts) {
            String role = String.valueOf(account.getAccountRole());
            if (!"PLATFORM_ROLE_SHOP_ACCOUNT".equals(role)) {
                continue;
            }
            try {
                QianchuanShopAdvertiserListV10Response resp = shopApi
                        .openApiV10QianchuanShopAdvertiserListGet(
                                account.getAdvertiserId(), null, 1L, 100L);
                if (resp.getCode() == null || resp.getCode() != 0L) {
                    System.out.printf("  店铺 %d (%s) -> 错误 code=%s message=%s%n",
                            account.getAdvertiserId(), account.getAdvertiserName(),
                            resp.getCode(), resp.getMessage());
                    continue;
                }
                QianchuanShopAdvertiserListV10ResponseData data = resp.getData();
                List<QianchuanShopAdvertiserListV10ResponseDataAdvIdListInner> advList =
                        data == null ? null : data.getAdvIdList();
                if (advList == null || advList.isEmpty()) {
                    System.out.printf("  店铺 %d (%s) -> 无业务账户（list=%s）%n",
                            account.getAdvertiserId(), account.getAdvertiserName(), data == null ? null : data.getList());
                    continue;
                }
                for (QianchuanShopAdvertiserListV10ResponseDataAdvIdListInner adv : advList) {
                    System.out.printf("  店铺 %d (%s) -> 业务账户 %d (%s)%n",
                            account.getAdvertiserId(), account.getAdvertiserName(),
                            adv.getAdvId(), adv.getAdvName());
                    candidates.putIfAbsent(adv.getAdvId(), adv.getAdvName());
                }
            } catch (Exception e) {
                System.out.printf("  店铺 %d (%s) -> 异常: %s%n",
                        account.getAdvertiserId(), account.getAdvertiserName(), e.getMessage());
            }
        }

        // 3. 补充：OAuth 列表里的客户账户也作为候选
        for (Oauth2AdvertiserGetResponseDataListInner account : accounts) {
            String role = String.valueOf(account.getAccountRole());
            if (!"PLATFORM_ROLE_SHOP_ACCOUNT".equals(role)) {
                candidates.putIfAbsent(account.getAdvertiserId(), account.getAdvertiserName());
            }
        }

        // 4. 逐候选账户探测随心推订单
        System.out.println();
        System.out.println("=== 候选千川账户 -> 随心推订单探测 (近179天) ===");
        for (Map.Entry<Long, String> entry : candidates.entrySet()) {
            probe(apiClient, entry.getKey(), entry.getValue(), start, end);
        }
    }

    private static void probe(ApiClient apiClient, Long advertiserId, String name,
                              LocalDate start, LocalDate end) {
        StringBuilder sb = new StringBuilder();
        for (QianchuanAwemeOrderGetV10FilteringMarketingGoal goal :
                new QianchuanAwemeOrderGetV10FilteringMarketingGoal[]{
                        QianchuanAwemeOrderGetV10FilteringMarketingGoal.VIDEO_PROM_GOODS,
                        QianchuanAwemeOrderGetV10FilteringMarketingGoal.LIVE_PROM_GOODS}) {
            try {
                QianchuanAwemeOrderGetV10Api api = new QianchuanAwemeOrderGetV10Api();
                api.setApiClient(apiClient);
                QianchuanAwemeOrderGetV10Filtering filtering =
                        new QianchuanAwemeOrderGetV10Filtering();
                filtering.setMarketingGoal(goal);
                QianchuanAwemeOrderGetV10Response resp =
                        api.openApiV10QianchuanAwemeOrderGetGet(
                                advertiserId, filtering, null, null, null,
                                start.toString(), end.toString());
                if (resp.getCode() == null || resp.getCode() != 0L) {
                    sb.append(String.format("  [%s] code=%s %s%n",
                            goal, resp.getCode(), resp.getMessage()));
                    continue;
                }
                QianchuanAwemeOrderGetV10ResponseData data = resp.getData();
                long count = data == null || data.getList() == null
                        ? 0 : data.getList().size();
                sb.append(String.format("  [%s] 第1页订单数=%d%n", goal, count));
            } catch (Exception e) {
                sb.append(String.format("  [%s] 异常: %s%n", goal, e.getMessage()));
            }
        }
        System.out.printf("advertiser_id=%d (%s):%n%s", advertiserId, name, sb);
    }
}
