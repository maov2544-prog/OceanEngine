package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderReportGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10Filter;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10PageSize;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10ResponseDataAdListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10TimeGranularity;

import java.util.List;

/**
 * 临时只读探针（调研用）：验证「随心推全域-订单报表」接口能否按单个 ad_id 过滤，
 * 即能否按订单（order_id ↔ ad_id 一一对应）维度取到 pay ROI。
 *
 * <p>仅 GET 只读，不调任何写接口，不产生任何追投。只有一个 main，无 @Test。</p>
 */
public final class RoiSourceProbe {

    private RoiSourceProbe() {
    }

    public static void main(String[] args) throws Exception {
        String token = SuixintuiTokenClient.getAccessToken();
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath("https://api.oceanengine.com");
        apiClient.addDefaultHeader("Access-Token", token);

        QianchuanAwemeUniPromotionOrderReportGetV10Api api =
                new QianchuanAwemeUniPromotionOrderReportGetV10Api();
        api.setApiClient(apiClient);

        // order_id=7692736387438755881 对应的 ad_id=1878109604852132（来自订单列表）
        QianchuanAwemeUniPromotionOrderReportGetV10Filter filter =
                new QianchuanAwemeUniPromotionOrderReportGetV10Filter();
        filter.setAdId(List.of(1878109604852132L));

        QianchuanAwemeUniPromotionOrderReportGetV10Response resp =
                api.openApiV10QianchuanAwemeUniPromotionOrderReportGetGet(
                        1779106144470100L,
                        "2026-10-01",
                        "2026-10-07",
                        QianchuanAwemeUniPromotionOrderReportGetV10TimeGranularity.DAILY,
                        filter,
                        null,
                        null,
                        1L,
                        QianchuanAwemeUniPromotionOrderReportGetV10PageSize.NUMBER_50);

        System.out.println("code=" + resp.getCode() + " msg=" + resp.getMessage());
        QianchuanAwemeUniPromotionOrderReportGetV10ResponseData d = resp.getData();
        List<QianchuanAwemeUniPromotionOrderReportGetV10ResponseDataAdListInner> list =
                d == null ? null : d.getAdList();
        int n = list == null ? 0 : list.size();
        System.out.println("ad_list size=" + n + " (期望只含 ad_id=1878109604852132)");
        if (list == null) {
            return;
        }
        for (QianchuanAwemeUniPromotionOrderReportGetV10ResponseDataAdListInner it : list) {
            System.out.printf("ad_id=%s | start=%s | stat_cost=%s | pay_roi2=%s | settle_roi2_1h=%s | pay_cnt=%s | settle_cnt=%s%n",
                    it.getAdId(), it.getStartDateTime(), it.getStatCostForRoi2(),
                    it.getTotalPrepayAndPayOrderRoi2(), it.getTotalPrepayAndPaySettleRoi21h(),
                    it.getTotalPayOrderCountForRoi2(), it.getTotalOrderSettleCountForRoi21h());
        }
    }
}
