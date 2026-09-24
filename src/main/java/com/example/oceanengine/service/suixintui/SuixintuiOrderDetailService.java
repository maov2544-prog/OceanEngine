package com.example.oceanengine.service.suixintui;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderDetailV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;

/**
 * 随心推全域订单详情服务。
 *
 * <p>对应官方接口：GET /open_api/v1.0/qianchuan/aweme/uni_promotion/order/detail/
 * （获取随心推全域订单详情，权限点：随心推全域投放 200000042）。</p>
 *
 * <p>注意：详情接口为「一单一请求」，批量查询时请自行控制数量与频率。</p>
 */
public class SuixintuiOrderDetailService {

    private final ApiClient apiClient;
    private final String accessToken;

    public SuixintuiOrderDetailService(ApiClient apiClient, String accessToken) {
        this.apiClient = apiClient;
        this.accessToken = accessToken;
    }

    /**
     * 查询单条随心推全域订单详情。
     *
     * @param orderId      订单 ID（来自列表接口返回的 order_id）
     * @param advertiserId 千川业务账户 ID
     * @return 订单详情 data（含 delivery_setting / product_info / aweme_info 等）
     * @throws ApiException 接口错误（code != 0 或 data 为空）
     */
    public QianchuanAwemeUniPromotionOrderDetailV10ResponseData getOrderDetail(
            Long orderId, Long advertiserId) throws ApiException {

        QianchuanAwemeUniPromotionOrderDetailV10Api api =
                new QianchuanAwemeUniPromotionOrderDetailV10Api();
        api.setApiClient(apiClient);

        // 注意：SDK 方法参数顺序为 (advertiser_id, order_id)，
        // 与文档入参顺序一致，传反会报 code=40002「Account <order_id> doesn't exist」
        QianchuanAwemeUniPromotionOrderDetailV10Response response =
                api.openApiV10QianchuanAwemeUniPromotionOrderDetailGet(advertiserId, orderId);

        Long code = response.getCode();
        if (code == null || code != 0L) {
            throw new ApiException("随心推全域订单详情 code=" + code
                    + " message=" + response.getMessage()
                    + " request_id=" + response.getRequestId());
        }
        if (response.getData() == null) {
            throw new ApiException("随心推全域订单详情 data 为空, request_id=" + response.getRequestId());
        }
        return response.getData();
    }

    /**
     * 查询订单创建时设定的预算（最多投多少钱）。
     *
     * @param orderId      订单 ID
     * @param advertiserId 千川业务账户 ID
     * @return 预算金额 delivery_setting.amount；订单无投放设置时返回 null
     * @throws ApiException 接口错误
     */
    public Long getBudget(Long orderId, Long advertiserId) throws ApiException {
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData data =
                getOrderDetail(orderId, advertiserId);
        return data.getDeliverySetting() == null
                ? null
                : data.getDeliverySetting().getAmount();
    }
}
