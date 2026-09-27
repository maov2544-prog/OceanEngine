package com.example.oceanengine.chen;

/** 多个计划匹配同一达人 + 商品组合时抛出的异常。 */
public final class AmbiguousPlanException extends Exception {

    private final QianchuanPlanQueryResult result;

    public AmbiguousPlanException(QianchuanPlanQueryResult result) {
        super("达人 " + result.getAwemeId() + " + 商品 " + result.getProductId()
                + " 匹配到 " + result.size() + " 个计划，需要人工确认");
        this.result = result;
    }

    public QianchuanPlanQueryResult getResult() {
        return result;
    }
}
