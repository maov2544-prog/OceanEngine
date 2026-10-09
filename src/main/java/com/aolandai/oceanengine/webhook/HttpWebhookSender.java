package com.aolandai.oceanengine.webhook;

import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 用 JDK 自带的 {@link java.net.http.HttpClient} 发送「追投阈值」webhook。
 *
 * <p>HTTP 细节（URL/headers/超时/日志口径）已抽到 {@link JsonHttpPoster}，
 * 本类只负责组装「追投阈值通知」的 payload 与解析发送结果。</p>
 *
 * <p>刻意<b>不在本轮重试</b>：发送失败会写进通知流水且不算「已通知」，
 * 下一轮（约 10 分钟后）自然重试。在轮次内部退避重试会拖长轮次时长，收益也有限。</p>
 */
@Component
public class HttpWebhookSender implements WebhookSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SuixintuiReinvestProperties props;

    public HttpWebhookSender(SuixintuiReinvestProperties props) {
        this.props = props;
    }

    @Override
    public Result send(WebhookNotification notification) {
        SuixintuiReinvestProperties.Webhook cfg = props.getWebhook();
        if (cfg == null || cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            return Result.fail(Result.NO_RESPONSE, "未配置 webhook.url");
        }

        String body;
        try {
            body = toJson(notification);
        } catch (Exception e) {
            return Result.fail(Result.NO_RESPONSE, "payload 序列化失败: " + e.getMessage());
        }

        JsonHttpPoster.Result result =
                JsonHttpPoster.post(cfg.getUrl(), cfg.getHeaders(), cfg.getTimeoutMs(), body);
        return result.success()
                ? Result.ok(result.httpStatus())
                : Result.fail(result.httpStatus(), result.detail());
    }

    /**
     * 把通知序列化成<b>将要发送的 JSON 正文</b>。
     *
     * <p>抽成 public static 是为了让探针（{@code tools/WebhookProbe --print}）能打印出与
     * 真实发送<b>完全一致</b>的字节，避免两处各写一份序列化逻辑而口径漂移。</p>
     */
    public static String toJson(WebhookNotification notification) throws Exception {
        return MAPPER.writeValueAsString(notification);
    }
}
