package com.aolandai.oceanengine.domain;

/**
 * 追投筛选的「预算使用率」判定规则 —— <b>纯函数，不碰 IO</b>，便于单测全覆盖。
 *
 * <p>两个分支取 <b>OR</b>（任一成立即可）：</p>
 * <pre>
 *   ①  C_now / B                        &gt;= 阈值      原口径：当前使用率
 *   ②  (2 × C_now − C_prev) / B         &gt;= 阈值      新增：累计 + 本轮增量
 * </pre>
 *
 * <p>符号含义：</p>
 * <ul>
 *   <li>{@code C_now} —— 本轮的整体消耗（{@code stats_info.stat_cost_for_roi2}，元）</li>
 *   <li>{@code C_prev} —— <b>上一轮</b>的整体消耗（本服务自己按轮次记录的快照）</li>
 *   <li>{@code B} —— 投放总金额 = 创建预算 + 追加金额（元）</li>
 * </ul>
 *
 * <p><b>分支②的业务含义</b>：把它展开成 {@code C_now >= 0.4×B + C_prev/2} 可以看出 ——
 * 「消耗涨得越快，越早触发」。{@code C_prev} 很小时门槛会低到 {@code 0.4×B}，
 * 这正是它能减少漏投的原因，也是它比分支①宽松的地方。</p>
 *
 * <p>⚠️ <b>{@code C_prev} 缺失时（服务重启后第一轮 / 订单首次出现 / 快照文件丢失）
 * 按 {@code C_prev = C_now} 处理</b> —— 即「本轮增量 = 0」，分支② 退化成与分支① 相同，
 * 这一轮只按 80% 门槛判，<b>不会多投</b>。详见 {@link #boostedRatio} 的说明。</p>
 */
public final class UsageRatioRule {

    private UsageRatioRule() {
    }

    /**
     * 两个分支是否任一达标。
     *
     * @param statCost     本轮整体消耗（C_now），为 null 视为不达标
     * @param prevStatCost 上一轮整体消耗（C_prev），为 null 时按「本轮增量 = 0」处理（见 {@link #boostedRatio}）
     * @param budget       投放总金额（B），为 null 或 &lt;= 0 视为不达标
     * @param minRatio     阈值（如 0.8 表示 80%）
     * @return true = 使用率达标
     */
    public static boolean hit(Double statCost, Double prevStatCost, Long budget,
                              double minRatio) {
        if (statCost == null || budget == null || budget <= 0) {
            return false;
        }
        // 分支①：当前使用率（原口径，行为不变）
        if (statCost / budget >= minRatio) {
            return true;
        }
        // 分支②：累计 + 本轮增量
        return boostedRatio(statCost, prevStatCost, budget) >= minRatio;
    }

    /**
     * 分支①的比值：{@code C_now / B}。
     *
     * <p>预算缺失 / 为 0 / 消耗缺失时返回 {@code 0.0}（视为未达标，由调用方阈值过滤）——
     * 与改造前的 {@code calcBudgetUsedRatio} 行为完全一致。</p>
     */
    public static double currentRatio(Double statCost, Long budget) {
        if (statCost == null || budget == null || budget <= 0) {
            return 0.0;
        }
        return statCost / budget;
    }

    /**
     * 分支②的比值：{@code (2 × C_now − C_prev) / B}。
     *
     * <p>⚠️ <b>{@code C_prev} 缺失时按 {@code C_prev = C_now} 处理</b>（即「本轮增量 = 0」），
     * 此时本式退化为 {@code C_now / B} —— <b>与分支① 完全相同</b>，等于这一轮只按 80% 门槛判。</p>
     *
     * <p>为什么不按 0 兜底：按 0 会让本式变成 {@code 2×C_now/B}，<b>把使用率虚高一倍</b> →
     * 门槛从 80% 悄悄降到 40% → 服务每次重启后的第一轮、订单首次出现、快照文件丢失时
     * <b>都会大批多投</b>（2026-09-30 实测首轮多投 10 单 ≈ 2000 元）。
     * 按 {@code C_now} 兜底则退化成保守的分支①，不会再出现这个"首轮效应"。</p>
     *
     * <p>比值可能为负（{@code C_prev} 比 {@code 2×C_now} 还大，即消耗回退），此时自然不达标。</p>
     */
    public static double boostedRatio(Double statCost, Double prevStatCost, Long budget) {
        if (statCost == null || budget == null || budget <= 0) {
            return 0.0;
        }
        double prev = prevStatCost == null ? statCost : prevStatCost;
        return (2 * statCost - prev) / budget;
    }
}
