package com.example.oceanengine.service.suixintui;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderBudgetAddV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderBudgetAddV10Request;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderBudgetAddV10Response;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 随心推全域订单追投服务。
 *
 * <p>对应官方接口：POST /open_api/v1.0/qianchuan/aweme/uni_promotion/order/budget/add/
 * （追加随心推全域订单预算，权限点：投放管理-随心推全域投放 200000042）。</p>
 *
 * <p>官方参数口径（已核对官方文档）：</p>
 * <ul>
 *   <li><b>add_amount：追加的预算，单位元</b>（与详情接口 delivery_setting.amount 同为元）</li>
 *   <li><b>add_delivery_time：必填</b>，延长的投放时间，单位小时，允许值
 *       24、48、72、96、120、144、168</li>
 * </ul>
 *
 * <p>追投 = 真实资金操作：调用后订单预算上限立即增加，会影响后续投放消耗，
 * 请确认金额与时长后再调用。</p>
 */
public class SuixintuiReinvestService {

    /** 官方允许的追加投放时长（小时） */
    public static final Set<Double> ALLOWED_DELIVERY_TIMES =
            new HashSet<>(Arrays.asList(24.0, 48.0, 72.0, 96.0, 120.0, 144.0, 168.0));

    private final ApiClient apiClient;
    private final String accessToken;

    public SuixintuiReinvestService(ApiClient apiClient, String accessToken) {
        this.apiClient = apiClient;
        this.accessToken = accessToken;
    }

    /**
     * 对单个随心推全域订单追加预算（追投）。
     *
     * @param advertiserId    千川业务账户 ID（随心推投放账户 id）
     * @param orderId         需要追加预算的订单 ID
     * @param addAmount       追加的预算，单位元，必须 &gt; 0
     * @param addDeliveryTime 延长的投放时间，单位小时，必填，允许值：
     *                        24、48、72、96、120、144、168
     * @throws ApiException 参数非法或接口错误（code != 0）
     */
    public void addBudget(Long advertiserId, Long orderId, Long addAmount, Double addDeliveryTime)
            throws ApiException {
        if (advertiserId == null) {
            throw new ApiException("随心推追投 advertiser_id 不能为空");
        }
        if (orderId == null) {
            throw new ApiException("随心推追投 order_id 不能为空");
        }
        if (addAmount == null || addAmount <= 0) {
            throw new ApiException("随心推追投 add_amount 必须大于 0（单位元），当前: " + addAmount);
        }
        if (addDeliveryTime == null || !ALLOWED_DELIVERY_TIMES.contains(addDeliveryTime)) {
            throw new ApiException("随心推追投 add_delivery_time 必填且须为 24/48/72/96/120/144/168 之一，当前: "
                    + addDeliveryTime);
        }

        QianchuanAwemeUniPromotionOrderBudgetAddV10Request request =
                new QianchuanAwemeUniPromotionOrderBudgetAddV10Request()
                        .advertiserId(advertiserId)
                        .orderId(orderId)
                        .addAmount(addAmount)
                        .addDeliveryTime(addDeliveryTime);

        QianchuanAwemeUniPromotionOrderBudgetAddV10Api api =
                new QianchuanAwemeUniPromotionOrderBudgetAddV10Api();
        api.setApiClient(apiClient);

        QianchuanAwemeUniPromotionOrderBudgetAddV10Response response =
                api.openApiV10QianchuanAwemeUniPromotionOrderBudgetAddPost(request);

        Long code = response.getCode();
        if (code == null || code != 0L) {
            throw new ApiException("随心推全域订单加预算 code=" + code
                    + " message=" + response.getMessage()
                    + " request_id=" + response.getRequestId());
        }
    }
}
