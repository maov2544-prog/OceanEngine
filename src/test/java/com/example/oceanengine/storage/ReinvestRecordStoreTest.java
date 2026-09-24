package com.example.oceanengine.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReinvestRecordStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void doneRecordIsRemovedFromPendingIndexAndSurvivesReload() throws Exception {
        Path file = tempDir.resolve("records.jsonl");
        ReinvestRecordStore store = new ReinvestRecordStore(file);

        ReinvestRecord record = store.markPending("round-1", 100L, 200L, 300L, 24.0, 1_000L);
        assertThat(store.findLatestPendingOrUnknown(200L)).isSameAs(record);

        store.mark(record, ReinvestRecord.Status.DONE, "OK");

        assertThat(store.findLatestPendingOrUnknown(200L)).isNull();
        assertThat(store.committedAmountOn(LocalDate.now(), 100L, 200L)).isEqualTo(300L);

        ReinvestRecordStore reloaded = new ReinvestRecordStore(file);
        assertThat(reloaded.findLatestPendingOrUnknown(200L)).isNull();
        assertThat(reloaded.all()).hasSize(1);
        assertThat(reloaded.all().get(0).getStatus()).isEqualTo(ReinvestRecord.Status.DONE);
    }

    @Test
    void failedRecordDoesNotConsumeSafetyBudget() throws Exception {
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        ReinvestRecord record = store.markPending("round-1", 100L, 200L, 300L, 24.0, 1_000L);

        store.mark(record, ReinvestRecord.Status.FAILED, "business rejected");

        assertThat(store.findLatestPendingOrUnknown(200L)).isNull();
        assertThat(store.committedAmountOn(LocalDate.now(), 100L, 200L)).isZero();
        assertThat(store.committedCountOn(LocalDate.now(), 200L)).isZero();
    }

    @Test
    void unknownRecordRemainsPendingAndConsumesSafetyBudget() throws Exception {
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        ReinvestRecord record = store.markPending("round-1", 100L, 200L, 300L, 24.0, 1_000L);

        store.mark(record, ReinvestRecord.Status.UNKNOWN, "timeout");

        assertThat(store.findLatestPendingOrUnknown(200L)).isSameAs(record);
        assertThat(store.committedAmountOn(LocalDate.now(), 100L, 200L)).isEqualTo(300L);
        assertThat(store.committedCountOn(LocalDate.now(), 200L)).isEqualTo(1L);
    }

    @Test
    void reloadRemovesEarlierUnknownWhenLaterRecordIsDone() throws Exception {
        Path file = tempDir.resolve("records.jsonl");
        ReinvestRecordStore store = new ReinvestRecordStore(file);
        ReinvestRecord unknown = store.markPending("round-1", 100L, 200L, 300L, 24.0, 1_000L);
        store.mark(unknown, ReinvestRecord.Status.UNKNOWN, "timeout");
        ReinvestRecord done = store.markPending("round-2", 100L, 200L, 300L, 24.0, 1_300L);
        store.mark(done, ReinvestRecord.Status.DONE, "OK");

        ReinvestRecordStore reloaded = new ReinvestRecordStore(file);

        assertThat(reloaded.findLatestPendingOrUnknown(200L)).isNull();
    }

    @Test
    void corruptRecordFailsClosed() throws Exception {
        Path file = tempDir.resolve("records.jsonl");
        Files.writeString(file, "{not-json}\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ReinvestRecordStore(file))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("流水损坏");
    }
}
