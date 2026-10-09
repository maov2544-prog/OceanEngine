package com.aolandai.oceanengine.scheduler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * webhook 配置自检测试。
 *
 * <p>核心规则：{@code enabled=true} 时必须有 http(s) 地址 —— 否则会退化成每轮往空地址发请求，
 * 只在日志里报错，很难发现。</p>
 */
public class SuixintuiWebhookPropertiesTest {

    @Test
    void defaults_matchTheAgreedRequirements() {
        SuixintuiReinvestProperties.Webhook w = new SuixintuiReinvestProperties().getWebhook();

        assertThat(w.isEnabled()).as("默认关闭，配好地址再打开").isFalse();
        assertThat(w.getCountThreshold()).isEqualTo(5);
        assertThat(w.getAmountThreshold()).isEqualTo(1000L);
        assertThat(w.getTimeoutMs()).isEqualTo(5000);
        assertThat(w.getHeaders()).isEmpty();
    }

    @Test
    void disabled_doesNotRequireUrl() {
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.getWebhook().setEnabled(false);

        assertThat(props.isWebhookConsistent()).isTrue();
    }

    @Test
    void enabled_withoutUrl_failsValidation() {
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.getWebhook().setEnabled(true);
        props.getWebhook().setUrl(null);
        assertThat(props.isWebhookConsistent()).isFalse();

        props.getWebhook().setUrl("   ");
        assertThat(props.isWebhookConsistent()).isFalse();
    }

    @Test
    void enabled_withNonHttpScheme_failsValidation() {
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.getWebhook().setEnabled(true);

        props.getWebhook().setUrl("ftp://example.com/hook");
        assertThat(props.isWebhookConsistent()).isFalse();

        props.getWebhook().setUrl("example.com/hook");
        assertThat(props.isWebhookConsistent()).isFalse();
    }

    @Test
    void enabled_withHttpOrHttps_passesValidation() {
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.getWebhook().setEnabled(true);

        props.getWebhook().setUrl("https://example.com/hook");
        assertThat(props.isWebhookConsistent()).isTrue();

        props.getWebhook().setUrl("http://10.0.0.5:8080/hook");
        assertThat(props.isWebhookConsistent()).isTrue();
    }

    @Test
    void describeWebhook_mentionsDryRunSuppression() {
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.setDryRun(true);
        props.getWebhook().setEnabled(true);
        props.getWebhook().setUrl("https://example.com/hook");

        assertThat(props.describeWebhook())
                .contains("https://example.com/hook")
                .contains("5 次")
                .contains("1000 元")
                .contains("dry-run");

        props.getWebhook().setEnabled(false);
        assertThat(props.describeWebhook()).isEqualTo("未启用");
    }
}
