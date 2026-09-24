package com.example.oceanengine.service.suixintui;

import com.example.oceanengine.model.SuixintuiUniOrderListRequest;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.Oauth2AdvertiserGetApi;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderGetV10Api;
import com.bytedance.ads.api.QianchuanShopAdvertiserListV10Api;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponse;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseData;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseDataListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Count;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataPageInfoHasMore;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Filtering;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10MarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataPageInfo;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10Response;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10ResponseData;
import com.bytedance.ads.model.QianchuanShopAdvertiserListV10ResponseDataAdvIdListInner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 千川随心推订单拉取服务（统一入口，仅「随心推全域-商品订单」）。
 *
 * <p>只拉取「随心推全域订单列表」接口
 * /open_api/v1.0/qianchuan/aweme/uni_promotion/order/get/，
 * 营销目标固定 VIDEO_PROM_GOODS（商品全域），cursor 翻页拉全。</p>
 *
 * <p>已移除：小店随心推订单列表（/open_api/v1.0/qianchuan/aweme/order/get/）与直播全域拉取。</p>
 */
public class SuixintuiOrderService {

    /** 单账户内防止异常导致死循环的最大翻页数 */
    private static final int MAX_PAGES = 2000;

    private final ApiClient apiClient;
    private final String accessToken;
    private final long pageIntervalMillis;

    public SuixintuiOrderService(ApiClient apiClient, String accessToken) {
        this(apiClient, accessToken, 100L);
    }

    /**
     * @param apiClient          已注入 Access-Token 的 SDK 客户端
     * @param accessToken        随心推 Access Token（用于账户发现接口）
     * @param pageIntervalMillis 相邻分页请求的间隔毫秒数（规避频率限制，0 表示不等待）
     */
    public SuixintuiOrderService(ApiClient apiClient, String accessToken, long pageIntervalMillis) {
        this.apiClient = apiClient;
        this.accessToken = accessToken;
        this.pageIntervalMillis = pageIntervalMillis;
    }

    // ==================== 拉取结果 ====================

    /** 拉取结果：随心推全域（商品）订单 */
    public static class FetchResult {
        /** 随心推全域订单（key: advertiserId） */
        public final Map<Long, List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>> uniByAccount =
                new LinkedHashMap<>();
        /** 查询失败的账户（key: advertiserId -> 原因） */
        public final Map<Long, String> failedAccounts = new LinkedHashMap<>();

        public long uniTotal() {
            long n = 0;
            for (List<?> l : uniByAccount.values()) n += l.size();
            return n;
        }

        public long total() {
            return uniTotal();
        }
    }

    // ==================== 账户发现 ====================

    /**
     * 自动发现当前 Token 下所有可操作的千川业务账户：
     * 已授权账户（OAuth）中的客户账户 + 各抖店店铺账户下管理的千川业务账户。
     */
    public Map<Long, String> discoverBusinessAdvertisers() throws ApiException {
        Map<Long, String> result = new LinkedHashMap<>();

        Oauth2AdvertiserGetApi oauthApi = new Oauth2AdvertiserGetApi();
        oauthApi.setApiClient(apiClient);

        Oauth2AdvertiserGetResponse oauthResp = oauthApi.openApiOauth2AdvertiserGetGet(accessToken);
        List<Oauth2AdvertiserGetResponseDataListInner> accounts =
                oauthResp.getData() == null ? null : oauthResp.getData().getList();
        if (accounts == null || accounts.isEmpty()) {
            return result;
        }

        QianchuanShopAdvertiserListV10Api shopApi = new QianchuanShopAdvertiserListV10Api();
        shopApi.setApiClient(apiClient);

        for (Oauth2AdvertiserGetResponseDataListInner account : accounts) {
            String role = String.valueOf(account.getAccountRole());
            if ("PLATFORM_ROLE_SHOP_ACCOUNT".equals(role)) {
                // 抖店店铺账户 -> 查询店铺下管理的千川业务账户
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
                        result.putIfAbsent(adv.getAdvId(), adv.getAdvName());
                    }
                } catch (Exception e) {
                    System.out.println("  [WARN] 店铺账户 " + account.getAdvertiserId()
                            + " 查询业务账户失败: " + e.getMessage());
                }
            } else {
                result.putIfAbsent(account.getAdvertiserId(), account.getAdvertiserName());
            }
        }
        return result;
    }

    // ==================== 主入口 ====================

    /**
     * 拉取指定千川业务账户列表下全部「随心推全域-商品订单」。
     *
     * @param advertiserIds 千川业务账户 ID 列表（空则自动发现全部账户）
     */
    public FetchResult fetchAllOrders(List<Long> advertiserIds) throws ApiException {
        List<Long> ids = advertiserIds == null || advertiserIds.isEmpty()
                ? new ArrayList<>(discoverBusinessAdvertisers().keySet())
                : advertiserIds;

        FetchResult result = new FetchResult();

        for (Long advertiserId : ids) {
            System.out.println();
            System.out.println("==== 账户 " + advertiserId + " ====");

            // 随心推全域（仅商品全域 VIDEO_PROM_GOODS）
            try {
                List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> uni =
                        withRetry(() -> fetchUniPromotionOrders(advertiserId),
                                "账户 " + advertiserId + " 随心推全域");
                result.uniByAccount.put(advertiserId, uni);
                System.out.println("  随心推全域订单: " + uni.size() + " 条");
            } catch (ApiException e) {
                if (isBusinessError(e)) {
                    result.uniByAccount.put(advertiserId, new ArrayList<>());
                    System.out.println("  [SKIP] 随心推全域接口不适用: " + e.getMessage());
                } else {
                    result.failedAccounts.merge(advertiserId,
                            "随心推全域: " + e.getMessage(),
                            (a, b) -> a + " | " + b);
                    System.out.println("  [WARN] 随心推全域拉取失败: " + e.getMessage());
                }
            }
        }
        return result;
    }

    // ==================== 重试与错误分类 ====================

    /** 业务性错误（账户类型不适用/接口不支持），重试无意义 */
    private static boolean isBusinessError(ApiException e) {
        String m = e.getMessage();
        return m != null && (m.contains("code=40002") || m.contains("code=40000")
                || m.contains("doesn't exist or the role is wrong")
                || m.contains("仅支持创建商品全域"));
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws ApiException;
    }

    /** 瞬时错误（网络/5xx/限流）最多重试 3 次，退避 1s/2s；业务错误直接抛出 */
    private static <T> T withRetry(ThrowingSupplier<T> action, String label)
            throws ApiException {
        int maxAttempts = 3;
        ApiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (ApiException e) {
                if (isBusinessError(e)) {
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

    // ==================== 随心推全域 ====================

    /**
     * 拉取指定账户的全部随心推全域订单（默认请求参数：商品全域、按创建时间排序、每页 50）。
     */
    public List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> fetchUniPromotionOrders(
            Long advertiserId) throws ApiException {
        return fetchUniPromotionOrders(new SuixintuiUniOrderListRequest(advertiserId));
    }

    /**
     * 按请求 DTO 拉取随心推全域订单列表（cursor 分页拉全，按 order_id 去重）。
     *
     * <p>DTO 未设置的字段使用默认值：营销目标 VIDEO_PROM_GOODS（商品全域）、
     * 排序按订单创建时间、每页 50 条。</p>
     *
     * @param request 查询请求 DTO（advertiserId 必填）
     */
    public List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> fetchUniPromotionOrders(
            SuixintuiUniOrderListRequest request) throws ApiException {

        if (request == null || request.getAdvertiserId() == null) {
            throw new ApiException("随心推全域订单列表请求缺少 advertiser_id");
        }
        Long advertiserId = request.getAdvertiserId();

        // 全域订单接口仅支持商品全域（VIDEO_PROM_GOODS），直播全域会返回
        // code=40000「仅支持创建商品全域投放订单」
        QianchuanAwemeUniPromotionOrderGetV10MarketingGoal goal = request.getMarketingGoal();
        if (goal == null) {
            goal = QianchuanAwemeUniPromotionOrderGetV10MarketingGoal.VIDEO_PROM_GOODS;
        }

        QianchuanAwemeUniPromotionOrderGetV10Filtering filtering = request.toFiltering();

        QianchuanAwemeUniPromotionOrderGetV10OrderField orderField = request.getOrderField();
        if (orderField == null) {
            orderField = QianchuanAwemeUniPromotionOrderGetV10OrderField.ORDER_CREATE_TIME;
        }

        QianchuanAwemeUniPromotionOrderGetV10Count count = request.getCount();
        if (count == null) {
            count = QianchuanAwemeUniPromotionOrderGetV10Count.NUMBER_50;
        }

        QianchuanAwemeUniPromotionOrderGetV10Api api =
                new QianchuanAwemeUniPromotionOrderGetV10Api();
        api.setApiClient(apiClient);

        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> collected =
                new ArrayList<>();
        Long cursor = request.getCursor();

        for (int page = 1; page <= MAX_PAGES; page++) {
            QianchuanAwemeUniPromotionOrderGetV10Response response =
                    api.openApiV10QianchuanAwemeUniPromotionOrderGetGet(
                            advertiserId,
                            goal,
                            filtering,
                            orderField,
                            cursor,
                            count);

            Long code = response.getCode();
            if (code == null || code != 0L) {
                throw new ApiException("随心推全域订单列表 code=" + code
                        + " message=" + response.getMessage()
                        + " request_id=" + response.getRequestId());
            }
            QianchuanAwemeUniPromotionOrderGetV10ResponseData data = response.getData();
            if (data == null) {
                throw new ApiException("随心推全域订单列表 data 为空, request_id=" + response.getRequestId());
            }
            if (data.getOrderList() != null) {
                collected.addAll(data.getOrderList());
            }

            QianchuanAwemeUniPromotionOrderGetV10ResponseDataPageInfo pageInfo = data.getPageInfo();
            boolean hasMore = pageInfo != null
                    && pageInfo.getHasMore()
                    == QianchuanAwemeUniPromotionOrderGetV10DataPageInfoHasMore.NUMBER_1;
            if (!hasMore) {
                break;
            }
            cursor = pageInfo.getCursor();
            if (cursor == null) {
                throw new ApiException("随心推全域返回 has_more=1 但缺少 cursor");
            }
            sleep();
        }
        return dedupeUniByOrderId(collected);
    }

    // ==================== 工具 ====================

    private static List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>
    dedupeUniByOrderId(
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> list) {
        Map<Long, QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> map =
                new LinkedHashMap<>();
        for (var o : list) {
            map.putIfAbsent(o.getOrderId(), o);
        }
        return new ArrayList<>(map.values());
    }

    private void sleep() {
        if (pageIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(pageIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
