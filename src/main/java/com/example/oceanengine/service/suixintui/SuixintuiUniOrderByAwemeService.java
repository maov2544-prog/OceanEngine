package com.example.oceanengine.service.suixintui;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanAwemeAuthorizedGetV10Api;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeAuthorizedGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeAuthorizedGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeAuthorizedGetV10ResponseDataAwemeIdListInner;
import com.bytedance.ads.model.QianchuanAwemeAuthorizedGetV10ResponseDataPageInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Count;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataPageInfoHasMore;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Filtering;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10MarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataPageInfo;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通过抖音ID（aweme_id）获取该抖音号名下所有「随心推商品全域订单」。
 *
 * <p>整体链路（对应巨量引擎开放平台）：</p>
 * <ol>
 *   <li>账户发现：复用 {@link SuixintuiOrderService#discoverBusinessAdvertisers()}，
 *       拿到当前 Token 下所有千川业务账户（advertiser_id）；</li>
 *   <li>抖音号匹配：调用「获取千川账户下抖音号授权列表」
 *       /open_api/v1.0/qianchuan/aweme/authorized/get/，
 *       只保留授权列表里包含目标抖音ID的账户；</li>
 *   <li>订单拉取：调用「获取随心推全域订单列表」
 *       /open_api/v1.0/qianchuan/aweme/uni_promotion/order/get/，
 *       营销目标固定 VIDEO_PROM_GOODS（商品全域），cursor 分页拉全；</li>
 *   <li>抖音号过滤：订单列表接口的过滤条件不支持按抖音号筛选（仅有时间/状态），
 *       因此对订单返回的 aweme_info.aweme_id 做客户端匹配。</li>
 * </ol>
 *
 * <p>注意：</p>
 * <ul>
 *   <li>这里的「抖音ID」指千川体系中的 aweme_id（抖音号 UID，数字），
 *       不是手机端展示的 aweme_show_id（如 doudian_xxx）。</li>
 *   <li>全域订单列表接口当前仅支持商品全域（VIDEO_PROM_GOODS），直播全域会报错。</li>
 *   <li>单凭抖音ID无法直接查订单：订单归属在千川投放账户（advertiser_id）下，
 *       必须先定位到有该抖音号授权的账户，再按账户拉取。</li>
 * </ul>
 */
public class SuixintuiUniOrderByAwemeService {

    /** 单账户内防止异常导致死循环的最大翻页数 */
    private static final int MAX_PAGES = 2000;

    /** 获取授权抖音号接口的单页大小 */
    private static final int AUTHORIZED_PAGE_SIZE = 100;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ApiClient apiClient;
    private final String accessToken;
    private final long pageIntervalMillis;

    public SuixintuiUniOrderByAwemeService(ApiClient apiClient, String accessToken) {
        this(apiClient, accessToken, 100L);
    }

    /**
     * @param apiClient          已注入 Access-Token 的 SDK 客户端
     * @param accessToken        随心推 Access Token（用于账户发现接口）
     * @param pageIntervalMillis 相邻分页请求的间隔毫秒数（规避频率限制，0 表示不等待）
     */
    public SuixintuiUniOrderByAwemeService(ApiClient apiClient, String accessToken,
                                           long pageIntervalMillis) {
        this.apiClient = apiClient;
        this.accessToken = accessToken;
        this.pageIntervalMillis = pageIntervalMillis;
    }

    // ==================== 拉取结果 ====================

    /** 按抖音ID拉取商品全域订单的结果 */
    public static class AwemeOrderFetchResult {
        /** 命中账户的订单（key: advertiserId -> 该抖音号的商品全域订单） */
        public final Map<Long, List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>>
                ordersByAccount = new LinkedHashMap<>();
        /** 命中账户及其抖音号描述（key: advertiserId -> aweme_show_id/aweme_name） */
        public final Map<Long, String> matchedAwemesByAccount = new LinkedHashMap<>();
        /** 被跳过的账户（key: advertiserId -> 原因：无该抖音号授权 / 接口不适用） */
        public final Map<Long, String> skippedAccounts = new LinkedHashMap<>();
        /** 查询失败的账户（key: advertiserId -> 原因） */
        public final Map<Long, String> failedAccounts = new LinkedHashMap<>();

        public long total() {
            long n = 0;
            for (List<?> l : ordersByAccount.values()) n += l.size();
            return n;
        }

        public long matchedAccountCount() {
            return ordersByAccount.size();
        }
    }

    // ==================== 账户发现 ====================

    /**
     * 自动发现当前 Token 下所有可操作的千川业务账户。
     * 复用 {@link SuixintuiOrderService} 的账户发现逻辑（已授权账户 + 抖店店铺账户下的千川业务账户）。
     */
    public Map<Long, String> discoverBusinessAdvertisers() throws ApiException {
        return new SuixintuiOrderService(apiClient, accessToken, pageIntervalMillis)
                .discoverBusinessAdvertisers();
    }

    // ==================== 抖音号授权匹配 ====================

    /**
     * 获取指定千川账户下已授权的抖音号列表（分页拉全）。
     */
    public List<QianchuanAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> fetchAuthorizedAwemes(
            Long advertiserId) throws ApiException {

        QianchuanAwemeAuthorizedGetV10Api api = new QianchuanAwemeAuthorizedGetV10Api();
        api.setApiClient(apiClient);

        List<QianchuanAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> all = new ArrayList<>();
        long totalPage = 1;

        for (int page = 1; page <= totalPage && page <= MAX_PAGES; page++) {
            QianchuanAwemeAuthorizedGetV10Response response =
                    api.openApiV10QianchuanAwemeAuthorizedGetGet(
                            advertiserId, null, page, AUTHORIZED_PAGE_SIZE);

            Long code = response.getCode();
            if (code == null || code != 0L) {
                throw new ApiException("获取千川账户下抖音号授权列表 code=" + code
                        + " message=" + response.getMessage()
                        + " request_id=" + response.getRequestId());
            }
            QianchuanAwemeAuthorizedGetV10ResponseData data = response.getData();
            if (data == null) {
                throw new ApiException("获取千川账户下抖音号授权列表 data 为空, request_id=" + response.getRequestId());
            }
            if (data.getAwemeIdList() != null) {
                all.addAll(data.getAwemeIdList());
            }
            QianchuanAwemeAuthorizedGetV10ResponseDataPageInfo pageInfo = data.getPageInfo();
            long tp = pageInfo == null || pageInfo.getTotalPage() == null
                    ? totalPage : pageInfo.getTotalPage();
            if (tp > totalPage) {
                totalPage = tp;
            }
            sleep();
        }
        return all;
    }

    /**
     * 在给定账户列表中定位包含目标抖音ID（aweme_id）的账户。
     *
     * @return advertiserId -> 匹配到的抖音号信息（aweme_show_id/aweme_name）
     */
    public Map<Long, QianchuanAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> findAccountsWithAweme(
            Long awemeId, List<Long> advertiserIds) throws ApiException {

        Map<Long, QianchuanAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> matched =
                new LinkedHashMap<>();

        for (Long advertiserId : advertiserIds) {
            List<QianchuanAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> awemes;
            try {
                awemes = fetchAuthorizedAwemes(advertiserId);
            } catch (ApiException e) {
                if (isBusinessError(e)) {
                    // 账户类型不支持随心推等业务性错误，跳过
                    continue;
                }
                throw e;
            }
            for (QianchuanAwemeAuthorizedGetV10ResponseDataAwemeIdListInner aweme : awemes) {
                if (awemeId.equals(aweme.getAwemeId())) {
                    matched.putIfAbsent(advertiserId, aweme);
                }
            }
        }
        return matched;
    }

    // ==================== 商品全域订单拉取 ====================

    /**
     * 拉取指定账户的「随心推商品全域订单列表」（VIDEO_PROM_GOODS），
     * 并在客户端按抖音ID（订单的 aweme_info.aweme_id）过滤。
     *
     * @param advertiserId 千川投放账户 ID
     * @param awemeId      目标抖音ID（aweme_id）
     * @param startDate    订单创建起始日期（可空，不传则不过滤）
     * @param endDate      订单创建结束日期（可空，不传则不过滤）
     */
    public List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> fetchUniOrdersByAweme(
            Long advertiserId, Long awemeId, LocalDate startDate, LocalDate endDate)
            throws ApiException {

        QianchuanAwemeUniPromotionOrderGetV10Api api =
                new QianchuanAwemeUniPromotionOrderGetV10Api();
        api.setApiClient(apiClient);

        QianchuanAwemeUniPromotionOrderGetV10Filtering filtering =
                new QianchuanAwemeUniPromotionOrderGetV10Filtering();
        if (startDate != null) {
            filtering.setOrderCreateStartDate(startDate.format(DATE_FMT));
        }
        if (endDate != null) {
            filtering.setOrderCreateEndDate(endDate.format(DATE_FMT));
        }

        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> collected =
                new ArrayList<>();
        Long cursor = null;

        for (int page = 1; page <= MAX_PAGES; page++) {
            QianchuanAwemeUniPromotionOrderGetV10Response response =
                    api.openApiV10QianchuanAwemeUniPromotionOrderGetGet(
                            advertiserId,
                            QianchuanAwemeUniPromotionOrderGetV10MarketingGoal.VIDEO_PROM_GOODS,
                            filtering,
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
                for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner order
                        : data.getOrderList()) {
                    // 订单列表接口过滤条件不支持按抖音号筛选，这里做客户端匹配
                    if (order.getAwemeInfo() != null
                            && awemeId.equals(order.getAwemeInfo().getAwemeId())) {
                        collected.add(order);
                    }
                }
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
        return dedupeByOrderId(collected);
    }

    // ==================== 主入口 ====================

    /**
     * 通过抖音ID获取该抖音号名下所有「随心推商品全域订单」。
     *
     * @param awemeId       目标抖音ID（aweme_id）
     * @param advertiserIds 千川业务账户 ID 列表（空则自动发现全部账户）
     * @param startDate     订单创建起始日期（可空）
     * @param endDate       订单创建结束日期（可空）
     */
    public AwemeOrderFetchResult fetchAllByAweme(Long awemeId, List<Long> advertiserIds,
                                                 LocalDate startDate, LocalDate endDate)
            throws ApiException {
        List<Long> ids = advertiserIds == null || advertiserIds.isEmpty()
                ? new ArrayList<>(discoverBusinessAdvertisers().keySet())
                : advertiserIds;

        AwemeOrderFetchResult result = new AwemeOrderFetchResult();

        for (Long advertiserId : ids) {
            System.out.println();
            System.out.println("==== 账户 " + advertiserId + " ====");

            // 1) 该账户下是否有目标抖音号的授权
            boolean hasAweme = false;
            String awemeDesc = "";
            try {
                for (QianchuanAwemeAuthorizedGetV10ResponseDataAwemeIdListInner aweme
                        : fetchAuthorizedAwemes(advertiserId)) {
                    if (awemeId.equals(aweme.getAwemeId())) {
                        hasAweme = true;
                        awemeDesc = aweme.getAwemeShowId() + "/" + aweme.getAwemeName();
                        break;
                    }
                }
            } catch (ApiException e) {
                if (isBusinessError(e)) {
                    result.skippedAccounts.put(advertiserId,
                            "查询授权抖音号失败(接口不适用): " + e.getMessage());
                    System.out.println("  [SKIP] 查询授权抖音号失败: " + e.getMessage());
                } else {
                    result.failedAccounts.put(advertiserId,
                            "查询授权抖音号: " + e.getMessage());
                    System.out.println("  [WARN] 查询授权抖音号失败: " + e.getMessage());
                }
                continue;
            }

            if (!hasAweme) {
                result.skippedAccounts.put(advertiserId, "账户下无该抖音号授权");
                System.out.println("  [SKIP] 账户下无该抖音号授权");
                continue;
            }
            result.matchedAwemesByAccount.put(advertiserId, awemeDesc);

            // 2) 拉取该账户的商品全域订单并按抖音号过滤
            try {
                List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                        withRetry(() -> fetchUniOrdersByAweme(
                                        advertiserId, awemeId, startDate, endDate),
                                "账户 " + advertiserId + " 随心推商品全域");
                result.ordersByAccount.put(advertiserId, orders);
                System.out.println("  随心推商品全域订单(该抖音号): " + orders.size() + " 条");
            } catch (ApiException e) {
                if (isBusinessError(e)) {
                    result.ordersByAccount.put(advertiserId, new ArrayList<>());
                    System.out.println("  [SKIP] 随心推全域接口不适用: " + e.getMessage());
                } else {
                    result.failedAccounts.put(advertiserId, "拉取订单: " + e.getMessage());
                    System.out.println("  [WARN] 拉取订单失败: " + e.getMessage());
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

    // ==================== 工具 ====================

    private static List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner>
    dedupeByOrderId(
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
