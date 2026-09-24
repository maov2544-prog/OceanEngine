package com.example.oceanengine.storage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单笔追投的幂等流水记录（JSONL 文件中的一行）。
 *
 * <p>核心用途：防止「进程重启 / 网络重试 / 并发重叠」导致同一订单被重复追投。
 * 每条记录以 (roundId, orderId) 为唯一键：同一轮次内同一订单只允许执行一次加预算。</p>
 *
 * <p>状态流转：</p>
 * <ul>
 *   <li>PENDING —— 已落盘、即将调用加预算接口（执行前快照 preTotalBudget 一并记录）</li>
 *   <li>DONE —— 接口返回成功（code=0）</li>
 *   <li>FAILED —— 接口业务失败（code != 0，如参数错误/状态不允许），下一轮会重新评估</li>
 *   <li>UNKNOWN —— 网络异常/超时，结果未知（可能已生效），<b>不重试</b>；
 *       下一轮先查详情校验预算是否已包含本次追加额，再决定是否重投</li>
 * </ul>
 */
public class ReinvestRecord {

    public enum Status {
        PENDING, DONE, FAILED, UNKNOWN
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 轮次 ID（一轮 = 一次完整扫描+追投） */
    private String roundId;

    /** 千川业务账户 ID */
    private Long advertiserId;

    /** 订单 ID */
    private Long orderId;

    /** 本次追加金额（元） */
    private Long amount;

    /** 本次追加投放时长（小时） */
    private Double deliveryTime;

    /** 追投前详情快照：投放总金额（创建预算 + 追加金额，元），用于重启后校验是否已生效 */
    private Long preTotalBudget;

    /** 状态 */
    private Status status;

    /** 附加信息（错误消息 / 校验说明） */
    private String message;

    /** 记录时间 */
    private String timestamp;

    public ReinvestRecord() {
    }

    public ReinvestRecord(String roundId, Long advertiserId, Long orderId,
                          Long amount, Double deliveryTime, Long preTotalBudget,
                          Status status, String message) {
        this.roundId = roundId;
        this.advertiserId = advertiserId;
        this.orderId = orderId;
        this.amount = amount;
        this.deliveryTime = deliveryTime;
        this.preTotalBudget = preTotalBudget;
        this.status = status;
        this.message = message;
        this.timestamp = LocalDateTime.now().format(TS);
    }

    // ==================== getter / setter ====================

    public String getRoundId() {
        return roundId;
    }

    public void setRoundId(String roundId) {
        this.roundId = roundId;
    }

    public Long getAdvertiserId() {
        return advertiserId;
    }

    public void setAdvertiserId(Long advertiserId) {
        this.advertiserId = advertiserId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
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

    public Long getPreTotalBudget() {
        return preTotalBudget;
    }

    public void setPreTotalBudget(Long preTotalBudget) {
        this.preTotalBudget = preTotalBudget;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "ReinvestRecord{roundId='" + roundId + '\''
                + ", advertiserId=" + advertiserId
                + ", orderId=" + orderId
                + ", amount=" + amount
                + ", deliveryTime=" + deliveryTime
                + ", preTotalBudget=" + preTotalBudget
                + ", status=" + status
                + ", message='" + message + '\''
                + ", timestamp='" + timestamp + '\'' + '}';
    }
}
