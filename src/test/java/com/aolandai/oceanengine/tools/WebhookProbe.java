package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.webhook.HttpWebhookSender;
import com.aolandai.oceanengine.webhook.WebhookNotification;
import com.aolandai.oceanengine.webhook.WebhookSender;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;

/**
 * 手工探针：往 webhook 地址发一条<b>样例</b> payload，用来验证对方端点通不通、字段对不对。
 *
 * <p><b>不产生任何追投、不花一分钱</b>，也不读写追投流水。</p>
 *
 * <p>为什么要它：生产是 {@code dry-run=true}，演练模式下不会写追投流水，
 * 所以「真跑一轮看看 webhook 发不发」是验证不了的（除非真实追投，那要花钱）。
 * 这个探针直接构造一条 payload 发出去，把「对方端点是否可达 / 字段是否符合预期」单独验掉。</p>
 *
 * <p><b>用法（项目根目录）：</b></p>
 * <pre>
 * mvn -o test-compile
 * mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt
 * java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      com.aolandai.oceanengine.tools.WebhookProbe &lt;webhook-url&gt; [orderId]
 * </pre>
 *
 * <p><b>只打印不发送</b>（加 {@code --print}，可用于核对字段而不打扰对方）：</p>
 * <pre>
 * ... WebhookProbe --print [url]
 * </pre>
 *
 * <p>可选参数指定订单号，方便对方按真实订单号对账：</p>
 * <pre>
 * ... WebhookProbe https://对方地址 7690526648109645860
 * </pre>
 *
 * <p>打印模式下 url 可省略；省略时取环境变量 {@code WEBHOOK_URL}，再省略就用占位符
 * （只影响日志里那一行「目标」，不影响 payload 内容）。</p>
 *
 * <p>退出码：0 = 对方返回 2xx（或打印模式正常结束）；1 = 发送失败（原因打印在控制台）；
 * 2 = 参数用法错误。</p>
 */
public final class WebhookProbe {

    /** 样例订单号（真实格式的 19 位，便于对方校验精度） */
    private static final long SAMPLE_ORDER_ID = 7_690_526_648_109_645_860L;

    /** 样例账户 ID（真实格式，19 位） */
    private static final long SAMPLE_ADVERTISER_ID = 1_823_379_540_880_396L;

    private static final String PLACEHOLDER_URL = "(未指定，仅打印)";

    /** 和 HttpWebhookSender 里那份是同一个类，只为 pretty 打印用 */
    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper();

    private WebhookProbe() {
    }

    public static void main(String[] args) {
        // ---- 参数解析：--print / -p 可出现在任意位置 ----
        boolean printOnly = false;
        java.util.List<String> positional = new java.util.ArrayList<>();
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            String a = arg.trim();
            if (a.isEmpty()) {
                continue;
            }
            if ("--print".equals(a) || "-p".equals(a) || "print".equals(a)) {
                printOnly = true;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                System.exit(0);
                return;
            } else {
                positional.add(a);
            }
        }

        String url = !positional.isEmpty() ? positional.get(0) : System.getenv("WEBHOOK_URL");
        if (url == null || url.isBlank()) {
            if (!printOnly) {
                System.out.println("缺少 webhook 地址。");
                printUsage();
                System.exit(2);
                return;
            }
            url = PLACEHOLDER_URL;
        }
        url = url.trim();

        long orderId = SAMPLE_ORDER_ID;
        if (positional.size() > 1) {
            try {
                orderId = Long.parseLong(positional.get(1));
            } catch (NumberFormatException e) {
                System.out.println("订单号不是合法数字: " + positional.get(1));
                System.exit(2);
                return;
            }
        }

        WebhookNotification notification = WebhookNotification.of(
                LocalDate.now(), orderId, SAMPLE_ADVERTISER_ID,
                "探针-测试抖音名", "MS4wLjABAAAAprobe",
                5L, 1000L, WebhookNotification.Reason.COUNT_AND_AMOUNT, false);

        if (printOnly) {
            printPayload(notification, url);
            System.exit(0);
            return;
        }

        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        SuixintuiReinvestProperties.Webhook webhook = props.getWebhook();
        webhook.setEnabled(true);
        webhook.setUrl(url);
        webhook.setTimeoutMs(5000);

        System.out.println("=== webhook 探针（不涉及任何追投，不花钱） ===");
        System.out.println("目标: " + url);
        System.out.println("payload 关键字段: orderId=" + orderId + "（按字符串发送）"
                + ", count=5, amount=1000, reason=COUNT_AND_AMOUNT");
        System.out.println("发送中...");

        WebhookSender.Result result = new HttpWebhookSender(props).send(notification);

        if (result.success()) {
            System.out.println("结果: 成功，HTTP " + result.httpStatus());
            System.out.println("→ 对方端点可达，且已收到本次 payload。");
            System.out.println("  请让对方确认 orderId 是否为 " + orderId + "（末几位没被截断）。");
            System.exit(0);
        } else {
            System.out.println("结果: 失败，HTTP " + result.httpStatus());
            System.out.println("原因: " + result.detail());
            System.out.println("→ 检查地址是否正确、对方服务是否在跑、是否需要自定义请求头（headers）。");
            System.exit(1);
        }
    }

    /**
     * 打印「将要发送的 JSON 正文」，<b>不发起任何网络请求</b>。
     *
     * <p>正文取自 {@link HttpWebhookSender#toJson} —— 与真实发送共用同一段序列化代码，
     * 所以打印出来的就是真发出去的那些字节，不会出现「打印的和发的不是一回事」。</p>
     */
    private static void printPayload(WebhookNotification notification, String url) {
        String json;
        try {
            json = HttpWebhookSender.toJson(notification);
        } catch (Exception e) {
            System.out.println("序列化失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("=== webhook payload（只打印，未发送） ===");
        System.out.println("目标: " + url);
        System.out.println("请求方法: POST    Content-Type: application/json    Accept: application/json");
        System.out.println();
        System.out.println("--- 实际发送的字节（UTF-8，单行） ---");
        System.out.println(json);
        System.out.println();
        System.out.println("--- 同一份内容的易读版 ---");
        try {
            System.out.println(PRETTY_MAPPER.readTree(json).toPrettyString());
        } catch (Exception e) {
            System.out.println("(pretty 打印失败，以上面单行为准)");
        }
        System.out.println();
        System.out.println("字节数: " + json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + "（UTF-8）");
        System.out.println("提示: orderId 是 JSON 字符串（带引号）才正确；"
                + "若对方收到的是数字，19 位会丢精度。");
        System.out.println("→ 本模式不发送，对方不会收到任何东西。");
    }

    private static void printUsage() {
        System.out.println("用法: WebhookProbe <webhook-url> [orderId]");
        System.out.println("      WebhookProbe --print [webhook-url] [orderId]");
        System.out.println();
        System.out.println("  <webhook-url>  下游地址，http:// 或 https:// 开头");
        System.out.println("  [orderId]      样例订单号，默认 " + SAMPLE_ORDER_ID);
        System.out.println("  --print, -p    只打印 payload 不发送（url 可省略）");
        System.out.println();
        System.out.println("示例: WebhookProbe https://example.com/hook 7690526648109645860");
        System.out.println("      WebhookProbe --print");
    }
}
