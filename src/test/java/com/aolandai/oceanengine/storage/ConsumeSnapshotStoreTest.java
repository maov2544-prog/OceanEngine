package com.aolandai.oceanengine.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConsumeSnapshotStore} 的单测。
 *
 * <p>重点验证三件事：① 覆盖式写入只保留每单最新值；② 重启后能读回（持久化真的落盘了）；
 * ③ 文件损坏时<b>拒绝启动</b>（快照丢失会让分支②门槛从 80% 降到 40%，可能多花钱）。</p>
 */
class ConsumeSnapshotStoreTest {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @TempDir
    Path tempDir;

    private Path snapshotFile() {
        return tempDir.resolve("consume-snapshot.jsonl");
    }

    // ==================== 基本读写 ====================

    @Test
    @DisplayName("文件不存在时正常启动，size=0，查询返回 null")
    void emptyWhenFileMissing() throws IOException {
        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 7);

        assertThat(store.size()).isZero();
        assertThat(store.statCostOf(123L)).isNull();
        assertThat(store.statCostOf(null)).isNull();
    }

    @Test
    @DisplayName("saveAll 之后能查到值")
    void saveThenRead() throws IOException {
        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 7);

        store.saveAll(List.of(
                ConsumeSnapshotStore.snapshotOf(111L, 999L, 160.0, "R1"),
                ConsumeSnapshotStore.snapshotOf(222L, 999L, 80.5, "R1")));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.statCostOf(111L)).isEqualTo(160.0);
        assertThat(store.statCostOf(222L)).isEqualTo(80.5);
        assertThat(store.statCostOf(333L)).isNull();
    }

    @Test
    @DisplayName("同一订单重复写入 → 只保留最新值，条数不增加")
    void overwritesSameOrder() throws IOException {
        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 7);

        store.saveAll(List.of(ConsumeSnapshotStore.snapshotOf(111L, 999L, 100.0, "R1")));
        store.saveAll(List.of(ConsumeSnapshotStore.snapshotOf(111L, 999L, 150.0, "R2")));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.statCostOf(111L)).isEqualTo(150.0);
    }

    @Test
    @DisplayName("本轮没看到的订单会保留旧值（合并语义，不是整份替换）")
    void keepsOrdersNotSeenThisRound() throws IOException {
        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 7);

        store.saveAll(List.of(
                ConsumeSnapshotStore.snapshotOf(111L, 999L, 100.0, "R1"),
                ConsumeSnapshotStore.snapshotOf(222L, 999L, 60.0, "R1")));
        // 第二轮只看到 111
        store.saveAll(List.of(ConsumeSnapshotStore.snapshotOf(111L, 999L, 130.0, "R2")));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.statCostOf(111L)).isEqualTo(130.0);
        assertThat(store.statCostOf(222L)).isEqualTo(60.0);
    }

    @Test
    @DisplayName("statCost 为 null 的条目被跳过 —— 保留旧值比写 null 有用")
    void skipsNullStatCost() throws IOException {
        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 7);

        store.saveAll(List.of(ConsumeSnapshotStore.snapshotOf(111L, 999L, 120.0, "R1")));
        store.saveAll(List.of(ConsumeSnapshotStore.snapshotOf(111L, 999L, null, "R2")));

        assertThat(store.statCostOf(111L)).isEqualTo(120.0);
    }

    @Test
    @DisplayName("orderId 为 null 的条目被跳过")
    void skipsNullOrderId() throws IOException {
        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 7);

        store.saveAll(List.of(new ConsumeSnapshot(null, 999L, 50.0, "R1", "2026-09-30 10:00:00")));

        assertThat(store.size()).isZero();
    }

    // ==================== 持久化 ====================

    @Test
    @DisplayName("重新构造实例（模拟重启）能读回之前的快照")
    void survivesRestart() throws IOException {
        ConsumeSnapshotStore first = new ConsumeSnapshotStore(snapshotFile(), 7);
        first.saveAll(List.of(ConsumeSnapshotStore.snapshotOf(111L, 999L, 175.25, "R1")));

        ConsumeSnapshotStore reloaded = new ConsumeSnapshotStore(snapshotFile(), 7);

        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(reloaded.statCostOf(111L)).isEqualTo(175.25);
    }

    @Test
    @DisplayName("父目录不存在时自动创建")
    void createsParentDirectory() throws IOException {
        Path nested = tempDir.resolve("a/b/c/consume-snapshot.jsonl");
        ConsumeSnapshotStore store = new ConsumeSnapshotStore(nested, 7);

        store.saveAll(List.of(ConsumeSnapshotStore.snapshotOf(111L, 999L, 10.0, "R1")));

        assertThat(Files.exists(nested)).isTrue();
    }

    // ==================== 保留天数 ====================

    @Test
    @DisplayName("超过保留天数的条目在下次写入时被清理")
    void purgesExpiredEntries() throws IOException {
        String old = LocalDateTime.now().minusDays(30).format(TS);
        Files.writeString(snapshotFile(),
                "{\"orderId\":111,\"advertiserId\":999,\"statCost\":50.0,"
                        + "\"roundId\":\"R0\",\"timestamp\":\"" + old + "\"}\n",
                StandardCharsets.UTF_8);

        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 7);
        // 加载阶段不清（保留原样），第一次写入时才清理
        assertThat(store.size()).isEqualTo(1);

        store.saveAll(List.of(ConsumeSnapshotStore.snapshotOf(222L, 999L, 10.0, "R1")));

        assertThat(store.statCostOf(111L)).isNull();
        assertThat(store.statCostOf(222L)).isEqualTo(10.0);
    }

    @Test
    @DisplayName("保留天数传 0 或负数时按默认 7 天处理")
    void invalidRetentionFallsBackToDefault() throws IOException {
        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 0);

        assertThat(store.describe()).contains("保留 7 天");
    }

    // ==================== 损坏处理 ====================

    @Test
    @DisplayName("文件损坏 → 拒绝启动（快照丢失会让分支②门槛从 80% 降到 40%，宁可停摆）")
    void corruptFile_refusesToStart() throws IOException {
        Files.writeString(snapshotFile(), "这不是 JSON\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ConsumeSnapshotStore(snapshotFile(), 7))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("消耗快照损坏");
    }

    @Test
    @DisplayName("orderId 为空的记录也视为损坏，拒绝启动")
    void missingOrderId_refusesToStart() throws IOException {
        Files.writeString(snapshotFile(),
                "{\"advertiserId\":999,\"statCost\":50.0,\"roundId\":\"R1\","
                        + "\"timestamp\":\"2026-09-30 10:00:00\"}\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ConsumeSnapshotStore(snapshotFile(), 7))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("orderId 为空");
    }

    @Test
    @DisplayName("空行被忽略，不算损坏")
    void blankLinesAreIgnored() throws IOException {
        Files.writeString(snapshotFile(), "\n\n", StandardCharsets.UTF_8);

        ConsumeSnapshotStore store = new ConsumeSnapshotStore(snapshotFile(), 7);

        assertThat(store.size()).isZero();
    }

    // ==================== 工厂 ====================

    @Test
    @DisplayName("snapshotOf 填的时间戳能被解析（格式 yyyy-MM-dd HH:mm:ss）")
    void snapshotOfTimestampIsParsable() {
        ConsumeSnapshot snapshot = ConsumeSnapshotStore.snapshotOf(1L, 2L, 3.0, "R1");

        assertThat(snapshot.getOrderId()).isEqualTo(1L);
        assertThat(snapshot.getStatCost()).isEqualTo(3.0);
        assertThat(snapshot.getRoundId()).isEqualTo("R1");
        assertThat(LocalDateTime.parse(snapshot.getTimestamp(), TS)).isNotNull();
    }
}
