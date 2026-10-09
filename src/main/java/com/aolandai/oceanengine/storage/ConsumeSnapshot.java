package com.aolandai.oceanengine.storage;

/**
 * 单个订单在<b>某一轮</b>的「整体消耗」快照。
 *
 * <p>为什么需要它：追投筛选的第二分支要算
 * {@code (2 × 本轮整体消耗 − 上一轮整体消耗) / 投放总金额}，
 * 而官方接口只返回「当前累计消耗」（{@code stats_info.stat_cost_for_roi2}），
 * 拿不到上一轮的值，所以必须自己按轮次记录下来。</p>
 *
 * <p>粒度：<b>每个订单只保留最新一条</b>（下一轮覆盖），所以文件大小只跟订单数有关，
 * 不随轮次数增长。</p>
 *
 * <p>⚠️ 这个文件丢了会导致「上一轮消耗」缺失 → 第二分支退化（门槛从 80% 降到 40% 左右）
 * → <b>可能多花钱</b>。所以 {@link ConsumeSnapshotStore} 对损坏采取<b>拒绝启动</b>策略，
 * 与 {@link ReinvestRecordStore} 一致（而不是像通知流水那样跳过坏行）。</p>
 */
public class ConsumeSnapshot {

    /** 订单 ID */
    private Long orderId;

    /** 千川业务账户 ID */
    private Long advertiserId;

    /** 该订单本轮的整体消耗（元） */
    private Double statCost;

    /** 采集时的轮次 ID，便于排查「这个值是哪一轮看到的」 */
    private String roundId;

    /** 采集时刻（yyyy-MM-dd HH:mm:ss） */
    private String timestamp;

    public ConsumeSnapshot() {
    }

    public ConsumeSnapshot(Long orderId, Long advertiserId, Double statCost,
                           String roundId, String timestamp) {
        this.orderId = orderId;
        this.advertiserId = advertiserId;
        this.statCost = statCost;
        this.roundId = roundId;
        this.timestamp = timestamp;
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

    public Double getStatCost() {
        return statCost;
    }

    public void setStatCost(Double statCost) {
        this.statCost = statCost;
    }

    public String getRoundId() {
        return roundId;
    }

    public void setRoundId(String roundId) {
        this.roundId = roundId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "ConsumeSnapshot{orderId=" + orderId
                + ", statCost=" + statCost
                + ", roundId='" + roundId + '\''
                + ", timestamp='" + timestamp + '\'' + '}';
    }
}
