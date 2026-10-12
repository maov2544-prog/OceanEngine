package com.aolandai.oceanengine.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SuixintuiRebuildRecordStore} 的单测。
 *
 * <p>重点：① 幂等只认「真建成功」（dry-run 记录不算数）；② 追加式、重启能读回；
 * ③ <b>坏行拒绝启动</b>（与追投流水同策略：流水丢了会重复复建 = 重复花钱）。</p>
 */
class SuixintuiRebuildRecordStoreTest {

    @TempDir
    Path tempDir;

    private Path storeFile() {
        return tempDir.resolve("rebuild-records.jsonl");
    }

    private static SuixintuiRebuildRecord effective(long sourceOrderId, long newOrderId) {
        return new SuixintuiRebuildRecord(sourceOrderId, newOrderId, 1779106144470100L,
                7446391238175474745L, 3700528515221946525L, 200L, 24.0,
                "MANUAL_BID", "QCPX_MODE_ON", 1.7, false);
    }

    private static SuixintuiRebuildRecord dryRun(long sourceOrderId) {
        return new SuixintuiRebuildRecord(sourceOrderId, null, 1779106144470100L,
                7446391238175474745L, 3700528515221946525L, 200L, 24.0,
                "MANUAL_BID", "QCPX_MODE_ON", 1.7, true);
    }

    /** 造一条「在投冲突跳过」记录（不建单、无新单号，但有冲突单号） */
    private static SuixintuiRebuildRecord skippedInflight(long sourceOrderId, long conflictOrderId) {
        return SuixintuiRebuildRecord.skippedInflight(sourceOrderId, conflictOrderId,
                1779106144470100L, 7446391238175474745L, 3700528515221946525L,
                200L, 24.0, "MANUAL_BID", "QCPX_MODE_ON", 1.7);
    }

    // ==================== 幂等语义 ====================

    @Test
    @DisplayName("★ 真建成功后 isRebuilt=true")
    void effectiveRecordMarksRebuilt() throws IOException {
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());
        store.append(effective(111L, 999L));

        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.findEffective(111L).getNewOrderId()).isEqualTo(999L);
    }

    @Test
    @DisplayName("★ dry-run 记录不算「已复建」（只留档，不阻挡后续真建）")
    void dryRunRecordDoesNotBlock() throws IOException {
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());
        store.append(dryRun(111L));

        assertThat(store.isRebuilt(111L)).isFalse();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.all().get(0).isDryRun()).isTrue();
    }

    @Test
    @DisplayName("★ 每日真建计数只算 effective（dry-run 不计入）")
    void effectiveCountExcludesDryRun() throws IOException {
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());
        store.append(effective(111L, 999L));
        store.append(dryRun(222L));

        assertThat(store.effectiveCountOn(LocalDate.now())).isEqualTo(1);
    }

    // ==================== 结果类型：在投冲突跳过（2026-10 新增） ====================

    @Test
    @DisplayName("★ 在投冲突跳过 → isRebuilt=true（永久标记为已处理，后续不再尝试）")
    void skippedInflightMarksRebuilt() throws IOException {
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());
        store.append(skippedInflight(111L, 888L));

        assertThat(store.isRebuilt(111L)).isTrue();
        SuixintuiRebuildRecord r = store.findEffective(111L);
        assertThat(r.getNewOrderId()).isNull();
        assertThat(r.getConflictOrderId()).isEqualTo(888L);
        assertThat(r.effectiveResult())
                .isEqualTo(SuixintuiRebuildRecord.RESULT_SKIPPED_INFLIGHT);
    }

    @Test
    @DisplayName("★ 在投冲突跳过【不】占用每日真复建计数（没真建、没花钱）")
    void skippedInflightDoesNotCountTowardDailyLimit() throws IOException {
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());
        store.append(effective(111L, 999L));
        store.append(skippedInflight(222L, 888L));

        // 只有那一条真建的算数
        assertThat(store.effectiveCountOn(LocalDate.now())).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 在投冲突跳过 → 重启后仍 isRebuilt=true（永久标记跨重启生效）")
    void skippedInflightSurvivesRestart() throws IOException {
        SuixintuiRebuildRecordStore first = new SuixintuiRebuildRecordStore(storeFile());
        first.append(skippedInflight(111L, 888L));

        SuixintuiRebuildRecordStore reloaded = new SuixintuiRebuildRecordStore(storeFile());

        assertThat(reloaded.isRebuilt(111L)).isTrue();
        assertThat(reloaded.findEffective(111L).getConflictOrderId()).isEqualTo(888L);
    }

    // ==================== 向后兼容：老流水行没有 result 字段 ====================

    @Test
    @DisplayName("★ 向后兼容：手写「无 result 字段」的老真建流水行 → 反序列化后当作成功（isRebuilt=true）")
    void oldLineWithoutResultFieldIsTreatedAsRebuilt() throws IOException {
        // 老格式：完全没有 "result" / "conflictOrderId" 键
        String oldLine = "{\"sourceOrderId\":111,\"newOrderId\":999,"
                + "\"advertiserId\":1779106144470100,\"awemeId\":7446391238175474745,"
                + "\"productId\":3700528515221946525,\"amount\":200,\"deliveryTime\":24.0,"
                + "\"bidType\":\"MANUAL_BID\",\"qcpxMode\":\"QCPX_MODE_ON\",\"roiGoal\":1.7,"
                + "\"dryRun\":false,\"rebuiltAt\":\"2026-10-12 09:00:00\"}";
        Files.writeString(storeFile(), oldLine, StandardCharsets.UTF_8);

        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());

        // null → 归一为 REBUILT，历史成功记录不能被漏判
        assertThat(store.isRebuilt(111L)).isTrue();
        assertThat(store.findEffective(111L).effectiveResult())
                .isEqualTo(SuixintuiRebuildRecord.RESULT_REBUILT);
        assertThat(store.effectiveCountOn(LocalDate.of(2026, 10, 12))).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 向后兼容：老 dry-run 流水行（无 result）仍不算「已处理」")
    void oldDryRunLineWithoutResultStillNotRebuilt() throws IOException {
        String oldLine = "{\"sourceOrderId\":222,\"newOrderId\":null,"
                + "\"advertiserId\":1779106144470100,\"awemeId\":7446391238175474745,"
                + "\"productId\":3700528515221946525,\"amount\":200,\"deliveryTime\":24.0,"
                + "\"bidType\":\"MANUAL_BID\",\"qcpxMode\":\"QCPX_MODE_ON\",\"roiGoal\":1.7,"
                + "\"dryRun\":true,\"rebuiltAt\":\"2026-10-12 09:05:00\"}";
        Files.writeString(storeFile(), oldLine, StandardCharsets.UTF_8);

        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());

        assertThat(store.isRebuilt(222L)).isFalse();
        assertThat(store.effectiveCountOn(LocalDate.of(2026, 10, 12))).isZero();
    }

    @Test
    @DisplayName("★ 派生方法（isSettled/countsTowardDailyLimit/effectiveResult）被 @JsonIgnore，绝不写进文件")
    void derivedMethodsNeverLeakIntoFile() throws IOException {
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());
        store.append(effective(111L, 999L));
        store.append(skippedInflight(222L, 888L));

        String content = Files.readString(storeFile(), StandardCharsets.UTF_8);
        for (String forbidden : new String[]{"settled", "countsTowardDailyLimit", "effectiveResult"}) {
            assertThat(content).doesNotContain(forbidden);
        }
        // result / conflictOrderId 是真实字段，应该写进去
        assertThat(content).contains("SKIPPED_INFLIGHT");
        assertThat(content).contains("conflictOrderId");
    }

    // ==================== 持久化 ====================

    @Test
    @DisplayName("★ 重启后能读回，幂等索引重建（不会重复复建）")
    void survivesRestart() throws IOException {
        SuixintuiRebuildRecordStore first = new SuixintuiRebuildRecordStore(storeFile());
        first.append(effective(111L, 999L));
        first.append(dryRun(222L));

        SuixintuiRebuildRecordStore reloaded = new SuixintuiRebuildRecordStore(storeFile());

        assertThat(reloaded.size()).isEqualTo(2);
        assertThat(reloaded.isRebuilt(111L)).isTrue();
        assertThat(reloaded.isRebuilt(222L)).isFalse();
    }

    // ==================== 损坏策略 ====================

    @Test
    @DisplayName("★ 坏行拒绝启动（流水丢了会重复复建 = 重复花钱，与追投流水同策略）")
    void corruptFileRefusesToStart() throws IOException {
        Files.writeString(storeFile(), "这不是 JSON\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new SuixintuiRebuildRecordStore(storeFile()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("复建流水损坏");
    }

    @Test
    @DisplayName("空行被忽略，不算坏行；文件不存在视为空")
    void blankLinesAndMissingFileAreFine() throws IOException {
        Files.writeString(storeFile(), "\n\n", StandardCharsets.UTF_8);
        assertThat(new SuixintuiRebuildRecordStore(storeFile()).size()).isZero();

        assertThat(new SuixintuiRebuildRecordStore(tempDir.resolve("none.jsonl")).size()).isZero();
    }

    @Test
    @DisplayName("append 缺 sourceOrderId → 抛 IllegalArgumentException，不写入")
    void appendWithoutSourceOrderIdIsRejected() throws IOException {
        SuixintuiRebuildRecordStore store = new SuixintuiRebuildRecordStore(storeFile());

        assertThatThrownBy(() -> store.append(new SuixintuiRebuildRecord()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.size()).isZero();
    }
}
