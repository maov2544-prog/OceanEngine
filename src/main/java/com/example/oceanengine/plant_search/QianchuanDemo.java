package com.example.oceanengine.plant_search;

import java.util.Arrays;
import java.util.List;

/**
 * 使用示例
 *
 * 调用流程：
 * 1. 初始化 QianchuanTokenClient（Token 从服务端自动获取）
 * 2. 初始化 QianchuanPlanQueryService（传入 tokenClient）
 * 3. 调用 queryPlansByKeyword() 查询计划
 * 4. 根据 PlanQueryResult 的三种类型做不同处理
 *
 * 查询模式（queryPlansByKeyword 四模式）：
 *  - 只填 awemeId   ：按抖音号查（服务端 AWEME 过滤）
 *  - 只填 productId ：按商品ID查（服务端 PRODUCT 过滤）
 *  - 都填           ：抖音号 + 商品 双重过滤（服务端按抖音号 + 本地按商品ID精确匹配）
 *  - 都为空         ：全量模式（投放中 + 有消耗 + 近1个月 + 创建时间降序，返回概要）
 */
public class QianchuanDemo {

    public static void main(String[] args) {

        // ===== 配置 =====
        Long advertiserId = 1823379540880396L; // 千川投放账户ID

        // ===== 初始化 =====
        QianchuanTokenClient tokenClient = new QianchuanTokenClient();
        QianchuanPlanQueryService queryService = new QianchuanPlanQueryService(tokenClient);

        // ===== 查询参数 =====
        String awemeId = "";   // 抖音号（可空，留空进入全量模式前提是 productId 也为空）
        String productId = ""; // 商品ID（可空）
        String marketingGoal = "VIDEO_PROM_GOODS"; // 商品投放
        List<String> fields = Arrays.asList(
                "stat_cost",
                "total_prepay_and_pay_order_roi2",
                "total_pay_order_gmv_for_roi2",
                "total_pay_order_count_for_roi2",
                "total_cost_per_pay_order_for_roi2",
                "total_pay_order_gmv_include_coupon_for_roi2"
        );

        // ===== 执行查询 =====
        PlanQueryResult result = queryService.queryPlansByKeyword(
                advertiserId, awemeId, productId, marketingGoal, fields);

        // ===== 处理结果 =====
        switch (result.getResultType()) {

            case NONE:
                System.out.println("✅ 无计划: " + result.getMessage());
                // 业务处理：记录无计划，继续下一组合
                break;

            case SINGLE:
                PromotionPlanDTO plan = result.getPlans().get(0);
                System.out.println("✅ 唯一计划: " + plan.getName()
                        + ", id=" + plan.getId()
                        + ", status=" + plan.getStatus());
                // 业务处理：直接使用这个计划继续流程
                break;

            case MULTIPLE:
                System.out.println("⚠️  " + result.getMessage());
                System.out.println("计划详情:");
                for (PromotionPlanDTO p : result.getPlans()) {
                    System.out.printf("  - id=%d, name=%s, status=%s, roi2=%.2f%n",
                            p.getId(), p.getName(), p.getStatus(),
                            p.getTotalPayOrderRoi2() != null ? p.getTotalPayOrderRoi2() : 0);
                }
                // 业务处理：暂停该组合，等待人工介入
                // 例如：写入异常队列、发送通知、跳过后续处理等
                break;
        }
    }
}
