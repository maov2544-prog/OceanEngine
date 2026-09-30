package com.example.oceanengine.add_footage;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanUniPromotionAdMaterialAddV10Api;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListImageMaterialImageMode;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListTitleMaterialTitleType;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListVideoMaterialImageMode;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10ProgrammaticCreativeMediaListVideoMaterialImageMode;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10Request;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerCarouselMaterialInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerImageMaterialInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerTitleMaterialInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerVideoMaterialInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaList;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaListTitleMaterialInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaListVideoMaterialInner;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10Response;
import com.example.oceanengine.client.ApiClients;
import com.example.oceanengine.client.QianchuanTokenClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * ============================================================================
 *  千川「添加乘方&全域投放计划下素材」
 * ============================================================================
 *  接口：POST https://api.oceanengine.com/open_api/v1.0/qianchuan/uni_promotion/ad/material/add/
 *  文档：https://open.oceanengine.com/labels/12/docs/1835232814536707
 *
 *  <p><b>增量添加</b>：只传要新增的素材，计划里原有素材不受影响。
 *  要给已有计划补素材就用这个，别用 {@code overall_video/update}（那是全量更新）。</p>
 *
 *  <p>两步用法（推荐，能先看请求体再决定是否真发）：</p>
 *  <pre>
 *  AddFootageService service = new AddFootageService();          // 自动取 token
 *  QianchuanUniPromotionAdMaterialAddV10Request req = service.prepare(param);
 *  System.out.println(req.toJson());                             // 先看一眼
 *  AddFootageService.Result r = service.add(param);              // 真发
 *  </pre>
 */
public class AddFootageService {

    /** SDK 默认就是 api.oceanengine.com，这里显式写出，避免以后被改默认值坑到 */
    public static final String BASE_PATH = "https://api.oceanengine.com";

    private final String accessToken;
    private final QianchuanUniPromotionAdMaterialAddV10Api api;

    /** 自动从 {@link QianchuanTokenClient} 取 Access Token */
    public AddFootageService() throws Exception {
        this(QianchuanTokenClient.getAccessToken());
    }

    /**
     * @param accessToken 千川 Access Token；只做本地自检（{@link #prepare}）时可传 null
     */
    public AddFootageService(String accessToken) {
        this.accessToken = accessToken;
        ApiClient client = new ApiClient();
        client.setBasePath(BASE_PATH);
        ApiClients.configure(client);
        if (accessToken != null && !accessToken.isBlank()) {
            client.addDefaultHeader("Access-Token", accessToken);
        }
        this.api = new QianchuanUniPromotionAdMaterialAddV10Api(client);
    }

    // ========================================================================
    // 对外方法
    // ========================================================================

    /**
     * 校验 + 组装请求体，<b>不发请求</b>（本地自检用，不需要 token）。
     *
     * @throws IllegalArgumentException 参数不合法
     */
    public QianchuanUniPromotionAdMaterialAddV10Request prepare(AddFootageParam param) {
        validate(param);
        return buildRequest(param);
    }

    /**
     * 真实调用接口添加素材。
     *
     * @throws ApiException 网络或协议层异常
     */
    public Result add(AddFootageParam param) throws ApiException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException(
                    "当前实例没有 Access-Token，无法真实调用。请用 new AddFootageService() 自动取 token。");
        }
        QianchuanUniPromotionAdMaterialAddV10Response response =
                api.openApiV10QianchuanUniPromotionAdMaterialAddPost(buildRequest(validate(param)));
        return Result.of(response);
    }

    /** 归一化结果。 */
    public record Result(boolean success, Long code, String message, String requestId) {

        static Result of(QianchuanUniPromotionAdMaterialAddV10Response r) {
            Long code = r == null ? null : r.getCode();
            return new Result(code != null && code == 0L,
                    code,
                    r == null ? null : r.getMessage(),
                    r == null ? null : r.getRequestId());
        }

        @Override
        public String toString() {
            return success
                    ? "✅ 添加成功 requestId=" + requestId
                    : "❌ 添加失败 code=" + code + " message=" + message + " requestId=" + requestId;
        }
    }

    // ========================================================================
    // 校验
    // ========================================================================

    private AddFootageParam validate(AddFootageParam p) {
        if (p == null) {
            throw new IllegalArgumentException("param 不能为空");
        }
        if (p.getAdvertiserId() == null) {
            throw new IllegalArgumentException("advertiser_id（千川客户id）必填");
        }
        if (p.getAdId() == null) {
            throw new IllegalArgumentException("ad_id（需要添加素材的计划id）必填");
        }

        boolean hasProductCreative = !p.getMultiProductCreativeList().isEmpty();
        boolean hasProgrammatic = p.getProgrammaticCreativeMediaList() != null
                && (!p.getProgrammaticCreativeMediaList().getVideoMaterial().isEmpty()
                || !p.getProgrammaticCreativeMediaList().getTitleMaterial().isEmpty());
        if (!hasProductCreative && !hasProgrammatic) {
            throw new IllegalArgumentException(
                    "没有任何素材：multi_product_creative_list（商品计划用）和 "
                            + "programmatic_creative_media_list（直播计划用）至少要填一个。");
        }

        for (AddFootageParam.Creative c : p.getMultiProductCreativeList()) {
            validateCreative(c);
        }
        return p;
    }

    private void validateCreative(AddFootageParam.Creative c) {
        if (c.getProductId() == null) {
            throw new IllegalArgumentException("multi_product_creative_list 里 product_id 必填");
        }

        List<AddFootageParam.Video> videos = c.getVideoMaterial();
        boolean hasImageOrCarousel = !c.getImageMaterial().isEmpty()
                || !c.getCarouselMaterial().isEmpty();
        boolean hasLibraryVideo = false;

        for (AddFootageParam.Video v : videos) {
            if (blank(v.videoId()) && v.awemeItemId() == null) {
                throw new IllegalArgumentException(
                        "商品 " + c.getProductId() + " 有一条视频素材既没填 video_id 也没填 aweme_item_id");
            }
            if (!blank(v.videoId())) {
                hasLibraryVideo = true;
            }
        }

        if (videos.isEmpty() && !hasImageOrCarousel) {
            throw new IllegalArgumentException("商品 " + c.getProductId() + " 没有填任何素材");
        }

        boolean needTitle = hasLibraryVideo || hasImageOrCarousel;
        boolean hasTitle = !c.getTitleMaterial().isEmpty();

        if (needTitle && !hasTitle) {
            throw new IllegalArgumentException(
                    "商品 " + c.getProductId() + " 存在非抖音主页视频/图片，必须至少填一条标题，否则素材不生效");
        }
        if (!needTitle && hasTitle) {
            throw new IllegalArgumentException(
                    "商品 " + c.getProductId() + " 的素材全是抖音主页视频，不支持设置标题（title_material 要留空）");
        }
    }

    // ========================================================================
    // DTO -> SDK 模型
    // ========================================================================

    private QianchuanUniPromotionAdMaterialAddV10Request buildRequest(AddFootageParam p) {
        QianchuanUniPromotionAdMaterialAddV10Request request =
                new QianchuanUniPromotionAdMaterialAddV10Request();
        request.setAdvertiserId(p.getAdvertiserId());
        request.setAdId(p.getAdId());

        if (!p.getMultiProductCreativeList().isEmpty()) {
            List<QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInner> list =
                    new ArrayList<>(p.getMultiProductCreativeList().size());
            for (AddFootageParam.Creative c : p.getMultiProductCreativeList()) {
                list.add(toCreative(c));
            }
            request.setMultiProductCreativeList(list);
        }

        if (p.getProgrammaticCreativeMediaList() != null) {
            request.setProgrammaticCreativeMediaList(
                    toProgrammatic(p.getProgrammaticCreativeMediaList()));
        }
        return request;
    }

    private QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInner toCreative(
            AddFootageParam.Creative c) {

        QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInner inner =
                new QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInner();
        inner.setProductId(c.getProductId());
        inner.setAwemeUid(c.getAwemeUid());

        if (!c.getVideoMaterial().isEmpty()) {
            List<QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerVideoMaterialInner> videos =
                    new ArrayList<>(c.getVideoMaterial().size());
            for (AddFootageParam.Video v : c.getVideoMaterial()) {
                videos.add(new QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerVideoMaterialInner()
                        .videoId(blank(v.videoId()) ? null : v.videoId().trim())
                        .awemeItemId(v.awemeItemId())
                        .videoCoverId(blank(v.videoCoverId()) ? null : v.videoCoverId().trim())
                        .imageMode(toEnum(v.imageMode(),
                                QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListVideoMaterialImageMode::fromValue,
                                "video_material.image_mode")));
            }
            inner.setVideoMaterial(videos);
        }

        if (!c.getImageMaterial().isEmpty()) {
            List<QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerImageMaterialInner> images =
                    new ArrayList<>(c.getImageMaterial().size());
            for (AddFootageParam.Image i : c.getImageMaterial()) {
                images.add(new QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerImageMaterialInner()
                        .imageIds(i.imageIds())
                        .imageMode(toEnum(i.imageMode(),
                                QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListImageMaterialImageMode::fromValue,
                                "image_material.image_mode")));
            }
            inner.setImageMaterial(images);
        }

        if (!c.getTitleMaterial().isEmpty()) {
            List<QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerTitleMaterialInner> titles =
                    new ArrayList<>(c.getTitleMaterial().size());
            for (AddFootageParam.Title t : c.getTitleMaterial()) {
                // 平台会拒首尾空格，这里统一去掉
                titles.add(new QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerTitleMaterialInner()
                        .title(blank(t.title()) ? null : t.title().trim())
                        .titleType(toEnum(t.titleType(),
                                QianchuanUniPromotionAdMaterialAddV10MultiProductCreativeListTitleMaterialTitleType::fromValue,
                                "title_material.title_type")));
            }
            inner.setTitleMaterial(titles);
        }

        if (!c.getCarouselMaterial().isEmpty()) {
            List<QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerCarouselMaterialInner> carousels =
                    new ArrayList<>(c.getCarouselMaterial().size());
            for (AddFootageParam.Carousel x : c.getCarouselMaterial()) {
                carousels.add(new QianchuanUniPromotionAdMaterialAddV10RequestMultiProductCreativeListInnerCarouselMaterialInner()
                        .awemeCarouselId(x.awemeCarouselId())
                        .carouselId(x.carouselId()));
            }
            inner.setCarouselMaterial(carousels);
        }
        return inner;
    }

    private QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaList toProgrammatic(
            AddFootageParam.Programmatic src) {

        QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaList target =
                new QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaList();

        if (!src.getVideoMaterial().isEmpty()) {
            List<QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaListVideoMaterialInner> videos =
                    new ArrayList<>(src.getVideoMaterial().size());
            for (AddFootageParam.Video v : src.getVideoMaterial()) {
                videos.add(new QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaListVideoMaterialInner()
                        .videoId(blank(v.videoId()) ? null : v.videoId().trim())
                        .awemeItemId(v.awemeItemId())
                        .videoCoverId(blank(v.videoCoverId()) ? null : v.videoCoverId().trim())
                        .imageMode(toEnum(v.imageMode(),
                                QianchuanUniPromotionAdMaterialAddV10ProgrammaticCreativeMediaListVideoMaterialImageMode::fromValue,
                                "programmatic.video_material.image_mode")));
            }
            target.setVideoMaterial(videos);
        }

        if (!src.getTitleMaterial().isEmpty()) {
            List<QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaListTitleMaterialInner> titles =
                    new ArrayList<>(src.getTitleMaterial().size());
            for (String t : src.getTitleMaterial()) {
                titles.add(new QianchuanUniPromotionAdMaterialAddV10RequestProgrammaticCreativeMediaListTitleMaterialInner()
                        .title(t.trim()));
            }
            target.setTitleMaterial(titles);
        }
        return target;
    }

    // ========================================================================
    // 小工具
    // ========================================================================

    /**
     * 字符串枚举转换：非法取值立刻抛错，不等到请求发出才发现。
     *
     * <p>SDK 生成的 {@code fromValue} 对未知取值会直接抛
     * {@code IllegalArgumentException: Unexpected value 'xxx'}，这里包一层，
     * 把字段名也带上，报错时能直接看出是哪个字段填错了。</p>
     */
    private static <E> E toEnum(String raw, Function<String, E> parser, String label) {
        if (blank(raw)) {
            return null;
        }
        try {
            E value = parser.apply(raw.trim());
            if (value == null) {
                throw new IllegalArgumentException("未知取值");
            }
            return value;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " 取值非法：" + raw);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
