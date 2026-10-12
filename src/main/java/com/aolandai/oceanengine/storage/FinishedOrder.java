package com.aolandai.oceanengine.storage;

/**
 * 「已结束且 ROI 达标」的随心推订单 —— 日报「已结束达标订单」sheet 的一条数据，
 * 同时也是「七天底表」里的一行。
 *
 * <p>为什么要底表：官方列表接口<b>只能按创建时间过滤，返回项里没有「结束时间」字段</b>，
 * 所以「当天结束的订单」无法直接查。做法是每天拉一次「近 N 天创建 + 状态∈结束态 + ROI 达标」的
 * 全量，与上一次的底表对比，<b>多出来的就是新结束的</b>，然后用本次全量覆盖底表。</p>
 *
 * <p>不涉及资金，所以 {@link FinishedOrderStore} 对损坏采取宽松策略（跳过坏行），
 * 与消耗快照那种「宁可停摆」的严格策略相反 —— 底表丢了最多让 sheet 多列一批历史订单。</p>
 */
public class FinishedOrder {

    /** 订单 ID（也是底表去重的 key） */
    private Long orderId;

    /** 千川业务账户 ID */
    private Long advertiserId;

    /** 抖音名（aweme_info.aweme_name） */
    private String awemeName;

    /** 抖音号（aweme_info.aweme_show_id） */
    private String awemeShowId;

    /**
     * 达人的<b>数值 UID</b>（{@code aweme_info.aweme_id}）—— 复建建单要的就是它。
     *
     * <p>⚠️ 与 {@link #awemeShowId}（抖音号字符串）不是一回事：建单接口的 {@code aweme_id}
     * 收的是数值 UID，传抖音号字符串会被拒。</p>
     */
    private Long awemeId;

    /**
     * 商品 ID（{@code product_info.product_id}，列表结构口径）—— 复建建单要的字段。
     *
     * <p>⚠️ 注意口径：<b>列表</b>结构里叫 {@code product_info.product_id}，
     * <b>详情</b>结构里叫 {@code product_info.id}；本字段来自列表。</p>
     */
    private Long productId;

    /** 订单结束时的 ROI（按配置口径：净成交或整体支付） */
    private Double roi;

    /** 订单状态（当前口径只抓 FINISHED 投放完成；FROZEN 投放终止 / OVER 投放结束不统计） */
    private String status;

    /** 订单创建时间（接口原样返回的字符串） */
    private String orderCreateTime;

    /** 最后一次出现在拉取结果里的时间（yyyy-MM-dd HH:mm:ss），用于排查底表是否在更新 */
    private String lastSeenAt;

    public FinishedOrder() {
    }

    public FinishedOrder(Long orderId, Long advertiserId, String awemeName, String awemeShowId,
                         Double roi, String status, String orderCreateTime, String lastSeenAt) {
        this.orderId = orderId;
        this.advertiserId = advertiserId;
        this.awemeName = awemeName;
        this.awemeShowId = awemeShowId;
        this.roi = roi;
        this.status = status;
        this.orderCreateTime = orderCreateTime;
        this.lastSeenAt = lastSeenAt;
    }

    /**
     * 全字段构造器（含复建所需的 {@code awemeId} / {@code productId}）。
     *
     * <p>与 8 参构造器并存：旧调用方不必改，新调用方（复建相关）用这个把建单字段一起带上。</p>
     */
    public FinishedOrder(Long orderId, Long advertiserId, String awemeName, String awemeShowId,
                         Long awemeId, Long productId, Double roi, String status,
                         String orderCreateTime, String lastSeenAt) {
        this(orderId, advertiserId, awemeName, awemeShowId, roi, status, orderCreateTime, lastSeenAt);
        this.awemeId = awemeId;
        this.productId = productId;
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

    public Double getRoi() {
        return roi;
    }

    public void setRoi(Double roi) {
        this.roi = roi;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOrderCreateTime() {
        return orderCreateTime;
    }

    public void setOrderCreateTime(String orderCreateTime) {
        this.orderCreateTime = orderCreateTime;
    }

    public String getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(String lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    @Override
    public String toString() {
        return "FinishedOrder{orderId=" + orderId
                + ", advertiserId=" + advertiserId
                + ", awemeShowId='" + awemeShowId + '\''
                + ", awemeId=" + awemeId
                + ", productId=" + productId
                + ", roi=" + roi
                + ", status='" + status + '\'' + '}';
    }
}
