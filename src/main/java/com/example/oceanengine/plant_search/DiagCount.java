package com.example.oceanengine.plant_search;

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

/** 临时诊断：对比不同 adlab_scene / status / marketing_goal 下的计划数量，用完即删。 */
public class DiagCount {

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient C =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private static final String ADV = "1823379540880396";
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        String token = QianchuanTokenClient.getAccessToken();
        LocalDateTime now = LocalDateTime.now();
        String st = URLEncoder.encode(now.minusDays(180).format(FMT), StandardCharsets.UTF_8);
        String et = URLEncoder.encode(now.format(FMT), StandardCharsets.UTF_8);
        String fields = URLEncoder.encode("[\"stat_cost\"]", StandardCharsets.UTF_8);

        probe(token, st, et, fields, "VIDEO_PROM_GOODS", "UNI_PROJECT", "ALL");
        probe(token, st, et, fields, "VIDEO_PROM_GOODS", "OVERALL_PROJECT", "ALL");
        probe(token, st, et, fields, "VIDEO_PROM_GOODS", "UNI_PROJECT", "ALL_INCLUDE_DELETED");
        probe(token, st, et, fields, "VIDEO_PROM_GOODS", "OVERALL_PROJECT", "ALL_INCLUDE_DELETED");
        probe(token, st, et, fields, "LIVE_PROM_GOODS", "UNI_PROJECT", "ALL");
        probe(token, st, et, fields, "LIVE_PROM_GOODS", "OVERALL_PROJECT", "ALL");
        // 不带 adlab_scene（模拟当前代码，默认值）
        probeNoScene(token, st, et, fields, "VIDEO_PROM_GOODS", "ALL");
    }

    private static void probe(String token, String st, String et, String fields,
                              String goal, String scene, String status) throws Exception {
        StringBuilder url = new StringBuilder(
                "https://api.oceanengine.com/open_api/v1.0/qianchuan/uni_promotion/list/")
                .append("?advertiser_id=").append(ADV)
                .append("&marketing_goal=").append(goal)
                .append("&start_time=").append(st)
                .append("&end_time=").append(et)
                .append("&page=1&page_size=100")
                .append("&fields=").append(fields)
                .append("&adlab_scene=").append(scene)
                .append("&filtering=").append(URLEncoder.encode(
                        "{\"status\":\"" + status + "\"}", StandardCharsets.UTF_8));
        JsonNode data = call(url.toString(), token, goal + "/" + scene + "/" + status);
        System.out.println("【" + goal + " | " + scene + " | " + status + "】 total_num="
                + data.path("page_info").path("total_num").asInt()
                + "，本页条数=" + data.path("ad_list").size());
    }

    private static void probeNoScene(String token, String st, String et, String fields,
                                     String goal, String status) throws Exception {
        StringBuilder url = new StringBuilder(
                "https://api.oceanengine.com/open_api/v1.0/qianchuan/uni_promotion/list/")
                .append("?advertiser_id=").append(ADV)
                .append("&marketing_goal=").append(goal)
                .append("&start_time=").append(st)
                .append("&end_time=").append(et)
                .append("&page=1&page_size=100")
                .append("&fields=").append(fields)
                .append("&filtering=").append(URLEncoder.encode(
                        "{\"status\":\"" + status + "\"}", StandardCharsets.UTF_8));
        JsonNode data = call(url.toString(), token, goal + "/无scene/" + status);
        System.out.println("【" + goal + " | 无adlab_scene | " + status + "】 total_num="
                + data.path("page_info").path("total_num").asInt()
                + "，本页条数=" + data.path("ad_list").size());
    }

    private static JsonNode call(String url, String token, String tag) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
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
            throw new IllegalStateException(tag + " 错误: " + body);
        }
        return root.path("data");
    }
}
