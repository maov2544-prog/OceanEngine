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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
        String accessToken = tokenClient.getAccessToken();
        log.info("开始查询千川计划, advertiserId={}, awemeId={}, marketingGoal={}",
                advertiserId, awemeId, marketingGoal);

        // 1. 查询第1页，获取 total_num
        QianchuanResponse.PromotionListData firstPageData = fetchPage(
                accessToken, advertiserId, awemeId, marketingGoal, fields, 1, PAGE_SIZE);

        if (firstPageData == null) {
            log.error("查询千川计划失败, advertiserId={}, awemeId={}", advertiserId, awemeId);
            return PlanQueryResult.none();
        }

        int totalNum = firstPageData.getPageInfo().getTotalNum();
        log.info("千川计划总数: {}", totalNum);

        // 2. 无计划
        if (totalNum == 0) {
            return PlanQueryResult.none();
        }

        // 收集第1页的计划
        List<PromotionPlanDTO> allPlans = new ArrayList<>(firstPageData.getAdList());
        int totalPage = firstPageData.getPageInfo().getTotalPage();

        // 3. 完整分页：从第2页开始拉取剩余数据
        for (int page = 2; page <= totalPage; page++) {
            log.info("拉取第 {}/{} 页...", page, totalPage);
            QianchuanResponse.PromotionListData pageData = fetchPage(
                    accessToken, advertiserId, awemeId, marketingGoal, fields, page, PAGE_SIZE);

            if (pageData != null && pageData.getAdList() != null) {
                allPlans.addAll(pageData.getAdList());
            } else {
                log.warn("第 {} 页返回为空，提前结束分页", page);
                break;
            }
        }

        log.info("分页完成, 共获取 {} 个计划", allPlans.size());

        // 4. 根据数量返回不同结果
        if (allPlans.size() == 1) {
            return PlanQueryResult.single(allPlans.get(0));
        } else {
            // 多个计划：打印完整结果，暂停该组合处理
            printMultiplePlans(awemeId, allPlans);
            return PlanQueryResult.multiple(allPlans);
        }
    }

    /**
     * 拉取指定页的数据
     */
    private QianchuanResponse.PromotionListData fetchPage(
            String accessToken,
            Long advertiserId,
            String awemeId,
            String marketingGoal,
            List<String> fields,
            int page,
            int pageSize
    ) {
        try {
            // 构建请求URL（GET请求，参数放query string）
            URIBuilder uriBuilder = new URIBuilder("https://api.oceanengine.com" + API_PATH);
            uriBuilder.addParameter("advertiser_id", String.valueOf(advertiserId));
            uriBuilder.addParameter("marketing_goal", marketingGoal);
            LocalDateTime endTime = LocalDateTime.now();
            uriBuilder.addParameter("start_time",
                    endTime.minusDays(QUERY_RANGE_DAYS).format(DATE_FORMAT));
            uriBuilder.addParameter("end_time", endTime.format(DATE_FORMAT));
            uriBuilder.addParameter("search_keyword", awemeId);
            uriBuilder.addParameter("search_keyword_type", "AWEME");
            uriBuilder.addParameter("page", String.valueOf(page));
            uriBuilder.addParameter("page_size", String.valueOf(pageSize));
            uriBuilder.addParameter("fields", objectMapper.writeValueAsString(fields));

            // 默认查所有状态（不含已删除）
            uriBuilder.addParameter("status", "ALL");

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
                throw new IllegalStateException(
                    "API返回错误, code=" + apiResponse.getCode()
                        + ", message=" + apiResponse.getMessage());
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
