package com.example.oceanengine.model;

import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Count;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10Filtering;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10MarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;

/**
 * 随心推全域订单列表查询请求 DTO。
 *
 * <p>对应官方接口：GET /open_api/v1.0/qianchuan/aweme/uni_promotion/order/get/
 * （获取随心推全域订单列表，权限点：随心推全域投放 200000042）。</p>
 *
 * <p>默认值说明：营销目标固定为 {@code VIDEO_PROM_GOODS}（商品全域）——
 * 当前接口仅支持商品全域，传直播全域（LIVE_PROM_GOODS）会返回 code=40000「仅支持创建商品全域投放订单」；
 * 排序默认按订单创建时间；每页默认 50 条。</p>
 */
public class SuixintuiUniOrderListRequest {

    /** 千川业务账户 ID（必填） */
    private Long advertiserId;

    /** 营销目标（默认：商品全域） */
    private QianchuanAwemeUniPromotionOrderGetV10MarketingGoal marketingGoal =
            QianchuanAwemeUniPromotionOrderGetV10MarketingGoal.VIDEO_PROM_GOODS;

    /** 订单状态过滤（可选，如 STATUS_DELIVERING、STATUS_DELIVERY_SUCCESSFUL 等，见 SDK 枚举） */
    private QianchuanAwemeUniPromotionOrderGetV10FilteringStatus status;

    /** 订单创建开始日期 yyyy-MM-dd（可选，用于收窄拉取范围） */
    private String orderCreateStartDate;

    /** 订单创建结束日期 yyyy-MM-dd（可选，用于收窄拉取范围） */
    private String orderCreateEndDate;

    /** 排序字段（默认：订单创建时间） */
    private QianchuanAwemeUniPromotionOrderGetV10OrderField orderField =
            QianchuanAwemeUniPromotionOrderGetV10OrderField.ORDER_CREATE_TIME;

    /** 分页游标（null 表示第一页） */
    private Long cursor;

    /** 每页数量（默认：50） */
    private QianchuanAwemeUniPromotionOrderGetV10Count count =
            QianchuanAwemeUniPromotionOrderGetV10Count.NUMBER_50;

    // ==================== 构造 ====================

    public SuixintuiUniOrderListRequest() {
    }

    public SuixintuiUniOrderListRequest(Long advertiserId) {
        this.advertiserId = advertiserId;
    }

    // ==================== 链式 setter ====================

    public SuixintuiUniOrderListRequest advertiserId(Long advertiserId) {
        this.advertiserId = advertiserId;
        return this;
    }

    public SuixintuiUniOrderListRequest marketingGoal(
            QianchuanAwemeUniPromotionOrderGetV10MarketingGoal marketingGoal) {
        this.marketingGoal = marketingGoal;
        return this;
    }

    public SuixintuiUniOrderListRequest status(
            QianchuanAwemeUniPromotionOrderGetV10FilteringStatus status) {
        this.status = status;
        return this;
    }

    public SuixintuiUniOrderListRequest orderCreateStartDate(String orderCreateStartDate) {
        this.orderCreateStartDate = orderCreateStartDate;
        return this;
    }

    public SuixintuiUniOrderListRequest orderCreateEndDate(String orderCreateEndDate) {
        this.orderCreateEndDate = orderCreateEndDate;
        return this;
    }

    public SuixintuiUniOrderListRequest orderField(
            QianchuanAwemeUniPromotionOrderGetV10OrderField orderField) {
        this.orderField = orderField;
        return this;
    }

    public SuixintuiUniOrderListRequest cursor(Long cursor) {
        this.cursor = cursor;
        return this;
    }

    public SuixintuiUniOrderListRequest count(QianchuanAwemeUniPromotionOrderGetV10Count count) {
        this.count = count;
        return this;
    }

    // ==================== getter ====================

    public Long getAdvertiserId() {
        return advertiserId;
    }

    public QianchuanAwemeUniPromotionOrderGetV10MarketingGoal getMarketingGoal() {
        return marketingGoal;
    }

    public QianchuanAwemeUniPromotionOrderGetV10FilteringStatus getStatus() {
        return status;
    }

    public String getOrderCreateStartDate() {
        return orderCreateStartDate;
    }

    public String getOrderCreateEndDate() {
        return orderCreateEndDate;
    }

    public QianchuanAwemeUniPromotionOrderGetV10OrderField getOrderField() {
        return orderField;
    }

    public Long getCursor() {
        return cursor;
    }

    public QianchuanAwemeUniPromotionOrderGetV10Count getCount() {
        return count;
    }

    // ==================== 便捷转换 ====================

    /**
     * 把 DTO 中的过滤条件（状态 + 订单创建时间范围）转换为 SDK 的 Filtering。
     * 未设置的过滤条件不放入结果，避免误传空值。
     */
    public QianchuanAwemeUniPromotionOrderGetV10Filtering toFiltering() {
        QianchuanAwemeUniPromotionOrderGetV10Filtering filtering =
                new QianchuanAwemeUniPromotionOrderGetV10Filtering();
        if (status != null) {
            filtering.setStatus(status);
        }
        if (orderCreateStartDate != null) {
            filtering.setOrderCreateStartDate(orderCreateStartDate);
        }
        if (orderCreateEndDate != null) {
            filtering.setOrderCreateEndDate(orderCreateEndDate);
        }
        return filtering;
    }

    @Override
    public String toString() {
        return "SuixintuiUniOrderListRequest{"
                + "advertiserId=" + advertiserId
                + ", marketingGoal=" + marketingGoal
                + ", status=" + status
                + ", orderCreateStartDate='" + orderCreateStartDate + '\''
                + ", orderCreateEndDate='" + orderCreateEndDate + '\''
                + ", orderField=" + orderField
                + ", cursor=" + cursor
                + ", count=" + count
                + '}';
    }
}
