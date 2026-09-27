package com.example.oceanengine.plant_search;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 千川投放计划查询结果 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromotionPlanDTO {

    // ========== 营销信息 ==========

    /** 投放ID */
    @JsonProperty("id")
    private Long id;

    /** 投放名称 */
    @JsonProperty("name")
    private String name;

    /** 当前周期开始时间 */
    @JsonProperty("start_time")
    private String startTime;

    /** 当前周期结束时间 */
    @JsonProperty("end_time")
    private String endTime;

    /** 修改时间 */
    @JsonProperty("modify_time")
    private String modifyTime;

    /** 创建时间 */
    @JsonProperty("create_time")
    private String createTime;

    /** 营销目标: LIVE_PROM_GOODS / VIDEO_PROM_GOODS */
    @JsonProperty("marketing_goal")
    private String marketingGoal;

    /** 支付ROI目标 */
    @JsonProperty("roi2_goal")
    private Double roi2Goal;

    /** 预算类型 */
    @JsonProperty("budget_mode")
    private String budgetMode;

    /** 预算 */
    @JsonProperty("budget")
    private Double budget;

    /** 投放状态 */
    @JsonProperty("status")
    private String status;

    /** 操作状态 */
    @JsonProperty("opt_status")
    private String optStatus;

    /** 投放时长(秒) */
    @JsonProperty("delivery_seconds")
    private Long deliverySeconds;

    /** 投放方式: SMART_BID_CONSERVATIVE / SMART_BID_CUSTOM */
    @JsonProperty("smart_bid_type")
    private String smartBidType;

    /** 每日投放时长 */
    @JsonProperty("daily_delivery_time")
    private Double dailyDeliveryTime;

    /** 优化目标 */
    @JsonProperty("deep_external_action")
    private String deepExternalAction;

    /** 计划类型: OVERALL_PROJECT / UNI_PROJECT */
    @JsonProperty("adlab_scene")
    private String adlabScene;

    // ========== 商品信息 ==========

    /** 商品列表 */
    @JsonProperty("product_info")
    private List<ProductInfo> productInfo;

    // ========== 主播信息 ==========

    /** 主播信息列表 */
    @JsonProperty("room_info")
    private List<RoomInfo> roomInfo;

    // ========== 消耗指标 ==========

    /** 整体消耗(千分之一分) */
    @JsonProperty("stat_cost")
    private Double statCost;

    /** 整体支付ROI */
    @JsonProperty("total_prepay_and_pay_order_roi2")
    private Double totalPayOrderRoi2;

    /** 用户实际支付金额 */
    @JsonProperty("total_pay_order_gmv_for_roi2")
    private Double totalPayOrderGmvForRoi2;

    /** 整体成交订单数 */
    @JsonProperty("total_pay_order_count_for_roi2")
    private Double totalPayOrderCountForRoi2;

    /** 整体成交订单成本 */
    @JsonProperty("total_cost_per_pay_order_for_roi2")
    private Double totalCostPerPayOrderForRoi2;

    /** 整体成交金额 */
    @JsonProperty("total_pay_order_gmv_include_coupon_for_roi2")
    private Double totalPayOrderGmvIncludeCouponForRoi2;

    /** 综合ROI */
    @JsonProperty("total_prepay_and_pay_settle_overall_roi2_1h")
    private Double settleOverallRoi21h;

    /** 综合订单成本 */
    @JsonProperty("total_cost_per_pay_order_settle_for_overall_roi2_1h")
    private Double costPerPayOrderSettleForOverallRoi21h;

    /** 综合成本 */
    @JsonProperty("stat_cost_for_overall_roi2")
    private Double statCostForOverallRoi2;

    // ========== 内嵌对象 ==========

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductInfo {
        /** 商品ID */
        @JsonProperty("product_id")
        private Long productId;
        /** 商品名称 */
        @JsonProperty("product_name")
        private String productName;
        /** 商品预览图 */
        @JsonProperty("product_image")
        private String productImage;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoomInfo {
        /** 主播ID */
        @JsonProperty("anchor_id")
        private String anchorId;
        /** 主播名称 */
        @JsonProperty("anchor_name")
        private String anchorName;
        /** 主播头像 */
        @JsonProperty("anchor_avatar")
        private String anchorAvatar;
    }
}
