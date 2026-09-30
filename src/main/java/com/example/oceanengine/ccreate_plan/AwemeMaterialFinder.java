package com.example.oceanengine.ccreate_plan;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanUniPromotionBlockMaterialGetV10Api;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10MarketingGoal;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10MediaType;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10OrderField;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10ResponseData;
import com.bytedance.ads.model.QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner;
import com.example.oceanengine.client.ApiClients;
import com.example.oceanengine.client.QianchuanTokenClient;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 *  【获取投放计划可排除抖音视频/图文列表】
 * ============================================================================
 *  接口：GET /open_api/v1.0/qianchuan/uni_promotion/block_material/get/
 *  文档：https://open.oceanengine.com/labels/12/docs/1825215820766292
 *  作用：给定「抖音号 uid + 商品id」，列出这个抖音号下**能投这个商品**的
 *        抖音视频 / 图文素材。返回的 video_list[] 里同时带
 *        <b>video_id</b>（千川视频id，形如 v0200fg10000...）和
 *        <b>aweme_item_id</b>（抖音视频ID，纯数字），正好是
 *        {@code overall_video/create} 里 video_material 需要的两个字段。
 *
 *  <p><b>从 CreatePlanDemo 拆出来的独立类</b>，只负责「查」，不负责「建计划」。
 *  两种用法：</p>
 *
 *  <p>① 命令行单独跑（自己看结果、复制 id）：</p>
 *  <pre>
 *  java -cp "target/classes;$(cat target/cp.txt)" \
 *       com.example.oceanengine.ccreate_plan.AwemeMaterialFinder \
 *       --advertiser-id=1778533513439244 \
 *       --aweme-uid=62725463295 \
 *       --product-ids=3836535969062977549
 *  </pre>
 *
 *  <p>② 被 CreatePlanDemo 调用：创建计划前它会<b>自动</b>调 {@link #find}
 *  按抖音号 + 商品id 查素材。{@code --list-aweme-video} 是手动看列表用的。</p>
 *
 *  <p><b>硬约束（官方文档）：</b></p>
 *  <ul>
 *    <li>{@code aweme_id} 必填，且是<b>数字 uid</b>（= 授权抖音号接口返回的 aweme_id），
 *        不是后台显示的「抖音号」字符串（那是 aweme_show_id）。</li>
 *    <li>{@code marketing_goal} 必填；本类固定用 VIDEO_PROM_GOODS（乘方商品投放）。</li>
 *    <li>{@code product_id} 是 number[]，marketing_goal=VIDEO_PROM_GOODS 时<b>必填</b>。</li>
 *    <li>分页是 {@code cursor} + {@code has_more}，不是 page/page_size。</li>
 *  </ul>
 * ============================================================================
 */
public final class AwemeMaterialFinder {

    /** 接口路径。SDK 默认域名就是 api.oceanengine.com，不用改 basePath。 */
    public static final String API_PATH =
            "/open_api/v1.0/qianchuan/uni_promotion/block_material/get/";

    /** 游标翻页最多跑几轮（防止服务端 has_more 一直 true 死循环）。 */
    private static final int MAX_ROUNDS = 20;

    /** 翻页间隔，避免触发 40100「系统请求频率超限」。 */
    private static final long PAGE_INTERVAL_MS = 500L;

    private AwemeMaterialFinder() {
    }

    // ========================================================================
    // 返回值模型
    // ========================================================================

    /**
     * 一条可投素材。
     *
     * <p>{@code videoId} 与 {@code awemeItemId} 由调用方二选一使用：</p>
     * <ul>
     *   <li>抖音主页视频 → 用 {@code awemeItemId}（填 create 接口的 video_material.aweme_item_id）</li>
     *   <li>自选素材 / 素材库视频 → 用 {@code videoId}（填 video_material.video_id）</li>
     * </ul>
     *
     * @param videoId         千川视频id，形如 v0200fg10000da638c7og65osna5vs1g
     * @param awemeItemId     抖音视频ID，19 位数字
     * @param productId       这条素材对应的商品id
     * @param title           抖音视频标题（不是千川创意标题，别直接当 title_material 用）
     * @param imageMode       由 width/height 推断：宽&gt;高 → VIDEO_LARGE(横版)，否则 VIDEO_VERTICAL(竖版)
     * @param duration        时长（秒）
     * @param isCarryMaterial 是否挂车素材
     */
    public record Material(String videoId,
                           Long awemeItemId,
                           Long productId,
                           String title,
                           String imageMode,
                           Double duration,
                           Boolean isCarryMaterial) {

        /** 方便打印：优先显示抖音视频ID，没有才显示 video_id。 */
        public String displayId() {
            return awemeItemId != null ? String.valueOf(awemeItemId) : String.valueOf(videoId);
        }
    }

    // ========================================================================
    // 核心查询
    // ========================================================================

    /**
     * 按「抖音号 uid + 商品id」查素材，media_type / order 用默认值（VIDEO / CREATE_TIME）。
     *
     * @param advertiserId 投放账户id
     * @param awemeUid     抖音号<b>数字 uid</b>（必填）
     * @param productIds   商品id 列表（必填，可多个）
     * @return 查到的素材；查不到返回空 list（不返回 null）
     */
    public static List<Material> find(long advertiserId, Long awemeUid, List<Long> productIds)
            throws Exception {
        return find(advertiserId, awemeUid, productIds, null, null, 0);
    }

    /**
     * 按「抖音号 uid + 商品id」查素材，不限条数。
     *
     * @param mediaType  VIDEO(默认) / CAROUSEL(图文)
     * @param orderField CREATE_TIME(默认) / PLAY_CNT / LIKE_CNT / STAT_COST
     */
    public static List<Material> find(long advertiserId, Long awemeUid, List<Long> productIds,
                                      String mediaType, String orderField) throws Exception {
        return find(advertiserId, awemeUid, productIds, mediaType, orderField, 0);
    }

    /**
     * 按「抖音号 uid + 商品id」查素材。
     *
     * @param mediaType  VIDEO(默认) / CAROUSEL(图文)
     * @param orderField CREATE_TIME(默认) / PLAY_CNT / LIKE_CNT / STAT_COST
     * @param limit      最多返回几条，&lt;=0 表示不限（默认）
     * @return 查到的素材；查不到返回空 list（不返回 null）
     * @throws IllegalArgumentException 必填参数缺失 / 枚举取值非法
     * @throws IllegalStateException    取不到 token、接口返回非 0
     */
    public static List<Material> find(long advertiserId, Long awemeUid, List<Long> productIds,
                                      String mediaType, String orderField, int limit)
            throws Exception {

        if (awemeUid == null) {
            throw new IllegalArgumentException(
                    "aweme_uid 必填：要数字 uid，不是后台那个「抖音号」字符串（如 Lili0333）。"
                            + "可用 CreatePlanDemo --list-aweme 查。");
        }
        if (productIds == null || productIds.isEmpty()) {
            throw new IllegalArgumentException("product_ids 必填（marketing_goal=VIDEO_PROM_GOODS 时）");
        }

        String token = QianchuanTokenClient.getAccessToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("没取到 Access-Token，检查 client/QianchuanTokenClient 的配置");
        }

        ApiClient client = new ApiClient();
        ApiClients.configure(client);
        client.addDefaultHeader("Access-Token", token);
        QianchuanUniPromotionBlockMaterialGetV10Api api =
                new QianchuanUniPromotionBlockMaterialGetV10Api(client);

        QianchuanUniPromotionBlockMaterialGetV10MediaType type =
                blank(mediaType)
                        ? QianchuanUniPromotionBlockMaterialGetV10MediaType.VIDEO
                        : QianchuanUniPromotionBlockMaterialGetV10MediaType.fromValue(
                                mediaType.trim().toUpperCase());
        QianchuanUniPromotionBlockMaterialGetV10OrderField order =
                blank(orderField)
                        ? QianchuanUniPromotionBlockMaterialGetV10OrderField.CREATE_TIME
                        : QianchuanUniPromotionBlockMaterialGetV10OrderField.fromValue(
                                orderField.trim().toUpperCase());

        List<Material> out = new ArrayList<>();
        long cursor = 0L;
        for (int round = 1; round <= MAX_ROUNDS; round++) {

            QianchuanUniPromotionBlockMaterialGetV10Response resp =
                    api.openApiV10QianchuanUniPromotionBlockMaterialGetGet(
                            advertiserId,
                            awemeUid,
                            QianchuanUniPromotionBlockMaterialGetV10MarketingGoal.VIDEO_PROM_GOODS,
                            type,
                            productIds,
                            null,          // filtering：本接口按 aweme + product 已足够定位，不额外过滤
                            order,
                            cursor);

            if (resp == null || resp.getCode() == null || resp.getCode() != 0) {
                throw new IllegalStateException("接口返回异常：code="
                        + (resp == null ? "null" : resp.getCode())
                        + "  message=" + (resp == null ? "null" : resp.getMessage())
                        + "  requestId=" + (resp == null ? "null" : resp.getRequestId()));
            }

            QianchuanUniPromotionBlockMaterialGetV10ResponseData data = resp.getData();
            List<QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner> videos =
                    data == null ? null : data.getVideoList();
            if (videos == null || videos.isEmpty()) {
                break;
            }

            for (QianchuanUniPromotionBlockMaterialGetV10ResponseDataVideoListInner v : videos) {
                out.add(new Material(
                        v.getVideoId(),
                        v.getAwemeItemId(),
                        v.getProductId(),
                        v.getTitle(),
                        inferImageMode(v.getWidth(), v.getHeight()),
                        v.getDuration(),
                        v.getIsCarryMaterial()));
                if (limit > 0 && out.size() >= limit) {
                    return out;
                }
            }

            Long next = data.getPageInfo() == null ? null : data.getPageInfo().getCursor();
            Boolean hasMore = data.getPageInfo() == null ? null : data.getPageInfo().getHasMore();
            if (!Boolean.TRUE.equals(hasMore) || next == null) {
                break;
            }
            cursor = next;
            Thread.sleep(PAGE_INTERVAL_MS);
        }
        return out;
    }

    /**
     * 按宽高推断 image_mode：宽 &gt; 高 → VIDEO_LARGE（横版），否则 VIDEO_VERTICAL（竖版）。
     * 接口没直接给 image_mode，只能这么推。拿不到宽高时按竖版处理（信息流绝大多数是竖版）。
     */
    private static String inferImageMode(Integer width, Integer height) {
        if (width == null || height == null || width <= 0 || height <= 0) {
            return "VIDEO_VERTICAL";
        }
        return width > height ? "VIDEO_LARGE" : "VIDEO_VERTICAL";
    }

    // ========================================================================
    // 命令行入口
    // ========================================================================

    /**
     * 独立运行入口。
     *
     * <pre>
     * AwemeMaterialFinder
     *   --advertiser-id=账户ID          （必填）
     *   --aweme-uid=抖音号数字uid        （必填）
     *   --product-ids=商品id1,商品id2    （必填）
     *   --media-type=VIDEO|CAROUSEL     默认 VIDEO
     *   --order=CREATE_TIME|PLAY_CNT|LIKE_CNT|STAT_COST   默认 CREATE_TIME
     *   --limit=N                       只取前 N 条，默认不限
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        String adv = argValue(args, "advertiser-id");
        if (adv == null) {
            System.out.println("""
                    用法：
                      AwemeMaterialFinder --advertiser-id=账户ID --aweme-uid=抖音号数字uid \
                    --product-ids=商品id1,商品id2 [--media-type=VIDEO|CAROUSEL] \
                    [--order=CREATE_TIME|PLAY_CNT|LIKE_CNT|STAT_COST] [--limit=N]

                    例：
                      java -cp "target/classes;$(cat target/cp.txt)" \\
                           com.example.oceanengine.ccreate_plan.AwemeMaterialFinder \\
                           --advertiser-id=1778533513439244 \\
                           --aweme-uid=62725463295 \\
                           --product-ids=3836535969062977549""");
            return;
        }
        run(Long.parseLong(adv.trim()), null, null, args);
    }

    /**
     * 被 {@code CreatePlanDemo} 复用的入口：默认值由调用方给（配置区的 AWEME_UID / PRODUCT_IDS），
     * 命令行参数优先级更高。
     *
     * @param defaultAwemeUid    没传 --aweme-uid / --list-aweme-video 时用的抖音号 uid
     * @param defaultProductIds  没传 --product-ids 时用的商品id
     */
    public static void run(long advertiserId,
                           Long defaultAwemeUid,
                           List<Long> defaultProductIds,
                           String[] args) throws Exception {

        // 抖音号：--aweme-uid= > --list-aweme-video=uid,pid > 调用方给的默认值
        // 商品id：--product-ids= > --list-aweme-video=uid,pid > 调用方给的默认值
        Long awemeUid = defaultAwemeUid;
        List<Long> productIds = defaultProductIds;

        String combined = argValue(args, "list-aweme-video");
        if (combined != null) {
            List<String> parts = splitCsv(combined);
            if (parts != null && parts.size() >= 2) {
                awemeUid = Long.valueOf(parts.get(0));
                List<Long> ids = new ArrayList<>();
                for (int i = 1; i < parts.size(); i++) {
                    ids.add(Long.valueOf(parts.get(i)));
                }
                productIds = ids;
            }
        }
        String awemeArg = argValue(args, "aweme-uid");
        if (awemeArg != null) {
            awemeUid = Long.valueOf(awemeArg);
        }
        List<Long> productArg = splitLongCsv(argValue(args, "product-ids"));
        if (productArg != null) {
            productIds = productArg;
        }

        if (awemeUid == null) {
            System.out.println("❌ 需要抖音号 uid：填配置区 AWEME_UID，或传 --aweme-uid=数字，"
                    + "或 --list-aweme-video=抖音号uid,商品id");
            return;
        }
        if (productIds == null || productIds.isEmpty()) {
            System.out.println("❌ 需要商品id：填配置区 PRODUCT_IDS，或传 --product-ids=id1,id2");
            return;
        }

        String mediaType = argValue(args, "media-type");
        String order = argValue(args, "order");
        int limit = parseInt(argValue(args, "limit"), 0);

        System.out.println("=== 【获取投放计划可排除抖音视频/图文列表】账户 " + advertiserId + " ===");
        System.out.println("接口 " + API_PATH);
        System.out.println("aweme_uid=" + awemeUid
                + "  product_ids=" + productIds
                + "  media_type=" + (mediaType == null ? "VIDEO" : mediaType.toUpperCase())
                + "  order=" + (order == null ? "CREATE_TIME" : order.toUpperCase())
                + (limit > 0 ? "  limit=" + limit : ""));
        System.out.println();
        System.out.printf("%-32s %-22s %-20s %-15s %s%n",
                "video_id", "aweme_item_id", "product_id", "image_mode", "抖音标题");
        System.out.println("-".repeat(150));

        List<Material> list;
        try {
            list = find(advertiserId, awemeUid, productIds, mediaType, order, limit);
        } catch (Exception e) {
            System.out.println("❌ 查询失败：" + e.getMessage());
            return;
        }

        for (Material m : list) {
            System.out.printf("%-32s %-22s %-20s %-15s %s%n",
                    m.videoId(),
                    m.awemeItemId(),
                    m.productId(),
                    m.imageMode(),
                    m.title() == null ? "-" : m.title());
        }

        System.out.println("-".repeat(150));
        if (list.isEmpty()) {
            System.out.println("(没查到素材：确认抖音号 uid 与商品id 是否匹配，且该抖音号已授权给本账户)");
            return;
        }
        System.out.println("共 " + list.size() + " 条。");
        System.out.println();
        System.out.println("--- CreatePlanDemo 怎么用这批素材 ---");
        System.out.println("不用手填：CreatePlanDemo 创建计划前会自动按「抖音号 + 商品id」再查一次，");
        System.out.println("查到什么就用什么。取哪一列由 MATERIAL_ID_FIELD 决定（默认 aweme_item_id），");
        System.out.println("也可命令行 --material-field=video_id|aweme_item_id 覆盖。");
    }

    // ========================================================================
    // 小工具（本类自带，保持文件独立）
    // ========================================================================

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
