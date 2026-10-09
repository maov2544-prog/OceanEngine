
package com.aolandai.oceanengine.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.time.Duration;

public class SuixintuiTokenClient {

    private static final String TOKEN_URL =
            "https://aocilenda.cn/qianchuan/callback"
            + "?action=token&state=shop_suixintui";

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

    private static final int MAX_ATTEMPTS = 3;

    /**
     * 从自建服务端获取有效的随心推 Access Token。
     */
    public static String getAccessToken() throws Exception {

        String bearer = System.getenv(
                "QIANCHUAN_SERVICE_BEARER_TOKEN"
        );

        if (bearer == null || bearer.isBlank()) {
            throw new IllegalStateException(
                    "未配置环境变量 QIANCHUAN_SERVICE_BEARER_TOKEN"
            );
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

                HttpResponse<String> response = null;
                IOException lastNetworkError = null;
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                        try {
                                response = CLIENT.send(
                                                request,
                                                HttpResponse.BodyHandlers.ofString()
                                );
                                break;
                        } catch (IOException e) {
                                lastNetworkError = e;
                                if (attempt < MAX_ATTEMPTS) {
                                        try {
                                                Thread.sleep(attempt * 1_000L);
                                        } catch (InterruptedException interrupted) {
                                                Thread.currentThread().interrupt();
                                                throw interrupted;
                                        }
                                }
                        }
                }
                if (response == null) {
                        throw new IOException("Token 服务连接失败，已重试 "
                                        + MAX_ATTEMPTS + " 次", lastNetworkError);
                }

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "获取随心推 Token 失败，HTTP 状态码："
                    + response.statusCode()
            );
        }

        JsonNode root = MAPPER.readTree(response.body());

        if (!root.path("success").asBoolean(false)) {
            throw new RuntimeException(
                    "服务端返回失败：" +
                    root.path("message").asText("未知错误")
            );
        }

        String state = root.path("state").asText();

        if (!"shop_suixintui".equals(state)) {
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
