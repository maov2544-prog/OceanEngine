package com.example.oceanengine.plant_search;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanUniAwemeAuthorizedGetV10Api;
import com.bytedance.ads.model.QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner;
import com.example.oceanengine.client.QianchuanTokenClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 临时诊断：排查 AWEME 关键词检索为何 0 条，用完即删。 */
public class DiagAweme {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient C =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private static final String ADV = "1823379540880396";
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        String token = QianchuanTokenClient.getAccessToken();
        String target = "7569236031343428667";

        LocalDateTime now = LocalDateTime.now();
        String st = URLEncoder.encode(now.minusDays(180).format(FMT), StandardCharsets.UTF_8);
        String et = URLEncoder.encode(now.format(FMT), StandardCharsets.UTF_8);
        String fields = URLEncoder.encode("[\"stat_cost\"]", StandardCharsets.UTF_8);

        // 1) 两种营销目标各拉全部计划
        List<JsonNode> videoPlans = fetchAll(token, "VIDEO_PROM_GOODS", st, et, fields, null);
        List<JsonNode> livePlans = fetchAll(token, "LIVE_PROM_GOODS", st, et, fields, null);
        System.out.println("VIDEO 计划数=" + videoPlans.size() + "，LIVE 计划数=" + livePlans.size());

        // 2) 打印所有去重主播 anchor_id / name
        Map<String, String> anchors = new LinkedHashMap<>();
        for (JsonNode ad : videoPlans) collectAnchors(ad, anchors);
        for (JsonNode ad : livePlans) collectAnchors(ad, anchors);
        System.out.println("---- 全部主播 (" + anchors.size() + ") ----");
        List<String> anchorLines = new ArrayList<>();
        for (Map.Entry<String, String> e : anchors.entrySet()) {
            String mark = e.getKey().equals(target) ? "   <<< TARGET" : "";
            System.out.println("anchor_id=" + e.getKey() + "  name=" + e.getValue() + mark);
            anchorLines.add("抖音号=" + e.getKey() + "  昵称=" + e.getValue() + mark);
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("target/anchors.txt"),
                anchorLines, StandardCharsets.UTF_8);

        // 3) 命中 target 的计划原始 JSON
        System.out.println("---- target 命中计划 ----");
        boolean any = false;
        for (JsonNode ad : videoPlans) {
            if (hasAnchor(ad, target)) { System.out.println(ad.toString()); any = true; }
        }
        for (JsonNode ad : livePlans) {
            if (hasAnchor(ad, target)) { System.out.println(ad.toString()); any = true; }
        }
        if (!any) System.out.println("(两种目标下均无 anchor_id=" + target + " 的计划)");

        // 4) 用 target 做 AWEME 关键词检索
        keywordSearch(token, st, et, fields, target);

        // 4.5) 查全部已授权号，看 long UID target 是否存在（授权列表含 awemeId 长ID）
        ApiClient pc = new ApiClient();
        pc.setBasePath("https://api.oceanengine.com");
        pc.addDefaultHeader("Access-Token", token);
        QianchuanUniAwemeAuthorizedGetV10Api authApi =
                new QianchuanUniAwemeAuthorizedGetV10Api(pc);
        List<String> authLines = new ArrayList<>();
        boolean uidFound = false;
        for (long p = 1; p <= 60; p++) {
            var resp = authApi.openApiV10QianchuanUniAwemeAuthorizedGetGet(
                    Long.valueOf(ADV), null, p, 100L);
            var data = resp == null ? null : resp.getData();
            if (data == null || data.getAwemeIdList() == null) break;
            for (QianchuanUniAwemeAuthorizedGetV10ResponseDataAwemeIdListInner a
                    : data.getAwemeIdList()) {
                boolean isT = a.getAwemeId() != null && a.getAwemeId().toString().equals(target);
                if (isT) uidFound = true;
                authLines.add("uid=" + a.getAwemeId() + "  showId=" + a.getAwemeShowId()
                        + "  name=" + a.getAwemeName() + (isT ? "   <<< TARGET" : ""));
            }
            if (data.getPageInfo() == null || data.getPageInfo().getTotalPage() == null
                    || p >= data.getPageInfo().getTotalPage()) break;
            Thread.sleep(500);
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("target/auth.txt"),
                authLines, StandardCharsets.UTF_8);
        System.out.println("已授权号总数=" + authLines.size() + "，target UID 是否在授权列表=" + uidFound);

        // 5) 对照组：用列表中已知的抖音号做检索（验证检索匹配的是 show_id）
        String[] known = anchors.keySet().toArray(new String[0]);
        keywordSearch(token, st, et, fields, known[0]);
        if (known.length > 1) keywordSearch(token, st, et, fields, known[1]);
    }

    private static void keywordSearch(String token, String st, String et, String fields,
                                      String keyword) throws Exception {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", "ALL");
        f.put("search_keyword", keyword);
        f.put("search_keyword_type", "AWEME");
        List<JsonNode> kwHits = fetchAll(token, "VIDEO_PROM_GOODS", st, et, fields,
                URLEncoder.encode(M.writeValueAsString(f), StandardCharsets.UTF_8));
        System.out.println("---- AWEME 关键词「" + keyword + "」检索计划数=" + kwHits.size());
        for (JsonNode ad : kwHits) {
            System.out.println("     id=" + ad.path("ad_info").path("id").asLong()
                    + " name=" + ad.path("ad_info").path("name").asText("")
                    + " anchors=" + M.writeValueAsString(ad.path("room_info")));
        }
    }

    private static List<JsonNode> fetchAll(String token, String goal, String st, String et,
                                           String fields, String filteringEnc) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        int totalPage = 1;
        for (int page = 1; page <= totalPage && page <= 60; page++) {
            StringBuilder url = new StringBuilder(
                    "https://api.oceanengine.com/open_api/v1.0/qianchuan/uni_promotion/list/")
                    .append("?advertiser_id=").append(ADV)
                    .append("&marketing_goal=").append(goal)
                    .append("&start_time=").append(st)
                    .append("&end_time=").append(et)
                    .append("&page=").append(page)
                    .append("&page_size=100")
                    .append("&fields=").append(fields);
            if (filteringEnc != null) {
                url.append("&filtering=").append(filteringEnc);
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .header("Access-Token", token).GET().build();

            JsonNode root = null;
            String body = "";
            for (int attempt = 1; attempt <= 4; attempt++) {
                HttpResponse<String> resp = C.send(req, HttpResponse.BodyHandlers.ofString());
                body = resp.body();
                root = M.readTree(body);
                int code = root.path("code").asInt(-1);
                if (code == 0) break;
                if (code == 40100 && attempt < 4) {
                    System.out.println("   频控，等待 " + (10 * attempt) + "s 后重试...");
                    Thread.sleep(10_000L * attempt);
                    continue;
                }
                throw new IllegalStateException(goal + " page" + page + " 错误: " + body);
            }
            JsonNode data = root.path("data");
            for (JsonNode ad : data.path("ad_list")) {
                all.add(ad);
            }
            totalPage = data.path("page_info").path("total_page").asInt(1);
        }
        return all;
    }

    private static void collectAnchors(JsonNode ad, Map<String, String> anchors) {
        for (JsonNode r : ad.path("room_info")) {
            String id = r.path("anchor_id").asText("");
            String name = r.path("anchor_name").asText("");
            if (!id.isEmpty()) anchors.put(id, name);
        }
    }

    private static boolean hasAnchor(JsonNode ad, String anchorId) {
        for (JsonNode r : ad.path("room_info")) {
            if (anchorId.equals(r.path("anchor_id").asText())) return true;
        }
        return false;
    }
}
