package com.aolandai.oceanengine.webhook;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 发给下游 webhook 的 JSON payload。
 *
 * <p>⚠️ 字段一旦变更就等于改<b>对外契约</b>，下游程序可能依赖它，改动前先确认。</p>
 *
 * <p>样例：</p>
 * <pre>
 * {
 *   "date": "2026-09-29",
 *   "orderId": "7690526648109645860",
 *   "advertiserId": 1823379540880396,
 *   "awemeName": "某某旗舰店",
 *   "awemeShowId": "MS4wLjABAAAA...",
 *   "reinvestCount": 5,
 *   "reinvestAmount": 1000,
 *   "reason": "COUNT_AND_AMOUNT",
 *   "dryRun": false,
 *   "sentAt": "2026-09-29T10:05:00+08:00"
 * }
 * </pre>
 */
public class WebhookNotification {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 触发原因 */
    public enum Reason {
        /** 当日追投次数达标 */
        COUNT,
        /** 当日追投金额达标 */
        AMOUNT,
        /** 次数与金额同时达标（当前 amount=200、阈值 5 次/1000 元 时恒为该值） */
        COUNT_AND_AMOUNT
    }

    /** 归属日期（yyyy-MM-dd）——「当日」指的就是这一天 */
    private String date;

    /**
     * 订单 ID。
     *
     * <p>⚠️ <b>必须序列化成字符串</b>：这是 19 位数字，若按 JSON number 发出去，
     * 下游用 JavaScript 或某些解析器会丢精度（末尾几位变 0），导致下游匹配不到订单。
     * 日报那边同样按文本处理，口径保持一致。</p>
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 千川业务账户 ID */
    private Long advertiserId;

    /** 抖音名（老流水可能为 null） */
    private String awemeName;

    /** 抖音号（老流水可能为 null） */
    private String awemeShowId;

    /** 当日累计追投次数（含本次达标的那次） */
    private long reinvestCount;

    /** 当日累计追投金额（元） */
    private long reinvestAmount;

    /** 触发原因 */
    private Reason reason;

    /** 是否演练模式产生。正常路径恒为 false —— dry-run 时不发送 */
    private boolean dryRun;

    /** 发送时刻（带时区偏移，便于下游排序） */
    private String sentAt;

    public WebhookNotification() {
    }

    /**
     * 按当前时间生成一条通知（{@code sentAt} 自动填）。
     *
     * @param date         归属日期
     * @param reason       触发原因
     * @param usageCount   当日累计次数
     * @param usageAmount  当日累计金额
     */
    public static WebhookNotification of(LocalDate date, Long orderId, Long advertiserId,
                                         String awemeName, String awemeShowId,
                                         long usageCount, long usageAmount,
                                         Reason reason, boolean dryRun) {
        WebhookNotification n = new WebhookNotification();
        n.date = date == null ? null : date.format(DATE);
        n.orderId = orderId;
        n.advertiserId = advertiserId;
        n.awemeName = awemeName;
        n.awemeShowId = awemeShowId;
        n.reinvestCount = usageCount;
        n.reinvestAmount = usageAmount;
        n.reason = reason;
        n.dryRun = dryRun;
        n.sentAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return n;
    }

    /** 归属日期；解析失败返回 null */
    public LocalDate parsedDate() {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date, DATE);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** 发送时刻；解析失败返回 null */
    public LocalDateTime parsedSentAt() {
        if (sentAt == null || sentAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(sentAt).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    // ==================== getter / setter ====================

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getAdvertiserId() {
        return advertiserId;
    }

    public void setAdvertiserId(Long advertiserId) {
        this.advertiserId = advertiserId;
    }

    public String getAwemeName() {
        return awemeName;
    }

    public void setAwemeName(String awemeName) {
        this.awemeName = awemeName;
    }

    public String getAwemeShowId() {
        return awemeShowId;
    }

    public void setAwemeShowId(String awemeShowId) {
        this.awemeShowId = awemeShowId;
    }

    public long getReinvestCount() {
        return reinvestCount;
    }

    public void setReinvestCount(long reinvestCount) {
        this.reinvestCount = reinvestCount;
    }

    public long getReinvestAmount() {
        return reinvestAmount;
    }

    public void setReinvestAmount(long reinvestAmount) {
        this.reinvestAmount = reinvestAmount;
    }

    public Reason getReason() {
        return reason;
    }

    public void setReason(Reason reason) {
        this.reason = reason;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getSentAt() {
        return sentAt;
    }

    public void setSentAt(String sentAt) {
        this.sentAt = sentAt;
    }

    @Override
    public String toString() {
        return "WebhookNotification{date='" + date + '\''
                + ", orderId=" + orderId
                + ", advertiserId=" + advertiserId
                + ", reinvestCount=" + reinvestCount
                + ", reinvestAmount=" + reinvestAmount
                + ", reason=" + reason
                + ", dryRun=" + dryRun + '}';
    }
}
