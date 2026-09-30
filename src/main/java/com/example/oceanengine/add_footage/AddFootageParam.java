package com.example.oceanengine.add_footage;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 *  「添加乘方&全域投放计划下素材」入参
 * ============================================================================
 *  接口：POST /open_api/v1.0/qianchuan/uni_promotion/ad/material/add/
 *  文档：https://open.oceanengine.com/labels/12/docs/1835232814536707
 *
 *  <p><b>这是【增量添加】接口</b>：只传要新增的素材，计划里原有素材不受影响。</p>
 *  <p>对比 {@code overall_video/update} 是<b>全量更新</b> —— 那条路只传新素材会把原有素材抹掉，
 *  要加素材就走本接口，不要走编辑接口。</p>
 *
 *  <p>两个并列的创意容器，按计划类型二选一（也可都传）：</p>
 *  <ul>
 *    <li>{@link #creative(Creative)} → {@code multi_product_creative_list}
 *        —— 商品维度创意，<b>乘方商品 / 全域商品计划用这个</b>。</li>
 *    <li>{@link #programmatic(Programmatic)} → {@code programmatic_creative_media_list}
 *        —— 直播全域创意，直播计划用。</li>
 *  </ul>
 *
 *  <p>用法：</p>
 *  <pre>
 *  AddFootageParam param = new AddFootageParam()
 *          .advertiserId(1823379540880396L)
 *          .adId(1234567890L)
 *          .creative(new AddFootageParam.Creative()
 *                  .productId(3518517808038344366L)
 *                  .awemeUid(3660744842026395L)
 *                  .videos(AddFootageParam.Video.awemeItem(7685287409449264198L)));
 *  </pre>
 */
public class AddFootageParam {

    /** 千川客户 id（advertiser_id），必填 */
    private Long advertiserId;

    /** 需要添加素材的计划 id（ad_id），必填 */
    private Long adId;

    /** 商品维度创意：一个商品一条 */
    private final List<Creative> multiProductCreativeList = new ArrayList<>();

    /** 直播全域创意（商品投放留空即可） */
    private Programmatic programmaticCreativeMediaList;

    public AddFootageParam advertiserId(Long value) {
        this.advertiserId = value;
        return this;
    }

    public AddFootageParam adId(Long value) {
        this.adId = value;
        return this;
    }

    public AddFootageParam creative(Creative value) {
        if (value != null) {
            this.multiProductCreativeList.add(value);
        }
        return this;
    }

    public AddFootageParam creatives(List<Creative> values) {
        if (values != null) {
            this.multiProductCreativeList.addAll(values);
        }
        return this;
    }

    public AddFootageParam programmatic(Programmatic value) {
        this.programmaticCreativeMediaList = value;
        return this;
    }

    public Long getAdvertiserId() {
        return advertiserId;
    }

    public Long getAdId() {
        return adId;
    }

    public List<Creative> getMultiProductCreativeList() {
        return multiProductCreativeList;
    }

    public Programmatic getProgrammaticCreativeMediaList() {
        return programmaticCreativeMediaList;
    }

    // ========================================================================
    // 商品维度创意（multi_product_creative_list）
    // ========================================================================

    /** 一个商品下的全部新增素材。 */
    public static class Creative {

        /** 商品 id，必填 */
        private Long productId;

        /** 抖音号 id，<b>多号场景必填</b> */
        private Long awemeUid;

        private final List<Video> videoMaterial = new ArrayList<>();
        private final List<Image> imageMaterial = new ArrayList<>();
        private final List<Title> titleMaterial = new ArrayList<>();
        private final List<Carousel> carouselMaterial = new ArrayList<>();

        public Creative productId(Long value) {
            this.productId = value;
            return this;
        }

        public Creative awemeUid(Long value) {
            this.awemeUid = value;
            return this;
        }

        public Creative videos(Video... values) {
            for (Video v : values) {
                if (v != null) {
                    videoMaterial.add(v);
                }
            }
            return this;
        }

        public Creative images(Image... values) {
            for (Image v : values) {
                if (v != null) {
                    imageMaterial.add(v);
                }
            }
            return this;
        }

        public Creative titles(Title... values) {
            for (Title v : values) {
                if (v != null) {
                    titleMaterial.add(v);
                }
            }
            return this;
        }

        public Creative carousels(Carousel... values) {
            for (Carousel v : values) {
                if (v != null) {
                    carouselMaterial.add(v);
                }
            }
            return this;
        }

        public Long getProductId() {
            return productId;
        }

        public Long getAwemeUid() {
            return awemeUid;
        }

        public List<Video> getVideoMaterial() {
            return videoMaterial;
        }

        public List<Image> getImageMaterial() {
            return imageMaterial;
        }

        public List<Title> getTitleMaterial() {
            return titleMaterial;
        }

        public List<Carousel> getCarouselMaterial() {
            return carouselMaterial;
        }
    }

    // ========================================================================
    // 素材叶子类型
    // ========================================================================

    /**
     * 视频素材：{@code videoId} 与 {@code awemeItemId} <b>二选一</b>。
     *
     * <ul>
     *   <li>{@code awemeItemId}（抖音视频ID）→ 平台判为<b>抖音主页视频</b>，
     *       此时<b>不能</b>带 {@link Title}。</li>
     *   <li>{@code videoId}（千川素材库视频id，形如 {@code v0200fg10000...}）
     *       → 判为<b>非主页视频</b>，<b>必须</b>至少带一条 {@link Title}。</li>
     * </ul>
     *
     * @param imageMode 素材类型：{@code VIDEO_VERTICAL} 竖版（默认）/ {@code VIDEO_LARGE} 横版
     */
    public record Video(String videoId, Long awemeItemId, String videoCoverId, String imageMode) {

        /** 抖音主页视频（抖音视频ID） */
        public static Video awemeItem(long awemeItemId) {
            return new Video(null, awemeItemId, null, "VIDEO_VERTICAL");
        }

        /** 素材库视频（千川 video_id） */
        public static Video videoId(String videoId) {
            return new Video(videoId, null, null, "VIDEO_VERTICAL");
        }

        public Video cover(String videoCoverId) {
            return new Video(videoId, awemeItemId, videoCoverId, imageMode);
        }

        public Video imageMode(String imageMode) {
            return new Video(videoId, awemeItemId, videoCoverId, imageMode);
        }
    }

    /**
     * 图片素材。
     *
     * @param imageMode 素材类型，可选值：{@code SQUARE} 商品卡方图
     */
    public record Image(String imageMode, List<String> imageIds) {

        public static Image square(String... imageIds) {
            return new Image("SQUARE", List.of(imageIds));
        }
    }

    /**
     * 创意标题。
     *
     * <p>规则（文档原文）：</p>
     * <ul>
     *   <li>素材<b>全是</b>抖音主页视频 → <b>不支持</b>设置 title</li>
     *   <li>存在非抖音主页视频/图片 → <b>至少</b>一条 title，否则素材不生效</li>
     * </ul>
     * <p>长度 10~110 字符，汉字算 2 个字符。支持动态词包 {@code XXX{词包名}XXX}（最多 2 个）。</p>
     *
     * @param titleType {@code CUSTOM} 自定义标题（默认）/ {@code COMMODITY_CARD} 商品卡标题
     */
    public record Title(String title, String titleType) {

        public static Title custom(String title) {
            return new Title(title, "CUSTOM");
        }

        public static Title commodityCard(String title) {
            return new Title(title, "COMMODITY_CARD");
        }
    }

    /**
     * 图文素材：{@code awemeCarouselId} 与 {@code carouselId} 二选一。
     * 文档注明「使用 {@code aweme_carousel_id} 时会忽略 {@code carousel_id}」。
     */
    public record Carousel(Long awemeCarouselId, Long carouselId) {

        /** 抖音图文id */
        public static Carousel aweme(long awemeCarouselId) {
            return new Carousel(awemeCarouselId, null);
        }

        /** 素材库图文id */
        public static Carousel library(long carouselId) {
            return new Carousel(null, carouselId);
        }
    }

    // ========================================================================
    // 直播全域创意（programmatic_creative_media_list）
    // ========================================================================

    /** 直播全域创意相关设置。商品投放场景留空。 */
    public static class Programmatic {

        private final List<Video> videoMaterial = new ArrayList<>();
        private final List<String> titleMaterial = new ArrayList<>();

        public Programmatic videos(Video... values) {
            for (Video v : values) {
                if (v != null) {
                    videoMaterial.add(v);
                }
            }
            return this;
        }

        public Programmatic titles(String... values) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    titleMaterial.add(v.trim());
                }
            }
            return this;
        }

        public List<Video> getVideoMaterial() {
            return videoMaterial;
        }

        public List<String> getTitleMaterial() {
            return titleMaterial;
        }
    }
}
