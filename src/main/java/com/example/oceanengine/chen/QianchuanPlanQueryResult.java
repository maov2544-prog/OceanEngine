package com.example.oceanengine.chen;

import com.bytedance.ads.model.QianchuanUniPromotionListV10ResponseDataAdListInner;

import java.util.Collections;
import java.util.List;

/** 千川达人 + 商品计划查询结果。 */
public final class QianchuanPlanQueryResult {

    public enum MatchType {
        NONE,
        SINGLE,
        MULTIPLE
    }

    private final String awemeId;
    private final String productId;
    private final List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans;
    private final int pageCount;

    public QianchuanPlanQueryResult(
            String awemeId,
            String productId,
            List<QianchuanUniPromotionListV10ResponseDataAdListInner> plans,
            int pageCount) {
        this.awemeId = awemeId;
        this.productId = productId;
        this.plans = plans == null ? Collections.emptyList() : List.copyOf(plans);
        this.pageCount = pageCount;
    }

    public String getAwemeId() {
        return awemeId;
    }

    public String getProductId() {
        return productId;
    }

    public List<QianchuanUniPromotionListV10ResponseDataAdListInner> getPlans() {
        return plans;
    }

    public int size() {
        return plans.size();
    }

    public int getPageCount() {
        return pageCount;
    }

    public MatchType getMatchType() {
        if (plans.isEmpty()) {
            return MatchType.NONE;
        }
        return plans.size() == 1 ? MatchType.SINGLE : MatchType.MULTIPLE;
    }

    public QianchuanUniPromotionListV10ResponseDataAdListInner getSingle() {
        if (getMatchType() != MatchType.SINGLE) {
            throw new IllegalStateException("当前结果不是唯一计划：" + getMatchType());
        }
        return plans.get(0);
    }
}
