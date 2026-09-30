package com.example.oceanengine.ccreate_plan;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.example.oceanengine.client.ApiClients;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *
 *  <p>相关类（本类只管「建计划」，查素材的逻辑都拆出去了）：</p>
 *  <ul>
 *   <li>{@link AwemeMaterialFinder} ——【获取投放计划可排除抖音视频/图文列表】<br>
 *       抖音号 + 商品id → 素材id。创建计划前本类<b>自动</b>用它查素材，
 *       所以改配置区的抖音号 / 商品id，素材id 就跟着变。
 *       想单独看素材列表可跑 {@code --list-aweme-video}。</li>
 *   <li>{@link QianchuanOverallVideoCreateService} —— 校验 + 组装请求体 + 真实调用。</li>
 *   <li>{@link OverallVideoCreateParam} —— 入参 DTO。</li>
 *  </ul>
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
    private static final boolean DRY_RUN = false;

    /** 投放账号 id（advertiser_id）—— 后台右上角头像旁那个 ID */
    private static final long ADVERTISER_ID = 1823379540880396L;

    /** 投放计划名称（name），1-100 字符，1 个汉字算 2 位。首尾不要有空格（平台会拒）。 */
    private static final String PLAN_NAME = "柚子妈-羊脂膏-9.30";

    /**
     * true = 在计划名称后自动追加时间戳（{@code -MMddHHmmss}），避免平台返回
     * {@code 40000「计划名称不能重复」}。
     *
     * <p>同一个账户下计划名称<b>不能重复</b>。反复跑同一个 PLAN_NAME 时，
     * 第一次成功后后面每次都会报「计划名称不能重复」，这时把它打开即可。</p>
     *
     * <p>也可命令行传 {@code --unique-name} 临时打开。</p>
     */
    private static final boolean PLAN_NAME_AUTO_TIMESTAMP = false;

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
    private static final List<Long> PRODUCT_IDS = List.of(3518517808038344366L);

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
    private static final Long AWEME_UID =3660744842026395L;

    /** 是否为乘方无号投商城（no_aweme_id） */
    private static final boolean NO_AWEME_ID = false;

    // ---------------------------- 投放设置 delivery_setting ---------------

    /** 预算（budget） */
    private static final double BUDGET = 20000D;

    /** 支付 ROI 目标（roi2_goal），最多两位小数 */
    private static final double ROI2_GOAL = 1.9D;

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
    //  ⚠ 这里**不用手填素材id**。创建计划前，程序会自己调
    //    【获取投放计划可排除抖音视频/图文列表】
    //    （GET /open_api/v1.0/qianchuan/uni_promotion/block_material/get/），
    //    用上面的 AWEME_UID + PRODUCT_IDS 查素材，查到什么就用什么。
    //
    //    ⇒ **改抖音号和商品id，素材id 就跟着变**，不用再手工同步。
    //
    //  ⚠ 标题规则（文档原文）：
    //    · 素材全是抖音主页视频 → 不能加标题（TITLE 留空）
    //    · 只要有一条非主页视频/图片 → 至少要有一个标题（TITLE 必填）
    //    标题条数不必等于素材条数，1 个标题可以覆盖多条素材。

    /**
     * 查到的素材，往 video_material 里填哪个字段：
     *
     * <ul>
     *   <li>{@code "AWEME_ITEM_ID"}（当前）—— 填 {@code aweme_item_id}（抖音视频ID，纯数字）。
     *       接口查出来的这批本来就是<b>抖音主页视频</b>，用这个才对口；
     *       本地校验会判定「全为主页视频」，此时 {@link #TITLE} <b>必须留空</b>。</li>
     *   <li>{@code "VIDEO_ID"} —— 填 {@code video_id}（千川视频id，形如 v0200fg10000...）。
     *       平台会当成「非主页视频」，那 {@link #TITLE} <b>必须至少填一条</b>，
     *       否则本地校验直接拦下。</li>
     * </ul>
     *
     * <p>也可命令行传 {@code --material-field=video_id} / {@code --material-field=aweme_item_id} 覆盖。</p>
     */
    private static final String MATERIAL_ID_FIELD = "AWEME_ITEM_ID";

    /**
     * 接口返回的素材最多用前 N 条；<b>0 = 全部使用</b>（当前）。
     *
     * <p>⚠ 接口给的是「这个抖音号下<b>能投这个商品的全部素材</b>」，不是「你在后台勾选的那几条」。
     * 如果查到条数明显多于后台显示的数量，把这个值改成正数即可截断。</p>
     */
    private static final int MATERIAL_LIMIT = 0;

    /**
     * 精确指定这次要投哪几条素材（<b>aweme_item_id 白名单</b>）。
     *
     * <p>留空（默认）= 接口返回什么就投什么，即「全用」，行为与不带本项时完全一致。</p>
     *
     * <p>为什么需要它：{@code block_material/get} 给的是<b>候选池</b>（该抖音号下能投该商品的全部素材），
     * 而不是你在千川后台勾选的那几条。{@link #MATERIAL_LIMIT} 只能「取前 N 条」，顺序不由你控制；
     * 想和后台完全一致，就把后台那几条的 aweme_item_id 抄到这里。</p>
     *
     * <p>填了之后，只有同时出现在「接口候选池」和本列表里的素材才会被投；
     * 若某条不在池子里，会打印 ⚠ 提示并跳过（不会因此报错）。</p>
     *
     * <p>也可命令行临时指定：{@code --pick-ids=id1,id2,id3}。</p>
     */
    private static final List<Long> MATERIAL_PICK = List.of();


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

    /**
     * 创意标题（title），10-110 字符，汉字算 2 位。
     *
     * <p><b>当前留空是必须的</b>：上面 3 条素材全是抖音主页视频，
     * 文档规定「如果素材全都是抖音主页视频，不支持添加标题」。
     * 将来换成非主页视频（素材库视频/图片）时，这里必须至少填 1 条标题，否则素材不生效。</p>
     */
    private static final String TITLE = "";

    /** 标题类型（title_type）：CUSTOM 自定义标题 / COMMODITY_CARD 商品卡标题 */
    private static final String TITLE_TYPE = "CUSTOM";

    // ========================================================================
    // 主流程
    // ========================================================================

    /**
     * 入口。配置类错误（{@link IllegalStateException}）只打印一行提示，不打栈——
     * 那些都是「值没填对」，不是代码 bug，抛栈只会让人误以为程序坏了。
     *
     * <pre>
     * 常用命令行开关：
     *   --dry-run              只看请求体，不下单（DRY_RUN=false 时也能安全预览）
     *   --create               强制真实下单（忽略 DRY_RUN）
     *   --unique-name          计划名称自动加时间戳，避免 40000「计划名称不能重复」
     *   --pick-ids=id1,id2     只投这几条素材（aweme_item_id 白名单），默认全用
     *   --material-field=...   取 aweme_item_id（默认）还是 video_id
     * </pre>
     */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalStateException e) {
            System.out.println();
            System.out.println("❌ " + e.getMessage());
        } catch (Exception e) {
            System.out.println();
            System.out.println("❌ 运行出错：" + e.getClass().getSimpleName() + " " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void run(String[] args) throws Exception {

        // 辅助模式：按「抖音号 + 商品id」找可投的抖音视频/图文素材
        //   实现在独立类 AwemeMaterialFinder —— 接口【获取投放计划可排除抖音视频/图文列表】
        //   --list-aweme-video                    用配置区的 AWEME_UID + PRODUCT_IDS
        //   --list-aweme-video=uid,productId      显式指定（多商品用逗号续写）
        //   --aweme-uid=数字 --product-ids=a,b     也可以分开传
        //   --media-type=CAROUSEL                 图文（默认 VIDEO）
        //   --order=PLAY_CNT                      排序：CREATE_TIME/PLAY_CNT/LIKE_CNT/STAT_COST
        //   --limit=N                             只取前 N 条
        if (hasArg(args, "--list-aweme-video") || hasArg(args, "--aweme-uid")) {
            AwemeMaterialFinder.run(ADVERTISER_ID, AWEME_UID, PRODUCT_IDS, args);
            return;
        }

        // 辅助模式：查千川素材库视频（只是想翻素材库时用，跟建计划无关）
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

        // 是否只打印请求体不下单：
        //   默认跟随 DRY_RUN 常量；--dry-run 强制预览；--create 强制真实调用
        boolean dryRun;
        if (hasFlag(args, "--dry-run")) {
            dryRun = true;
        } else if (hasFlag(args, "--create")) {
            dryRun = false;
        } else {
            dryRun = DRY_RUN;
        }

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
        // 素材：自动按 AWEME_UID + PRODUCT_IDS 查，不用手工填
        List<VideoItem> materials = resolveVideoMaterials(args);
        String planName = resolvePlanName(args);
        OverallVideoCreateParam param = buildParam(materials, planName);
        System.out.println("① 计划名称     = " + planName);
        System.out.println("   商品ID       = " + PRODUCT_IDS);
        System.out.println("   抖音号uid    = " + (NO_AWEME_ID ? "(无号投商城)" : String.valueOf(AWEME_UID)));
        System.out.println("   预算/ROI     = " + BUDGET + " / " + ROI2_GOAL);
        System.out.println("   视频素材     = " + materials.size() + " 条"
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
        CreatePlanResult result;
        try {
            result = service.create(param);
        } catch (ApiException e) {
            // 平台侧业务报错（如 40000 计划名称不能重复 / 参数非法）会走到这里，
            // 打成可读的一行，避免直接抛栈让人只看到 exit=1。
            System.out.println("❌ 接口返回异常 code=" + e.getCode());
            System.out.println("   " + firstLine(e.getResponseBody() != null
                    ? e.getResponseBody() : e.getMessage()));
            System.out.println("   常见原因：计划名称重复 / 首尾空格 / 必填缺失 / 素材不合法。");
            System.out.println("   名称重复可加 --unique-name 或打开 PLAN_NAME_AUTO_TIMESTAMP。");
            return;
        }
        System.out.println(result);

        if (!result.isSuccess()) {
            System.out.println("❌ 创建失败，请带上 requestId=" + result.getRequestId()
                    + " 查询【附录-返回码】");
            return;
        }
        System.out.println("✅ 创建成功，计划 id = " + result.getAdId());
    }

    // ========================================================================
    // 素材来源：手工配置区  or  接口自动查（AwemeMaterialFinder）
    // ========================================================================

    /**
     * 查素材：调 {@link AwemeMaterialFinder}（接口【获取投放计划可排除抖音视频/图文列表】），
     * 用配置区的 {@link #AWEME_UID} + {@link #PRODUCT_IDS} 查，查到什么就用什么。
     *
     * <p>⇒ <b>改抖音号 / 商品id，素材id 自动跟着变</b>，不用再手工同步。</p>
     *
     * <p>取接口的哪一列由 {@link #MATERIAL_ID_FIELD} 决定，
     * 命令行 {@code --material-field=video_id|aweme_item_id} 可临时覆盖。</p>
     */
    private static List<VideoItem> resolveVideoMaterials(String[] args) throws Exception {

        String field = argValue(args, "material-field");
        if (blankToNull(field) == null) {
            field = MATERIAL_ID_FIELD;
        }
        boolean byVideoId = "VIDEO_ID".equalsIgnoreCase(field.trim());
        String mediaType = argValue(args, "media-type");

        System.out.println("⚙ 查素材：【获取投放计划可排除抖音视频/图文列表】");
        System.out.println("   aweme_uid=" + AWEME_UID
                + "  product_ids=" + PRODUCT_IDS
                + "  media_type=" + (mediaType == null ? "VIDEO" : mediaType.toUpperCase())
                + "  取字段=" + (byVideoId ? "video_id" : "aweme_item_id"));

        List<AwemeMaterialFinder.Material> found = AwemeMaterialFinder.find(
                ADVERTISER_ID,
                AWEME_UID,
                PRODUCT_IDS,
                mediaType,
                argValue(args, "order"));

        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "按 aweme_uid=" + AWEME_UID + " + product_ids=" + PRODUCT_IDS
                            + " 查到 0 条素材。确认这两个值是否匹配、该抖音号是否已授权给本账户，"
                            + "可先用 --list-aweme-video 单独看一眼。");
        }

        // 白名单过滤：只投 MATERIAL_PICK / --material-ids 指定的几条（默认空 = 全用）
        found = applyMaterialPick(found, args);
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "白名单过滤后一条素材都不剩。MATERIAL_PICK 里填的 aweme_item_id "
                            + "都不在接口返回的候选池里，检查是否抄错、或抖音号/商品id 是否配对。");
        }

        if (MATERIAL_LIMIT > 0 && found.size() > MATERIAL_LIMIT) {
            System.out.println("   接口返回 " + found.size() + " 条，按 MATERIAL_LIMIT="
                    + MATERIAL_LIMIT + " 只取前 " + MATERIAL_LIMIT + " 条");
            found = found.subList(0, MATERIAL_LIMIT);
        }

        System.out.println("   查到 " + found.size() + " 条素材：");
        for (int i = 0; i < found.size(); i++) {
            AwemeMaterialFinder.Material m = found.get(i);
            System.out.printf("     %2d. aweme_item_id=%-22s video_id=%-32s %s%n",
                    i + 1,
                    m.awemeItemId() == null ? "-" : String.valueOf(m.awemeItemId()),
                    m.videoId() == null ? "-" : m.videoId(),
                    m.imageMode());
        }

        List<VideoItem> items = toVideoItems(found, byVideoId);
        if (items.isEmpty()) {
            throw new IllegalStateException("接口返回 " + found.size()
                    + " 条素材，但没有一条带 " + (byVideoId ? "video_id" : "aweme_item_id")
                    + "，换个 --material-field 试试。");
        }
        System.out.println("   → 转成 " + items.size() + " 条 video_material");
        return items;
    }

    /**
     * 按白名单过滤素材：命令行 {@code --pick-ids=id1,id2} 优先，其次配置区 {@link #MATERIAL_PICK}。
     * 两者都为空时原样返回（= 全用）。
     */
    private static List<AwemeMaterialFinder.Material> applyMaterialPick(
            List<AwemeMaterialFinder.Material> found, String[] args) {

        String raw = argValue(args, "pick-ids");
        List<Long> pick = blankToNull(raw) == null ? MATERIAL_PICK : splitLongCsv(raw);
        if (pick == null || pick.isEmpty()) {
            return found;
        }

        Set<Long> missing = new LinkedHashSet<>(pick);
        List<AwemeMaterialFinder.Material> kept = new ArrayList<>(found.size());
        for (AwemeMaterialFinder.Material m : found) {
            if (m.awemeItemId() != null && missing.remove(m.awemeItemId())) {
                kept.add(m);
            }
        }
        System.out.println("   白名单 " + pick.size() + " 条 → 命中 " + kept.size()
                + " 条（接口候选池共 " + found.size() + " 条）");
        if (!missing.isEmpty()) {
            System.out.println("   ⚠ 这几条不在候选池里，已跳过：" + missing);
        }
        return kept;
    }

    /** 接口返回的 {@link AwemeMaterialFinder.Material} → 配置区用的 {@link VideoItem}。 */
    private static List<VideoItem> toVideoItems(List<AwemeMaterialFinder.Material> found,
                                                boolean byVideoId) {
        List<VideoItem> out = new ArrayList<>(found.size());
        for (AwemeMaterialFinder.Material m : found) {
            if (byVideoId) {
                if (blankToNull(m.videoId()) == null) {
                    continue;
                }
                out.add(new VideoItem(m.videoId(), m.imageMode()));
            } else {
                if (m.awemeItemId() == null) {
                    continue;
                }
                // 抖音主页视频：只填 aweme_item_id，不填 video_id
                out.add(new VideoItem(null, m.imageMode(), m.awemeItemId(), null));
            }
        }
        return out;
    }

    // ========================================================================
    // 把配置区常量组装成入参对象（一般不用改）
    // ========================================================================

    /**
     * 算出这次用的计划名称。
     *
     * <p>同一个账户下计划名称<b>不能重复</b>（平台返回 {@code 40000「计划名称不能重复」}）。
     * {@link #PLAN_NAME_AUTO_TIMESTAMP} 打开（或命令行 {@code --unique-name}）时，
     * 自动追加 {@code -MMddHHmmss} 保证唯一。</p>
     */
    private static String resolvePlanName(String[] args) {
        if (!PLAN_NAME_AUTO_TIMESTAMP && !hasFlag(args, "--unique-name")) {
            return PLAN_NAME;
        }
        String name = PLAN_NAME + "-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        System.out.println("ℹ 计划名称自动加时间戳（避免重名）→ " + name);
        return name;
    }

    private static OverallVideoCreateParam buildParam(List<VideoItem> videoMaterials, String planName) {

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

        // 视频素材：N 条 → N 个 video_material 元素
        List<OverallVideoCreateParam.VideoMaterial> videos = new ArrayList<>(videoMaterials.size());
        for (VideoItem v : videoMaterials) {
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
                .name(planName)
                .productIds(PRODUCT_IDS)
                .deliverySetting(deliverySetting)
                .multiProductCreativeList(creativeItems);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 取多行文本的第一行（接口报错时响应体可能很长，只留首行摘要）。 */
    private static String firstLine(String text) {
        if (text == null) {
            return "(无响应体)";
        }
        int idx = text.indexOf('\n');
        String line = idx < 0 ? text : text.substring(0, idx);
        return line.length() > 500 ? line.substring(0, 500) + "…" : line;
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
        ApiClients.configure(client);
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
    // 按「抖音号 + 商品id」找可投的抖音视频/图文素材
    //   已拆成独立类：{@link AwemeMaterialFinder}
    //   接口：GET /open_api/v1.0/qianchuan/uni_promotion/block_material/get/
    //   文档：【获取投放计划可排除抖音视频/图文列表】
    //   入口：本类创建计划时自动调用；想单独看列表可跑 --list-aweme-video。
    // ========================================================================

    // ========================================================================
    // 辅助：查千川素材库视频（只是想翻素材库时用，跟建计划无关）
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
        ApiClients.configure(client);
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
                "video_id", "image_mode", "时长(s)", "上传日期", "文件名");
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
        System.out.println(scope + "。");
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