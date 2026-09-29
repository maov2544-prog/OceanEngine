package com.example.oceanengine.plant_search;

import com.example.oceanengine.plant_search.PlanQueryResult;
import com.example.oceanengine.plant_search.PromotionPlanDTO;
import com.example.oceanengine.plant_search.QianchuanResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 千川投放计划查询服务
 *
 * 核心逻辑：
 * 1. 通过 QianchuanTokenClient 获取 Access-Token
 * 2. 调用 uni_promotion/list 接口，search_keyword_type=AWEME 按抖音号查询
 * 3. 完整处理分页，合并所有页数据
 * 4. 返回 0/1/多个 三种结果
 */
public class QianchuanPlanQueryService {

    private static final Logger log = LoggerFactory.getLogger(QianchuanPlanQueryService.class);
    private static final String API_PATH = "/open_api/v1.0/qianchuan/uni_promotion/list/";
    private static final int PAGE_SIZE = 100;
    private static final int QUERY_RANGE_DAYS = 180;

    /** 翻页间隔（毫秒），降低请求密度避免触发接口频控(40100) */
    private static final long PAGE_INTERVAL_MS = 500L;

    /** 40100 频控退避等待时间（秒），按序递增，最多重试 3 次 */
    private static final long[] BACKOFF_SECONDS = {5, 10, 20};

    /** 默认查询的计划类型：全域计划(UNI_PROJECT) + 乘方计划(OVERALL_PROJECT)，都查并合并去重 */
    private static final List<String> DEFAULT_ADLAB_SCENES =
            List.of("UNI_PROJECT", "OVERALL_PROJECT");

        private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QianchuanTokenClient tokenClient;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public QianchuanPlanQueryService(QianchuanTokenClient tokenClient) {
        this.tokenClient = tokenClient;
        this.httpClient = new OkHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 按抖音号查询达人+商品对应的千川计划（完整分页）
     *
     * @param advertiserId  千川投放账户ID
     * @param awemeId       抖音号
     * @param marketingGoal 营销目标 VIDEO_PROM_GOODS(商品投放) / LIVE_PROM_GOODS(直播带货)
     * @param fields        需要查询的消耗指标
     * @return PlanQueryResult 三种结果：NONE / SINGLE / MULTIPLE
     */
    public PlanQueryResult queryPlansByAwemeId(
            Long advertiserId,
            String awemeId,
            String marketingGoal,
            List<String> fields
    ) {
        List<PromotionPlanDTO> allPlans =
                fetchAllPlans(advertiserId, awemeId, null, marketingGoal, fields, false);
        return classify(awemeId, allPlans);
    }

    /**
     * 按「抖音号 + 商品ID」查询千川计划（完整分页 + 本地按商品精确过滤）
     *
     * <p>接口的 search_keyword 一次只能按一种类型过滤，无法同时限定抖音号和商品，
     * 因此请求仍按抖音号（AWEME）查询，返回后在本地对 product_info 做商品ID精确匹配。</p>
     *
     * @param advertiserId  千川投放账户ID
     * @param awemeId       抖音号
     * @param productId     商品ID（为空时退化为仅按抖音号查询）
     * @param marketingGoal 营销目标 VIDEO_PROM_GOODS(商品投放) / LIVE_PROM_GOODS(直播带货)
     * @param fields        需要查询的消耗指标
     * @return PlanQueryResult 三种结果：NONE / SINGLE / MULTIPLE
     */
    public PlanQueryResult queryPlansByAwemeAndProduct(
            Long advertiserId,
            String awemeId,
            String productId,
            String marketingGoal,
            List<String> fields
    ) {
        if (productId == null || productId.isBlank()) {
            log.warn("productId 为空，退化为仅按抖音号查询");
            return queryPlansByAwemeId(advertiserId, awemeId, marketingGoal, fields);
        }

        List<PromotionPlanDTO> allPlans =
                fetchAllPlans(advertiserId, awemeId, null, marketingGoal, fields, false);

        List<PromotionPlanDTO> matched = new ArrayList<>();
        for (PromotionPlanDTO plan : allPlans) {
            if (containsProduct(plan, productId)) {
                matched.add(plan);
            }
        }
        log.info("抖音号 {} + 商品 {} 命中 {} 个计划（该抖音号共 {} 个）",
                awemeId, productId, matched.size(), allPlans.size());
        return classify(awemeId, matched);
    }

    /**
     * 完整分页拉取计划（按 awemeId 或 productId 过滤，两者都空则查全部）。
     * 默认同时查全域计划(UNI_PROJECT)和乘方计划(OVERALL_PROJECT)，合并并按计划ID去重。
     */
    private List<PromotionPlanDTO> fetchAllPlans(
            Long advertiserId,
            String awemeId,
            String productId,
            String marketingGoal,
            List<String> fields,
            boolean fullList
    ) {
        Map<Long, PromotionPlanDTO> merged = new LinkedHashMap<>();
        for (String scene : DEFAULT_ADLAB_SCENES) {
            List<PromotionPlanDTO> scenePlans = fetchPlansByScene(
                    advertiserId, awemeId, productId, marketingGoal, fields, scene, fullList);
            for (PromotionPlanDTO plan : scenePlans) {
                if (plan.getId() != null) {
                    merged.putIfAbsent(plan.getId(), plan);
                }
            }
        }
        log.info("分页完成, {} 种计划类型共获取 {} 个计划（按ID去重后）",
                DEFAULT_ADLAB_SCENES.size(), merged.size());
        return new ArrayList<>(merged.values());
    }

    /** 拉取指定 adlab_scene 下的全部页计划，返回合并后的列表。 */
    private List<PromotionPlanDTO> fetchPlansByScene(
            Long advertiserId,
            String awemeId,
            String productId,
            String marketingGoal,
            List<String> fields,
            String adlabScene,
            boolean fullList
    ) {
        String accessToken = tokenClient.getAccessToken();
        log.info("开始查询千川计划, advertiserId={}, awemeId={}, productId={}, marketingGoal={}, adlabScene={}, fullList={}",
                advertiserId, awemeId, productId, marketingGoal, adlabScene, fullList);

        // 1. 查询第1页，获取 total_num
        QianchuanResponse.PromotionListData firstPageData = fetchPage(
                accessToken, advertiserId, awemeId, productId, marketingGoal, fields, 1, PAGE_SIZE, adlabScene, fullList);

        if (firstPageData == null) {
            log.error("查询千川计划失败, advertiserId={}, awemeId={}, adlabScene={}",
                    advertiserId, awemeId, adlabScene);
            return new ArrayList<>();
        }

        int totalNum = firstPageData.getPageInfo().getTotalNum();
        log.info("千川计划总数[{}]: {}", adlabScene, totalNum);

        // 2. 无计划
        if (totalNum == 0) {
            return new ArrayList<>();
        }

        // 收集第1页的计划
        List<PromotionPlanDTO> allPlans = new ArrayList<>(firstPageData.getAdList());
        int totalPage = firstPageData.getPageInfo().getTotalPage();

        // 3. 完整分页：从第2页开始拉取剩余数据
        for (int page = 2; page <= totalPage; page++) {
            log.info("拉取第 {}/{} 页[{}]...", page, totalPage, adlabScene);

            // 翻页间隔：降低请求密度，避免触发接口频控(40100)
            try {
                Thread.sleep(PAGE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("查询千川计划被中断", e);
            }

            QianchuanResponse.PromotionListData pageData = fetchPage(
                    accessToken, advertiserId, awemeId, productId, marketingGoal, fields, page, PAGE_SIZE, adlabScene, fullList);

            if (pageData != null && pageData.getAdList() != null) {
                allPlans.addAll(pageData.getAdList());
            } else {
                log.warn("第 {} 页返回为空，提前结束分页", page);
                break;
            }
        }

        log.info("分页完成[{}], 共获取 {} 个计划", adlabScene, allPlans.size());
        return allPlans;
    }

    /** 按 0 / 1 / 多个 对计划列表分类并返回结果。 */
    private PlanQueryResult classify(String awemeId, List<PromotionPlanDTO> plans) {
        if (plans == null || plans.isEmpty()) {
            return PlanQueryResult.none();
        }
        if (plans.size() == 1) {
            return PlanQueryResult.single(plans.get(0));
        }
        // 多个计划：打印完整结果，暂停该组合处理
        printMultiplePlans(awemeId, plans);
        return PlanQueryResult.multiple(plans);
    }

    /** 全量模式分类：多个计划时只打印概要（id/name/status/roi2），不打印长详情。 */
    private PlanQueryResult classifyFull(String tag, List<PromotionPlanDTO> plans) {
        if (plans == null || plans.isEmpty()) {
            return PlanQueryResult.none();
        }
        if (plans.size() == 1) {
            return PlanQueryResult.single(plans.get(0));
        }
        printSummaryPlans(tag, plans);
        return PlanQueryResult.multiple(plans);
    }

    /** 概要打印：仅计划数量 + 每行 id/name/status/roi2。 */
    private void printSummaryPlans(String tag, List<PromotionPlanDTO> plans) {
        log.info("【全量模式】{} 共 {} 个计划（投放中+有消耗+近1个月）:",
                tag, plans.size());
        for (int i = 0; i < plans.size(); i++) {
            PromotionPlanDTO plan = plans.get(i);
            log.info("  {}: id={}, name={}, status={}, roi2={}",
                    i + 1,
                    plan.getId(),
                    plan.getName(),
                    plan.getStatus(),
                    plan.getTotalPayOrderRoi2() != null ? plan.getTotalPayOrderRoi2() : 0);
        }
    }

    /** 判断计划是否包含指定商品ID（product_info 精确匹配）。 */
    private boolean containsProduct(PromotionPlanDTO plan, String productId) {
        if (plan == null || plan.getProductInfo() == null) {
            return false;
        }
        for (PromotionPlanDTO.ProductInfo product : plan.getProductInfo()) {
            if (product.getProductId() != null
                    && productId.equals(String.valueOf(product.getProductId()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统一查询入口：按 抖音号 / 商品ID / 抖音号+商品 / 全部 查询计划（完整分页）
     *
     * <p>规则：</p>
     * <ul>
     *   <li>只填 awemeId：查该抖音号的所有计划（服务端 AWEME 过滤）</li>
     *   <li>只填 productId：查该商品的所有计划（服务端 PRODUCT 过滤）</li>
     *   <li>都填：抖音号 + 商品 双重过滤（服务端按抖音号 + 本地按商品精确匹配）</li>
     *   <li>都不填：全量模式——查账户下所有投放中且有消耗的计划，
     *       时间范围为「触发当天-1天 往前推1个月」到「触发当天-1天」，
     *       按创建时间降序，返回概要</li>
     * </ul>
     *
     * @param advertiserId  千川投放账户ID
     * @param awemeId       抖音号（可空）
     * @param productId     商品ID（可空）
     * @param marketingGoal 营销目标 VIDEO_PROM_GOODS(商品投放) / LIVE_PROM_GOODS(直播带货)
     * @param fields        需要查询的消耗指标
     * @return PlanQueryResult 三种结果：NONE / SINGLE / MULTIPLE
     */
    public PlanQueryResult queryPlansByKeyword(
            Long advertiserId,
            String awemeId,
            String productId,
            String marketingGoal,
            List<String> fields
    ) {
        boolean hasAweme = awemeId != null && !awemeId.isBlank();
        boolean hasProduct = productId != null && !productId.isBlank();
        boolean fullList = !hasAweme && !hasProduct;

        // 抖音号 + 商品 双重过滤：服务端按抖音号，本地再按商品ID精确匹配
        if (hasAweme && hasProduct) {
            List<PromotionPlanDTO> allPlans =
                    fetchAllPlans(advertiserId, awemeId, null, marketingGoal, fields, false);
            List<PromotionPlanDTO> matched = new ArrayList<>();
            for (PromotionPlanDTO plan : allPlans) {
                if (containsProduct(plan, productId)) {
                    matched.add(plan);
                }
            }
            log.info("抖音号 {} + 商品 {} 命中 {} 个计划（该抖音号共 {} 个）",
                    awemeId, productId, matched.size(), allPlans.size());
            return classify(awemeId, matched);
        }

        // 全量模式：抖音号、商品ID 都为空，按新参数查全部计划，输出概要
        if (fullList) {
            List<PromotionPlanDTO> allPlans =
                    fetchAllPlans(advertiserId, null, null, marketingGoal, fields, true);
            return classifyFull("账户全部计划", allPlans);
        }

        // 只填一个：直接交给服务端过滤
        List<PromotionPlanDTO> allPlans = fetchAllPlans(
                advertiserId,
                hasAweme ? awemeId : null,
                hasProduct ? productId : null,
                marketingGoal,
                fields,
                false);
        String tag = hasAweme ? awemeId : "商品" + productId;
        return classify(tag, allPlans);
    }

    /**
     * 拉取指定页的数据
     */
    private QianchuanResponse.PromotionListData fetchPage(
            String accessToken,
            Long advertiserId,
            String awemeId,
            String productId,
            String marketingGoal,
            List<String> fields,
            int page,
            int pageSize,
            String adlabScene,
            boolean fullList
    ) {
        // 40100 频控自动退避重试：最多重试 BACKOFF_SECONDS.length 次（5s/10s/20s）
        int maxAttempts = BACKOFF_SECONDS.length + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 构建请求URL（GET请求，参数放query string）
                URIBuilder uriBuilder = new URIBuilder("https://api.oceanengine.com" + API_PATH);
                uriBuilder.addParameter("advertiser_id", String.valueOf(advertiserId));
                uriBuilder.addParameter("marketing_goal", marketingGoal);

                if (fullList) {
                    // 全量模式：时间范围 = 触发当天-1天 往前推1个月 ~ 触发当天-1天（按当天动态计算，不写死）
                    LocalDate triggerDate = LocalDate.now();
                    LocalDate endDate = triggerDate.minusDays(1);
                    LocalDate startDate = endDate.minusMonths(1);
                    uriBuilder.addParameter("start_time",
                            startDate.atStartOfDay().format(DATE_FORMAT));
                    uriBuilder.addParameter("end_time",
                            endDate.atTime(23, 59, 59).format(DATE_FORMAT));
                } else {
                    // 常规模式：近180天
                    LocalDateTime endTime = LocalDateTime.now();
                    uriBuilder.addParameter("start_time",
                            endTime.minusDays(QUERY_RANGE_DAYS).format(DATE_FORMAT));
                    uriBuilder.addParameter("end_time", endTime.format(DATE_FORMAT));
                }

                uriBuilder.addParameter("page", String.valueOf(page));
                uriBuilder.addParameter("page_size", String.valueOf(pageSize));
                uriBuilder.addParameter("fields", objectMapper.writeValueAsString(fields));

                // 计划类型：UNI_PROJECT 全域计划 / OVERALL_PROJECT 乘方计划
                if (adlabScene != null && !adlabScene.isBlank()) {
                    uriBuilder.addParameter("adlab_scene", adlabScene);
                }

                // 全量模式：按创建时间降序（order_type 是顶层参数，不在 filtering 中）
                if (fullList) {
                    uriBuilder.addParameter("order_type", "DESC");
                }

                // 过滤条件必须放在 filtering 对象中（接口文档要求）：
                //  填了抖音号 -> search_keyword_type=AWEME 按抖音号过滤；
                //  只填商品ID -> search_keyword_type=PRODUCT 按商品过滤；
                //  都不填     -> 不带关键词，查账户下全部计划。
                // 注意：这些参数若放在顶层会被接口忽略，导致返回账户下全部计划。
                Map<String, Object> filtering = new LinkedHashMap<>();
                if (awemeId != null && !awemeId.isBlank()) {
                    filtering.put("search_keyword", awemeId);
                    filtering.put("search_keyword_type", "AWEME");
                } else if (productId != null && !productId.isBlank()) {
                    filtering.put("search_keyword", productId);
                    filtering.put("search_keyword_type", "PRODUCT");
                }

                if (fullList) {
                    // 全量模式：仅投放中 + 有消耗
                    filtering.put("status", "DELIVERY_OK");
                    filtering.put("having_cost", "YES");
                } else {
                    // 常规模式：所有状态（不含已删除）
                    filtering.put("status", "ALL");
                }
                uriBuilder.addParameter("filtering", objectMapper.writeValueAsString(filtering));

                URL url = uriBuilder.build().toURL();
                log.debug("请求URL: {}", url);

                Request request = new Request.Builder()
                        .url(url)
                        .method("GET", null)
                        .addHeader("Access-Token", accessToken)
                        .build();

                Response response = httpClient.newCall(request).execute();
                String body = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    throw new IllegalStateException(
                            "HTTP请求失败, status=" + response.code() + ", body=" + body);
                }

                JsonNode root = objectMapper.readTree(body);
                normalizeAdInfo(root);
                QianchuanResponse<QianchuanResponse.PromotionListData> apiResponse =
                    objectMapper.readValue(root.toString(),
                        new TypeReference<QianchuanResponse<QianchuanResponse.PromotionListData>>() {});

                if (!apiResponse.isSuccess()) {
                    int code = apiResponse.getCode();
                    // 40100 系统请求频率超限：按退避等待后重试，不直接抛异常
                    if (code == 40100 && attempt < maxAttempts) {
                        log.warn("接口频率超限(40100), 等待 {}s 后重试 (第 {}/{} 次)...",
                                BACKOFF_SECONDS[attempt - 1], attempt, maxAttempts - 1);
                        try {
                            Thread.sleep(BACKOFF_SECONDS[attempt - 1] * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("查询千川计划被中断", ie);
                        }
                        continue;
                    }
                    throw new IllegalStateException(
                            "API返回错误, code=" + code + ", message=" + apiResponse.getMessage());
                }

                return apiResponse.getData();

            } catch (URISyntaxException | JsonProcessingException e) {
                throw new IllegalStateException("构建千川计划请求参数失败", e);
            } catch (IOException e) {
                throw new IllegalStateException("请求千川计划接口失败", e);
            } catch (Exception e) {
                throw new IllegalStateException("查询千川计划异常", e);
            }
        }
        throw new IllegalStateException("查询千川计划异常: 重试" + (maxAttempts - 1) + "次后仍被限流");
    }

    /** 接口返回的 ad_info 是嵌套对象，DTO 使用扁平字段，先兼容展开。 */
    private void normalizeAdInfo(JsonNode root) {
        JsonNode adList = root.path("data").path("ad_list");
        if (!adList.isArray()) {
            return;
        }
        for (JsonNode ad : adList) {
            if (!(ad instanceof ObjectNode) || !ad.path("ad_info").isObject()) {
                continue;
            }
            ObjectNode target = (ObjectNode) ad;
            ad.path("ad_info").fields().forEachRemaining(entry -> {
                if (!target.has(entry.getKey())) {
                    target.set(entry.getKey(), entry.getValue());
                }
            });
            target.remove("ad_info");
        }
    }

    /**
     * 多个计划时打印完整结果，并暂停该组合处理
     */
    private void printMultiplePlans(String awemeId, List<PromotionPlanDTO> plans) {
        log.warn("========================================");
        log.warn("抖音号 {} 匹配到 {} 个计划，暂停该组合处理", awemeId, plans.size());
        log.warn("========================================");

        for (int i = 0; i < plans.size(); i++) {
            PromotionPlanDTO plan = plans.get(i);
            log.warn("  计划[{}]: id={}, name={}, status={}, marketingGoal={}, " +
                            "roi2Goal={}, budget={}, smartBidType={}, createTime={}",
                    i + 1,
                    plan.getId(),
                    plan.getName(),
                    plan.getStatus(),
                    plan.getMarketingGoal(),
                    plan.getRoi2Goal(),
                    plan.getBudget(),
                    plan.getSmartBidType(),
                    plan.getCreateTime());

            // 打印商品信息
            if (plan.getProductInfo() != null && !plan.getProductInfo().isEmpty()) {
                for (PromotionPlanDTO.ProductInfo product : plan.getProductInfo()) {
                    log.warn("    商品: id={}, name={}", product.getProductId(), product.getProductName());
                }
            }

            // 打印主播信息
            if (plan.getRoomInfo() != null && !plan.getRoomInfo().isEmpty()) {
                for (PromotionPlanDTO.RoomInfo room : plan.getRoomInfo()) {
                    log.warn("    主播: id={}, name={}", room.getAnchorId(), room.getAnchorName());
                }
            }

            // 打印消耗指标
            log.warn("    消耗: statCost={}, totalPayOrderGmv={}, payOrderCount={}, roi2={}",
                    plan.getStatCost(),
                    plan.getTotalPayOrderGmvForRoi2(),
                    plan.getTotalPayOrderCountForRoi2(),
                    plan.getTotalPayOrderRoi2());
        }

        log.warn("========================================");
        log.warn("请人工确认后继续处理该抖音号对应的组合");
        log.warn("========================================");
    }
}
