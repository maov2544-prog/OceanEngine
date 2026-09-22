package com.example.oceanengine.test;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.Oauth2AdvertiserGetApi;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponse;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseData;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseDataListInner;
import com.example.oceanengine.client.SuixintuiTokenClient;

import java.util.List;

/**
 * 诊断工具：用当前随心推 Access Token 查询「已授权账户」列表，
 * 用于确认该 token 实际可操作的千川广告主 ID（advertiser_id）。
 */
public class Oauth2AdvertiserTest {

    public static void main(String[] args) throws Exception {
        String token = SuixintuiTokenClient.getAccessToken();

        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");

        Oauth2AdvertiserGetApi api = new Oauth2AdvertiserGetApi();
        api.setApiClient(apiClient);

        Oauth2AdvertiserGetResponse response = api.openApiOauth2AdvertiserGetGet(token);

        if (response.getCode() == null || response.getCode() != 0L) {
            System.out.println("查询失败 code=" + response.getCode()
                    + " message=" + response.getMessage()
                    + " request_id=" + response.getRequestId());
            return;
        }

        Oauth2AdvertiserGetResponseData data = response.getData();
        List<Oauth2AdvertiserGetResponseDataListInner> list =
                data == null ? null : data.getList();

        System.out.println("=== 已授权账户列表 ===");
        if (list == null || list.isEmpty()) {
            System.out.println("（空）");
            return;
        }
        for (Oauth2AdvertiserGetResponseDataListInner item : list) {
            System.out.printf("advertiser_id=%s | advertiser_name=%s | account_type=%s | "
                            + "account_role=%s | account_string_id=%s | is_valid=%s%n",
                    item.getAdvertiserId(),
                    item.getAdvertiserName(),
                    item.getAccountType(),
                    item.getAccountRole(),
                    item.getAccountStringId(),
                    item.getIsValid());
        }
    }
}
