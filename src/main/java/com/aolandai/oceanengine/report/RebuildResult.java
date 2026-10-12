package com.aolandai.oceanengine.report;

/**
 * 单个原订单的复建结果（日报「复建状态」F 列 /「复建订单ID」G 列的数据来源）。
 *
 * <p>{@link SuixintuiRebuildService#rebuild} 返回 {@code Map<原订单号, RebuildResult>}，
 * 日报据此填充两列。业务判断（F 列文案 / G 列该写什么）都收敛在<b>本类</b>，
 * 日报不再写 if-else。</p>
 *
 * <h2>F 列「复建状态」5 种取值</h2>
 * <ul>
 *   <li>{@link Status#REBUILT} → 「已复建」</li>
 *   <li>{@link Status#SKIPPED_INFLIGHT} → 「已有投放中订单」</li>
 *   <li>{@link Status#SKIPPED_LIMIT} → 「超每日上限」</li>
 *   <li>{@link Status#SKIPPED_INVALID} → 「参数缺失」</li>
 *   <li>{@link Status#FAILED} → 「复建失败」</li>
 * </ul>
 *
 * <h2>G 列「复建订单ID」取值</h2>
 * <ul>
 *   <li>已复建 → 新订单号</li>
 *   <li>在投冲突 → 那个已在投的冲突订单号</li>
 *   <li>其它失败（超上限 / 参数缺失 / 复建失败）→ <b>留空</b>（保持 G 列「订单号」的纯净语义）</li>
 * </ul>
 */
public record RebuildResult(Status status, Long orderId) {

    /** 复建结果类型 —— 决定日报 F 列文案与 G 列是否写订单号 */
    public enum Status {
        /** 真建成功（含幂等命中，取回历史新订单号） */
        REBUILT("已复建"),
        /** 在投冲突跳过（同一「商品+达人」组合已有在投订单） */
        SKIPPED_INFLIGHT("已有投放中订单"),
        /** 当天已达每日复建上限，跳过（下次可重试） */
        SKIPPED_LIMIT("超每日上限"),
        /** 原单参数缺失（拿不到 amount/deliveryTime 等），跳过（下次可重试） */
        SKIPPED_INVALID("参数缺失"),
        /** 建单失败（接口异常等其它原因），跳过（下次可重试） */
        FAILED("复建失败");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        /** F 列「复建状态」文案 */
        public String label() {
            return label;
        }
    }

    public RebuildResult {
        if (status == null) {
            throw new IllegalArgumentException("复建结果必须有 status");
        }
    }

    /** 真复建成功（newOrderId 非空）。 */
    public static RebuildResult rebuilt(Long newOrderId) {
        if (newOrderId == null) {
            throw new IllegalArgumentException("真复建成功必须有 newOrderId");
        }
        return new RebuildResult(Status.REBUILT, newOrderId);
    }

    /**
     * 在投冲突跳过；{@code conflictOrderId} 为那个已在投的订单号（用于日报 G 列，可能为 null）。
     */
    public static RebuildResult skippedInflight(Long conflictOrderId) {
        return new RebuildResult(Status.SKIPPED_INFLIGHT, conflictOrderId);
    }

    /** 超每日上限跳过（G 列留空）。 */
    public static RebuildResult skippedLimit() {
        return new RebuildResult(Status.SKIPPED_LIMIT, null);
    }

    /** 原单参数缺失跳过（G 列留空）。 */
    public static RebuildResult skippedInvalid() {
        return new RebuildResult(Status.SKIPPED_INVALID, null);
    }

    /** 建单失败（G 列留空）。 */
    public static RebuildResult failed() {
        return new RebuildResult(Status.FAILED, null);
    }

    /** F 列「复建状态」文案。 */
    public String statusLabel() {
        return status.label();
    }

    /**
     * G 列「复建订单ID」文案：有订单号（成功的新单号 / 冲突单号）时返回其字符串，否则 {@code null}
     * （日报据此留空）。⚠️ 只有 {@link Status#REBUILT} 与 {@link Status#SKIPPED_INFLIGHT} 会带号。
     */
    public String orderIdLabel() {
        return orderId == null ? null : String.valueOf(orderId);
    }
}
