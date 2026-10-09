package com.aolandai.oceanengine.webhook;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 已通知流水的一行（JSONL）。
 *
 * <p>用途有二：</p>
 * <ol>
 *   <li><b>防重复</b>：{@code date + orderId} 已存在且 {@code success=true} 时不再发送；</li>
 *   <li><b>审计</b>：出问题时能看到「几点发的、对方返回什么」。</li>
 * </ol>
 *
 * <p>字段刻意与 {@link WebhookNotification} 平铺重复（不做嵌套对象），
 * 这样这个文件用任何工具打开都能直接看懂。</p>
 */
public class WebhookLedgerEntry {

    /** 归属日期（yyyy-MM-dd） */
    private String date;

    /** 订单 ID（同 payload，序列化成字符串避免丢精度） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 触发原因 */
    private String reason;

    /** 触发时该订单的当日累计次数 */
    private long reinvestCount;

    /** 触发时该订单的当日累计金额 */
    private long reinvestAmount;

    /** 发送时刻（ISO-8601 带时区） */
    private String sentAt;

    /** 是否发送成功（HTTP 2xx）。只有成功才算「已通知」 */
    private boolean success;

    /** HTTP 状态码；连接失败/超时为 -1 */
    private int httpStatus;

    /** 失败原因或 OK */
    private String detail;

    public WebhookLedgerEntry() {
    }

    /** 由 payload + 发送结果生成一行流水 */
    public static WebhookLedgerEntry of(WebhookNotification n, WebhookSender.Result result) {
        WebhookLedgerEntry e = new WebhookLedgerEntry();
        e.date = n.getDate();
        e.orderId = n.getOrderId();
        e.reason = n.getReason() == null ? null : n.getReason().name();
        e.reinvestCount = n.getReinvestCount();
        e.reinvestAmount = n.getReinvestAmount();
        e.sentAt = n.getSentAt();
        e.success = result.success();
        e.httpStatus = result.httpStatus();
        e.detail = result.detail();
        return e;
    }

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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

    public String getSentAt() {
        return sentAt;
    }

    public void setSentAt(String sentAt) {
        this.sentAt = sentAt;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    @Override
    public String toString() {
        return "WebhookLedgerEntry{date='" + date + '\''
                + ", orderId=" + orderId
                + ", reason='" + reason + '\''
                + ", count=" + reinvestCount
                + ", amount=" + reinvestAmount
                + ", success=" + success
                + ", httpStatus=" + httpStatus
                + ", detail='" + detail + '\'' + '}';
    }
}
