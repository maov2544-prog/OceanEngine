package com.aolandai.oceanengine.domain;

import com.aolandai.oceanengine.suixintui.Decimals;

import java.util.List;

/**
 * 建随心推的<b>接口硬约束</b>（来源：官方文档，老板 2026-10-05 提供）。
 *
 * <h2>官方口径（原文照抄，别改）</h2>
 * <table border="1">
 *   <tr><th>字段</th><th>类型</th><th>必填</th><th>说明</th></tr>
 *   <tr><td>{@code amount}</td><td>number</td><td>是</td>
 *       <td>预算，<b>单位：元</b>；取值范围 <b>[100, 5000000]</b>，且<b>数值需为 10 的倍数</b></td></tr>
 *   <tr><td>{@code delivery_time}</td><td>double</td><td>是</td>
 *       <td>投放时长，<b>单位：小时</b>；<b>允许值仅 10 个</b>：
 *           2 / 6 / 12 / 24 / 48 / 72 / 96 / 120 / 144 / 168</td></tr>
 *   <tr><td>{@code roi_goal}</td><td>double</td><td><b>条件必填</b></td>
 *       <td>支付 ROI；<b>最多两位小数</b>，取值范围 <b>[0.01, 10000]</b>；
 *           <b>当 {@code marketing_goal=VIDEO_PROM_GOODS} 且 {@code bid_type=MANUAL_BID} 时必填</b></td></tr>
 * </table>
 *
 * <h2>⚠️ 为什么要把约束集中在一个类里</h2>
 * <p>这些约束会在<b>三个地方</b>被需要：配置自检（启动）、请求组装前校验、单测。
 * 如果各写一份，改一处漏一处 —— 而且这类错误<b>不会编译失败</b>，
 * 只会表现为「接口报 40000 参数不合法」或更糟的<b>静默按错误预算建单（花钱）</b>。
 * 同 {@link ProductRoiRule} / {@link RoasRule} 的做法：<b>规则只写一次</b>。</p>
 *
 * <p>⚠️ {@code delivery_time} 不是「任意小时数」而是<b>白名单</b> —— 传 {@code 3} 或 {@code 36}
 * 会被接口拒。所以配置里也<b>不能</b>随便填。</p>
 */
public final class SuixintuiConstraints {

    /**
     * {@code delivery_time} 的<b>全部合法取值</b>（单位：小时）。
     *
     * <p>⚠️ 是白名单，不是范围 —— 传 3 / 36 之类的值接口会拒。</p>
     */
    public static final List<Double> ALLOWED_DELIVERY_TIMES =
            List.of(2.0, 6.0, 12.0, 24.0, 48.0, 72.0, 96.0, 120.0, 144.0, 168.0);

    /** {@code amount} 下限（单位：元）。 */
    public static final long MIN_AMOUNT = 100L;

    /** {@code amount} 上限（单位：元）。 */
    public static final long MAX_AMOUNT = 5_000_000L;

    /** {@code amount} 必须是这个数的倍数（单位：元）。 */
    public static final long AMOUNT_STEP = 10L;

    /** {@code roi_goal} 下限（支付 ROI）。 */
    public static final double MIN_ROI_GOAL = 0.01;

    /** {@code roi_goal} 上限（支付 ROI）。 */
    public static final double MAX_ROI_GOAL = 10_000.0;

    /** {@code roi_goal} 最多允许的小数位数。 */
    public static final int ROI_GOAL_MAX_SCALE = 2;

    /** 出价方式：自动出价。 */
    public static final String BID_TYPE_AUTO = "AUTO_BID";

    /** 出价方式：手动出价。⚠️ 该取值会让 {@code roi_goal} 变成<b>必填</b>。 */
    public static final String BID_TYPE_MANUAL = "MANUAL_BID";

    /** A 场景固定的营销目标（本服务只建商品推广的随心推）。 */
    public static final String MARKETING_GOAL_VIDEO_PROM_GOODS = "VIDEO_PROM_GOODS";

    private SuixintuiConstraints() {
    }

    /**
     * {@code delivery_time} 是否合法。
     *
     * <p>用 {@link Double#compare} 而不是 {@code ==} —— 配置从 yml 读进来是 {@code double}，
     * 与常量做精确比较时用 {@code ==} 会触发「浮点数相等比较」告警，且语义上不严谨。</p>
     */
    public static boolean isDeliveryTimeAllowed(double deliveryTime) {
        for (Double allowed : ALLOWED_DELIVERY_TIMES) {
            if (Double.compare(allowed, deliveryTime) == 0) {
                return true;
            }
        }
        return false;
    }

    /** {@code amount} 是否合法（区间 + 10 的倍数）。 */
    public static boolean isAmountAllowed(long amount) {
        return amount >= MIN_AMOUNT && amount <= MAX_AMOUNT && amount % AMOUNT_STEP == 0;
    }

    /**
     * {@code roi_goal} 是否<b>必填</b>。
     *
     * <p>官方原文：<b>当 {@code marketing_goal=VIDEO_PROM_GOODS} 且 {@code bid_type=MANUAL_BID} 时必填</b>。
     * 本服务（A 场景）的 {@code marketing_goal} <b>恒为</b> {@link #MARKETING_GOAL_VIDEO_PROM_GOODS}
     * （在 {@code SuixintuiOrderCreateService#buildRequest} 里写死），所以这里<b>只看 bidType</b>。</p>
     *
     * <p>⚠️ 若将来本服务要支持别的 marketing_goal（如直播），<b>必须把 marketingGoal 也传进来</b> ——
     * 否则会把「VIDEO_PROM_GOODS 才成立」的规则错误地套到其他场景上。</p>
     */
    public static boolean isRoiGoalRequired(String bidType) {
        return BID_TYPE_MANUAL.equals(bidType);
    }

    /**
     * 校验 {@code amount}，合法返回 {@code null}，否则返回<b>可直接给用户看的</b>错误说明。
     *
     * <p>返回说明文本而不是布尔值，是为了让调用方（配置自检 / 参数校验）不必各自再拼一遍提示 ——
     * 提示里带上<b>实际收到的值</b>，排查时才知道到底填了什么。</p>
     */
    public static String amountProblem(long amount) {
        if (amount < MIN_AMOUNT || amount > MAX_AMOUNT) {
            return "预算 amount=" + amount + " 超出允许范围（单位：元），必须落在 "
                    + describeAmountRule();
        }
        if (amount % AMOUNT_STEP != 0) {
            return "预算 amount=" + amount + " 不是 " + AMOUNT_STEP + " 的倍数（单位：元），"
                    + "接口会拒；请改成 " + describeAmountRule();
        }
        return null;
    }

    /** 校验 {@code deliveryTime}，合法返回 {@code null}，否则返回错误说明。 */
    public static String deliveryTimeProblem(double deliveryTime) {
        if (isDeliveryTimeAllowed(deliveryTime)) {
            return null;
        }
        return "投放时长 delivery_time=" + deliveryTime + " 不在允许值内（单位：小时），"
                + "只能是 " + describeDeliveryTimes();
    }

    /**
     * 校验 {@code roi_goal}（含<b>条件必填</b>），合法返回 {@code null}，否则返回错误说明。
     *
     * @param roiGoal 支付 ROI；{@code null} = 不传
     * @param bidType 出价方式；{@code MANUAL_BID} 时 {@code roiGoal} 不能为空
     */
    public static String roiGoalProblem(Double roiGoal, String bidType) {
        if (roiGoal == null) {
            if (isRoiGoalRequired(bidType)) {
                return "ROI 目标 roi_goal 必填：官方规定 marketing_goal="
                        + MARKETING_GOAL_VIDEO_PROM_GOODS + " 且 bid_type=" + bidType
                        + " 时不能为空（本服务的 marketing_goal 恒为 "
                        + MARKETING_GOAL_VIDEO_PROM_GOODS + "）";
            }
            return null;
        }
        if (roiGoal < MIN_ROI_GOAL || roiGoal > MAX_ROI_GOAL) {
            return "ROI 目标 roi_goal=" + roiGoal + " 超出允许范围（支付 ROI），必须落在 "
                    + describeRoiGoalRule();
        }
        int scale = Decimals.scaleOf(roiGoal);
        if (scale > ROI_GOAL_MAX_SCALE) {
            return "ROI 目标 roi_goal=" + roiGoal + " 有 " + scale + " 位小数，超过官方允许的 "
                    + ROI_GOAL_MAX_SCALE + " 位；请改成 " + describeRoiGoalRule();
        }
        return null;
    }

    /** 形如 {@code 2 / 6 / 12 / 24 / 48 / 72 / 96 / 120 / 144 / 168}。 */
    public static String describeDeliveryTimes() {
        StringBuilder sb = new StringBuilder();
        for (Double allowed : ALLOWED_DELIVERY_TIMES) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            // 允许值都是整小时，去掉小数点，避免打印成「2.0 / 6.0」
            sb.append(allowed.longValue());
        }
        return sb.toString();
    }

    /** 形如 {@code [100, 5000000] 且为 10 的倍数}。 */
    public static String describeAmountRule() {
        return "[" + MIN_AMOUNT + ", " + MAX_AMOUNT + "] 且为 " + AMOUNT_STEP + " 的倍数";
    }

    /** 形如 {@code [0.01, 10000] 且最多 2 位小数}。 */
    public static String describeRoiGoalRule() {
        return "[" + Decimals.plain(MIN_ROI_GOAL) + ", " + Decimals.plain(MAX_ROI_GOAL) + "] 且最多 "
                + ROI_GOAL_MAX_SCALE + " 位小数";
    }
}
