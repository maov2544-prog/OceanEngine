package com.example.oceanengine.chen;

import com.bytedance.ads.ApiClient;
import com.example.oceanengine.client.ApiClients;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanUniPromotionListV10Api;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Fields;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Filtering;
import com.bytedance.ads.model.QianchuanUniPromotionListV10FilteringSearchKeywordType;
import com.bytedance.ads.model.QianchuanUniPromotionListV10MarketingGoal;
import com.bytedance.ads.model.QianchuanUniPromotionListV10PageSize;
import com.bytedance.ads.model.QianchuanUniPromotionListV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;
import com.example.oceanengine.client.QianchuanTokenClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 基于 OceanEngine Java SDK 的千川投放计划查询服务。 */
public final class QianchuanPlanService {

    private static final String BASE_PATH = "https://api.oceanengine.com";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 200;

    private final QianchuanUniPromotionListV10Api api;

    /** 自动通过 QianchuanTokenClient 获取 Access-Token。 */
    public QianchuanPlanService() throws Exception {
        this(QianchuanTokenClient.getAccessToken());
    }

    /** 使用已经获取的 Access-Token 创建服务，便于批量查询复用 Token。 */
    public QianchuanPlanService(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken 不能为空");
        }
        ApiClient client = new ApiClient();
        client.setBasePath(BASE_PATH);
        ApiClients.configure(client);
        client.addDefaultHeader("Access-Token", accessToken);
        this.api = new QianchuanUniPromotionListV10Api(client);
    }

    /**
     * 查询一个达人 + 商品组合的全部匹配计划。
     *
     * <p>SDK 请求中的 search_keyword_type 固定为 AWEME（抖音号），商品 ID
     * 对返回计划的 product_info 做本地精确匹配。</p>
     */
    public QianchuanPlanQueryResult queryByAwemeAndProduct(
            Long advertiserId, String awemeId, String productId) throws ApiException {
        validate(advertiserId, awemeId, productId);

        QianchuanUniPromotionListV10Filtering filtering =
                new QianchuanUniPromotionListV10Filtering();
        filtering.setSearchKeyword(awemeId);
        filtering.setSearchKeywordType(QianchuanUniPromotionListV10FilteringSearchKeywordType.AWEME);

        List<QianchuanUniPromotionListV10ResponseDataAdListInner> matched = new ArrayList<>();
        int pageCount = 0;
        int totalPages = 1;

        for (int page = 1; page <= totalPages && page <= MAX_PAGES; page++) {
            QianchuanUniPromotionListV10Response response =
                    api.openApiV10QianchuanUniPromotionListGet(
                            advertiserId,
                            null,
                            null,
                            QianchuanUniPromotionListV10MarketingGoal.VIDEO_PROM_GOODS,
                            allFields(),
                            filtering,
                            null,
                            null,
                            null,
                            page,
                            QianchuanUniPromotionListV10PageSize.NUMBER_100,
                            null,
                            null);

            pageCount++;
            if (response == null || response.getData() == null) {
                break;
            }

            if (response.getData().getAdList() != null) {
                for (QianchuanUniPromotionListV10ResponseDataAdListInner plan
                        : response.getData().getAdList()) {
                    if (containsProduct(plan, productId)) {
                        matched.add(plan);
                    }
                }
            }

            if (response.getData().getPageInfo() != null
                    && response.getData().getPageInfo().getTotalPage() != null) {
                totalPages = response.getData().getPageInfo().getTotalPage().intValue();
            } else if (response.getData().getAdList() == null
                    || response.getData().getAdList().size() < PAGE_SIZE) {
                break;
            }
        }

        return new QianchuanPlanQueryResult(awemeId, productId, matched, pageCount);
    }

    /** 查询并处理 0、1、多个三种结果；多个计划会完整打印后抛出异常暂停当前组合。 */
    public QianchuanPlanQueryResult queryAndHandle(
            Long advertiserId, String awemeId, String productId)
            throws ApiException, AmbiguousPlanException {
        QianchuanPlanQueryResult result = queryByAwemeAndProduct(advertiserId, awemeId, productId);
        switch (result.getMatchType()) {
            case NONE:
                System.out.println("[千川] 达人 " + awemeId + " + 商品 " + productId + "：0 个计划");
                break;
            case SINGLE:
                System.out.println("[千川] 达人 " + awemeId + " + 商品 " + productId + "：1 个计划");
                printPlan(1, result.getSingle());
                break;
            case MULTIPLE:
                System.out.println("[千川] 达人 " + awemeId + " + 商品 " + productId
                        + "：多个计划（" + result.size() + " 个），完整结果如下");
                printAll(result);
                throw new AmbiguousPlanException(result);
            default:
                throw new IllegalStateException("未知匹配类型");
        }
        return result;
    }

    private static List<QianchuanUniPromotionListV10Fields> allFields() {
        QianchuanUniPromotionListV10Fields[] values = QianchuanUniPromotionListV10Fields.values();
        List<QianchuanUniPromotionListV10Fields> fields = new ArrayList<>(values.length);
        Collections.addAll(fields, values);
        return fields;
    }

    private static boolean containsProduct(
            QianchuanUniPromotionListV10ResponseDataAdListInner plan, String productId) {
        if (plan == null || plan.getProductInfo() == null) {
            return false;
        }
        return plan.getProductInfo().stream()
            .anyMatch(product -> product != null && product.getProductId() != null
                && productId.equals(String.valueOf(product.getProductId())));
    }

    private static void printAll(QianchuanPlanQueryResult result) {
        System.out.println("[千川] 共 " + result.size() + " 个计划，翻页 "
                + result.getPageCount() + " 页");
        int index = 1;
        for (QianchuanUniPromotionListV10ResponseDataAdListInner plan : result.getPlans()) {
            printPlan(index++, plan);
        }
    }

    private static void printPlan(
            int index, QianchuanUniPromotionListV10ResponseDataAdListInner plan) {
        var info = plan == null ? null : plan.getAdInfo();
        if (info == null) {
            System.out.println("  " + index + ". 计划详情为空");
            return;
        }
        System.out.println("  " + index + ". adId=" + info.getId()
                + " | name=" + info.getName()
                + " | status=" + info.getStatus()
                + " | goal=" + info.getMarketingGoal()
                + " | " + info.getStartTime() + " ~ " + info.getEndTime());
    }

    private static void validate(Long advertiserId, String awemeId, String productId) {
        if (advertiserId == null) {
            throw new IllegalArgumentException("advertiserId 不能为空");
        }
        if (awemeId == null || awemeId.isBlank()) {
            throw new IllegalArgumentException("awemeId 不能为空");
        }
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId 不能为空");
        }
    }
}
