package com.aolandai.oceanengine.webhook;

import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 发送器测试：起一个本地 {@link HttpServer} 收请求，走真实的 HTTP 路径。
 *
 * <p>最关键的断言是 <b>orderId 必须以字符串形式出现</b> —— 19 位数字若按 JSON number 发出去，
 * 下游用 JavaScript 解析会丢精度（末尾几位变 0），导致匹配不到订单。</p>
 */
public class HttpWebhookSenderTest {

    private static final long ORDER = 7_690_526_648_109_645_860L;

    private HttpServer server;

    /** 最近一次收到的请求，由 handler 写入 */
    private final AtomicReference<Received> lastRequest = new AtomicReference<>();

    private record Received(String method, String contentType, String body, String authHeader) {
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个本地服务并返回可用的 url；status 是响应状态码 */
    private String startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequest.set(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body,
                    exchange.getRequestHeaders().getFirst("X-Test-Auth")));
            byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    @Test
    void send_postsJsonWithOrderIdSerializedAsString() throws Exception {
        String url = startServer(200);
        HttpWebhookSender sender = new HttpWebhookSender(props(url));

        WebhookSender.Result result = sender.send(notification());

        assertThat(result.success()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);

        Received got = lastRequest.get();
        assertThat(got).isNotNull();
        assertThat(got.method()).isEqualTo("POST");
        assertThat(got.contentType()).startsWith("application/json");

        // 核心：19 位订单号必须是字符串，不能是裸数字
        assertThat(got.body())
                .contains("\"orderId\":\"" + ORDER + "\"")
                .doesNotContain("\"orderId\":" + ORDER);
        assertThat(got.body())
                .contains("\"reason\":\"COUNT_AND_AMOUNT\"")
                .contains("\"reinvestCount\":5")
                .contains("\"reinvestAmount\":1000")
                .contains("\"dryRun\":false");
    }

    @Test
    void send_includesConfiguredCustomHeaders() throws Exception {
        String url = startServer(200);
        SuixintuiReinvestProperties props = props(url);
        props.getWebhook().getHeaders().put("X-Test-Auth", "Bearer secret-token");

        new HttpWebhookSender(props).send(notification());

        assertThat(lastRequest.get().authHeader()).isEqualTo("Bearer secret-token");
    }

    @Test
    void send_non2xx_isReportedAsFailureWithStatusAndBody() throws Exception {
        String url = startServer(500);

        WebhookSender.Result result = new HttpWebhookSender(props(url)).send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(result.detail()).contains("HTTP 500");
    }

    @Test
    void send_connectionRefused_returnsNoResponseInsteadOfThrowing() throws Exception {
        String url = "http://127.0.0.1:" + closedPort() + "/hook";

        WebhookSender.Result result = new HttpWebhookSender(props(url)).send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(WebhookSender.Result.NO_RESPONSE);
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void send_withoutUrl_returnsFailure() {
        SuixintuiReinvestProperties props = props("");
        props.getWebhook().setUrl("   ");

        WebhookSender.Result result = new HttpWebhookSender(props).send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("webhook.url");
    }

    // ==================== 辅助 ====================

    private static SuixintuiReinvestProperties props(String url) {
        SuixintuiReinvestProperties p = new SuixintuiReinvestProperties();
        SuixintuiReinvestProperties.Webhook w = new SuixintuiReinvestProperties.Webhook();
        w.setEnabled(true);
        w.setUrl(url);
        w.setTimeoutMs(3000);
        p.setWebhook(w);
        return p;
    }

    private static WebhookNotification notification() {
        return WebhookNotification.of(LocalDate.of(2026, 9, 29), ORDER,
                1_823_379_540_880_396L, "测试抖音名", "MS4wLjABAAAA",
                5L, 1000L, WebhookNotification.Reason.COUNT_AND_AMOUNT, false);
    }

    /** 拿一个「刚刚被释放、因此肯定没人监听」的端口，用来测连接失败 */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
