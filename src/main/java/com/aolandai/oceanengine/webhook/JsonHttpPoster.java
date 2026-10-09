package com.aolandai.oceanengine.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 通用的「POST 一段 JSON 出去」组件 —— 把 HTTP 细节从 {@code HttpWebhookSender} 里抽出来。
 *
 * <p>为什么保留这层抽象：出站 HTTP 的细节（URL / headers / 超时 / 日志口径）集中在一处，
 * 调用方只关心「发什么 payload、拿到什么结果」。将来若要再加别的出站通知，直接复用本类即可，
 * 不必把 HTTP 逻辑重写一遍（否则容易出现一处加了超时、另一处忘了的漂移）。</p>
 *
 * <p>与 {@code SuixintuiTokenClient} 保持同一套写法（静态复用 HttpClient），不引新依赖。</p>
 *
 * <p>刻意<b>不在调用处内部重试</b>：失败由调用方记流水、下一轮自然重试。
 * 在轮次内部退避重试会拖长轮次时长，收益也有限。</p>
 */
public final class JsonHttpPoster {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 日志里最多保留这么长的响应体，避免把一大段 HTML 写进日志 */
    private static final int MAX_BODY_IN_LOG = 200;

    private JsonHttpPoster() {
    }

    /** 发送结果 */
    public record Result(boolean success, int httpStatus, String detail) {

        /** 连请求都没发出去（未配置 url / 序列化失败 / 连不上 / 超时）时的 httpStatus */
        public static final int NO_RESPONSE = -1;

        public static Result ok(int httpStatus) {
            return new Result(true, httpStatus, "");
        }

        public static Result fail(int httpStatus, String detail) {
            return new Result(false, httpStatus, detail == null ? "" : detail);
        }
    }

    /**
     * 把 {@code body} 作为 JSON POST 到 {@code url}。
     *
     * @param url       目标地址；为空直接失败（不抛异常）
     * @param headers   附加请求头，可为 null
     * @param timeoutMs 请求超时（毫秒）
     * @param body      已经序列化好的 JSON 正文
     */
    public static Result post(String url, Map<String, String> headers, long timeoutMs, String body) {
        if (url == null || url.isBlank()) {
            return Result.fail(Result.NO_RESPONSE, "未配置目标 url");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            if (headers != null) {
                headers.forEach((name, value) -> {
                    if (name != null && !name.isBlank() && value != null) {
                        builder.header(name, value);
                    }
                });
            }

            HttpResponse<String> response = CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Result.ok(status);
            }
            return Result.fail(status, "HTTP " + status + " " + abbreviate(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail(Result.NO_RESPONSE, "发送被中断");
        } catch (Exception e) {
            // IOException（连不上/超时）、IllegalArgumentException（url 非法）等都归到这里
            return Result.fail(Result.NO_RESPONSE,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 压平空白并截断，只用于日志 */
    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_BODY_IN_LOG
                ? flat
                : flat.substring(0, MAX_BODY_IN_LOG) + "...";
    }
}
