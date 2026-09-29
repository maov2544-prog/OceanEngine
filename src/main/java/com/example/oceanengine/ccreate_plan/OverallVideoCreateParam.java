package com.example.oceanengine.ccreate_plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 「新建乘方商品投放计划」入参 DTO。
 *
 * <p>字段名与官方文档一致，取值也用文档原值（如 {@code QCPX_MODE_ON}），
 * 由 {@link QianchuanOverallVideoCreateService} 负责转成 SDK 的强类型枚举，
 * 调用方不需要 import SDK 的 model。</p>
 *
 * <p><b>注意层级关系</b>——官方接口是嵌套的，不是扁平的：</p>
 * <pre>
 * advertiser_id / name / product_ids             -&gt; 顶层
 * budget / roi2_goal / qcpx_mode / ...           -&gt; delivery_setting
 * aweme_uid / video_material / image_material    -&gt; multi_product_creative_list[]（按商品维度）
 * </pre>
 *
 * <p>接口文档：https://open.oceanengine.com/labels/12/docs/1872485038037385</p>
 */
public class OverallVideoCreateParam {

    /** 投放账号 id（advertiser_id） */
    private Long advertiserId;
    /** 投放计划名称（name），1-100 字符，1 个汉字算 2 位 */
    private String name;
    /** 商品 id 列表（product_ids） */
    private List<Long> productIds;
    /** 营销目标（marketing_goal），默认 VIDEO_PROM_GOODS（短视频带货） */
    private String marketingGoal = "VIDEO_PROM_GOODS";
    /** 投放设置（delivery_setting） */
    private DeliverySetting deliverySetting;
    /** 商品创意素材信息（multi_product_creative_list），每个商品一条 */
    private List<CreativeItem> multiProductCreativeList;

    public Long getAdvertiserId() {
        return advertiserId;
    }

    public OverallVideoCreateParam advertiserId(Long advertiserId) {
        this.advertiserId = advertiserId;
        return this;
    }

    public void setAdvertiserId(Long advertiserId) {
        this.advertiserId = advertiserId;
    }

    public String getName() {
        return name;
    }

    public OverallVideoCreateParam name(String name) {
        this.name = name;
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Long> getProductIds() {
        return productIds;
    }

    public OverallVideoCreateParam productIds(List<Long> productIds) {
        this.productIds = productIds;
        return this;
    }

    public void setProductIds(List<Long> productIds) {
        this.productIds = productIds;
    }

    public String getMarketingGoal() {
        return marketingGoal;
    }

    public OverallVideoCreateParam marketingGoal(String marketingGoal) {
        this.marketingGoal = marketingGoal;
        return this;
    }

    public void setMarketingGoal(String marketingGoal) {
        this.marketingGoal = marketingGoal;
    }

    public DeliverySetting getDeliverySetting() {
        return deliverySetting;
    }

    public OverallVideoCreateParam deliverySetting(DeliverySetting deliverySetting) {
        this.deliverySetting = deliverySetting;
        return this;
    }

    public void setDeliverySetting(DeliverySetting deliverySetting) {
        this.deliverySetting = deliverySetting;
    }

    public List<CreativeItem> getMultiProductCreativeList() {
        return multiProductCreativeList;
    }

    public OverallVideoCreateParam multiProductCreativeList(List<CreativeItem> list) {
        this.multiProductCreativeList = list;
        return this;
    }

    public void setMultiProductCreativeList(List<CreativeItem> multiProductCreativeList) {
        this.multiProductCreativeList = multiProductCreativeList;
    }

    // ========================================================================
    // 投放设置（delivery_setting）
    // ========================================================================

    /** 投放设置，对应文档 delivery_setting。 */
    public static class DeliverySetting {
        /** 预算（budget），必填 */
        private Double budget;
        /** 支付 ROI 目标（roi2_goal），必填，最多两位小数 */
        private Double roi2Goal;
        /** 是否开启智能优惠券（qcpx_mode）：QCPX_MODE_ON / QCPX_MODE_OFF / QCPX_MODE_DEFAULT */
        private String qcpxMode;
        /** 投放时间方式（video_schedule_type）：SCHEDULE_FROM_NOW 从今天起长期投放 / SCHEDULE_START_END 设置起止日期 */
        private String videoScheduleType;
        /** 出价方式（smart_bid_type），乘方当前仅支持 SMART_BID_CUSTOM（控成本投放） */
        private String smartBidType;
        /** 深度转化目标（deep_external_action），默认 AD_CONVERT_TYPE_LIVE_PURE_PAY_ROI（净成交 ROI） */
        private String deepExternalAction;
        /** 投放开始时间 yyyy-MM-dd，video_schedule_type=SCHEDULE_START_END 时必填 */
        private String startTime;
        /** 投放结束时间 yyyy-MM-dd，video_schedule_type=SCHEDULE_START_END 时必填 */
        private String endTime;
        /** AIGC 动态创意开关（后台「AIGC 动态创意」） */
        private Boolean enableAigcCreative;
        /** 是否为乘方无号投商城（true 时不传 aweme_uid） */
        private Boolean noAwemeId;
        /** 乘方-成本项设置（后台「营销服务」里的星选素材投放 / 达人佣金出价） */
        private OverallCostItems overallCostItems;

        public Double getBudget() {
            return budget;
        }

        public DeliverySetting budget(Double budget) {
            this.budget = budget;
            return this;
        }

        public void setBudget(Double budget) {
            this.budget = budget;
        }

        public Double getRoi2Goal() {
            return roi2Goal;
        }

        public DeliverySetting roi2Goal(Double roi2Goal) {
            this.roi2Goal = roi2Goal;
            return this;
        }

        public void setRoi2Goal(Double roi2Goal) {
            this.roi2Goal = roi2Goal;
        }

        public String getQcpxMode() {
            return qcpxMode;
        }

        public DeliverySetting qcpxMode(String qcpxMode) {
            this.qcpxMode = qcpxMode;
            return this;
        }

        public void setQcpxMode(String qcpxMode) {
            this.qcpxMode = qcpxMode;
        }

        public String getVideoScheduleType() {
            return videoScheduleType;
        }

        public DeliverySetting videoScheduleType(String videoScheduleType) {
            this.videoScheduleType = videoScheduleType;
            return this;
        }

        public void setVideoScheduleType(String videoScheduleType) {
            this.videoScheduleType = videoScheduleType;
        }

        public String getSmartBidType() {
            return smartBidType;
        }

        public DeliverySetting smartBidType(String smartBidType) {
            this.smartBidType = smartBidType;
            return this;
        }

        public void setSmartBidType(String smartBidType) {
            this.smartBidType = smartBidType;
        }

        public String getDeepExternalAction() {
            return deepExternalAction;
        }

        public DeliverySetting deepExternalAction(String deepExternalAction) {
            this.deepExternalAction = deepExternalAction;
            return this;
        }

        public void setDeepExternalAction(String deepExternalAction) {
            this.deepExternalAction = deepExternalAction;
        }

        public String getStartTime() {
            return startTime;
        }

        public DeliverySetting startTime(String startTime) {
            this.startTime = startTime;
            return this;
        }

        public void setStartTime(String startTime) {
            this.startTime = startTime;
        }

        public String getEndTime() {
            return endTime;
        }

        public DeliverySetting endTime(String endTime) {
            this.endTime = endTime;
            return this;
        }

        public void setEndTime(String endTime) {
            this.endTime = endTime;
        }

        public Boolean getEnableAigcCreative() {
            return enableAigcCreative;
        }

        public DeliverySetting enableAigcCreative(Boolean enableAigcCreative) {
            this.enableAigcCreative = enableAigcCreative;
            return this;
        }

        public void setEnableAigcCreative(Boolean enableAigcCreative) {
            this.enableAigcCreative = enableAigcCreative;
        }

        public Boolean getNoAwemeId() {
            return noAwemeId;
        }

        public DeliverySetting noAwemeId(Boolean noAwemeId) {
            this.noAwemeId = noAwemeId;
            return this;
        }

        public void setNoAwemeId(Boolean noAwemeId) {
            this.noAwemeId = noAwemeId;
        }

        public OverallCostItems getOverallCostItems() {
            return overallCostItems;
        }

        public DeliverySetting overallCostItems(OverallCostItems overallCostItems) {
            this.overallCostItems = overallCostItems;
            return this;
        }

        public void setOverallCostItems(OverallCostItems overallCostItems) {
            this.overallCostItems = overallCostItems;
        }
    }

    /**
     * 乘方-成本项设置，对应文档 overall_cost_items（在 delivery_setting 内）。
     *
     * <p>后台「营销服务」三选一，实际就是这两个开关的组合：</p>
     * <ul>
     *   <li>基础投放 -&gt; 两个开关都关（默认）</li>
     *   <li>千川星选素材投放 -&gt; starTaskMaterialSwitch = true</li>
     *   <li>达人佣金出价 -&gt; allianceCommisionSwitch = true</li>
     * </ul>
     */
    public static class OverallCostItems {
        /** 千川星选素材投放开关（后台「千川星选素材投放」） */
        private Boolean starTaskMaterialSwitch;
        /** 达人带货佣金优化开关（后台「达人佣金出价」）；仅白名单用户可用，且 marketing_goal=VIDEO_PROM_GOODS、无号不支持 */
        private Boolean allianceCommisionSwitch;

        public Boolean getStarTaskMaterialSwitch() {
            return starTaskMaterialSwitch;
        }

        public OverallCostItems starTaskMaterialSwitch(Boolean starTaskMaterialSwitch) {
            this.starTaskMaterialSwitch = starTaskMaterialSwitch;
            return this;
        }

        public void setStarTaskMaterialSwitch(Boolean starTaskMaterialSwitch) {
            this.starTaskMaterialSwitch = starTaskMaterialSwitch;
        }

        public Boolean getAllianceCommisionSwitch() {
            return allianceCommisionSwitch;
        }

        public OverallCostItems allianceCommisionSwitch(Boolean allianceCommisionSwitch) {
            this.allianceCommisionSwitch = allianceCommisionSwitch;
            return this;
        }

        public void setAllianceCommisionSwitch(Boolean allianceCommisionSwitch) {
            this.allianceCommisionSwitch = allianceCommisionSwitch;
        }
    }

    // ========================================================================
    // 商品创意素材（multi_product_creative_list 的一项）
    // ========================================================================

    /** 商品创意素材信息，对应文档 multi_product_creative_list 的一项。 */
    public static class CreativeItem {
        /** 商品 id（product_id） */
        private Long productId;
        /** 抖音号 id（aweme_uid）；有号商家必填，无号商家不传 */
        private Long awemeUid;
        /** 创意类型（creative_type），默认 PROGRAMMATIC_CREATIVE（程序化创意） */
        private String creativeType;
        /** 抖音主页可见性（hide_in_aweme）：true 仅单次展示可见 / false 主页始终可见 */
        private Boolean hideInAweme;
        /** 视频素材列表（video_material） */
        private List<VideoMaterial> videoMaterial;
        /** 图片素材列表（image_material） */
        private List<ImageMaterial> imageMaterial;
        /** 标题素材（title_material），支持 0-30 个 */
        private List<TitleMaterial> titleMaterial;

        public Long getProductId() {
            return productId;
        }

        public CreativeItem productId(Long productId) {
            this.productId = productId;
            return this;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public Long getAwemeUid() {
            return awemeUid;
        }

        public CreativeItem awemeUid(Long awemeUid) {
            this.awemeUid = awemeUid;
            return this;
        }

        public void setAwemeUid(Long awemeUid) {
            this.awemeUid = awemeUid;
        }

        public String getCreativeType() {
            return creativeType;
        }

        public CreativeItem creativeType(String creativeType) {
            this.creativeType = creativeType;
            return this;
        }

        public void setCreativeType(String creativeType) {
            this.creativeType = creativeType;
        }

        public Boolean getHideInAweme() {
            return hideInAweme;
        }

        public CreativeItem hideInAweme(Boolean hideInAweme) {
            this.hideInAweme = hideInAweme;
            return this;
        }

        public void setHideInAweme(Boolean hideInAweme) {
            this.hideInAweme = hideInAweme;
        }

        public List<VideoMaterial> getVideoMaterial() {
            return videoMaterial;
        }

        public CreativeItem videoMaterial(List<VideoMaterial> videoMaterial) {
            this.videoMaterial = videoMaterial;
            return this;
        }

        public CreativeItem addVideoMaterial(VideoMaterial material) {
            if (this.videoMaterial == null) {
                this.videoMaterial = new ArrayList<>();
            }
            this.videoMaterial.add(material);
            return this;
        }

        public void setVideoMaterial(List<VideoMaterial> videoMaterial) {
            this.videoMaterial = videoMaterial;
        }

        public List<ImageMaterial> getImageMaterial() {
            return imageMaterial;
        }

        public CreativeItem imageMaterial(List<ImageMaterial> imageMaterial) {
            this.imageMaterial = imageMaterial;
            return this;
        }

        public void setImageMaterial(List<ImageMaterial> imageMaterial) {
            this.imageMaterial = imageMaterial;
        }

        public List<TitleMaterial> getTitleMaterial() {
            return titleMaterial;
        }

        public CreativeItem titleMaterial(List<TitleMaterial> titleMaterial) {
            this.titleMaterial = titleMaterial;
            return this;
        }

        public void setTitleMaterial(List<TitleMaterial> titleMaterial) {
            this.titleMaterial = titleMaterial;
        }
    }

    // ========================================================================
    // 素材
    // ========================================================================

    /** 视频素材，对应文档 video_material。 */
    public static class VideoMaterial {
        /** 素材类型（image_mode）：VIDEO_LARGE 横版视频 / VIDEO_VERTICAL 竖版视频，必填 */
        private String imageMode;
        /** 视频 ID（video_id），可通过【获取视频素材】接口获得 */
        private String videoId;
        /** 视频封面 ID（video_cover_id） */
        private String videoCoverId;
        /** 抖音视频 ID（aweme_item_id），投抖音主页视频时使用；与 video_id 二选一 */
        private Long awemeItemId;

        public String getImageMode() {
            return imageMode;
        }

        public VideoMaterial imageMode(String imageMode) {
            this.imageMode = imageMode;
            return this;
        }

        public void setImageMode(String imageMode) {
            this.imageMode = imageMode;
        }

        public String getVideoId() {
            return videoId;
        }

        public VideoMaterial videoId(String videoId) {
            this.videoId = videoId;
            return this;
        }

        public void setVideoId(String videoId) {
            this.videoId = videoId;
        }

        public String getVideoCoverId() {
            return videoCoverId;
        }

        public VideoMaterial videoCoverId(String videoCoverId) {
            this.videoCoverId = videoCoverId;
            return this;
        }

        public void setVideoCoverId(String videoCoverId) {
            this.videoCoverId = videoCoverId;
        }

        public Long getAwemeItemId() {
            return awemeItemId;
        }

        public VideoMaterial awemeItemId(Long awemeItemId) {
            this.awemeItemId = awemeItemId;
            return this;
        }

        public void setAwemeItemId(Long awemeItemId) {
            this.awemeItemId = awemeItemId;
        }
    }

    /** 图片素材，对应文档 image_material。 */
    public static class ImageMaterial {
        /** 素材类型（image_mode）：RECTANGLE 商品卡竖图 / SQUARE 方图 */
        private String imageMode;
        /** 图片 ID 列表（image_ids），目前仅支持上传一张，大小不超过 1.5M */
        private List<String> imageIds;

        public String getImageMode() {
            return imageMode;
        }

        public ImageMaterial imageMode(String imageMode) {
            this.imageMode = imageMode;
            return this;
        }

        public void setImageMode(String imageMode) {
            this.imageMode = imageMode;
        }

        public List<String> getImageIds() {
            return imageIds;
        }

        public ImageMaterial imageIds(List<String> imageIds) {
            this.imageIds = imageIds;
            return this;
        }

        public void setImageIds(List<String> imageIds) {
            this.imageIds = imageIds;
        }
    }

    /** 标题素材，对应文档 title_material。 */
    public static class TitleMaterial {
        /** 创意标题（title），10-110 字符，汉字算 2 位 */
        private String title;
        /** 标题类型（title_type）：COMMODITY_CARD 商品卡标题 / CUSTOM 自定义标题 */
        private String titleType;

        public String getTitle() {
            return title;
        }

        public TitleMaterial title(String title) {
            this.title = title;
            return this;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getTitleType() {
            return titleType;
        }

        public TitleMaterial titleType(String titleType) {
            this.titleType = titleType;
            return this;
        }

        public void setTitleType(String titleType) {
            this.titleType = titleType;
        }
    }
}
