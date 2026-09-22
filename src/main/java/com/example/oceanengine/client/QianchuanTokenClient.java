
package com.example.oceanengine.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class QianchuanTokenClient {

    private static final String TOKEN_URL =
            "https://aocilenda.cn/qianchuan/callback"
            + "?action=token&state=shop_qianchuan";

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

    /**
     * 从自建服务端获取千川 PC 版 Access Token
     */
    public static String getAccessToken() throws Exception {

        // 读取自建服务端认证密钥
        String bearer = System.getenv(
                "QIANCHUAN_SERVICE_BEARER_TOKEN"
        );

        if (bearer == null || bearer.isBlank()) {
            throw new IllegalStateException(
                    "未配置环境变量 QIANCHUAN_SERVICE_BEARER_TOKEN"
            );
        }

        // 请求服务端
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "获取千川 Token 失败，HTTP 状态码："
                    + response.statusCode()
                    + "，响应：" + response.body()
            );
        }

        // 解析 JSON
        JsonNode root = MAPPER.readTree(response.body());

        if (!root.path("success").asBoolean(false)) {
            throw new RuntimeException(
                    "服务端返回失败：" +
                    root.path("message").asText("未知错误")
            );
        }

        // 确认返回的是千川 PC 版 Token
        String state = root.path("state").asText();

        if (!"shop_qianchuan".equals(state)) {
            throw new RuntimeException(
                    "返回的 state 不正确：" + state
            );
        }

        String accessToken =
                root.path("access_token").asText("");

        if (accessToken.isBlank()) {
            throw new RuntimeException(
                    "服务端响应中没有有效的 access_token"
            );
        }

        return accessToken;
    }
}
