package com.aolandai.oceanengine.domain;

/**
 * 追投筛选的「第二条路径（ROAS）」判定规则 —— <b>纯函数，不碰 IO</b>，便于单测全覆盖。
 *
 * <p>业务背景（2026-10-05 老板需求）：原有追投只按「预算使用率」判（见 {@link UsageRatioRule}），
 * 会出现「钱快花完但没换回足够成交」的订单也被追投。现在<b>新增一条并行路径</b>，
 * 与原有使用率判定是 <b>OR</b> 关系（任一成立即纳入，<b>不替代</b>）：</p>
 * <pre>
 *   ①  消耗 / 投放总金额        &gt;= minUsageRatio   跑量：钱花得差不多了
 *   ②  净成交金额 / 投放总金额  &gt;= threshold       效果：花出去的钱换回了足够净成交
 * </pre>
 * <p>两条<b>同时</b>成立才纳入。</p>
 *
 * <p>符号含义（金额单位均为元）：</p>
 * <ul>
 *   <li>{@code 消耗} —— {@code stats_info.stat_cost_for_roi2}（整体消耗）</li>
 *   <li>{@code 投放总金额} —— 创建预算 + 追加金额（{@code delivery_setting.amount}
 *       + {@code add_amount_info.add_amount}）</li>
 *   <li>{@code 净成交金额} —— {@code stats_info.total_order_settle_amount_for_roi2_1h}
 *       （<b>原始金额字段</b>，不是用 ROI×消耗 反算）</li>
 * </ul>
 *
 * <p><b>门槛 {@code threshold} 的取值</b>：复用追投的「ROI 门槛」口径
 * （全局 {@code min-roi} + 商品级 {@code product-roi-overrides}），即「这个订单被要求达到多高的
 * 净成交 ROI 才允许追投」。这样两条路径对「效果」的标尺是同一把。</p>
 *
 * <p><b>缺失 / 脏数据一律判不达标</b>（保守，宁可少投不误投）：消耗、净成交金额、投放总金额
 * 任一为 {@code null}，或投放总金额 {@code <= 0}，直接返回 {@code false}。
 * 边界<b>含等于</b>（正好等于阈值即纳入）。</p>
 */
public final class RoasRule {

    private RoasRule() {
    }

    /**
     * 第二条路径（ROAS）是否达标：<b>两个比值同时</b>满足才成立。
     *
     * @param statCost        整体消耗（元），为 null 视为不达标
     * @param netSettleAmount 净成交金额（元），为 null 视为不达标
     * @param budget          投放总金额（元），为 null 或 &lt;= 0 视为不达标
     * @param minUsageRatio   消耗使用率下限（如 0.5 表示 50%）
     * @param threshold       净成交金额/投放总金额 下限（复用 ROI 门槛）
     * @return true = 第二条路径达标
     */
    public static boolean hit(Double statCost, Double netSettleAmount, Long budget,
                              double minUsageRatio, double threshold) {
        if (statCost == null || netSettleAmount == null || budget == null || budget <= 0) {
            return false;
        }
        return statCost / budget >= minUsageRatio
                && netSettleAmount / budget >= threshold;
    }

    /**
     * 消耗使用率：{@code 消耗 / 投放总金额}。
     *
     * <p>消耗缺失 / 投放总金额缺失或 &lt;= 0 时返回 {@code 0.0}（视为未达标，由调用方阈值过滤）。</p>
     */
    public static double usageRatio(Double statCost, Long budget) {
        if (statCost == null || budget == null || budget <= 0) {
            return 0.0;
        }
        return statCost / budget;
    }

    /**
     * 净成交金额比：{@code 净成交金额 / 投放总金额}。
     *
     * <p>净成交金额缺失 / 投放总金额缺失或 &lt;= 0 时返回 {@code 0.0}（视为未达标）。</p>
     */
    public static double netSettleRatio(Double netSettleAmount, Long budget) {
        if (netSettleAmount == null || budget == null || budget <= 0) {
            return 0.0;
        }
        return netSettleAmount / budget;
    }

    /** 启动横幅 / 日志用的一行概览。 */
    public static String describe(double minUsageRatio, double threshold) {
        return "消耗/投放总金额≥" + minUsageRatio
                + " 且 净成交金额/投放总金额≥" + threshold;
    }
}
