package com.example.oceanengine.domain;

import com.example.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.example.oceanengine.storage.ReinvestRecord;
import com.example.oceanengine.storage.ReinvestRecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReinvestSafetyGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsWhenRoundLimitWouldBeExceeded() throws Exception {
        SuixintuiReinvestProperties props = properties();
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        ReinvestSafetyGuard guard = new ReinvestSafetyGuard(props, store);

        ReinvestSafetyGuard.Decision decision = guard.evaluate(100L, 200L, 900L, LocalDateTime.now());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("本轮资金上限");
    }

    @Test
    void pendingOrUnknownAmountConsumesDailyLimit() throws Exception {
        SuixintuiReinvestProperties props = properties();
        props.setMaxAmountPerOrderPerDay(400L);
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        ReinvestRecord pending = store.markPending("round-1", 100L, 200L, 300L, 24.0, 1_000L);
        store.mark(pending, ReinvestRecord.Status.UNKNOWN, "timeout");
        ReinvestSafetyGuard guard = new ReinvestSafetyGuard(props, store);

        ReinvestSafetyGuard.Decision decision = guard.evaluate(100L, 200L, 0L, LocalDateTime.now());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("订单日资金上限");
    }

    @Test
    void rejectsDuringCooldown() throws Exception {
        SuixintuiReinvestProperties props = properties();
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        ReinvestRecord record = store.markPending("round-1", 100L, 200L, 200L, 24.0, 1_000L);
        store.mark(record, ReinvestRecord.Status.DONE, "OK");
        ReinvestSafetyGuard guard = new ReinvestSafetyGuard(props, store);

        ReinvestSafetyGuard.Decision decision = guard.evaluate(100L, 200L, 0L, LocalDateTime.now());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("订单冷却中");
    }

    @Test
    void allowsFirstInvestmentWithinAllLimits() throws Exception {
        SuixintuiReinvestProperties props = properties();
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        ReinvestSafetyGuard guard = new ReinvestSafetyGuard(props, store);

        ReinvestSafetyGuard.Decision decision = guard.evaluate(100L, 200L, 0L, LocalDateTime.now());

        assertThat(decision.allowed()).isTrue();
    }

    private SuixintuiReinvestProperties properties() {
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.setAmount(200L);
        props.setMaxAmountPerOrderPerDay(600L);
        props.setMaxAmountPerAdvertiserPerDay(5_000L);
        props.setMaxAmountPerRound(1_000L);
        props.setMaxReinvestCountPerOrderPerDay(3);
        props.setCooldownMinutes(60L);
        return props;
    }
}
