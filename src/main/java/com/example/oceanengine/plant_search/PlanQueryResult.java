package com.example.oceanengine.plant_search;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 计划查询结果封装（对外返回用）
 * 区分三种结果：0个计划、1个计划、多个计划
 */
@Data
public class PlanQueryResult {

    /** 计划总数 */
    private int totalCount;

    /** 查询结果类型 */
    private ResultType resultType;

    /** 计划列表（所有页合并后） */
    private List<PromotionPlanDTO> plans;

    /** 当多个计划时的提示信息 */
    private String message;

    public enum ResultType {
        /** 无计划 */
        NONE,
        /** 唯一计划 */
        SINGLE,
        /** 多个计划 */
        MULTIPLE
    }

    public static PlanQueryResult none() {
        PlanQueryResult result = new PlanQueryResult();
        result.setTotalCount(0);
        result.setResultType(ResultType.NONE);
        result.setMessage("未查询到匹配的投放计划");
        return result;
    }

    public static PlanQueryResult single(PromotionPlanDTO plan) {
        PlanQueryResult result = new PlanQueryResult();
        result.setTotalCount(1);
        result.setResultType(ResultType.SINGLE);
        result.setPlans(List.of(plan));
        result.setMessage("查询到唯一匹配计划");
        return result;
    }

    public static PlanQueryResult multiple(List<PromotionPlanDTO> plans) {
        PlanQueryResult result = new PlanQueryResult();
        result.setTotalCount(plans.size());
        result.setResultType(ResultType.MULTIPLE);
        result.setPlans(plans);
        result.setMessage(String.format("查询到 %d 个匹配计划，已暂停该组合处理，请人工确认", plans.size()));
        return result;
    }
}
