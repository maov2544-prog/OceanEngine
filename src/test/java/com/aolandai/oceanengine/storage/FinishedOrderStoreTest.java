package com.aolandai.oceanengine.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FinishedOrderStore} 的单测 —— 七天底表（覆盖式）。
 *
 * <p>重点：① 是「整份替换」不是「合并」；② 重启能读回；
 * ③ <b>坏行跳过、不拒绝启动</b>（与消耗快照的严格策略相反，因为底表丢了不涉及资金）。</p>
 */
class FinishedOrderStoreTest {

    @TempDir
    Path tempDir;

    private Path storeFile() {
        return tempDir.resolve("finished-orders.jsonl");
    }

    private static FinishedOrder order(long orderId, double roi) {
        return new FinishedOrder(orderId, 999L, "抖音名", "aweme_" + orderId, roi, "FINISHED",
                "2026-09-23 09:00:00", "2026-09-24 00:01:00");
    }

    // ==================== 首次运行判定 ====================

    @Test
    @DisplayName("文件不存在 → isEstablished=false（首次运行，只建底表不输出新增）")
    void notEstablishedWhenFileMissing() throws IOException {
        FinishedOrderStore store = new FinishedOrderStore(storeFile());

        assertThat(store.isEstablished()).isFalse();
        assertThat(store.size()).isZero();
        assertThat(store.orderIds()).isEmpty();
    }

    @Test
    @DisplayName("写过一次之后 → isEstablished=true")
    void establishedAfterFirstWrite() throws IOException {
        FinishedOrderStore store = new FinishedOrderStore(storeFile());
        store.replaceAll(List.of(order(111L, 2.0)));

        assertThat(new FinishedOrderStore(storeFile()).isEstablished()).isTrue();
    }

    // ==================== 覆盖式语义 ====================

    @Test
    @DisplayName("replaceAll 是整份替换：上次有、这次没有的订单会被清掉")
    void replaceAllReplacesEntireContent() throws IOException {
        FinishedOrderStore store = new FinishedOrderStore(storeFile());

        store.replaceAll(List.of(order(111L, 2.0), order(222L, 1.9)));
        assertThat(store.orderIds()).containsExactlyInAnyOrder(111L, 222L);

        store.replaceAll(List.of(order(222L, 1.95)));
        assertThat(store.orderIds()).containsExactly(222L);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("replaceAll(空) 会把底表清空（底表文件仍然存在 → 仍算已建立）")
    void replaceAllEmptyClearsButStaysEstablished() throws IOException {
        FinishedOrderStore store = new FinishedOrderStore(storeFile());
        store.replaceAll(List.of(order(111L, 2.0)));

        store.replaceAll(List.of());

        assertThat(store.size()).isZero();
        assertThat(new FinishedOrderStore(storeFile()).isEstablished()).isTrue();
    }

    @Test
    @DisplayName("orderId 为 null 的条目被跳过")
    void skipsNullOrderId() throws IOException {
        FinishedOrderStore store = new FinishedOrderStore(storeFile());

        store.replaceAll(List.of(new FinishedOrder(null, 999L, "n", "a", 2.0, "FINISHED",
                "2026-09-23 09:00:00", "2026-09-24 00:01:00")));

        assertThat(store.size()).isZero();
    }

    // ==================== 持久化 ====================

    @Test
    @DisplayName("重新构造实例（模拟重启）能读回底表与全部字段")
    void survivesRestart() throws IOException {
        FinishedOrderStore first = new FinishedOrderStore(storeFile());
        first.replaceAll(List.of(order(7690526648109645860L, 2.35)));

        FinishedOrderStore reloaded = new FinishedOrderStore(storeFile());

        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(reloaded.orderIds()).containsExactly(7690526648109645860L);

        FinishedOrder loaded = reloaded.find(7690526648109645860L);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAdvertiserId()).isEqualTo(999L);
        assertThat(loaded.getRoi()).isEqualTo(2.35);
        assertThat(loaded.getStatus()).isEqualTo("FINISHED");
        assertThat(loaded.getAwemeShowId()).isEqualTo("aweme_7690526648109645860");
        assertThat(loaded.getOrderCreateTime()).isEqualTo("2026-09-23 09:00:00");
        assertThat(loaded.getLastSeenAt()).isEqualTo("2026-09-24 00:01:00");
    }

    @Test
    @DisplayName("父目录不存在时自动创建")
    void createsParentDirectory() throws IOException {
        Path nested = tempDir.resolve("x/y/z/finished.jsonl");
        FinishedOrderStore store = new FinishedOrderStore(nested);

        store.replaceAll(List.of(order(111L, 2.0)));

        assertThat(Files.exists(nested)).isTrue();
    }

    // ==================== 损坏：宽松策略 ====================

    @Test
    @DisplayName("坏行跳过、不拒绝启动，并计数")
    void skipsBadLinesInsteadOfRefusingToStart() throws IOException {
        Files.writeString(storeFile(),
                "{\"orderId\":111,\"advertiserId\":999,\"roi\":2.0,\"status\":\"FINISHED\"}\n"
                        + "这不是 JSON\n"
                        + "{\"advertiserId\":999,\"roi\":1.9}\n"
                        + "{\"orderId\":222,\"advertiserId\":999,\"roi\":1.8}\n",
                StandardCharsets.UTF_8);

        FinishedOrderStore store = new FinishedOrderStore(storeFile());

        // 111 和 222 能读出来；两行坏数据（非 JSON + orderId 缺失）被跳过
        assertThat(store.orderIds()).containsExactlyInAnyOrder(111L, 222L);
        assertThat(store.getSkippedBadLines()).isEqualTo(2);
        assertThat(store.isEstablished()).isTrue();
    }

    @Test
    @DisplayName("空行被忽略，不算坏行")
    void blankLinesAreIgnored() throws IOException {
        Files.writeString(storeFile(), "\n\n", StandardCharsets.UTF_8);

        FinishedOrderStore store = new FinishedOrderStore(storeFile());

        assertThat(store.size()).isZero();
        assertThat(store.getSkippedBadLines()).isZero();
    }

    // ==================== 复建字段（awemeId / productId） ====================

    @Test
    @DisplayName("★ 新增的 awemeId / productId 能落盘并读回（复建建单要用）")
    void newRebuildFieldsSurviveRestart() throws IOException {
        FinishedOrder order = new FinishedOrder(111L, 999L, "抖音名", "show_111",
                7446391238175474745L, 3700528515221946525L, 2.0, "FINISHED",
                "2026-10-11 14:29", "2026-10-12 00:01:00");
        new FinishedOrderStore(storeFile()).replaceAll(List.of(order));

        FinishedOrder loaded = new FinishedOrderStore(storeFile()).find(111L);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getAwemeId()).isEqualTo(7446391238175474745L);
        assertThat(loaded.getProductId()).isEqualTo(3700528515221946525L);
    }

    @Test
    @DisplayName("★ 向后兼容：旧底表没有 awemeId / productId 这两个 key，读回应为 null（不报错）")
    void oldRecordsWithoutNewFieldsReadAsNull() throws IOException {
        // 模拟「改造前」写下的底表：只有旧字段
        Files.writeString(storeFile(),
                "{\"orderId\":111,\"advertiserId\":999,\"awemeName\":\"抖音名\","
                        + "\"awemeShowId\":\"show_111\",\"roi\":2.0,\"status\":\"FINISHED\","
                        + "\"orderCreateTime\":\"2026-10-11 14:29\","
                        + "\"lastSeenAt\":\"2026-10-12 00:01:00\"}\n",
                StandardCharsets.UTF_8);

        FinishedOrderStore store = new FinishedOrderStore(storeFile());

        assertThat(store.getSkippedBadLines()).isZero();
        FinishedOrder loaded = store.find(111L);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAwemeShowId()).isEqualTo("show_111");
        assertThat(loaded.getAwemeId()).isNull();
        assertThat(loaded.getProductId()).isNull();
    }
}
