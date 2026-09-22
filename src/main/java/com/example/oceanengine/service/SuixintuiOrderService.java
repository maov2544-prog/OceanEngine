package com.example.oceanengine.service;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.Oauth2AdvertiserGetApi;
import com.bytedance.ads.api.QianchuanAwemeOrderGetV10Api;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderGetV10Api;
import com.bytedance.ads.api.QianchuanShopAdvertiserListV10Api;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponse;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseData;
import com.bytedance.ads.model.Oauth2AdvertiserGetResponseDataListInner;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10Count;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10DataPageInfoHasMore;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10Filtering;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10FilteringMarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10ResponseDataListInner;
import com.bytedance.ads.model.QianchuanAwemeOrderGetV10ResponseDataPageInfo;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 千川随心推订单拉取服务（统一入口）。
 *
 * <p>实测结论（2026-09-22）：当前随心推 Token 下，
 * 「小店随心推订单列表」接口近 179 天无订单，
 * 真实订单挂在「随心推全域订单列表」接口（VIDEO_PROM_GOODS 商品全域）下。</p>
 *
 * <p>因此本服务同时拉取两类订单并统一去重：</p>
 * <ol>
 *   <li>小店随心推：/open_api/v1.0/qianchuan/aweme/order/get/
 *       —— 按营销目标（必填）分别查、180 天窗口切分、cursor 翻页；</li>
 *   <li>随心推全域：/open_api/v1.0/qianchuan/aweme/uni_promotion/order/get/
 *       —— 目前仅支持 VIDEO_PROM_GOODS（直播全域会报错），cursor 翻页。</li>
 * </ol>
 */
public class SuixintuiOrderService {

    /** 小店随心推接口限制：单次查询日期跨度不能超过 180 天 */
    public static final int MAX_DATE_SPAN_DAYS = 180;

    /** 小店随心推接口实际允许的最早查询：今天 - 179 天（180 天整会报「开始时间不能早于180天」） */
    public static final int MAX_LOOKBACK_DAYS = MAX_DATE_SPAN_DAYS - 1;

    /** 单窗口内防止异常导致死循环的最大翻页数 */
    private static final int MAX_PAGES_PER_WINDOW = 2000;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

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

    /** 拉取结果：小店随心推 + 随心推全域 两类订单 */
    public static class FetchResult {
        /** 小店随心推订单（key: advertiserId） */
        public final Map<Long, List<QianchuanAwemeOrderGetV10ResponseDataListInner>> classicByAccount =
                new LinkedHashMap<>();
        /** 随心推全域订单（key: advertiserId） */
        public final Map<Long, List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>> uniByAccount =
                new LinkedHashMap<>();
        /** 查询失败的账户（key: advertiserId -> 原因） */
        public final Map<Long, String> failedAccounts = new LinkedHashMap<>();

        public long classicTotal() {
            long n = 0;
            for (List<?> l : classicByAccount.values()) n += l.size();
            return n;
        }

        public long uniTotal() {
            long n = 0;
            for (List<?> l : uniByAccount.values()) n += l.size();
            return n;
        }

        public long total() {
            return classicTotal() + uniTotal();
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
     * 拉取指定千川业务账户列表在 [startDate, endDate] 范围内的全部随心推订单。
     *
     * @param advertiserIds 千川业务账户 ID 列表（空则自动发现全部账户）
     * @param startDate     查询起始日期（小店随心推接口受 180 天限制，会内部钳制）
     * @param endDate       查询结束日期
     */
    public FetchResult fetchAllOrders(List<Long> advertiserIds,
                                      LocalDate startDate, LocalDate endDate) throws ApiException {
        List<Long> ids = advertiserIds == null || advertiserIds.isEmpty()
                ? new ArrayList<>(discoverBusinessAdvertisers().keySet())
                : advertiserIds;

        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "起始日期不能晚于结束日期: " + startDate + " > " + endDate);
        }

        FetchResult result = new FetchResult();

        for (Long advertiserId : ids) {
            System.out.println();
            System.out.println("==== 账户 " + advertiserId + " ====");

            // 1) 小店随心推（独立执行，业务性错误不阻断账户）
            try {
                List<QianchuanAwemeOrderGetV10ResponseDataListInner> classic = withRetry(
                        () -> fetchClassicOrders(advertiserId, startDate, endDate),
                        "账户 " + advertiserId + " 小店随心推");
                result.classicByAccount.put(advertiserId, classic);
                System.out.println("  小店随心推订单: " + classic.size() + " 条");
            } catch (ApiException e) {
                if (isBusinessError(e)) {
                    result.classicByAccount.put(advertiserId, new ArrayList<>());
                    System.out.println("  [SKIP] 小店随心推接口不适用: " + e.getMessage());
                } else {
                    result.failedAccounts.put(advertiserId, "小店随心推: " + e.getMessage());
                    System.out.println("  [WARN] 小店随心推拉取失败: " + e.getMessage());
                }
            }

            // 2) 随心推全域（仅商品全域 VIDEO_PROM_GOODS）
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

    // ==================== 小店随心推（经典接口） ====================

    public List<QianchuanAwemeOrderGetV10ResponseDataListInner> fetchClassicOrders(
            Long advertiserId, LocalDate startDate, LocalDate endDate) throws ApiException {

        if (startDate.isBefore(endDate.minusDays(MAX_LOOKBACK_DAYS))) {
            startDate = endDate.minusDays(MAX_LOOKBACK_DAYS);
        }

        List<QianchuanAwemeOrderGetV10ResponseDataListInner> all = new ArrayList<>();
        for (QianchuanAwemeOrderGetV10FilteringMarketingGoal goal :
                new QianchuanAwemeOrderGetV10FilteringMarketingGoal[]{
                        QianchuanAwemeOrderGetV10FilteringMarketingGoal.VIDEO_PROM_GOODS,
                        QianchuanAwemeOrderGetV10FilteringMarketingGoal.LIVE_PROM_GOODS}) {
            for (LocalDate[] window : splitWindows(startDate, endDate)) {
                all.addAll(fetchClassicWindow(advertiserId, goal, window[0], window[1]));
            }
        }
        return dedupeByOrderId(all);
    }

    private List<QianchuanAwemeOrderGetV10ResponseDataListInner> fetchClassicWindow(
            Long advertiserId,
            QianchuanAwemeOrderGetV10FilteringMarketingGoal goal,
            LocalDate start, LocalDate end) throws ApiException {

        QianchuanAwemeOrderGetV10Api api = new QianchuanAwemeOrderGetV10Api();
        api.setApiClient(apiClient);

        QianchuanAwemeOrderGetV10Filtering filtering = new QianchuanAwemeOrderGetV10Filtering();
        filtering.setMarketingGoal(goal);

        List<QianchuanAwemeOrderGetV10ResponseDataListInner> collected = new ArrayList<>();
        Long cursor = null;

        for (int page = 1; page <= MAX_PAGES_PER_WINDOW; page++) {
            QianchuanAwemeOrderGetV10Response response =
                    api.openApiV10QianchuanAwemeOrderGetGet(
                            advertiserId,
                            filtering,
                            cursor,
                            QianchuanAwemeOrderGetV10Count.NUMBER_50,
                            QianchuanAwemeOrderGetV10OrderField.ORDER_CREATE_TIME,
                            start.format(DATE_FMT),
                            end.format(DATE_FMT));

            Long code = response.getCode();
            if (code == null || code != 0L) {
                throw new ApiException("小店随心推订单列表 code=" + code
                        + " message=" + response.getMessage()
                        + " request_id=" + response.getRequestId());
            }
            QianchuanAwemeOrderGetV10ResponseData data = response.getData();
            if (data == null) {
                throw new ApiException("小店随心推订单列表 data 为空, request_id=" + response.getRequestId());
            }
            if (data.getList() != null) {
                collected.addAll(data.getList());
            }
            List<Long> failList = data.getFailList();
            if (failList != null && !failList.isEmpty()) {
                System.out.println("    [WARN] 小店随心推该页存在获取失败订单: " + failList);
            }

            QianchuanAwemeOrderGetV10ResponseDataPageInfo pageInfo = data.getPageInfo();
            boolean hasMore = pageInfo != null
                    && pageInfo.getHasMore() == QianchuanAwemeOrderGetV10DataPageInfoHasMore.NUMBER_1;
            if (!hasMore) {
                break;
            }
            cursor = pageInfo.getCursor();
            if (cursor == null) {
                throw new ApiException("小店随心推返回 has_more=1 但缺少 cursor");
            }
            sleep();
        }
        return collected;
    }

    // ==================== 随心推全域 ====================

    public List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> fetchUniPromotionOrders(
            Long advertiserId) throws ApiException {

        QianchuanAwemeUniPromotionOrderGetV10Api api =
                new QianchuanAwemeUniPromotionOrderGetV10Api();
        api.setApiClient(apiClient);

        // 全域订单目前仅支持商品全域（VIDEO_PROM_GOODS），直播全域会返回
        // code=40000「仅支持创建商品全域投放订单」
        QianchuanAwemeUniPromotionOrderGetV10MarketingGoal goal =
                QianchuanAwemeUniPromotionOrderGetV10MarketingGoal.VIDEO_PROM_GOODS;

        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> collected =
                new ArrayList<>();
        Long cursor = null;

        for (int page = 1; page <= MAX_PAGES_PER_WINDOW; page++) {
            QianchuanAwemeUniPromotionOrderGetV10Response response =
                    api.openApiV10QianchuanAwemeUniPromotionOrderGetGet(
                            advertiserId,
                            goal,
                            new QianchuanAwemeUniPromotionOrderGetV10Filtering(), // status 过滤
                            QianchuanAwemeUniPromotionOrderGetV10OrderField.ORDER_CREATE_TIME,
                            cursor,
                            QianchuanAwemeUniPromotionOrderGetV10Count.NUMBER_50);

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

    /** 按 180 天跨度把 [start, end] 切分为若干时间窗口（含边界） */
    public static List<LocalDate[]> splitWindows(LocalDate start, LocalDate end) {
        List<LocalDate[]> windows = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate windowEnd = cursor.plusDays(MAX_DATE_SPAN_DAYS - 1);
            if (windowEnd.isAfter(end)) {
                windowEnd = end;
            }
            windows.add(new LocalDate[]{cursor, windowEnd});
            cursor = windowEnd.plusDays(1);
        }
        return windows;
    }

    private static List<QianchuanAwemeOrderGetV10ResponseDataListInner> dedupeByOrderId(
            List<QianchuanAwemeOrderGetV10ResponseDataListInner> list) {
        Map<Long, QianchuanAwemeOrderGetV10ResponseDataListInner> map = new LinkedHashMap<>();
        for (var o : list) {
            map.putIfAbsent(o.getOrderId(), o);
        }
        return new ArrayList<>(map.values());
    }

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
