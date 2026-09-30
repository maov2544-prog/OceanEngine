package com.example.oceanengine.ccreate_plan;

import com.bytedance.ads.ApiClient;
import com.example.oceanengine.client.ApiClients;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanOverallVideoCreateV10Api;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10DeliverySettingDeepExternalAction;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10DeliverySettingQcpxMode;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10DeliverySettingSmartBidType;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10DeliverySettingVideoScheduleType;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10MarketingGoal;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10MultiProductCreativeListCreativeType;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10MultiProductCreativeListImageMaterialImageMode;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10MultiProductCreativeListTitleMaterialTitleType;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10MultiProductCreativeListVideoMaterialImageMode;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10Request;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10RequestDeliverySetting;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10RequestDeliverySettingOverallCostItems;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInner;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerImageMaterialInner;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerTitleMaterialInner;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerVideoMaterialInner;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10Response;
import com.example.oceanengine.client.QianchuanTokenClient;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 千川「新建乘方商品投放计划」创建服务（基于 OceanEngine Java SDK）。
 *
 * <p>官方接口：POST https://api.oceanengine.com/open_api/v1.0/qianchuan/overall_video/create/
 * <br>SDK 入口：{@code QianchuanOverallVideoCreateV10Api#openApiV10QianchuanOverallVideoCreatePost}</p>
 *
 * <p>职责：入参校验 -&gt; 转 SDK 请求模型 -&gt; 注入 Access-Token -&gt; 调用 -&gt; 归一化返回。</p>
 *
 * <p>用法（参考 QianchuanPlanService 的写法）：</p>
 * <pre>
 * QianchuanOverallVideoCreateService service = new QianchuanOverallVideoCreateService();
 * CreatePlanResult result = service.create(param);
 * </pre>
 */
public final class QianchuanOverallVideoCreateService {

    private static final String BASE_PATH = "https://api.oceanengine.com";

    private final QianchuanOverallVideoCreateV10Api api;
    private final String accessToken;

    /** 自动通过 QianchuanTokenClient 获取 Access-Token。 */
    public QianchuanOverallVideoCreateService() throws Exception {
        this(QianchuanTokenClient.getAccessToken());
    }

    /**
     * 使用已经获取的 Access-Token 创建服务，便于批量创建时复用 Token。
     *
     * <p>accessToken 允许为空——只做本地校验/组装请求体（dry-run）时不需要 token，
     * 但此时调用 {@link #create} 会直接抛异常。</p>
     */
    public QianchuanOverallVideoCreateService(String accessToken) {
        this.accessToken = accessToken;
        ApiClient client = new ApiClient();
        client.setBasePath(BASE_PATH);
        ApiClients.configure(client);
        if (accessToken != null && !accessToken.isBlank()) {
            client.addDefaultHeader("Access-Token", accessToken);
        }
        this.api = new QianchuanOverallVideoCreateV10Api(client);
    }

    /**
     * 创建乘方商品投放计划。
     *
     * @param param 入参
     * @return 归一化结果，成功时 {@link CreatePlanResult#getAdId()} 为新建计划 id
     * @throws ApiException 网络或协议层异常
     */
    public CreatePlanResult create(OverallVideoCreateParam param) throws ApiException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException(
                    "当前实例没有 Access-Token，无法真实调用。请用 new QianchuanOverallVideoCreateService() "
                    + "自动取 token，或传入已有 token。");
        }
        QianchuanOverallVideoCreateV10Request request = prepare(param);
        QianchuanOverallVideoCreateV10Response response =
                api.openApiV10QianchuanOverallVideoCreatePost(request);
        return CreatePlanResult.from(response);
    }

    /**
     * 校验 + 组装，一步到位。
     *
     * <p>枚举取值非法也会在这一步抛出，不会留到请求发出时才报错。
     * 本地自检（打印请求体但不下单）时可以直接用这个方法。</p>
     *
     * @param param 入参
     * @return SDK 请求对象
     */
    public QianchuanOverallVideoCreateV10Request prepare(OverallVideoCreateParam param) {
        validate(param);
        return buildRequest(param);
    }

    /**
     * 只组装请求、不做校验。
     *
     * @param param 入参
     * @return SDK 请求对象
     */
    public QianchuanOverallVideoCreateV10Request buildRequest(OverallVideoCreateParam param) {
        QianchuanOverallVideoCreateV10Request request = new QianchuanOverallVideoCreateV10Request();
        request.setAdvertiserId(param.getAdvertiserId());
        // 平台会拒首尾空格（40000「计划名称不能包含首尾空格」），这里统一去掉
        request.setName(trimWithNotice(param.getName(), "计划名称"));
        request.setProductIds(param.getProductIds());
        request.setMarketingGoal(toEnum(param.getMarketingGoal(),
                QianchuanOverallVideoCreateV10MarketingGoal::fromValue, "marketing_goal"));
        request.setDeliverySetting(toDeliverySetting(param.getDeliverySetting()));
        request.setMultiProductCreativeList(toCreativeList(param));
        return request;
    }

    // ========================================================================
    // DTO -> SDK 模型
    // ========================================================================

    private QianchuanOverallVideoCreateV10RequestDeliverySetting toDeliverySetting(
            OverallVideoCreateParam.DeliverySetting src) {
        QianchuanOverallVideoCreateV10RequestDeliverySetting target =
                new QianchuanOverallVideoCreateV10RequestDeliverySetting();
        target.setBudget(src.getBudget());
        target.setRoi2Goal(src.getRoi2Goal());
        target.setQcpxMode(toEnum(src.getQcpxMode(),
                QianchuanOverallVideoCreateV10DeliverySettingQcpxMode::fromValue, "qcpx_mode"));
        target.setVideoScheduleType(toEnum(src.getVideoScheduleType(),
                QianchuanOverallVideoCreateV10DeliverySettingVideoScheduleType::fromValue,
                "video_schedule_type"));
        target.setSmartBidType(toEnum(src.getSmartBidType(),
                QianchuanOverallVideoCreateV10DeliverySettingSmartBidType::fromValue, "smart_bid_type"));
        target.setDeepExternalAction(toEnum(src.getDeepExternalAction(),
                QianchuanOverallVideoCreateV10DeliverySettingDeepExternalAction::fromValue,
                "deep_external_action"));
        target.setStartTime(src.getStartTime());
        target.setEndTime(src.getEndTime());
        target.setEnableAigcCreative(src.getEnableAigcCreative());
        target.setNoAwemeId(src.getNoAwemeId());
        target.setOverallCostItems(toOverallCostItems(src.getOverallCostItems()));
        return target;
    }

    /** 乘方-成本项设置（星选素材投放 / 达人佣金出价开关）。 */
    private QianchuanOverallVideoCreateV10RequestDeliverySettingOverallCostItems toOverallCostItems(
            OverallVideoCreateParam.OverallCostItems src) {
        if (src == null) {
            return null;
        }
        QianchuanOverallVideoCreateV10RequestDeliverySettingOverallCostItems target =
                new QianchuanOverallVideoCreateV10RequestDeliverySettingOverallCostItems();
        target.setStarTaskMaterialSwitch(src.getStarTaskMaterialSwitch());
        target.setAllianceCommisionSwitch(src.getAllianceCommisionSwitch());
        return target;
    }

    private List<QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInner> toCreativeList(
            OverallVideoCreateParam param) {
        List<QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInner> list = new ArrayList<>();
        for (OverallVideoCreateParam.CreativeItem item : param.getMultiProductCreativeList()) {
            QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInner inner =
                    new QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInner();
            inner.setProductId(item.getProductId());
            inner.setAwemeUid(item.getAwemeUid());
            inner.setCreativeType(toEnum(item.getCreativeType(),
                    QianchuanOverallVideoCreateV10MultiProductCreativeListCreativeType::fromValue,
                    "creative_type"));
            inner.setHideInAweme(item.getHideInAweme());
            inner.setVideoMaterial(toVideoMaterial(item.getVideoMaterial()));
            inner.setImageMaterial(toImageMaterial(item.getImageMaterial()));
            inner.setTitleMaterial(toTitleMaterial(item.getTitleMaterial()));
            list.add(inner);
        }
        return list;
    }

    private List<QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerVideoMaterialInner>
            toVideoMaterial(List<OverallVideoCreateParam.VideoMaterial> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        List<QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerVideoMaterialInner> list =
                new ArrayList<>(src.size());
        for (OverallVideoCreateParam.VideoMaterial item : src) {
            QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerVideoMaterialInner inner =
                    new QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerVideoMaterialInner();
            inner.setImageMode(toEnum(item.getImageMode(),
                    QianchuanOverallVideoCreateV10MultiProductCreativeListVideoMaterialImageMode::fromValue,
                    "video_material.image_mode"));
            inner.setVideoId(item.getVideoId());
            inner.setVideoCoverId(item.getVideoCoverId());
            inner.setAwemeItemId(item.getAwemeItemId());
            list.add(inner);
        }
        return list;
    }

    private List<QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerImageMaterialInner>
            toImageMaterial(List<OverallVideoCreateParam.ImageMaterial> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        List<QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerImageMaterialInner> list =
                new ArrayList<>(src.size());
        for (OverallVideoCreateParam.ImageMaterial item : src) {
            QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerImageMaterialInner inner =
                    new QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerImageMaterialInner();
            inner.setImageMode(toEnum(item.getImageMode(),
                    QianchuanOverallVideoCreateV10MultiProductCreativeListImageMaterialImageMode::fromValue,
                    "image_material.image_mode"));
            inner.setImageIds(item.getImageIds());
            list.add(inner);
        }
        return list;
    }

    private List<QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerTitleMaterialInner>
            toTitleMaterial(List<OverallVideoCreateParam.TitleMaterial> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        List<QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerTitleMaterialInner> list =
                new ArrayList<>(src.size());
        for (OverallVideoCreateParam.TitleMaterial item : src) {
            QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerTitleMaterialInner inner =
                    new QianchuanOverallVideoCreateV10RequestMultiProductCreativeListInnerTitleMaterialInner();
            inner.setTitle(trimWithNotice(item.getTitle(), "创意标题"));
            inner.setTitleType(toEnum(item.getTitleType(),
                    QianchuanOverallVideoCreateV10MultiProductCreativeListTitleMaterialTitleType::fromValue,
                    "title_material.title_type"));
            list.add(inner);
        }
        return list;
    }

    /** 文档取值 -> SDK 强类型枚举；空值返回 null（不传该字段，用平台默认值）。 */
    private static <T> T toEnum(String value, Function<String, T> converter, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return converter.apply(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("字段 " + fieldName + " 取值非法：" + value);
        }
    }

    // ========================================================================
    // 校验（按官方文档约束，避免把必然失败的请求打到线上）
    // ========================================================================

    /** 按官方文档的约束做前置校验。 */
    public void validate(OverallVideoCreateParam param) {
        require(param != null, "入参不能为空");
        require(param.getAdvertiserId() != null, "advertiser_id 必填");
        require(notBlank(param.getName()), "name 必填");
        // 长度按 trim 后算：首尾空格平台不认，buildRequest 里会自动去掉
        int nameLength = weightedLength(param.getName().trim());
        require(nameLength >= 1 && nameLength <= 100,
                "name 长度需在 1-100 之间（汉字算 2 位），当前 " + nameLength);
        require(param.getProductIds() != null && !param.getProductIds().isEmpty(),
                "product_ids 必填");

        OverallVideoCreateParam.DeliverySetting setting = param.getDeliverySetting();
        require(setting != null, "delivery_setting 必填");
        require(setting.getBudget() != null, "delivery_setting.budget 必填");
        require(setting.getRoi2Goal() != null, "delivery_setting.roi2_goal 必填");
        if ("SCHEDULE_START_END".equals(setting.getVideoScheduleType())) {
            require(notBlank(setting.getStartTime()),
                    "video_schedule_type=SCHEDULE_START_END 时 start_time 必填");
            require(notBlank(setting.getEndTime()),
                    "video_schedule_type=SCHEDULE_START_END 时 end_time 必填");
        }

        List<OverallVideoCreateParam.CreativeItem> creativeList = param.getMultiProductCreativeList();
        require(creativeList != null && !creativeList.isEmpty(),
                "multi_product_creative_list 必填");
        boolean noAwemeId = Boolean.TRUE.equals(setting.getNoAwemeId());

        for (int i = 0; i < creativeList.size(); i++) {
            OverallVideoCreateParam.CreativeItem item = creativeList.get(i);
            String prefix = "multi_product_creative_list[" + i + "].";
            require(item.getProductId() != null, prefix + "product_id 必填");
            if (!noAwemeId) {
                require(item.getAwemeUid() != null,
                        prefix + "aweme_uid 必填（有号商家）；无号商家请设置 no_aweme_id=true");
            }

            boolean hasVideo = item.getVideoMaterial() != null && !item.getVideoMaterial().isEmpty();
            boolean hasImage = item.getImageMaterial() != null && !item.getImageMaterial().isEmpty();
            require(hasVideo || hasImage, prefix + "至少要有一个 video_material 或 image_material");

            // 素材全为抖音主页视频时，不能加标题；否则至少要有一个标题
            boolean allAwemeHomeVideo = hasVideo && !hasImage;
            if (hasVideo) {
                for (int j = 0; j < item.getVideoMaterial().size(); j++) {
                    OverallVideoCreateParam.VideoMaterial video = item.getVideoMaterial().get(j);
                    String vPrefix = prefix + "video_material[" + j + "].";
                    require(notBlank(video.getImageMode()), vPrefix + "image_mode 必填");
                    boolean isHomeVideo = video.getAwemeItemId() != null;
                    require(isHomeVideo || notBlank(video.getVideoId()),
                            vPrefix + "video_id 与 aweme_item_id 至少填一个");
                    if (!isHomeVideo) {
                        allAwemeHomeVideo = false;
                    }
                }
            }

            boolean hasTitle = item.getTitleMaterial() != null && !item.getTitleMaterial().isEmpty();
            if (allAwemeHomeVideo) {
                require(!hasTitle, prefix + "素材全为抖音主页视频时不能添加 title_material");
            } else {
                require(hasTitle, prefix + "存在非抖音主页视频/图片素材时，至少要有一个 title_material");
            }
        }
    }

    /**
     * 去掉字符串首尾空白，并打印提示。
     *
     * <p>平台对计划名称会直接拒（{@code 40000 计划名称不能包含首尾空格}），
     * 标题同理，所以统一在这里规范化，避免白白发一次必然失败的请求。</p>
     *
     * @param raw        原始值，null 原样返回
     * @param fieldLabel 出错时打印用的中文名，如「计划名称」
     */
    private static String trimWithNotice(String raw, String fieldLabel) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (!trimmed.equals(raw)) {
            System.out.println("⚠ " + fieldLabel + "首尾有空格，已自动去掉："
                    + "「" + raw + "」→「" + trimmed + "」");
        }
        return trimmed;
    }

    /** 按平台规则算长度：汉字算 2 位，其余算 1 位。 */
    private static int weightedLength(String text) {
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            length += isCjk(text.charAt(i)) ? 2 : 1;
        }
        return length;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
