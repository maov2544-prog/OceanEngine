package com.example.oceanengine.ccreate_plan;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanUniAwemeAuthorizedGetV10Api;
import com.bytedance.ads.api.QianchuanVideoGetV10Api;
import com.bytedance.ads.model.QianchuanOverallVideoCreateV10Request;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10Response;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseData;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner;
import com.bytedance.ads.model.QianchuanVideoGetV10Filtering;
import com.bytedance.ads.model.QianchuanVideoGetV10FilteringImageMode;
import com.bytedance.ads.model.QianchuanVideoGetV10FilteringSources;
import com.bytedance.ads.model.QianchuanVideoGetV10Response;
import com.bytedance.ads.model.QianchuanVideoGetV10ResponseData;
import com.bytedance.ads.model.QianchuanVideoGetV10ResponseDataListInner;
import com.example.oceanengine.client.QianchuanTokenClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ============================================================================
 *  千川「新建乘方商品投放计划」调用示例
 * ============================================================================
 *  接口：POST https://api.oceanengine.com/open_api/v1.0/qianchuan/overall_video/create/
 *  文档：https://open.oceanengine.com/labels/12/docs/1872485038037385
 *
 *  调用流程：
 *    ⓪ 自动取 Token（复用 client.QianchuanTokenClient）
 *    ① 组装入参（下面「配置区」）
 *    ② 本地校验 + 组装请求体
 *    ③ 打印请求体（DRY_RUN=true）或真实创建计划（DRY_RUN=false）
 *
 *  ⚠ 参数全部写在下面的「配置区」，其他地方不用改。
 * ============================================================================
 */
public class CreatePlanDemo {

    // ========================================================================
    // 配置区：只改这里
    // ========================================================================

    /**
     * true  = 只组装并打印请求体，不真的创建计划（默认，先跑通再用）
     * false = 真实调用接口创建计划
     * 也可命令行传 --create 强制真实调用
     */
    private static final boolean DRY_RUN = true;

    /** 投放账号 id（advertiser_id）—— 后台右上角头像旁那个 ID */
    private static final long ADVERTISER_ID = 1778533513439244L;

    /** 投放计划名称（name），1-100 字符，1 个汉字算 2 位 */
    private static final String PLAN_NAME = "熙熙丫-澳兰黛青少年洗发水-0929";

    /**
     * 商品 id 列表（product_ids）—— 抖音商品ID（19位数字），不是你自己系统的商品编号。
     *
     * <p>三种拿法：</p>
     * <ol>
     *   <li>千川后台 →「商品管理」里复制商品ID（截图里商品信息卡片上的 ID）</li>
     *   <li>接口【获取商品列表】：{@code QianchuanUniPromotionProductGetV10Api}，取返回项的 {@code id}</li>
     *   <li>已有计划里查：{@code PromotionPlanDTO.ProductInfo.productId}（即返回的 product_id 字段）</li>
     * </ol>
     *
     * <p>下面这个值来自你后台截图里的商品「【控油去屑】澳兰黛青少年洗发水…」。</p>
     */
    private static final List<Long> PRODUCT_IDS = List.of(3836535969062977549L);

    /**
     * 抖音号 id（aweme_uid）—— <b>必须是纯数字 uid</b>，不能填后台那个「抖音号」字符串。
     *
     * <p>官方文档字段定义：{@code aweme_uid  number  抖音号id}，类型是 number。
     * 后台「投放抖音号」卡片上写的 ID: Lili0333 是<b>抖音号</b>（{@code aweme_show_id}，string），
     * 填进去会参数校验失败。接口要的是 {@code aweme_id}（Long）。</p>
     *
     * <p>先跑一次下面的命令，从输出第一列复制数字 uid 填到这里：</p>
     * <pre>
     * java -cp "target/classes;$(cat target/cp.txt)" \
     *      com.example.oceanengine.ccreate_plan.CreatePlanDemo --list-aweme
     * </pre>
     *
     * <p>无号投商城时把 NO_AWEME_ID 设为 true，这里保持 null。</p>
     */
    private static final Long AWEME_UID = null;

    /** 是否为乘方无号投商城（no_aweme_id） */
    private static final boolean NO_AWEME_ID = false;

    // ---------------------------- 投放设置 delivery_setting ---------------

    /** 预算（budget） */
    private static final double BUDGET = 20000D;

    /** 支付 ROI 目标（roi2_goal），最多两位小数 */
    private static final double ROI2_GOAL = 2D;

    /** 是否开启智能优惠券（qcpx_mode）：QCPX_MODE_ON / QCPX_MODE_OFF / QCPX_MODE_DEFAULT */
    private static final String QCPX_MODE = "QCPX_MODE_ON";

    /** 投放时间方式（video_schedule_type）：SCHEDULE_FROM_NOW 从今天起长期投放 / SCHEDULE_START_END 设置起止日期 */
    private static final String VIDEO_SCHEDULE_TYPE = "SCHEDULE_FROM_NOW";

    /** 出价方式（smart_bid_type），乘方当前仅支持控成本投放 */
    private static final String SMART_BID_TYPE = "SMART_BID_CUSTOM";

    /** 开始/结束时间（video_schedule_type=SCHEDULE_START_END 时才需要），格式 yyyy-MM-dd */
    private static final String START_TIME = "";
    private static final String END_TIME = "";

    // ---------------------------- 营销服务 overall_cost_items ---------------

    /**
     * 千川星选素材投放开关（后台「营销服务」→「千川星选素材投放」）。
     * 需满足条件后再生效：① 营销服务选择千川星选素材投放 ② 至少需要一个星选任务商品成交官方自运营抖音号。
     */
    private static final boolean STAR_TASK_MATERIAL_SWITCH = false;

    /**
     * 达人佣金出价开关（后台「营销服务」→「达人佣金出价」）。
     * 仅白名单用户可用，且要求 marketing_goal=VIDEO_PROM_GOODS、非无号。
     */
    private static final boolean ALLIANCE_COMMISION_SWITCH = false;

    /** AIGC 动态创意开关（后台「AIGC 动态创意」） */
    private static final boolean ENABLE_AIGC_CREATIVE = false;

    // ---------------------------- 视频素材 video_material ----------------
    //
    //  接口里 video_material 是**数组**：后台显示「共 N 条自选素材」，这里就写 N 条。
    //  每条素材各自带 image_mode，所以横版/竖版可以混着放。
    //
    //  三种来源，按实际情况选：
    //    ① 自选素材 / 素材库视频  → new VideoItem("video_id", "VIDEO_VERTICAL")
    //       video_id 由【获取视频素材】接口拿
    //    ② 抖音主页视频          → VideoItem.fromAweme(1234567890123456789L)
    //       参数是 aweme_item_id（抖音视频ID）
    //    ③ 需要指定封面时        → new VideoItem(videoId, imageMode).cover("cover_id")
    //
    //  ⚠ 标题规则（文档原文）：
    //    · 素材全是抖音主页视频 → 不能加标题（TITLE 留空）
    //    · 只要有一条非主页视频/图片 → 至少要有一个标题（TITLE 必填）
    //    标题条数不必等于素材条数，1 个标题可以覆盖多条素材。

    /** 视频素材列表。后台「共N条自选素材」就写 N 条。 */
    private static final List<VideoItem> VIDEO_MATERIALS = List.of(
            new VideoItem("v0200fg10000aaaaaaaaaaaa", "VIDEO_VERTICAL"),
            new VideoItem("v0200fg10000bbbbbbbbbbbb", "VIDEO_VERTICAL"),
            new VideoItem("v0200fg10000cccccccccccc", "VIDEO_VERTICAL"));

    /** 一条视频素材：video_id 与 aweme_item_id 二选一。 */
    private record VideoItem(String videoId, String imageMode, Long awemeItemId, String videoCoverId) {

        /** 自选素材 / 素材库视频：image_mode = VIDEO_VERTICAL 竖版 / VIDEO_LARGE 横版 */
        VideoItem(String videoId, String imageMode) {
            this(videoId, imageMode, null, null);
        }

        /** 抖音主页视频：传 aweme_item_id（抖音视频ID） */
        static VideoItem fromAweme(long awemeItemId) {
            return new VideoItem(null, "VIDEO_VERTICAL", awemeItemId, null);
        }

        VideoItem cover(String videoCoverId) {
            return new VideoItem(videoId, imageMode, awemeItemId, videoCoverId);
        }
    }

    /** 创意标题（title），10-110 字符，汉字算 2 位。全部素材为主页视频时留空。 */
    private static final String TITLE = "这个价格真的太香了，闭眼入不踩雷";

    /** 标题类型（title_type）：CUSTOM 自定义标题 / COMMODITY_CARD 商品卡标题 */
    private static final String TITLE_TYPE = "CUSTOM";

    // ========================================================================
    // 主流程
    // ========================================================================

    public static void main(String[] args) throws Exception {

        // 辅助模式：查千川素材库视频，拿 video_id 填 VIDEO_MATERIALS
        //   --list-video                    列出素材库视频
        //   --list-video=洗发水              文件名关键词过滤（客户端过滤，服务端不支持按名字查）
        //   --video-ids=id1,id2,id3         按 video_id 批量查（<=100）
        //   --material-ids=123,456          按素材id 批量查（<=100）
        //   --signatures=md5a,md5b          按视频 md5 批量查（<=100）
        //   --image-mode=VIDEO_VERTICAL     按素材类型过滤（VIDEO_VERTICAL/VIDEO_LARGE）
        //   --tags=标签1,标签2               按素材标签过滤
        //   --sources=E_COMMERCE            按素材来源过滤
        //   --start=2026-08-01 --end=2026-08-31   按上传时间过滤（yyyy-MM-dd）
        //   --pages=5                       翻页数（默认 5，每页 100）
        //   注意：video-ids / material-ids / signatures 三者只能选一个
        if (hasAnyArg(args, "--list-video", "--video-ids", "--material-ids", "--signatures",
                "--name", "--image-mode", "--tags", "--sources", "--start", "--end", "--pages")) {
            listLibraryVideo(ADVERTISER_ID, args);
            return;
        }

        // 辅助模式：列出已授权抖音号，用来把后台的「抖音号」换成数字 AWEME_UID
        //   --list-aweme              列出全部
        //   --list-aweme=Lili0333     只看匹配的行（按 uid / 抖音号 / 昵称模糊匹配）
        for (String a : args == null ? new String[0] : args) {
            if (a.equals("--list-aweme") || a.startsWith("--list-aweme=")) {
                String kw = a.startsWith("--list-aweme=")
                        ? a.substring("--list-aweme=".length()).trim()
                        : "";
                listAuthorizedAweme(ADVERTISER_ID, kw);
                return;
            }
        }

        boolean dryRun = DRY_RUN && !hasFlag(args, "--create");

        // ---------- ⓪ 前置校验 ----------
        if (ADVERTISER_ID == 123456L || ADVERTISER_ID == 0L) {
            throw new IllegalStateException(
                    "ADVERTISER_ID 还是占位符「" + ADVERTISER_ID + "」，没改成你的真实千川账户ID！");
        }
        if (!NO_AWEME_ID && AWEME_UID == null) {
            throw new IllegalStateException("""
                    AWEME_UID 还没填（有号商家必填）。
                    后台那个「抖音号」如 Lili0333 不能直接填，接口要的是数字 uid。
                    先跑一次下面的命令，从输出第一列复制数字 uid：
                      java -cp "target/classes;$(cat target/cp.txt)" \
                           com.example.oceanengine.ccreate_plan.CreatePlanDemo --list-aweme
                    如果是无号投商城，把 NO_AWEME_ID 改成 true。""");
        }
        System.out.println("⓪ 使用账户 ID = " + ADVERTISER_ID);

        // ---------- ① 组装入参 ----------
        OverallVideoCreateParam param = buildParam();
        System.out.println("① 计划名称     = " + PLAN_NAME);
        System.out.println("   商品ID       = " + PRODUCT_IDS);
        System.out.println("   抖音号uid    = " + (NO_AWEME_ID ? "(无号投商城)" : String.valueOf(AWEME_UID)));
        System.out.println("   预算/ROI     = " + BUDGET + " / " + ROI2_GOAL);
        System.out.println("   视频素材     = " + VIDEO_MATERIALS.size() + " 条"
                + (blankToNull(TITLE) == null ? "，无标题" : "，标题 1 条"));

        // ---------- ② 校验 + 组装请求体（dry-run 不需要 token）----------
        String accessToken = dryRun ? null : QianchuanTokenClient.getAccessToken();
        QianchuanOverallVideoCreateService service =
                new QianchuanOverallVideoCreateService(accessToken);
        QianchuanOverallVideoCreateV10Request request;
        try {
            request = service.prepare(param);
        } catch (IllegalArgumentException e) {
            System.out.println();
            System.out.println("❌ 参数校验未通过：" + e.getMessage());
            return;
        }
        System.out.println("② 参数校验通过");

        // ---------- ③ 打印 / 真实调用 ----------
        System.out.println();
        System.out.println("=== 请求体 ===");
        System.out.println(request.toJson());
        System.out.println();

        if (dryRun) {
            System.out.println("=== DRY-RUN 模式，未真正创建计划 ===");
            System.out.println("确认上面的请求体无误后，把 DRY_RUN 改成 false（或加 --create）再跑一次。");
            return;
        }

        System.out.println("③ 真实调用 ===");
        CreatePlanResult result = service.create(param);
        System.out.println(result);

        if (!result.isSuccess()) {
            System.out.println("❌ 创建失败，请带上 requestId=" + result.getRequestId()
                    + " 查询【附录-返回码】");
            return;
        }
        System.out.println("✅ 创建成功，计划 id = " + result.getAdId());
    }

    // ========================================================================
    // 把配置区常量组装成入参对象（一般不用改）
    // ========================================================================

    private static OverallVideoCreateParam buildParam() {

        OverallVideoCreateParam.DeliverySetting deliverySetting =
                new OverallVideoCreateParam.DeliverySetting()
                        .budget(BUDGET)
                        .roi2Goal(ROI2_GOAL)
                        .qcpxMode(blankToNull(QCPX_MODE))
                        .videoScheduleType(blankToNull(VIDEO_SCHEDULE_TYPE))
                        .smartBidType(blankToNull(SMART_BID_TYPE))
                        .startTime(blankToNull(START_TIME))
                        .endTime(blankToNull(END_TIME))
                        .enableAigcCreative(ENABLE_AIGC_CREATIVE)
                        .noAwemeId(NO_AWEME_ID ? Boolean.TRUE : null)
                        .overallCostItems(new OverallVideoCreateParam.OverallCostItems()
                                .starTaskMaterialSwitch(STAR_TASK_MATERIAL_SWITCH)
                                .allianceCommisionSwitch(ALLIANCE_COMMISION_SWITCH));

        // 视频素材：N 条配置 → N 个 video_material 元素
        List<OverallVideoCreateParam.VideoMaterial> videos = new ArrayList<>(VIDEO_MATERIALS.size());
        for (VideoItem v : VIDEO_MATERIALS) {
            videos.add(new OverallVideoCreateParam.VideoMaterial()
                    .imageMode(v.imageMode())
                    .videoId(blankToNull(v.videoId()))
                    .videoCoverId(blankToNull(v.videoCoverId()))
                    .awemeItemId(v.awemeItemId()));
        }

        // 商品创意素材必须与 product_ids 一一对应：有几个商品就生成几条 CreativeItem。
        // 这里默认所有商品共用同一批素材；不同商品要用不同素材时，改成按商品分别构造。
        List<OverallVideoCreateParam.CreativeItem> creativeItems = new ArrayList<>();
        for (Long productId : PRODUCT_IDS) {
            creativeItems.add(new OverallVideoCreateParam.CreativeItem()
                    .productId(productId)
                    .awemeUid(NO_AWEME_ID ? null : AWEME_UID)
                    .videoMaterial(videos)
                    // 用非抖音主页视频时至少要有一个标题；全为主页视频时不能带标题
                    .titleMaterial(blankToNull(TITLE) == null
                            ? null
                            : List.of(new OverallVideoCreateParam.TitleMaterial()
                                    .title(TITLE)
                                    .titleType(blankToNull(TITLE_TYPE)))));
        }

        return new OverallVideoCreateParam()
                .advertiserId(ADVERTISER_ID)
                .name(PLAN_NAME)
                .productIds(PRODUCT_IDS)
                .deliverySetting(deliverySetting)
                .multiProductCreativeList(creativeItems);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        return Arrays.asList(args).contains(flag);
    }

    // ========================================================================
    // 辅助：列出已授权的抖音号（把后台的「抖音号」换成接口要的数字 uid）
    //   接口：GET /open_api/v1.0/qianchuan/uni_aweme/authorized/get/
    // ========================================================================

    private static void listAuthorizedAweme(long advertiserId, String keyword) throws Exception {

        String token = QianchuanTokenClient.getAccessToken();
        if (token == null || token.isBlank()) {
            System.out.println("❌ 没取到 Access-Token，检查 client/QianchuanTokenClient 的配置");
            return;
        }

        ApiClient client = new ApiClient();
        client.addDefaultHeader("Access-Token", token);
        QianchuanUniAwemeAuthorizedGetV10Api api =
                new QianchuanUniAwemeAuthorizedGetV10Api(client);

        boolean filtered = keyword != null && !keyword.isBlank();
        String kw = filtered ? keyword.toLowerCase() : null;

        System.out.println("=== 账户 " + advertiserId + " 已授权的抖音号"
                + (filtered ? "（过滤：" + keyword + "）" : "") + " ===");
        System.out.printf("%-22s %-26s %-18s%n", "aweme_uid(填配置区)", "抖音昵称", "抖音号(show_id)");
        System.out.println("--------------------------------------------------------------------------------");

        long page = 1L;
        long pageSize = 100L;
        int scanned = 0;
        int total = 0;
        while (true) {
            QianchuanUniAwemeAuthorizedGetV10Response resp =
                    api.openApiV10QianchuanUniAwemeAuthorizedGetGet(advertiserId, null, page, pageSize);

            if (resp == null || resp.getCode() == null || resp.getCode() != 0) {
                System.out.println("❌ 接口返回异常：code=" + (resp == null ? "null" : resp.getCode())
                        + "  message=" + (resp == null ? "null" : resp.getMessage())
                        + "  requestId=" + (resp == null ? "null" : resp.getRequestId()));
                return;
            }

            QianchuanUniAwemeAuthorizedGetV10ResponseData data = resp.getData();
            List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> list =
                    data == null ? null : data.getAwemeIdList();
            if (list == null || list.isEmpty()) {
                break;
            }

            for (QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner item : list) {
                scanned++;
                if (filtered && !matches(item, kw)) {
                    continue;
                }
                System.out.printf("%-22s %-26s %-18s%n",
                        item.getAwemeId(),
                        item.getAwemeName() == null ? "-" : item.getAwemeName(),
                        item.getAwemeShowId() == null ? "-" : item.getAwemeShowId());
                total++;
            }

            Long totalPage = data.getPageInfo() == null ? null : data.getPageInfo().getTotalPage();
            if (totalPage == null || page >= totalPage) {
                break;
            }
            page++;
        }

        System.out.println("--------------------------------------------------------------------------------");
        if (total == 0) {
            System.out.println(filtered
                    ? "没有匹配「" + keyword + "」的抖音号（共扫了 " + scanned + " 个）"
                    : "(该账户下没有已授权的抖音号)");
        } else {
            System.out.println("共 " + total + " 个。把第一列的数字填到配置区的 AWEME_UID。");
        }
    }

    /** uid / 昵称 / 抖音号 任一包含关键词即算命中（已转小写） */
    private static boolean matches(QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner item,
                                   String lowerKeyword) {
        if (item.getAwemeId() != null
                && String.valueOf(item.getAwemeId()).toLowerCase().contains(lowerKeyword)) {
            return true;
        }
        if (item.getAwemeName() != null
                && item.getAwemeName().toLowerCase().contains(lowerKeyword)) {
            return true;
        }
        return item.getAwemeShowId() != null
                && item.getAwemeShowId().toLowerCase().contains(lowerKeyword);
    }

    // ========================================================================
    // 辅助：查千川素材库视频（拿 video_id 填 VIDEO_MATERIALS）
    //   接口：GET https://ad.oceanengine.com/open_api/v1.0/qianchuan/video/get/
    // ========================================================================

    /**
     * 素材库接口的域名是 {@code ad.oceanengine.com}，而 SDK 默认走 {@code api.oceanengine.com}，
     * 所以这里必须显式 setBasePath，否则会 404 / 报错。
     */
    private static final String AD_HOST = "https://ad.oceanengine.com";

    /**
     * 查素材库视频，支持命令行过滤参数（见 main 里的用法说明）。
     *
     * <p>服务端过滤（文档支持）：video_ids / material_ids / signatures（三选一，各 &lt;=100）、
     * image_mode、tags、sources、start_time+end_time。</p>
     * <p>客户端过滤：文件名关键词（{@code --list-video=xxx} 或 {@code --name=xxx}），
     * 服务端没有按文件名查的能力。</p>
     */
    private static void listLibraryVideo(long advertiserId, String[] args) throws Exception {

        String token = QianchuanTokenClient.getAccessToken();
        if (token == null || token.isBlank()) {
            System.out.println("❌ 没取到 Access-Token，检查 client/QianchuanTokenClient 的配置");
            return;
        }

        // ⚠ 这个接口的域名是 ad.oceanengine.com，SDK 默认是 api.oceanengine.com，必须显式改
        ApiClient client = new ApiClient();
        client.setBasePath(AD_HOST);
        client.addDefaultHeader("Access-Token", token);
        QianchuanVideoGetV10Api api = new QianchuanVideoGetV10Api(client);

        // ---------------- 解析过滤参数 ----------------
        List<String> videoIds = splitCsv(argValue(args, "video-ids"));
        List<Long> materialIds = splitLongCsv(argValue(args, "material-ids"));
        List<String> signatures = splitCsv(argValue(args, "signatures"));

        int exclusive = (videoIds == null ? 0 : 1)
                + (materialIds == null ? 0 : 1)
                + (signatures == null ? 0 : 1);
        if (exclusive > 1) {
            System.out.println("❌ video-ids / material-ids / signatures 三者只能选一个（文档约束）");
            return;
        }
        if (videoIds != null && videoIds.size() > 100) {
            System.out.println("❌ video-ids 最多 100 个，当前 " + videoIds.size() + " 个");
            return;
        }

        QianchuanVideoGetV10Filtering filtering = new QianchuanVideoGetV10Filtering();
        if (videoIds != null) {
            filtering.videoIds(videoIds);
        }
        if (materialIds != null) {
            filtering.materialIds(materialIds);
        }
        if (signatures != null) {
            filtering.signatures(signatures);
        }

        List<String> imageModes = splitCsv(argValue(args, "image-mode"));
        if (imageModes != null) {
            List<QianchuanVideoGetV10FilteringImageMode> modes = new ArrayList<>(imageModes.size());
            for (String m : imageModes) {
                modes.add(QianchuanVideoGetV10FilteringImageMode.fromValue(m.toUpperCase()));
            }
            filtering.imageMode(modes);
        }

        List<String> tags = splitCsv(argValue(args, "tags"));
        if (tags != null) {
            filtering.tags(tags);
        }

        List<String> sources = splitCsv(argValue(args, "sources"));
        if (sources != null) {
            List<QianchuanVideoGetV10FilteringSources> srcs = new ArrayList<>(sources.size());
            for (String s : sources) {
                srcs.add(QianchuanVideoGetV10FilteringSources.fromValue(s.toUpperCase()));
            }
            filtering.sources(srcs);
        }

        String start = argValue(args, "start");
        String end = argValue(args, "end");
        if (start != null) {
            filtering.startTime(start);
        }
        if (end != null) {
            filtering.endTime(end);
        }

        // 文件名关键词：客户端过滤
        String keyword = argValue(args, "name");
        if (keyword == null) {
            keyword = argValue(args, "list-video");
        }
        boolean filtered = keyword != null && !keyword.isBlank();
        String kw = filtered ? keyword.toLowerCase() : null;

        // ---------------- 打印本次实际使用的过滤条件 ----------------
        boolean byIds = videoIds != null || materialIds != null || signatures != null;
        int pageSize = 100;
        int maxPage = byIds ? 1 : parseInt(argValue(args, "pages"), 5);

        System.out.println("=== 千川素材库视频查询（账户 " + advertiserId + "）===");
        System.out.println("过滤条件：" + describeFilter(videoIds, materialIds, signatures,
                imageModes, tags, sources, start, end, filtered ? keyword : null));
        System.out.println();
        System.out.printf("%-30s %-15s %-9s %-12s %s%n",
                "video_id(填 VIDEO_MATERIALS)", "image_mode", "时长(s)", "上传日期", "文件名");
        System.out.println("-".repeat(120));

        int total = 0;
        long totalNumber = -1L;
        for (int page = 1; page <= maxPage; page++) {
            QianchuanVideoGetV10Response resp =
                    apiCall(api, advertiserId, filtering, page, pageSize);
            if (resp == null) {
                return;
            }
            if (resp.getCode() == null || resp.getCode() != 0) {
                System.out.println("❌ 接口返回异常：code=" + resp.getCode()
                        + "  message=" + resp.getMessage()
                        + "  requestId=" + resp.getRequestId());
                return;
            }

            QianchuanVideoGetV10ResponseData data = resp.getData();
            if (data != null && data.getPageInfo() != null
                    && data.getPageInfo().getTotalNumber() != null) {
                totalNumber = data.getPageInfo().getTotalNumber();
            }
            List<QianchuanVideoGetV10ResponseDataListInner> list =
                    data == null ? null : data.getList();
            if (list == null || list.isEmpty()) {
                break;
            }

            for (QianchuanVideoGetV10ResponseDataListInner item : list) {
                String name = item.getFilename() == null ? "-" : item.getFilename();
                if (filtered && !name.toLowerCase().contains(kw)) {
                    continue;
                }
                System.out.printf("%-30s %-15s %-9s %-12s %s%n",
                        item.getId(),
                        item.getImageMode() == null ? "-" : item.getImageMode().getValue(),
                        item.getDuration() == null ? "-" : String.valueOf(item.getDuration()),
                        item.getCreateTime() == null ? "-" : item.getCreateTime(),
                        name);
                total++;
            }

            Long totalPage = data.getPageInfo() == null ? null : data.getPageInfo().getTotalPage();
            if (totalPage == null || page >= totalPage) {
                break;
            }
            // 素材库接口频控较紧（翻太快会返回 40100），页间留点间隔
            Thread.sleep(800L);
        }

        System.out.println("-".repeat(120));
        if (total == 0) {
            System.out.println("(没有查到视频素材；素材库有分钟级延迟，刚上传的请等几分钟再查)");
            return;
        }
        StringBuilder scope = new StringBuilder("共 " + total + " 条");
        if (totalNumber >= 0) {
            scope.append("（符合过滤条件的总数 ").append(totalNumber).append(" 条");
            if (totalNumber > total) {
                scope.append("，本次只列了前 ").append(maxPage).append(" 页，可加 --pages=N 调大");
            }
            scope.append("）");
        }
        System.out.println(scope + "。把第一列的 video_id 填到 VIDEO_MATERIALS。");
    }

    /** 抽出来是为了让上面的主循环读起来干净。 */
    private static QianchuanVideoGetV10Response apiCall(QianchuanVideoGetV10Api api,
                                                         long advertiserId,
                                                         QianchuanVideoGetV10Filtering filtering,
                                                         int page, int pageSize) throws Exception {
        try {
            return api.openApiV10QianchuanVideoGetGet(advertiserId, filtering,
                    Integer.valueOf(page), Integer.valueOf(pageSize));
        } catch (com.bytedance.ads.ApiException e) {
            System.out.println("❌ 调用失败：" + e.getMessage());
            return null;
        }
    }

    private static String describeFilter(List<String> videoIds, List<Long> materialIds,
                                         List<String> signatures, List<String> imageModes,
                                         List<String> tags, List<String> sources,
                                         String start, String end, String keyword) {
        List<String> parts = new ArrayList<>();
        if (videoIds != null) {
            parts.add("video_ids=" + videoIds);
        }
        if (materialIds != null) {
            parts.add("material_ids=" + materialIds);
        }
        if (signatures != null) {
            parts.add("signatures=" + signatures);
        }
        if (imageModes != null) {
            parts.add("image_mode=" + imageModes);
        }
        if (tags != null) {
            parts.add("tags=" + tags);
        }
        if (sources != null) {
            parts.add("sources=" + sources);
        }
        if (start != null) {
            parts.add("start_time=" + start);
        }
        if (end != null) {
            parts.add("end_time=" + end);
        }
        if (keyword != null) {
            parts.add("文件名含「" + keyword + "」(客户端过滤)");
        }
        return parts.isEmpty() ? "(无，返回全部)" : String.join("  ", parts);
    }

    /** 取 {@code --key=value} 里的 value；没传返回 null。 */
    private static String argValue(String[] args, String key) {
        if (args == null) {
            return null;
        }
        String prefix = "--" + key + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) {
                String v = a.substring(prefix.length()).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 是否出现了 {@code --flag} 或 {@code --flag=xxx}。 */
    private static boolean hasArg(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (a.equals(flag) || a.startsWith(flag + "=")) {
                return true;
            }
        }
        return false;
    }

    /** 任意一个 flag 命中即返回 true。 */
    private static boolean hasAnyArg(String[] args, String... flags) {
        for (String f : flags) {
            if (hasArg(args, f)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<Long> splitLongCsv(String value) {
        List<String> parts = splitCsv(value);
        if (parts == null) {
            return null;
        }
        List<Long> out = new ArrayList<>(parts.size());
        for (String s : parts) {
            out.add(Long.valueOf(s));
        }
        return out;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int v = Integer.parseInt(value.trim());
            return v < 1 ? defaultValue : v;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}