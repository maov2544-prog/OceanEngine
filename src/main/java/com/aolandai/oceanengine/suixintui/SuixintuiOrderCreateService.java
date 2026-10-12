package com.aolandai.oceanengine.suixintui;

import com.aolandai.oceanengine.client.ApiErrors;
import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.domain.SuixintuiConstraints;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderCreateV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10DeliverySettingBidType;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10DeliverySettingQcpxMode;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10MarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10Request;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10RequestDeliverySetting;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 建<b>随心推</b>订单（A 场景，<b>花钱</b>）。
 *
 * <p>SDK 入口：{@code /qianchuan/aweme/uni_promotion/order/create/}。</p>
 *
 * <h2>⚠️ 硬约束在组装前先校验</h2>
 * <p>官方文档（2026-10-05 确认）的硬约束：
 * {@code amount}（元）必须是 <b>[100, 5000000] 且为 10 的倍数</b>；
 * {@code delivery_time}（小时）是<b>白名单</b>（2/6/12/24/48/72/96/120/144/168），<b>不是范围</b>；
 * {@code roi_goal}（支付 ROI）<b>最多两位小数</b>、范围 <b>[0.01, 10000]</b>，
 * 且 <b>{@code bid_type=MANUAL_BID} 时必填</b>。
 * 规则集中在 {@link SuixintuiConstraints}，本类在<b>组装请求体之前</b>先拦一道 ——
 * 参数不合法时<b>连 dry-run 也不放行</b>，否则演练会打印出一个「看起来没问题、
 * 真跑必被拒」的请求体，把演练变成假绿灯。</p>
 *
 * <h2>⚠️⚠️ dry-run 是硬保护，别绕过</h2>
 * <p>建随心推是<b>花钱</b>的操作。虽然单位已由官方文档确认，仍保留 dry-run：
 * 第一次跑只组装请求体并打印，便于与千川后台的实际效果核对后再真建。</p>
 *
 * <h2>为什么把「组装请求体」和「真调」放在同一个方法里</h2>
 * <p>dry-run 要打印的必须是<b>即将真实发出的那一份请求体</b>。
 * 如果 dry-run 和真调各组装一份，两份迟早会漂移 ——
 * 于是「dry-run 核对通过」变成假绿灯，而真跑时用的是另一份参数。</p>
 *
 * <h2>与 dingtou 的差异（移植适配）</h2>
 * <p>dingtou 用 {@code QianchuanClientFactory.suixintuiOrderCreateFetcher()} 提供 fetcher；
 * OceanEngine 没有这个类，改为在默认 {@link FetcherProvider} 里走
 * {@link SuixintuiTokenClient#getAccessToken()} + {@link ApiClient}，
 * 写法与 {@code service/suixintui/SuixintuiOrderService} 保持一致。</p>
 */
public class SuixintuiOrderCreateService {

    private static final Logger log = LoggerFactory.getLogger(SuixintuiOrderCreateService.class);

    /** SDK 接口路径（仅用于日志说明，实际由 SDK 拼） */
    private static final String CREATE_ENDPOINT =
            "/qianchuan/aweme/uni_promotion/order/create/";

    /**
     * 底层的「建一次」函数抽象 —— 便于单测注入假实现，<b>绝不真建</b>。
     *
     * <p>把 SDK 调用收敛到一个函数式接口，测试就能完全不碰网络（更别说花钱）。</p>
     */
    @FunctionalInterface
    public interface OrderCreateFetcher {
        QianchuanAwemeUniPromotionOrderCreateV10Response create(
                QianchuanAwemeUniPromotionOrderCreateV10Request request) throws ApiException;
    }

    /**
     * 「取一次 fetcher」的抽象（会换 token，所以可能抛异常）。
     *
     * <p>抽出来是为了让单测能直接塞一个假 fetcher —— <b>测试绝不真建单</b>。</p>
     */
    @FunctionalInterface
    public interface FetcherProvider {
        OrderCreateFetcher get() throws Exception;
    }

    private final FetcherProvider fetcherProvider;

    /**
     * 默认构造器（生产用）：建单前从 {@link SuixintuiTokenClient} 换 token、
     * 组装 {@link ApiClient}（base path 取 {@link SuixintuiReinvestProperties#getApiBaseUrl()}）。
     *
     * <p>⚠️ token 只在<b>真建</b>时才换（dry-run 不碰）—— 见 {@link #create}。</p>
     */
    public SuixintuiOrderCreateService(SuixintuiReinvestProperties properties) {
        this(defaultFetcherProvider(properties));
    }

    /**
     * 测试用：直接注入 fetcher 来源（<b>绝不真建单</b>）。
     */
    public SuixintuiOrderCreateService(FetcherProvider fetcherProvider) {
        this.fetcherProvider = fetcherProvider;
    }

    /**
     * 默认的 fetcher 来源：换取 token → 建 API 客户端 → 每次建单 new 一个 API 实例。
     *
     * <p>写法参照 {@code SuixintuiOrderService}/{@code SuixintuiOrderDetailService}：
     * base path 与 Access-Token header 都在这里设好。</p>
     */
    private static FetcherProvider defaultFetcherProvider(SuixintuiReinvestProperties properties) {
        String baseUrl = properties == null ? null : properties.getApiBaseUrl();
        return () -> {
            String token = SuixintuiTokenClient.getAccessToken();
            ApiClient apiClient = new ApiClient();
            apiClient.setBasePath(baseUrl == null || baseUrl.isBlank()
                    ? "https://api.oceanengine.com" : baseUrl);
            apiClient.addDefaultHeader("Access-Token", token);
            return request -> {
                QianchuanAwemeUniPromotionOrderCreateV10Api api =
                        new QianchuanAwemeUniPromotionOrderCreateV10Api();
                api.setApiClient(apiClient);
                return api.openApiV10QianchuanAwemeUniPromotionOrderCreatePost(request);
            };
        };
    }

    /**
     * 建随心推订单。
     *
     * @param param   建单参数
     * @param dryRun  {@code true} = 只组装请求体、<b>不真调</b>
     * @return 结果；dry-run 时 {@code orderId} 为空
     * @throws ApiException             接口/网络异常
     * @throws RateLimitException       被频控（调用方<b>不要重试</b>，重试只会加重惩罚）
     * @throws SuixintuiParamException  参数不满足官方硬约束（{@code code=2}）
     */
    public SuixintuiCreateOutcome create(SuixintuiOrderParam param, boolean dryRun)
            throws ApiException {
        // ⚠️ 先校验官方硬约束：不合法的参数连 dry-run 都不放行。
        //    否则演练会打印出一个「看起来没问题、真跑必被拒」的请求体 —— 演练就成了假绿灯。
        String problem = validate(param);
        if (problem != null) {
            throw new SuixintuiParamException(problem);
        }

        QianchuanAwemeUniPromotionOrderCreateV10Request request = buildRequest(param);
        // ⚠️ 这份 body 就是「即将真实发出的那一份」，dry-run 与真调共用，不重复组装
        String requestBody = request.toJson();

        if (dryRun) {
            log.warn("【dry-run】不会真的建随心推（{}）。即将发出的请求体：{}",
                    CREATE_ENDPOINT, requestBody);
            return new SuixintuiCreateOutcome(true, null, requestBody, List.of());
        }

        log.info("建随心推：账户={} 达人UID={} 商品={} 请求体={}",
                param.advertiserId(), param.awemeId(), param.productId(), requestBody);

        // ⚠️ 只在「真要建」时才去换 token —— dry-run 不该因为 token 没配好而失败，
        //    否则「先演练一遍看看请求体」这个最重要的用法会被 token 卡住。
        OrderCreateFetcher fetcher;
        try {
            fetcher = fetcherProvider.get();
        } catch (Exception e) {
            throw new ApiException("换取随心推 Access-Token 失败，无法建单：" + e.getMessage());
        }

        QianchuanAwemeUniPromotionOrderCreateV10Response response = fetcher.create(request);
        checkResponse(response, param);

        Long orderId = response.getData().getOrderId();
        log.info("随心推已建成：orderId={}（账户={} 达人UID={} 商品={}）",
                orderId, param.advertiserId(), param.awemeId(), param.productId());
        return new SuixintuiCreateOutcome(false, orderId, requestBody, List.of());
    }

    // ==================== 参数校验 ====================

    /**
     * 校验官方硬约束，合法返回 {@code null}。
     *
     * <p>规则本体在 {@link SuixintuiConstraints}（只写一次），这里只负责<b>把结果转成异常</b>。</p>
     */
    static String validate(SuixintuiOrderParam param) {
        if (param == null) {
            return "建随心推参数不能为空";
        }
        String amountProblem = SuixintuiConstraints.amountProblem(param.amount());
        if (amountProblem != null) {
            return amountProblem;
        }
        String deliveryTimeProblem = SuixintuiConstraints.deliveryTimeProblem(param.deliveryTime());
        if (deliveryTimeProblem != null) {
            return deliveryTimeProblem;
        }
        // ⚠️ 含【条件必填】：MANUAL_BID 时必须给 roi_goal（本服务 marketing_goal 恒为 VIDEO_PROM_GOODS）
        return SuixintuiConstraints.roiGoalProblem(param.roiGoal(), param.bidType());
    }

    // ==================== 组装请求体 ====================

    private static QianchuanAwemeUniPromotionOrderCreateV10Request buildRequest(
            SuixintuiOrderParam param) {
        QianchuanAwemeUniPromotionOrderCreateV10RequestDeliverySetting setting =
                new QianchuanAwemeUniPromotionOrderCreateV10RequestDeliverySetting();
        setting.setAmount(param.amount());                 // 必填
        setting.setDeliveryTime(param.deliveryTime());     // 必填
        if (notBlank(param.bidType())) {                   // 可选
            setting.setBidType(
                    QianchuanAwemeUniPromotionOrderCreateV10DeliverySettingBidType
                            .fromValue(param.bidType()));
        }
        if (notBlank(param.qcpxMode())) {                  // 可选
            setting.setQcpxMode(
                    QianchuanAwemeUniPromotionOrderCreateV10DeliverySettingQcpxMode
                            .fromValue(param.qcpxMode()));
        }
        if (param.roiGoal() != null) {                     // 可选
            setting.setRoiGoal(param.roiGoal());
        }

        QianchuanAwemeUniPromotionOrderCreateV10Request request =
                new QianchuanAwemeUniPromotionOrderCreateV10Request();
        request.setAdvertiserId(param.advertiserId());     // 必填
        request.setAwemeId(param.awemeId());               // 必填（数值 UID）
        request.setProductId(param.productId());           // 必填
        // ⚠️ marketing_goal 写死 VIDEO_PROM_GOODS —— 它正是「roi_goal 条件必填」规则的一半条件
        //    （另一半是 bid_type=MANUAL_BID，见 SuixintuiConstraints#isRoiGoalRequired）。
        //    若将来要支持直播等场景，这里和那条规则必须一起改。
        request.setMarketingGoal(QianchuanAwemeUniPromotionOrderCreateV10MarketingGoal
                .VIDEO_PROM_GOODS);
        request.setDeliverySetting(setting);               // 必填
        return request;
    }

    /**
     * ⚠️ 把 SDK 的「业务错误」显式抛出来。
     *
     * <p>SDK 在 {@code code != 0} 时<b>不抛异常</b>，只返回 {@code data == null} ——
     * 那正是本项目历史上「假阴性」的根源（报错被静默翻译成「没有数据」）。
     * 建单尤其不能静默：静默会让上层以为「建成了」。</p>
     */
    private static void checkResponse(QianchuanAwemeUniPromotionOrderCreateV10Response response,
                                      SuixintuiOrderParam param) throws ApiException {
        if (response == null) {
            throw new ApiException("建随心推返回 null（账户 " + param.advertiserId() + "）");
        }
        Long code = response.getCode();
        if (code != null && code != 0L) {
            RateLimitException rateLimit = ApiErrors.rateLimitOrNull(
                    code, "建随心推(账户 " + param.advertiserId() + ")", response.getRequestId());
            if (rateLimit != null) {
                throw rateLimit;
            }
            throw new ApiException(code.intValue(), "建随心推业务错误（账户 "
                    + param.advertiserId() + "）：code=" + code + " message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new ApiException("建随心推 code=0 但 data 为空（账户 " + param.advertiserId() + "）");
        }
        if (response.getData().getOrderId() == null) {
            throw new ApiException("建随心推返回成功但 orderId 为空（账户 "
                    + param.advertiserId() + "）—— 无法确认是否真的建成，请人工核对");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
