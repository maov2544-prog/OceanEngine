package com.example.oceanengine.add_footage;

import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanUniPromotionAdMaterialAddV10Request;
import com.example.oceanengine.ccreate_plan.AwemeMaterialFinder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ============================================================================
 *  「添加乘方&全域投放计划下素材」调用示例
 * ============================================================================
 *  接口：POST https://api.oceanengine.com/open_api/v1.0/qianchuan/uni_promotion/ad/material/add/
 *  文档：https://open.oceanengine.com/labels/12/docs/1835232814536707
 *
 *  <p><b>增量添加</b>：只传要新增的素材，计划里原有素材不受影响。</p>
 *  <p>⚠ 别用 {@code overall_video/update}（编辑乘方商品投放计划）来做这件事 ——
 *  那个接口是<b>全量更新</b>，只传新素材会把原有素材全抹掉。</p>
 *
 *  <p><b>素材从哪来：</b>不用手抄。配置区只填「抖音号 uid + 商品id」，
 *  本类会调 {@link AwemeMaterialFinder}（接口【获取投放计划可排除抖音视频/图文列表】）
 *  自动查出这个号下能投这个商品的全部素材。</p>
 *
 *  <p>用法：只改下面的「配置区」，然后跑 main。</p>
 *  <pre>
 *  预览（默认）：  java ... AddFootageDemo
 *  真实添加：      java ... AddFootageDemo --create
 *  只看素材池：    java ... AddFootageDemo --list-materials
 *  临时换计划：    java ... AddFootageDemo --ad-id=1234567890
 *  手填素材时：    java ... AddFootageDemo --no-find      （跳过自动查素材）
 *  只用某几条：    java ... AddFootageDemo --pick-ids=111,222
 *  </pre>
 */
public class AddFootageDemo {

    // ========================================================================
    // 配置区（只改这里）
    // ========================================================================

    /** true = 只打印请求体不真发（默认）；false = 真实调用。也可命令行 --dry-run / --create 覆盖 */
    private static final boolean DRY_RUN = false;

    /** 千川客户 id —— 后台右上角头像旁那个 ID。→ 请求体 {@code advertiser_id} */
    private static final long ADVERTISER_ID = 1823379540880396L;

    /**
     * ⚠ 必填：要往哪个计划加素材。→ 请求体 {@code ad_id}
     *
     * <p>可在千川后台计划列表里看，或用【获取千川投放计划列表】接口查。</p>
     */
    private static final long AD_ID = 1877746196794116L;

    /**
     * 抖音号 id（纯数字 uid）。→ 请求体 {@code multi_product_creative_list[].aweme_uid}
     *
     * <p><b>多号场景必填</b>，单号场景可留 null。</p>
     * <p>这个值同时是「自动查素材」的入参之一，别填后台那个「抖音号」字符串。</p>
     */
    private static final Long AWEME_UID = 3660744842026395L;

    /**
     * 商品 id 列表（抖音商品ID，19位数字）。
     * → 请求体 {@code multi_product_creative_list[].product_id}
     *
     * <p>也是「自动查素材」的入参之一。</p>
     */
    private static final List<Long> PRODUCT_IDS = List.of(3518517808038344366L);

    // ------------------------------------------------------------------
    // 素材：下面两个列表留空 → 自动按「抖音号 + 商品id」去查
    // ------------------------------------------------------------------

    /**
     * 素材列表留空时，是否自动查素材并填进来。命令行 {@code --no-find} 可关掉。
     *
     * <p>⚠ 自动查素材要走网络（只读 GET），所以 dry-run 时如果素材列表是空的，
     * 也会去取一次 token —— 想纯离线预览就先把下面两个列表手工填上。</p>
     */
    private static final boolean AUTO_FIND_MATERIALS = true;

    /**
     * 自动查到的素材取哪一列：
     * <ul>
     *   <li>{@code AWEME_ITEM_ID}（默认）→ 抖音主页视频，填 {@link #AWEME_ITEM_IDS}</li>
     *   <li>{@code VIDEO_ID} → 素材库/自选视频，填 {@link #VIDEO_IDS}</li>
     * </ul>
     * 命令行 {@code --material-field=} 可临时覆盖。
     */
    private static final String MATERIAL_ID_FIELD = "AWEME_ITEM_ID";

    /** 自动查到的素材最多用几条，0 = 全用。 */
    private static final int MATERIAL_LIMIT = 0;

    /** 白名单：只投这几个 id，空 = 全用。命令行 {@code --pick-ids=} 优先。 */
    private static final List<Long> MATERIAL_PICK = List.of();

    /**
     * 要新增的<b>抖音主页视频</b>（aweme_item_id，纯数字）。
     * → 请求体 {@code multi_product_creative_list[].video_material[].aweme_item_id}
     *
     * <p>留空 = 交给自动查素材填。填了这类素材 → 平台判为「抖音主页视频」
     * → {@link #TITLES} <b>必须留空</b>。</p>
     */
    private static final List<Long> AWEME_ITEM_IDS = List.of();

    /**
     * 要新增的<b>素材库视频</b>（video_id，形如 {@code v0200fg10000...}）。
     * → 请求体 {@code multi_product_creative_list[].video_material[].video_id}
     *
     * <p>填了这类素材 → 平台判为「非主页视频」→ {@link #TITLES} <b>必须至少填一条</b>，否则素材不生效。</p>
     */
    private static final List<String> VIDEO_IDS = List.of();

    /**
     * 创意标题，10~110 字符，汉字算 2 个字符。
     * → 请求体 {@code multi_product_creative_list[].title_material[].title}
     *
     * <p>规则：素材全是抖音主页视频 → 不能带标题；只要有非主页视频/图片 → 至少一条标题。</p>
     */
    private static final List<String> TITLES = List.of();

    // ========================================================================

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.out.println();
            System.out.println("❌ " + e.getMessage());
        } catch (ApiException e) {
            // 平台侧业务报错（参数非法 / 计划不存在等）走这里，打成可读的一行，不冒栈
            System.out.println();
            System.out.println("❌ 接口返回异常 code=" + e.getCode());
            System.out.println("   " + e.getResponseBody());
        } catch (Exception e) {
            System.out.println();
            System.out.println("❌ 运行出错：" + e.getClass().getSimpleName() + " " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void run(String[] args) throws Exception {

        long adId = AD_ID;
        for (String a : args) {
            if (a.startsWith("--ad-id=")) {
                adId = Long.parseLong(a.substring("--ad-id=".length()).trim());
            }
        }

        boolean dryRun;
        if (hasFlag(args, "--dry-run")) {
            dryRun = true;
        } else if (hasFlag(args, "--create")) {
            dryRun = false;
        } else {
            dryRun = DRY_RUN;
        }

        if (adId == 0L) {
            throw new IllegalStateException("""
                    AD_ID 还没填（要往哪个计划加素材）。
                    在千川后台的计划列表里能看到计划 id，也可以用【获取千川投放计划列表】接口查。
                    或者命令行传：--ad-id=1234567890""");
        }

        // ---- 素材：配置区填了就用配置区，没填就自动查 ----
        List<Long> awemeItemIds = AWEME_ITEM_IDS;
        List<String> videoIds = VIDEO_IDS;

        boolean needFind = awemeItemIds.isEmpty()
                && videoIds.isEmpty()
                && AUTO_FIND_MATERIALS
                && !hasFlag(args, "--no-find");

        if (needFind) {
            Resolved resolved = resolveMaterials(args);
            awemeItemIds = resolved.awemeItemIds();
            videoIds = resolved.videoIds();

            // --list-materials：只看素材池，不组装也不发请求
            if (hasFlag(args, "--list-materials")) {
                System.out.println();
                System.out.println("=== 只看素材池，未组装请求（--list-materials）===");
                return;
            }
        } else if (awemeItemIds.isEmpty() && videoIds.isEmpty()) {
            throw new IllegalStateException("""
                    AWEME_ITEM_IDS 和 VIDEO_IDS 至少要填一个。
                    两种办法：
                      a) 什么都不用填，让程序自动查 —— 确保 AUTO_FIND_MATERIALS=true，
                         且配置区 AWEME_UID + PRODUCT_IDS 是对的；
                      b) 手工填 —— 先跑 AwemeMaterialFinder 拿到素材 id 再抄进来。""");
        }

        AddFootageParam param = buildParam(adId, awemeItemIds, videoIds);

        System.out.println("⓪ 账户 ID      = " + ADVERTISER_ID);
        System.out.println("   计划 ID      = " + adId);
        System.out.println("   抖音号 uid   = " + (AWEME_UID == null ? "(未填)" : AWEME_UID));
        System.out.println("   商品 ID      = " + PRODUCT_IDS);
        System.out.println("   主页视频     = " + awemeItemIds.size() + " 条");
        System.out.println("   素材库视频   = " + videoIds.size() + " 条");
        System.out.println("   标题         = " + TITLES.size() + " 条");

        // prepare() 只做本地校验 + 组装，不发请求。
        // dry-run 且素材是手工填的时候不取 token，纯离线预览；真实调用才去取。
        AddFootageService service = dryRun
                ? new AddFootageService(null)
                : new AddFootageService();
        QianchuanUniPromotionAdMaterialAddV10Request request = service.prepare(param);
        System.out.println("① 参数校验通过");

        System.out.println();
        System.out.println("=== 请求体 ===");
        System.out.println(request.toJson());
        System.out.println();

        if (dryRun) {
            System.out.println("=== DRY-RUN，未真正添加 ===");
            System.out.println("确认无误后加 --create（或把 DRY_RUN 改成 false）再跑一次。");
            return;
        }

        System.out.println("② 真实调用 ===");
        System.out.println(service.add(param));
    }

    // ========================================================================
    // 自动查素材：抖音号 uid + 商品id → 素材 id
    //   接口：GET /open_api/v1.0/qianchuan/uni_promotion/block_material/get/
    //   实现：com.example.oceanengine.ccreate_plan.AwemeMaterialFinder
    // ========================================================================

    /** 自动查素材的结果：按所选字段拆成「主页视频」和「素材库视频」两列。 */
    private record Resolved(List<Long> awemeItemIds, List<String> videoIds) {
    }

    private static Resolved resolveMaterials(String[] args) throws Exception {

        if (AWEME_UID == null) {
            throw new IllegalStateException("自动查素材需要抖音号 uid：请填配置区 AWEME_UID，"
                    + "或改用 --no-find 手工填素材。查 uid 可跑 CreatePlanDemo --list-aweme");
        }
        if (PRODUCT_IDS.isEmpty()) {
            throw new IllegalStateException("自动查素材需要商品id：请填配置区 PRODUCT_IDS。"
                    + "查商品id 可跑【获取全域计划下商品列表】(uni_promotion/product/get，必须带 aweme_id)");
        }

        String field = argValue(args, "material-field");
        if (isBlank(field)) {
            field = MATERIAL_ID_FIELD;
        }
        boolean byVideoId = "VIDEO_ID".equalsIgnoreCase(field.trim());

        String mediaType = argValue(args, "media-type");

        System.out.println("⚙ 配置区素材为空 → 自动查素材【获取投放计划可排除抖音视频/图文列表】");
        System.out.println("   aweme_uid=" + AWEME_UID
                + "  product_ids=" + PRODUCT_IDS
                + "  media_type=" + (isBlank(mediaType) ? "VIDEO" : mediaType.toUpperCase())
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
                            + " 查到 0 条素材。确认这两个值是否配对、该抖音号是否已授权给本账户，"
                            + "可先用 AwemeMaterialFinder --list-aweme-video 单独看一眼。");
        }

        // 白名单：--pick-ids= 优先，其次配置区 MATERIAL_PICK；都空 = 全用
        List<Long> pick = splitLongCsv(argValue(args, "pick-ids"));
        if (pick == null || pick.isEmpty()) {
            pick = MATERIAL_PICK;
        }
        if (pick != null && !pick.isEmpty()) {
            Set<String> keep = new LinkedHashSet<>();
            for (Long id : pick) {
                keep.add(String.valueOf(id));
            }
            List<AwemeMaterialFinder.Material> filtered = new ArrayList<>();
            for (AwemeMaterialFinder.Material m : found) {
                if (keep.contains(String.valueOf(m.awemeItemId())) || keep.contains(m.videoId())) {
                    filtered.add(m);
                }
            }
            if (filtered.isEmpty()) {
                throw new IllegalStateException("白名单过滤后一条素材都不剩。"
                        + "MATERIAL_PICK / --pick-ids 里填的 id 都不在接口返回的候选池里，"
                        + "检查是否抄错、或抖音号 / 商品id 是否配对。");
            }
            found = filtered;
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

        // 按所选字段拆成两列
        List<Long> items = new ArrayList<>();
        List<String> vids = new ArrayList<>();
        for (AwemeMaterialFinder.Material m : found) {
            if (byVideoId) {
                if (m.videoId() != null && !m.videoId().isBlank()) {
                    vids.add(m.videoId());
                }
            } else {
                if (m.awemeItemId() != null) {
                    items.add(m.awemeItemId());
                }
            }
        }
        if (items.isEmpty() && vids.isEmpty()) {
            throw new IllegalStateException("接口返回 " + found.size() + " 条素材，但没有一条带 "
                    + (byVideoId ? "video_id" : "aweme_item_id")
                    + "，换个 --material-field 试试。");
        }
        System.out.println("   → 主页视频 " + items.size() + " 条，素材库视频 " + vids.size() + " 条");
        System.out.println();

        return new Resolved(items, vids);
    }

    // ========================================================================
    // 配置区 → 入参对象
    // ========================================================================

    private static AddFootageParam buildParam(long adId, List<Long> awemeItemIds, List<String> videoIds) {
        AddFootageParam param = new AddFootageParam()
                .advertiserId(ADVERTISER_ID)
                .adId(adId);

        if (PRODUCT_IDS.isEmpty()) {
            throw new IllegalStateException("PRODUCT_IDS 不能为空");
        }
        if (awemeItemIds.isEmpty() && videoIds.isEmpty()) {
            throw new IllegalStateException("AWEME_ITEM_IDS 和 VIDEO_IDS 至少要填一个");
        }

        // 一个商品一条 creative；多个商品共用同一批素材。
        // 不同商品要用不同素材时，改成在循环里分别构造。
        for (Long productId : PRODUCT_IDS) {
            AddFootageParam.Creative creative = new AddFootageParam.Creative()
                    .productId(productId)
                    .awemeUid(AWEME_UID);

            for (Long itemId : awemeItemIds) {
                creative.videos(AddFootageParam.Video.awemeItem(itemId));
            }
            for (String videoId : videoIds) {
                creative.videos(AddFootageParam.Video.videoId(videoId));
            }
            for (String title : TITLES) {
                creative.titles(AddFootageParam.Title.custom(title));
            }
            param.creative(creative);
        }
        return param;
    }

    // ========================================================================
    // 命令行小工具
    // ========================================================================

    private static boolean hasFlag(String[] args, String flag) {
        return args != null && new ArrayList<>(List.of(args)).contains(flag);
    }

    /** 取 --key=value 里的 value，没有则返回 null。 */
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

    /** 逗号分隔的 id 串 → List&lt;Long&gt;，空串返回 null。 */
    private static List<Long> splitLongCsv(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        List<Long> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            String s = p.trim();
            if (!s.isEmpty()) {
                out.add(Long.valueOf(s));
            }
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
