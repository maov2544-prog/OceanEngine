package com.aolandai.oceanengine.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知流水测试。
 *
 * <p>重点：只有<b>成功</b>的记录才算「已通知」；坏行必须跳过而不是拒绝启动。</p>
 */
public class WebhookNotificationStoreTest {

    @TempDir
    Path tempDir;

    private static final long ORDER = 7_690_526_648_109_645_860L;

    @Test
    void append_thenAlreadyNotified() throws Exception {
        Path path = tempDir.resolve("webhook.jsonl");
        WebhookNotificationStore store = new WebhookNotificationStore(path);

        assertThat(store.alreadyNotified(LocalDate.now(), ORDER)).isFalse();

        store.append(entry(ORDER, true, 200));

        assertThat(store.alreadyNotified(LocalDate.now(), ORDER)).isTrue();
        assertThat(Files.readAllLines(path, StandardCharsets.UTF_8)).hasSize(1);
    }

    @Test
    void failedEntry_doesNotCountAsNotified() throws Exception {
        WebhookNotificationStore store = new WebhookNotificationStore(tempDir.resolve("w.jsonl"));

        store.append(entry(ORDER, false, 500));

        assertThat(store.alreadyNotified(LocalDate.now(), ORDER))
                .as("发送失败不能算已通知，否则下一轮不会重试")
                .isFalse();
    }

    @Test
    void reloadFromDisk_restoresNotifiedState() throws Exception {
        Path path = tempDir.resolve("w.jsonl");
        new WebhookNotificationStore(path).append(entry(ORDER, true, 200));

        WebhookNotificationStore reloaded = new WebhookNotificationStore(path);

        assertThat(reloaded.all()).hasSize(1);
        assertThat(reloaded.alreadyNotified(LocalDate.now(), ORDER)).isTrue();
    }

    @Test
    void brokenLine_isSkippedInsteadOfBlockingStartup() throws Exception {
        Path path = tempDir.resolve("w.jsonl");
        new WebhookNotificationStore(path).append(entry(ORDER, true, 200));
        Files.write(path, List.of("{ 这一行不是合法 JSON"),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        // 关键：构造不能抛异常。通知流水损坏最多导致重复发一次，
        // 而拒绝启动会让整个追投服务停摆 —— 严重性不对等。
        WebhookNotificationStore reloaded = new WebhookNotificationStore(path);

        assertThat(reloaded.all()).as("坏行被跳过，好行仍在").hasSize(1);
        assertThat(reloaded.alreadyNotified(LocalDate.now(), ORDER)).isTrue();
    }

    @Test
    void append_createsMissingParentDirectory() throws Exception {
        Path path = tempDir.resolve("nested/deeper/w.jsonl");

        new WebhookNotificationStore(path).append(entry(ORDER, true, 200));

        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void notifiedCountOn_countsOnlyThatDate() throws Exception {
        WebhookNotificationStore store = new WebhookNotificationStore(tempDir.resolve("w.jsonl"));
        store.append(entry(ORDER, true, 200));
        store.append(entry(ORDER + 1, true, 200));

        WebhookLedgerEntry yesterday = entry(ORDER + 2, true, 200);
        yesterday.setDate(LocalDate.now().minusDays(1).toString());
        store.append(yesterday);

        assertThat(store.notifiedCountOn(LocalDate.now())).isEqualTo(2);
    }

    private static WebhookLedgerEntry entry(long orderId, boolean success, int httpStatus) {
        WebhookLedgerEntry e = new WebhookLedgerEntry();
        e.setDate(LocalDate.now().toString());
        e.setOrderId(orderId);
        e.setReason("COUNT_AND_AMOUNT");
        e.setReinvestCount(5);
        e.setReinvestAmount(1000);
        e.setSentAt(OffsetDateTime.now().toString());
        e.setSuccess(success);
        e.setHttpStatus(httpStatus);
        e.setDetail(success ? "OK" : "boom");
        return e;
    }
}
