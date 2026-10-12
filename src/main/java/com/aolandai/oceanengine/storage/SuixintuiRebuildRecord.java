package com.aolandai.oceanengine.storage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单笔「随心推订单复建」的幂等流水记录（JSONL 文件中的一行）。
 *
 * <p>核心用途：保证<b>一个原订单最多真复建一次</b>（老板口径「只要这个订单号没有复建过就行」）。
 * 以 {@link #sourceOrderId}（原订单号）为去重键。</p>
 *
 * <h2>⚠️ 幂等语义：只有「真建成功」算数，dry-run 记录不算</h2>
 * <p>{@link #dryRun} 为 {@code true} 的记录<b>只用于审计/日志</b>，<b>不阻挡</b>后续的真复建。
 * 理由：dry-run 只是「演练打印请求体」，并没有真的建出订单；如果它算作「已复建」，
 * 那么一旦在生产上用 dry-run 跑过一次日报，之后切到真建时这些订单会被永久跳过 ——
 * 那不是老板要的「没复建过就复建」，而是「演练过就不复建」，会把该建的订单漏掉。</p>
 *
 * <p>{@link #newOrderId} 非空 = 真建成功，是「已复建」的唯一判定依据
 * （见 {@code SuixintuiRebuildRecordStore#isRebuilt}）。</p>
 *
 * <p>记录字段覆盖建单 8 参数（照抄原单），便于人工核对与问题追溯。</p>
 *
 * <h2>⚠️ 结果类型 {@link #result}（2026-10 新增）</h2>
 * <p>区分「真建成功」与「在投冲突跳过」两类<b>需要永久标记</b>的结果：</p>
 * <ul>
 *   <li>{@code REBUILT}：真建成功（{@link #newOrderId} 非空）。</li>
 *   <li>{@code SKIPPED_INFLIGHT}：因「同一(商品+达人)组合已有在投订单」而跳过，
 *       <b>不建单、不花钱</b>，但按老板口径<b>永久标记为已处理</b>（后续永不再尝试），
 *       冲突订单号记在 {@link #conflictOrderId}。</li>
 *   <li>{@code DRY_RUN}：演练，只留档、不阻挡后续真建。</li>
 * </ul>
 * <p>⚠️ <b>向后兼容</b>：老流水行没有 {@code result} 字段 → 反序列化后为 {@code null} →
 * 一律当作 {@code REBUILT} 处理（见 {@link #effectiveResult()}），否则历史成功记录会被误判为非成功。</p>
 */
public class SuixintuiRebuildRecord {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 结果类型枚举名：{@code REBUILT} / {@code SKIPPED_INFLIGHT} / {@code DRY_RUN} */
    public static final String RESULT_REBUILT = "REBUILT";
    public static final String RESULT_SKIPPED_INFLIGHT = "SKIPPED_INFLIGHT";
    public static final String RESULT_DRY_RUN = "DRY_RUN";

    /** 原订单号（复建来源，<b>去重 key</b>） */
    private Long sourceOrderId;

    /** 复建出的新订单号；dry-run 时为 {@code null}（没有真的建单） */
    private Long newOrderId;

    /**
     * 结果类型（{@code REBUILT} / {@code SKIPPED_INFLIGHT} / {@code DRY_RUN}）。
     *
     * <p>⚠️ 老流水行没有本字段 → 读回为 {@code null} → 用 {@link #effectiveResult()} 归一为 {@code REBUILT}。</p>
     */
    private String result;

    /**
     * 在投冲突时那个「已在投」的订单号 —— 仅供日报 G 列展示，参与幂等判定的是 {@link #sourceOrderId}。
     * 非冲突情形为 {@code null}。
     */
    private Long conflictOrderId;

    /** 千川业务账户 ID */
    private Long advertiserId;

    /** 达人数值 UID（aweme_info.aweme_id） */
    private Long awemeId;

    /** 商品 ID（product_info.product_id） */
    private Long productId;

    /** 预算（元） */
    private Long amount;

    /** 投放时长（小时） */
    private Double deliveryTime;

    /** 出价方式（AUTO_BID / MANUAL_BID） */
    private String bidType;

    /** 乘方模式（QCPX_MODE_ON / QCPX_MODE_OFF / QCPX_MODE_DEFAULT） */
    private String qcpxMode;

    /** 支付 ROI 目标 */
    private Double roiGoal;

    /** 这条记录是否为演练（true = 没真建，仅审计） */
    private boolean dryRun;

    /** 复建时间 */
    private String rebuiltAt;

    public SuixintuiRebuildRecord() {
    }

    public SuixintuiRebuildRecord(Long sourceOrderId, Long newOrderId, Long advertiserId,
                                  Long awemeId, Long productId, Long amount, Double deliveryTime,
                                  String bidType, String qcpxMode, Double roiGoal, boolean dryRun) {
        this.sourceOrderId = sourceOrderId;
        this.newOrderId = newOrderId;
        this.advertiserId = advertiserId;
        this.awemeId = awemeId;
        this.productId = productId;
        this.amount = amount;
        this.deliveryTime = deliveryTime;
        this.bidType = bidType;
        this.qcpxMode = qcpxMode;
        this.roiGoal = roiGoal;
        this.dryRun = dryRun;
        // 结果类型：真建（有 newOrderId）→ REBUILT；否则（dry-run）→ DRY_RUN。
        // 兼容构造器不产出 SKIPPED_INFLIGHT（该结果由带参构造器显式传入）。
        this.result = dryRun ? RESULT_DRY_RUN
                : (newOrderId != null ? RESULT_REBUILT : null);
        this.rebuiltAt = LocalDateTime.now().format(TS);
    }

    /**
     * 全参构造器：显式指定结果类型与冲突订单号（在投冲突跳过 / 真建 / dry-run 通用）。
     *
     * @param result          结果类型枚举名，可为 {@code null}（按 {@link #effectiveResult()} 归一）
     * @param conflictOrderId 冲突订单号（仅 {@code SKIPPED_INFLIGHT} 有值），否则为 {@code null}
     */
    public SuixintuiRebuildRecord(Long sourceOrderId, Long newOrderId, Long conflictOrderId,
                                  String result, Long advertiserId,
                                  Long awemeId, Long productId, Long amount, Double deliveryTime,
                                  String bidType, String qcpxMode, Double roiGoal, boolean dryRun) {
        this(sourceOrderId, newOrderId, advertiserId, awemeId, productId, amount, deliveryTime,
                bidType, qcpxMode, roiGoal, dryRun);
        this.result = result;
        this.conflictOrderId = conflictOrderId;
    }

    /** 造一条「在投冲突跳过」记录（不建单、不花钱，但永久标记为已处理）。 */
    public static SuixintuiRebuildRecord skippedInflight(
            Long sourceOrderId, Long conflictOrderId, Long advertiserId,
            Long awemeId, Long productId, Long amount, Double deliveryTime,
            String bidType, String qcpxMode, Double roiGoal) {
        return new SuixintuiRebuildRecord(sourceOrderId, null, conflictOrderId,
                RESULT_SKIPPED_INFLIGHT, advertiserId, awemeId, productId, amount, deliveryTime,
                bidType, qcpxMode, roiGoal, false);
    }

    // ==================== getter / setter ====================

    public Long getSourceOrderId() {
        return sourceOrderId;
    }

    public void setSourceOrderId(Long sourceOrderId) {
        this.sourceOrderId = sourceOrderId;
    }

    public Long getNewOrderId() {
        return newOrderId;
    }

    public void setNewOrderId(Long newOrderId) {
        this.newOrderId = newOrderId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Long getConflictOrderId() {
        return conflictOrderId;
    }

    public void setConflictOrderId(Long conflictOrderId) {
        this.conflictOrderId = conflictOrderId;
    }

    public Long getAdvertiserId() {
        return advertiserId;
    }

    public void setAdvertiserId(Long advertiserId) {
        this.advertiserId = advertiserId;
    }

    public Long getAwemeId() {
        return awemeId;
    }

    public void setAwemeId(Long awemeId) {
        this.awemeId = awemeId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public Double getDeliveryTime() {
        return deliveryTime;
    }

    public void setDeliveryTime(Double deliveryTime) {
        this.deliveryTime = deliveryTime;
    }

    public String getBidType() {
        return bidType;
    }

    public void setBidType(String bidType) {
        this.bidType = bidType;
    }

    public String getQcpxMode() {
        return qcpxMode;
    }

    public void setQcpxMode(String qcpxMode) {
        this.qcpxMode = qcpxMode;
    }

    public Double getRoiGoal() {
        return roiGoal;
    }

    public void setRoiGoal(Double roiGoal) {
        this.roiGoal = roiGoal;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getRebuiltAt() {
        return rebuiltAt;
    }

    public void setRebuiltAt(String rebuiltAt) {
        this.rebuiltAt = rebuiltAt;
    }

    /**
     * 归一化后的结果类型：{@code result} 为 {@code null} 时返回 {@link #RESULT_REBUILT}。
     *
     * <p>⚠️ 向后兼容的关键：老流水行没有 {@code result} 字段，读回为 {@code null}，
     * 一律当作「真建成功」处理，否则历史成功记录会被当成非成功记录、日报会漏显示。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String effectiveResult() {
        return result == null ? RESULT_REBUILT : result;
    }

    /**
     * 是否<b>真建成功</b>（拿到新订单号、且非演练）—— 日报 G 列/新订单号判定用。
     *
     * <p>⚠️ {@code @JsonIgnore} 不能省：Jackson 会把这个 {@code isXxx()} 当属性 {@code effective}
     * 序列化进流水，但反序列化时没有对应 setter 会抛 {@code UnrecognizedPropertyException}
     * 导致<b>重启读不回流水</b>（已被 {@code survivesRestart} 测出来）。它是派生值，不入库。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEffective() {
        return !dryRun && newOrderId != null;
    }

    /**
     * 该记录是否代表「这个原订单已处理、无需再复建」—— 幂等判定的唯一依据。
     *
     * <p>两类算数：</p>
     * <ul>
     *   <li>{@link #RESULT_REBUILT}（真建成功，{@code newOrderId != null}）；</li>
     *   <li>{@link #RESULT_SKIPPED_INFLIGHT}（在投冲突跳过，按老板口径<b>永久标记</b>，
     *       {@code newOrderId == null} 但仍算已处理）。</li>
     * </ul>
     * <p>{@link #RESULT_DRY_RUN} 与老格式里「真建但无单号」的异常行都不算数。</p>
     *
     * <p>⚠️ {@code @JsonIgnore} 同 {@link #isEffective()}：派生值，不入库。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSettled() {
        String r = effectiveResult();
        if (RESULT_SKIPPED_INFLIGHT.equals(r)) {
            return true;
        }
        return !dryRun && newOrderId != null;    // REBUILT（含老格式归一）
    }

    /**
     * 是否计入「每日真复建」额度 —— <b>只有真建成功才算</b>。
     *
     * <p>⚠️ 在投冲突跳过（{@code SKIPPED_INFLIGHT}）虽然「已处理」，但<b>没真建单、没花钱</b>，
     * 不应占用每日上限额度；dry-run 同理。</p>
     *
     * <p>{@code @JsonIgnore} 同上。</p>
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean countsTowardDailyLimit() {
        return isEffective();
    }

    /** 解析 {@link #rebuiltAt} 为 {@link LocalDateTime}；为空或格式异常返回 {@code null} */
    public LocalDateTime parsedRebuiltAt() {
        if (rebuiltAt == null || rebuiltAt.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(rebuiltAt, TS);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "SuixintuiRebuildRecord{sourceOrderId=" + sourceOrderId
                + ", newOrderId=" + newOrderId
                + ", result='" + effectiveResult() + '\''
                + ", conflictOrderId=" + conflictOrderId
                + ", advertiserId=" + advertiserId
                + ", awemeId=" + awemeId
                + ", productId=" + productId
                + ", amount=" + amount
                + ", deliveryTime=" + deliveryTime
                + ", bidType='" + bidType + '\''
                + ", qcpxMode='" + qcpxMode + '\''
                + ", roiGoal=" + roiGoal
                + ", dryRun=" + dryRun
                + ", rebuiltAt='" + rebuiltAt + '\'' + '}';
    }
}
