package com.aolandai.oceanengine.service.suixintui;

import com.aolandai.oceanengine.client.ApiErrors;
import com.aolandai.oceanengine.client.RateLimitException;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderDetailV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 随心推全域订单详情服务。
 *
 * <p>对应官方接口：GET /open_api/v1.0/qianchuan/aweme/uni_promotion/order/detail/
 * （获取随心推全域订单详情，权限点：随心推全域投放 200000042）。</p>
 *
 * <p>注意：详情接口为「一单一请求」，批量查询时请自行控制数量与频率。</p>
 */
public class SuixintuiOrderDetailService {

    private static final long MIN_REQUEST_INTERVAL_MILLIS =
            Long.getLong("suixintui.detail.min-request-interval-ms", 100L);
    private static final AtomicLong LAST_REQUEST_AT = new AtomicLong();

    private final ApiClient apiClient;
    private final String accessToken;

    public SuixintuiOrderDetailService(ApiClient apiClient, String accessToken) {
        this.apiClient = apiClient;
        this.accessToken = accessToken;
    }

    /**
     * 查询单条随心推全域订单详情（瞬时失败自动重试）。
     *
     * <p>详情接口是「一单一请求」，一次瞬时失败就会让该订单被当作「详情失败」跳过，
     * 直接造成候选漏判 —— 所以这里必须重试，不能像以前那样一次失败就放弃。</p>
     *
     * @param orderId      订单 ID（来自列表接口返回的 order_id）
     * @param advertiserId 千川业务账户 ID
     * @return 订单详情 data（含 delivery_setting / product_info / aweme_info 等）
     * @throws RateLimitException 被频控拦截（不重试，交由轮次层决定是否中止）
     * @throws ApiException       其他接口错误（code != 0 或 data 为空）
     */
    public QianchuanAwemeUniPromotionOrderDetailV10ResponseData getOrderDetail(
            Long orderId, Long advertiserId) throws ApiException {
        return withRetry(() -> fetchOrderDetail(orderId, advertiserId),
                "订单详情 order_id=" + orderId);
    }

    /** 真正发起一次详情请求（含节流）。 */
    private QianchuanAwemeUniPromotionOrderDetailV10ResponseData fetchOrderDetail(
            Long orderId, Long advertiserId) throws ApiException {

        throttle();
        QianchuanAwemeUniPromotionOrderDetailV10Api api =
                new QianchuanAwemeUniPromotionOrderDetailV10Api();
        api.setApiClient(apiClient);

        // 注意：SDK 方法参数顺序为 (advertiser_id, order_id)，
        // 与文档入参顺序一致，传反会报 code=40002「Account <order_id> doesn't exist」
        QianchuanAwemeUniPromotionOrderDetailV10Response response =
                api.openApiV10QianchuanAwemeUniPromotionOrderDetailGet(advertiserId, orderId);

        Long code = response.getCode();
        if (ApiErrors.isRateLimit(code)) {
            throw ApiErrors.rateLimitOrNull(code, "随心推全域订单详情 order_id=" + orderId,
                    response.getRequestId());
        }
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

    // ==================== 重试 ====================

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws ApiException;
    }

    /**
     * 瞬时错误（网络 / 5xx）最多重试 3 次，退避 1s/2s。
     *
     * <p>业务错误（参数错误等）与限流错误直接抛出，不重试：</p>
     * <ul>
     *   <li>业务错误重试没有意义；</li>
     *   <li>限流里 40110 有 5 分钟惩罚期，原地退避 1~2 秒必然全部失败，应由上层中止本轮。</li>
     * </ul>
     */
    private static <T> T withRetry(ThrowingSupplier<T> action, String label)
            throws ApiException {
        int maxAttempts = 3;
        ApiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RateLimitException e) {
                throw e;
            } catch (ApiException e) {
                if (ApiErrors.isBusinessError(e.getMessage())) {
                    throw e;
                }
                last = e;
                if (attempt < maxAttempts) {
                    long backoffMillis = 1000L * attempt;
                    System.out.printf("  [RETRY] %s 第 %d 次失败（%s），%d ms 后重试%n",
                            label, attempt, e.getMessage(), backoffMillis);
                    sleepMillis(backoffMillis);
                }
            }
        }
        throw last;
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("详情接口重试等待被中断", e);
        }
    }

    private static void throttle() {
        while (true) {
            long now = System.currentTimeMillis();
            long previous = LAST_REQUEST_AT.get();
            long wait = MIN_REQUEST_INTERVAL_MILLIS - (now - previous);
            if (wait <= 0 && LAST_REQUEST_AT.compareAndSet(previous, now)) {
                return;
            }
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("详情接口限速等待被中断", e);
                }
            }
        }
    }

    /**
     * 查询订单创建时设定的预算（最多投多少钱）。
     *
     * @param orderId      订单 ID
     * @param advertiserId 千川业务账户 ID
     * @return 预算金额 delivery_setting.amount，单位元（官方口径）；订单无投放设置时返回 null
     * @throws ApiException 接口错误
     */
    public Long getBudget(Long orderId, Long advertiserId) throws ApiException {
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData data =
                getOrderDetail(orderId, advertiserId);
        return data.getDeliverySetting() == null
                ? null
                : data.getDeliverySetting().getAmount();
    }

    /**
     * 查询订单「投放总金额」= 创建时设定的预算 + 追加金额总和。
     *
     * <p>已追投（加预算）的订单，实际投放金额 = delivery_setting.amount
     * + add_amount_info.add_amount；未追投时 add_amount_info 可能为 null，
     * 此时等于创建预算。</p>
     *
     * @param orderId      订单 ID
     * @param advertiserId 千川业务账户 ID
     * @return 投放总金额（单位元，官方口径）；订单无投放设置时返回 null
     * @throws ApiException 接口错误
     */
    public Long getTotalBudget(Long orderId, Long advertiserId) throws ApiException {
        QianchuanAwemeUniPromotionOrderDetailV10ResponseData data =
                getOrderDetail(orderId, advertiserId);
        if (data.getDeliverySetting() == null) {
            return null;
        }
        Long base = data.getDeliverySetting().getAmount();
        Long add = data.getAddAmountInfo() == null
                ? null : data.getAddAmountInfo().getAddAmount();
        if (base == null) {
            return add;
        }
        return add == null ? base : base + add;
    }
}
