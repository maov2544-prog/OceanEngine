package com.aolandai.oceanengine.domain;

import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.storage.ReinvestRecord;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

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

    @Test
    void dailyCapComesFromTheTierTheAccountBelongsTo() throws Exception {
        SuixintuiReinvestProperties props = properties();
        // 高档：账户 100，日限额 1000；低档：账户 101，日限额 5000
        props.setAdvertiserTiers(List.of(
                tier("高档", 1_000L, 100L),
                tier("低档", 5_000L, 101L)));
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        ReinvestRecord used = store.markPending("round-1", 100L, 201L, 900L, 24.0, 1_000L);
        store.mark(used, ReinvestRecord.Status.DONE, "OK");
        ReinvestSafetyGuard guard = new ReinvestSafetyGuard(props, store);

        // 同一笔 900 元占用：高档账户已到顶（900+200 > 1000），低档账户毫无压力
        ReinvestSafetyGuard.Decision highTier = guard.evaluate(100L, 202L, 0L, LocalDateTime.now());
        ReinvestSafetyGuard.Decision lowTier = guard.evaluate(101L, 202L, 0L, LocalDateTime.now());

        assertThat(highTier.allowed()).isFalse();
        assertThat(highTier.reason()).contains("账户日资金上限").contains("高档").contains("1000");
        assertThat(lowTier.allowed()).isTrue();
    }

    @Test
    void rejectsAccountThatBelongsToNoTier() throws Exception {
        SuixintuiReinvestProperties props = properties();
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        ReinvestSafetyGuard guard = new ReinvestSafetyGuard(props, store);

        // 999 不在任何档次里 → 宁可拒投，也不用错误的限额放行
        ReinvestSafetyGuard.Decision decision = guard.evaluate(999L, 200L, 0L, LocalDateTime.now());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("账户未配置档次");
    }

    private SuixintuiReinvestProperties properties() {
        SuixintuiReinvestProperties props = new SuixintuiReinvestProperties();
        props.setAmount(200L);
        props.setMaxAmountPerOrderPerDay(600L);
        props.setMaxAmountPerRound(1_000L);
        props.setMaxReinvestCountPerOrderPerDay(3);
        props.setCooldownMinutes(60L);
        // 单账户日限额改为按档次查，测试账户 100 必须落在某个档次里
        props.setAdvertiserTiers(List.of(tier("测试档", 5_000L, 100L)));
        return props;
    }

    private static SuixintuiReinvestProperties.AdvertiserTier tier(String name, long cap, Long... ids) {
        SuixintuiReinvestProperties.AdvertiserTier tier =
                new SuixintuiReinvestProperties.AdvertiserTier();
        tier.setName(name);
        tier.setMaxAmountPerAdvertiserPerDay(cap);
        tier.setAdvertiserIds(List.of(ids));
        return tier;
    }
}
