package com.example.oceanengine.chen;

import com.bytedance.ads.ApiClient;
import com.example.oceanengine.client.ApiClients;
import com.bytedance.ads.api.QianchuanAwemeVideoGetV10Api;
import com.bytedance.ads.api.QianchuanUniAwemeAuthorizedGetV10Api;
import com.bytedance.ads.api.QianchuanUniPromotionProductAwemeGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeVideoGetV10DataPageInfoHasMore;
import com.bytedance.ads.model.QianchuanAwemeVideoGetV10MarketingGoal;
import com.bytedance.ads.model.QianchuanAwemeVideoGetV10ResponseDataVideoListInner;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10Filtering;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10FilteringMarketingGoal;
import com.bytedance.ads.model.QianchuanUniPromotionProductAwemeGetV10Filtering;
import com.bytedance.ads.model.QianchuanUniPromotionProductAwemeGetV10FilteringTab;
import com.bytedance.ads.model.QianchuanUniPromotionProductAwemeGetV10Platform;
import com.example.oceanengine.client.QianchuanTokenClient;
import com.example.oceanengine.client.SuixintuiTokenClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================================
 *  抖音分享短链 -> 千川商品ID（Java 版 · 自动取 Token）
 * ============================================================================
 *  链路：
 *    ⓪ 自动取 Token
 *    ① 分享口令 -> 19位视频ID (aweme_item_id)
 *    ② 抖音号 uid（优先视频ID直查作者；其次并发翻全部已授权号做匹配）
 *    ③ 翻该抖音号的视频列表（每页20条，接口上限）-> 匹配视频ID -> product_info.id
 *    ④ 兜底：按品名反查商品
 *
 *  放哪里：src/main/java/com/example/oceanengine/service/qianchuan/ProductIdFromVideo.java
 * ============================================================================
 */
public class ProductIdFromVideo {

    // ========================================================================
    // 配置区：只改这里
    // ========================================================================

    /** false = 每次运行自动取 token（推荐）；true = 用下面手填的 */
    private static final boolean USE_MANUAL_TOKEN = false;

    /** USE_MANUAL_TOKEN = true 时才用这个（千川PC） */
    private static final String MANUAL_ACCESS_TOKEN = "";

    /** USE_MANUAL_TOKEN = true 时才用这个（随心推） */
    private static final String MANUAL_SUIXINTUI_TOKEN = "";

    /**
     * 随心推接口（③④）的 token 从哪来：
     *   true  = 用 SuixintuiTokenClient 单独取（推荐），取不到时自动降级
     *   false = 直接复用千川PC的 token
     */
    private static final boolean SUIXINTUI_USE_OWN_TOKEN = true;

    private static final String ADVERTISER_ID = "1823379540880396";         // 千川账户ID
    private static final String SHARE_TEXT =
            "1.53 复制打开抖音，看看【辰辰麻麻🌙的作品】# 澳兰黛 # 儿童面霜 "
            + "# 澳兰黛婴儿面霜 # ... https://v.douyin.com/v0DJ0HJy7zY/ 09/12 YMJ:/ I@v.SY :3pm ";
    private static final String AWEME_NICKNAME = "辰辰麻麻🌙";               // 抖音号昵称
    private static final String FALLBACK_PRODUCT_NAME = "澳兰黛";            // 兜底反查用的品名

    /** 已知抖音号uid时直接填（纯数字！），非空则跳过②的查询 */
    private static final String MANUAL_AWEME_UID = "";

    /** 后台看到的抖音号字符串（如 9168360782），比昵称可靠，优先用它精确匹配 */
    private static final String MANUAL_DOUYIN_ID = "";

    /** 短链解析失败时，手工填19位视频ID */
    private static final String MANUAL_ITEM_ID = "";

    /** 并发翻页线程数：撞限流就调小到 3 */
    private static final int FETCH_THREADS = 6;

    // ========================================================================
    // 运行时状态
    // ========================================================================
    private static String pcToken;   // 千川PC
    private static String sxToken;   // 随心推
    private static ApiClient sdkClient;
    private static String lastLandingBody = "";   // 短链最终落地页 HTML
    private static String lastLandingUrl  = "";   // 短链最终落地 URL
    private static long autoAuthorUid = 0;        // 从分享参数 social_author_id 解析出的作者 uid

    // ========================================================================
    // 常量
    // ========================================================================
    private static final String API_HOST = "https://api.oceanengine.com";
    private static final String AD_HOST  = "https://ad.oceanengine.com";

    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ========================================================================
    // 主流程
    // ========================================================================
    public static void main(String[] args) throws Exception {

        // ---------- 前置校验 ----------
        if ("123456".equals(ADVERTISER_ID) || ADVERTISER_ID.trim().isEmpty()) {
            throw new IllegalStateException(
                    "ADVERTISER_ID 还是占位符「" + ADVERTISER_ID + "」，没改成你的真实千川账户ID！");
        }
        System.out.println("⓪ 使用账户 ID = " + ADVERTISER_ID);

        // ---------- ⓪ Token ----------
        if (USE_MANUAL_TOKEN) {
            pcToken = MANUAL_ACCESS_TOKEN;
            sxToken = MANUAL_SUIXINTUI_TOKEN.isEmpty() ? pcToken : MANUAL_SUIXINTUI_TOKEN;
            System.out.println("⓪ 使用手动 Token，千川PC长度=" + pcToken.length()
                    + "，随心推长度=" + sxToken.length());
        } else {
            pcToken = QianchuanTokenClient.getAccessToken();
            System.out.println("⓪ 千川PC Token 获取成功，长度 = " + pcToken.length());

            if (SUIXINTUI_USE_OWN_TOKEN) {
                try {
                    sxToken = SuixintuiTokenClient.getAccessToken();
                    System.out.println("⓪ 随心推 Token 获取成功，长度 = " + sxToken.length());
                } catch (Exception e) {
                    sxToken = pcToken;
                    System.out.println("⓪ 随心推 Token 获取失败，降级复用千川PC Token：" + e.getMessage());
                }
            } else {
                sxToken = pcToken;
            }
        }
        sdkClient = new ApiClient();
        sdkClient.setBasePath(API_HOST);
        ApiClients.configure(sdkClient);
        sdkClient.addDefaultHeader("Access-Token", sxToken);

        // ---------- ① 视频ID ----------
        String itemId = resolveItemId();
        System.out.println("① 视频ID       = " + itemId);

        // ---------- ② 抖音号uid ----------
        // 优先级：手动填 uid > 视频ID直查作者 > 分享参数 social_author_id > 授权号检索匹配
        long uid;
        if (!MANUAL_AWEME_UID.isEmpty()) {
            uid = Long.parseLong(MANUAL_AWEME_UID.trim());
            System.out.println("   已指定 uid，跳过查询");
        } else {
            uid = resolveAuthorUidByItemId(itemId);
            if (uid == 0) {
                if (autoAuthorUid == 0) {
                    // 短链每一跳的 URL 都扫过了，这里再兜底扫一次口令原文 + 最终落地 URL
                    autoAuthorUid = extractAuthorIdFromShare(SHARE_TEXT + " " + lastLandingUrl);
                }
                if (autoAuthorUid != 0) {
                    uid = autoAuthorUid;
                    System.out.println("   [分享链接] social_author_id 命中 uid=" + uid
                            + "（抖音分享参数自带，不走风控接口）");
                }
            }
            if (uid == 0) {
                uid = findAwemeUid();
            }
        }
        System.out.println("② 抖音号uid    = " + uid);

        // ---------- ③ 商品ID ----------
        JsonNode hit;
        try {
            hit = findVideoProduct(uid, itemId);
        } catch (Exception e) {
            System.out.println();
            System.out.println("取视频列表失败：" + e.getMessage());
            System.out.println("   多半是抖音号 uid=" + uid + " 未授权给千川账户 "
                    + ADVERTISER_ID + "，请去千川后台做授权后重试");
            return;
        }

        if (hit != null) {
            JsonNode pi = hit.path("product_info");
            System.out.println();
            System.out.println("✅ 商品ID       = " + pi.path("id").asText());
            System.out.println("   商品名称     = " + pi.path("name").asText(""));
            System.out.println("   视频标题     = " + hit.path("title").asText(""));
            System.out.println("   商品主图     = " + pi.path("img").asText(""));
            return;
        }

        // ---------- ④ 兜底 ----------
        System.out.println();
        System.out.println("可投视频列表未匹配到该视频，转用品名反查：");
        searchProductByName(uid);
    }

    // ========================================================================
    // ① 短链 -> 视频ID（手动多跳跟随，最多10跳，同时检查URL与正文）
    // ========================================================================
    private static String resolveItemId() throws Exception {
        if (!MANUAL_ITEM_ID.isEmpty()) {
            return MANUAL_ITEM_ID;
        }

        Matcher m = Pattern.compile("https?://v\\.douyin\\.com/[\\w\\-]+/?").matcher(SHARE_TEXT);
        if (!m.find()) {
            throw new IllegalStateException("未能从 SHARE_TEXT 中提取到 v.douyin.com 短链");
        }
        String url = m.group();
        System.out.println("   短链        = " + url);

        for (int hop = 1; hop <= 10; hop++) {
            HttpURLConnection conn = null;
            int status = 0;
            String location = null;
            String body = "";

            try {
                conn = (HttpURLConnection) toUrl(url).openConnection();
                conn.setInstanceFollowRedirects(false);   // 关掉自动跳转，自己读 Location
                conn.setRequestProperty("User-Agent", MOBILE_UA);
                conn.setRequestProperty("Referer", "https://www.douyin.com/");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(20000);

                status = conn.getResponseCode();
                location = conn.getHeaderField("Location");
                if (status == 200) {
                    body = readStream(conn.getInputStream());
                    lastLandingBody = body;      // 留住落地页 HTML，②步可从中抠作者 uid
                    lastLandingUrl  = url;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

            String id = extractItemId(url);
            if (id == null) {
                id = extractItemId(body);
            }

            // 分享链路参数 activity_info.social_author_id 即作者数字 uid，
            // 每一跳的 URL / 正文都扫一遍，找到就留住（不依赖 iteminfo，不受风控影响）
            if (autoAuthorUid == 0) {
                autoAuthorUid = extractAuthorIdFromShare(url);
                if (autoAuthorUid == 0 && !body.isEmpty()) {
                    autoAuthorUid = extractAuthorIdFromShare(body);
                }
            }

            if (id != null) {
                System.out.println("   最终落地    = " + url);
                return id;
            }

            boolean isRedirect = status == 301 || status == 302 || status == 303
                    || status == 307 || status == 308;
            if (isRedirect && location != null && !location.isEmpty()) {
                url = resolveRelative(url, location.trim());
                System.out.println("   第" + hop + "跳       = " + url);
                continue;
            }

            System.out.println("   停止在      = " + url + "（HTTP " + status + "）");
            break;
        }

        throw new IllegalStateException(
                "短链解析失败（可能被风控/已过期/视频私密）。\n"
                + "请用手机复制链接在浏览器打开，取 /video/ 后的19位数字，填入 MANUAL_ITEM_ID。");
    }

    private static String extractItemId(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] pats = {
                "/video/(\\d{10,25})",
                "/share/video/(\\d{10,25})",
                "modal_id=(\\d{10,25})",
                "item_id=(\\d{10,25})"
        };
        for (String p : pats) {
            Matcher mm = Pattern.compile(p).matcher(text);
            if (mm.find()) {
                return mm.group(1);
            }
        }
        return null;
    }

    /**
     * 从分享链接里提取作者数字 uid。
     *
     * <p>抖音分享短链展开后的 URL 带 activity_info 参数（URL 编码的 JSON），
     * 形如 {@code activity_info=%7B%22social_author_id%22%3A%223206653666920558%22...}，
     * 其中 social_author_id 就是作者的数字 uid。先 URL 解码再正则匹配。</p>
     *
     * @return 作者 uid；取不到返回 0
     */
    private static long extractAuthorIdFromShare(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = raw;
        }
        Matcher m = Pattern.compile("social_author_id[\"']?\\s*[:=]\\s*\"?(\\d{5,25})")
                .matcher(decoded);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /** 读流，流为 null 时返回空串（避免 4xx 且无响应体时 NPE） */
    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    // ========================================================================
    // ②-0  视频ID -> 作者uid（非官方路径，取不到返回 0，调用方自动回退）
    // ========================================================================

    /** 通用 HTTP GET，不走千川鉴权，返回原始文本 */
    private static String httpGetRaw(String url, String referer) throws IOException {
        HttpURLConnection conn = null;
        int status = 0;
        String resp = "";
        try {
            conn = (HttpURLConnection) toUrl(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", MOBILE_UA);
            conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("Accept-Encoding", "identity"); // 不让服务端 gzip，省得解压
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            status = conn.getResponseCode();
            resp = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        if (status >= 400) {
            throw new IOException("HTTP " + status + " url=" + url);
        }
        return resp;
    }

    /** 从 itemId 直接查作者 uid；取不到返回 0 */
    private static long resolveAuthorUidByItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return 0;
        }

        // —— 取法 1：iesdouyin 公开 iteminfo 接口，返回结构化 JSON ——
        try {
            String api = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=" + itemId;
            String resp = httpGetRaw(api, "https://www.douyin.com/");
            JsonNode root = MAPPER.readTree(resp);
            JsonNode list = root.path("item_list");
            if (list.isArray() && !list.isEmpty()) {
                JsonNode author = list.get(0).path("author");
                String uid  = author.path("uid").asText("");
                String nick = author.path("nickname").asText("");
                if (!uid.isEmpty()) {
                    System.out.println("   [作者直查] uid=" + uid + "  昵称=" + nick);
                    return Long.parseLong(uid);
                }
            }
            System.out.println("   [作者直查] iteminfo 返回空列表（接口可能已被限制）");
        } catch (Exception e) {
            System.out.println("   [作者直查] iteminfo 失败：" + e.getMessage());
        }

        // —— 取法 2：从落地页 HTML 正则抠（兜底）——
        try {
            String html = lastLandingBody;
            if (html == null || html.isEmpty()) {
                html = httpGetRaw("https://www.iesdouyin.com/share/video/" + itemId,
                                  "https://www.douyin.com/");
            }
            Matcher mUid  = Pattern.compile("\"uid\"\\s*:\\s*\"(\\d{5,25})\"").matcher(html);
            Matcher mNick = Pattern.compile("\"nickname\"\\s*:\\s*\"([^\"]{1,60})\"").matcher(html);
            if (mUid.find()) {
                String nick = mNick.find() ? mNick.group(1) : "(未取到)";
                System.out.println("   [作者直查] 落地页抠到 uid=" + mUid.group(1)
                        + "  昵称=" + nick + "（请人工核对是否作者本人）");
                return Long.parseLong(mUid.group(1));
            }
            System.out.println("   [作者直查] 落地页无作者信息（可能撞风控验证码页）");
        } catch (Exception e) {
            System.out.println("   [作者直查] 落地页解析失败：" + e.getMessage());
        }
        return 0;
    }

    // ========================================================================
    // ②  查全部已授权抖音号（并发翻页 + 去重 + 精确匹配）
    // ========================================================================
    private static long findAwemeUid() throws Exception {
        Map<Long, Cand> uniq = new LinkedHashMap<>();
        ApiClient client = new ApiClient();
        client.setBasePath(API_HOST);
        ApiClients.configure(client);
        client.addDefaultHeader("Access-Token", pcToken);
        QianchuanUniAwemeAuthorizedGetV10Api api =
                new QianchuanUniAwemeAuthorizedGetV10Api(client);
        Long adv = Long.valueOf(ADVERTISER_ID);

        // —— ① 服务端关键词检索（覆盖全部授权号，不受翻页上限影响）——
        // 抖音号优先；没填抖音号就用昵称（去掉 emoji 后的核心词，服务端是模糊检索）
        String keyword = !MANUAL_DOUYIN_ID.isEmpty() ? MANUAL_DOUYIN_ID : clean(AWEME_NICKNAME);
        if (keyword != null && !keyword.isBlank()) {
            QianchuanUniAwemeAuthorizedGetV10FilteringMarketingGoal[] goals = {
                    QianchuanUniAwemeAuthorizedGetV10FilteringMarketingGoal.VIDEO_PROM_GOODS,
                    QianchuanUniAwemeAuthorizedGetV10FilteringMarketingGoal.LIVE_PROM_GOODS};
            for (var goal : goals) {
                collectAuthorizedSearch(api, adv, keyword, goal, uniq, 5);
                long hit = matchCandidate(uniq);
                if (hit != 0) {
                    return hit;
                }
            }
            System.out.println("   [SDK] 服务端检索「" + keyword + "」未精确命中，转全量翻页");
        }

        // —— ② 全量翻页兜底：按服务端 total_page 翻完，硬上限 200 页（2 万个号）——
        for (long page = 1; page <= 200; page++) {
            var response = api.openApiV10QianchuanUniAwemeAuthorizedGetGet(
                    adv, null, page, 100L);
            if (response == null || response.getData() == null
                    || response.getData().getAwemeIdList() == null) {
                break;
            }
            mergeCandidates(response.getData().getAwemeIdList(), uniq);
            var pi = response.getData().getPageInfo();
            if (pi == null || pi.getTotalPage() == null || page >= pi.getTotalPage()) {
                break;
            }
            Thread.sleep(300L);   // 放慢节奏，避免触发频控 40100
        }
        System.out.println("   [SDK] 完成，唯一抖音号 " + uniq.size() + " 个");

        if (uniq.isEmpty()) {
            throw new IllegalStateException("没查到任何已授权抖音号，请确认 ADVERTISER_ID 是否正确");
        }

        long hit = matchCandidate(uniq);
        if (hit != 0) {
            return hit;
        }

        // —— ③ 仍未命中：模糊列出候选，交人工确认 ——
        System.out.println("   未匹配到「" + AWEME_NICKNAME + "」，唯一 " + uniq.size() + " 个，含关键词的：");
        String key = clean(AWEME_NICKNAME);
        key = key.substring(0, Math.min(2, key.length()));
        int i = 1;
        for (Cand c : uniq.values()) {
            if (c.name != null && c.name.contains(key)) {
                System.out.println("   " + (i++) + ". uid=" + c.id
                        + "  抖音号=" + c.douyinId + "  昵称=[" + c.name + "]");
            }
        }
        throw new IllegalStateException(
                "请从上面复制正确昵称填 AWEME_NICKNAME，或把数字 uid 填 MANUAL_AWEME_UID 后重跑。");
    }

    /** 服务端关键词检索（search_key_words），逐页并入候选集合 */
    private static void collectAuthorizedSearch(
            QianchuanUniAwemeAuthorizedGetV10Api api, Long adv, String keyword,
            QianchuanUniAwemeAuthorizedGetV10FilteringMarketingGoal goal,
            Map<Long, Cand> uniq, int maxPages) throws Exception {
        QianchuanUniAwemeAuthorizedGetV10Filtering f =
                new QianchuanUniAwemeAuthorizedGetV10Filtering();
        f.setMarketingGoal(goal);
        f.setSearchKeyWords(keyword);
        for (long page = 1; page <= maxPages; page++) {
            var resp = api.openApiV10QianchuanUniAwemeAuthorizedGetGet(adv, f, page, 100L);
            var data = resp == null ? null : resp.getData();
            var list = data == null ? null : data.getAwemeIdList();
            if (list == null || list.isEmpty()) {
                break;
            }
            mergeCandidates(list, uniq);
            if (data.getPageInfo() == null || data.getPageInfo().getTotalPage() == null
                    || page >= data.getPageInfo().getTotalPage()) {
                break;
            }
        }
    }

    /** 把一页授权号并入去重集合 */
    private static void mergeCandidates(
            List<QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner> list,
            Map<Long, Cand> uniq) {
        for (var a : list) {
            if (a.getAwemeId() != null) {
                uniq.putIfAbsent(a.getAwemeId(),
                        new Cand(a.getAwemeId(), a.getAwemeName(), a.getAwemeShowId()));
            }
        }
    }

    /** 三级匹配：抖音号精确 → 昵称全等 → 去 emoji 清洗后相等；不中返回 0 */
    private static long matchCandidate(Map<Long, Cand> uniq) {
        if (!MANUAL_DOUYIN_ID.isEmpty()) {
            for (Cand c : uniq.values()) {
                if (MANUAL_DOUYIN_ID.equalsIgnoreCase(c.douyinId)) {
                    System.out.println("   抖音号命中：" + c.douyinId + " -> uid=" + c.id);
                    return c.id;
                }
            }
        }
        for (Cand c : uniq.values()) {
            if (AWEME_NICKNAME.equals(c.name)) {
                System.out.println("   精确命中：" + c.name + " -> uid=" + c.id);
                return c.id;
            }
        }
        String t = clean(AWEME_NICKNAME);
        if (!t.isEmpty()) {
            for (Cand c : uniq.values()) {
                if (t.equals(clean(c.name))) {
                    System.out.println("   去表情后命中：" + c.name + " -> uid=" + c.id);
                    return c.id;
                }
            }
        }
        return 0;
    }

    /** 拉一个数据源的全部页：首页探测总数 → 并发拉剩余页 */
    private static void fetchAll(String urlPattern, String token, Map<Long, Cand> uniq) throws Exception {
        final int pageSize = 100;
        final int maxPages = 60;              // 上限 6000 个，防止死循环

        // —— 1) 首页：探测服务端总数 ——
        JsonNode first;
        try {
            first = get(String.format(urlPattern, 1), token);
        } catch (Exception e) {
            System.out.println("   [拉取] 首页失败：" + e.getMessage());
            return;
        }
        int addedFirst = merge(first, uniq);
        int total = detectTotal(first);
        System.out.println("   [拉取] 首页 +" + addedFirst + "，累计 " + uniq.size()
                + (total > 0 ? "，服务端总数≈" + total : "（服务端未返回总数）"));

        int pages = total > 0
                ? Math.min(maxPages, (total + pageSize - 1) / pageSize)
                : maxPages;
        if (pages <= 1) {
            return;
        }

        // —— 2) 并发拉剩余页 ——
        ExecutorService pool = Executors.newFixedThreadPool(FETCH_THREADS);
        List<Future<List<Cand>>> futures = new ArrayList<>();
        try {
            for (int p = 2; p <= pages; p++) {
                final String url = String.format(urlPattern, p);
                futures.add(pool.submit(() -> {
                    try {
                        JsonNode d = get(url, token);
                        List<Cand> one = new ArrayList<>();
                        parseList(d, one);
                        return one;
                    } catch (Exception e) {
                        return new ArrayList<>();      // 单页失败不影响整体
                    }
                }));
            }
            int got = 0;
            for (Future<List<Cand>> f : futures) {
                for (Cand c : f.get()) {
                    if (uniq.putIfAbsent(c.id, c) == null) {
                        got++;                          // 只统计真正新增的
                    }
                }
            }
            System.out.println("   [拉取] 并发 " + (pages - 1) + " 页，新增 " + got + "，累计 " + uniq.size());
        } finally {
            pool.shutdown();
            pool.awaitTermination(60, TimeUnit.SECONDS);
        }
    }

    /** 解析一页并合并去重，返回真正新增的数量 */
    private static int merge(JsonNode data, Map<Long, Cand> uniq) {
        List<Cand> one = new ArrayList<>();
        parseList(data, one);
        int n = 0;
        for (Cand c : one) {
            if (uniq.putIfAbsent(c.id, c) == null) {
                n++;
            }
        }
        return n;
    }

    /** 探测服务端总数（字段名各家不统一，多试几个） */
    private static int detectTotal(JsonNode data) {
        if (data == null || data.isNull()) {
            return 0;
        }
        JsonNode pi = data.path("page_info");
        int t = pi.path("total_number").asInt(0);
        if (t == 0) t = pi.path("total_count").asInt(0);
        if (t == 0) t = pi.path("total").asInt(0);
        if (t == 0) t = data.path("total_number").asInt(0);
        if (t == 0) t = data.path("total_count").asInt(0);
        return t;
    }

    /** 从接口返回里抠出抖音号列表，兼容多种字段名 */
    private static void parseList(JsonNode data, List<Cand> out) {
        if (data == null || data.isNull()) {
            return;
        }
        String[] listFields = {"aweme_list", "list", "aweme_id_list", "data", "awemes"};
        for (String f : listFields) {
            JsonNode arr = data.path(f);
            if (arr.isArray()) {
                for (JsonNode a : arr) {
                    long id = a.path("aweme_id").asLong();
                    if (id == 0) {
                        id = a.path("aweme_id").path("id").asLong();
                    }
                    String name = a.path("aweme_name").asText("");
                    if (name.isEmpty()) {
                        name = a.path("name").asText("");
                    }
                    // 抖音号字符串：z1590389 这种
                    String dyId = a.path("douyin_id").asText("");
                    if (dyId.isEmpty()) dyId = a.path("douyinId").asText("");
                    if (dyId.isEmpty()) dyId = a.path("aweme_douyin_id").asText("");
                    if (dyId.isEmpty()) dyId = a.path("unique_id").asText("");

                    if (id != 0 && ((name != null && !name.isEmpty()) || !dyId.isEmpty())) {
                        out.add(new Cand(id, name == null ? "" : name, dyId));
                    }
                }
                return;
            }
        }
    }

    /** 候选：数字 uid + 昵称 + 抖音号字符串 */
    private static class Cand {
        long id;
        String name;
        String douyinId;
        Cand(long id, String name, String douyinId) {
            this.id = id; this.name = name; this.douyinId = douyinId;
        }
    }

    /** 去掉首尾空白、emoji、不可见字符 */
    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s\\u3000\\uFE0F\\u200D]", "")
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}]", "")
                .trim();
    }

    // ========================================================================
    // ③ 翻视频列表 -> 商品ID
    // ========================================================================
    private static JsonNode findVideoProduct(long uid, String itemId) throws Exception {
        long cursor = 0;
        int count = 20;                       // ⚠ 接口硬上限：count 最多 20，写 50 会报 40000
        QianchuanAwemeVideoGetV10Api api = new QianchuanAwemeVideoGetV10Api(sdkClient);

        for (int page = 1; page <= 100; page++) {   // 每页20条，放宽到100页（命中即停）
            var response = api.openApiV10QianchuanAwemeVideoGetGet(
                    Long.valueOf(ADVERTISER_ID), uid,
                    QianchuanAwemeVideoGetV10MarketingGoal.VIDEO_PROM_GOODS,
                    cursor, (long) count);
            var data = response == null ? null : response.getData();
            List<QianchuanAwemeVideoGetV10ResponseDataVideoListInner> videos =
                    data == null || data.getVideoList() == null
                            ? Collections.emptyList() : data.getVideoList();
            System.out.println("   第" + page + "页返回 " + videos.size() + " 条视频");

            for (QianchuanAwemeVideoGetV10ResponseDataVideoListInner v : videos) {
                if (v.getAwemeItemId() != null && itemId.equals(String.valueOf(v.getAwemeItemId()))) {
                    return MAPPER.readTree(v.toJson());
                }
            }

            if (data == null || data.getPageInfo() == null
                    || data.getPageInfo().getHasMore() != QianchuanAwemeVideoGetV10DataPageInfoHasMore.NUMBER_1) {
                System.out.println("   已翻完所有页，未匹配到视频ID " + itemId);
                return null;
            }
            cursor = data.getPageInfo().getCursor();
        }
        System.out.println("   达到最大页数，停止翻页");
        return null;
    }

    // ========================================================================
    // ④ 兜底：品名反查
    // ========================================================================
    private static void searchProductByName(long uid) throws Exception {
        QianchuanUniPromotionProductAwemeGetV10Filtering filtering =
            new QianchuanUniPromotionProductAwemeGetV10Filtering()
                .productName(FALLBACK_PRODUCT_NAME)
                .tab(QianchuanUniPromotionProductAwemeGetV10FilteringTab.ALL);
        QianchuanUniPromotionProductAwemeGetV10Api api =
            new QianchuanUniPromotionProductAwemeGetV10Api(sdkClient);
        var response = api.openApiV10QianchuanUniPromotionProductAwemeGetGet(
            Long.valueOf(ADVERTISER_ID), uid, filtering, 1L,
            QianchuanUniPromotionProductAwemeGetV10Platform.QIANCHUAN);
        var data = response == null ? null : response.getData();
        var products = data == null ? null : data.getProductList();
        if (products == null || products.isEmpty()) {
            System.out.println("   品名「" + FALLBACK_PRODUCT_NAME + "」没有搜到任何商品");
            return;
        }
        System.out.println("   候选商品（请按名称/主图人工确认）：");
        int i = 1;
        for (var p : products) {
            String gray = p.getGrayReason() == null ? "" : String.join(",", p.getGrayReason());
            System.out.println("   " + (i++) + ". ID=" + p.getId()
                + "  名称=" + (p.getName() == null ? "" : p.getName())
                + (gray.isEmpty() ? "" : "  ⚠ " + gray));
        }
    }

    // ========================================================================
    // 统一 GET
    // ========================================================================
    private static JsonNode get(String url, String token) throws Exception {
        HttpURLConnection conn = null;
        int status = 0;
        String resp = "";

        try {
            conn = (HttpURLConnection) toUrl(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Access-Token", token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            status = conn.getResponseCode();
            resp = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        if (resp.isEmpty()) {
            throw new RuntimeException("接口无响应体，HTTP=" + status + "，url=" + url);
        }

        JsonNode root = MAPPER.readTree(resp);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new RuntimeException(
                    "接口错误 code=" + code + " msg=" + root.path("message").asText("")
                    + " request_id=" + root.path("request_id").asText(""));
        }
        return root.path("data");
    }

    // ========================================================================
    // URL 工具：替代已弃用的 new URL(String)
    // ========================================================================

    /**
     * 字符串 -> URL，替代 Java 20+ 已弃用的 {@code new URL(String)}。
     */
    private static URL toUrl(String s) throws IOException {
        try {
            return URI.create(s).toURL();
        } catch (IllegalArgumentException e) {
            try {
                return new URI(null, null, s, null).toURL();
            } catch (URISyntaxException ex) {
                throw new IOException("无法解析为 URL: " + s, e);
            }
        }
    }

    /**
     * 把 Location 补全为绝对地址（可能是相对路径或协议相对路径）。
     */
    private static String resolveRelative(String baseUrl, String location) throws IOException {
        if (location == null || location.isEmpty()) {
            return baseUrl;
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        if (location.startsWith("//")) {           // 协议相对：//host/path
            return "https:" + location;
        }

        try {
            URI base = URI.create(baseUrl);
            String origin = base.getScheme() + "://" + base.getHost()
                    + (base.getPort() > 0 ? ":" + base.getPort() : "");
            return location.startsWith("/") ? origin + location : origin + "/" + location;
        } catch (IllegalArgumentException e) {
            return location;
        }
    }
}
