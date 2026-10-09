package com.aolandai.oceanengine.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UsageRatioRule} 的单测 —— 覆盖两个分支、边界值（正好 80%）、
 * C_prev 缺失时的处理、以及各种缺失/脏数据。
 *
 * <p>约定：B（投放总金额）= 200，阈值 = 0.8。</p>
 */
class UsageRatioRuleTest {

    private static final long B = 200L;
    private static final double RATIO = 0.8;

    // ==================== 分支①：当前使用率 ====================

    @Test
    @DisplayName("分支① 达标：C_now/B 正好 80%（边界含等于）")
    void branch1_exactlyEightyPercent_hits() {
        assertThat(UsageRatioRule.hit(160.0, 100.0, B, RATIO)).isTrue();
    }

    @Test
    @DisplayName("两分支都不达标：分支① 79.5%，分支② 79%")
    void branch1_belowThreshold_misses() {
        // C_now=159 → 分支① 159/200 = 0.795 ❌
        // C_prev=160 → 分支② (318-160)/200 = 0.79 ❌
        // （注意：C_prev 取 158 时分支②正好是 0.80 会命中 —— 分支②比想象中宽松）
        assertThat(UsageRatioRule.hit(159.0, 160.0, B, RATIO)).isFalse();
    }

    // ==================== 分支②：累计 + 本轮增量 ====================

    @Test
    @DisplayName("分支② 达标：当前使用率只有 70%，但增强使用率 90%")
    void branch2_hitsWhenCurrentRatioBelowThreshold() {
        // C_now=140 → 分支① 0.70 ❌
        // C_prev=100 → 分支② (280-100)/200 = 0.90 ✅
        assertThat(UsageRatioRule.hit(140.0, 100.0, B, RATIO)).isTrue();
    }

    @Test
    @DisplayName("分支② 未达标：增强使用率 75%，两分支都不成立")
    void branch2_belowThreshold_misses() {
        // C_now=140 → 分支① 0.70 ❌
        // C_prev=130 → 分支② (280-130)/200 = 0.75 ❌
        assertThat(UsageRatioRule.hit(140.0, 130.0, B, RATIO)).isFalse();
    }

    @Test
    @DisplayName("分支② 边界：增强使用率正好 80%")
    void branch2_exactlyEightyPercent_hits() {
        // C_now=140, C_prev=120 → (280-120)/200 = 0.80
        assertThat(UsageRatioRule.hit(140.0, 120.0, B, RATIO)).isTrue();
    }

    @Test
    @DisplayName("消耗回退（C_prev 比 2×C_now 还大）→ 分支②为负，不达标")
    void branch2_negativeRatio_misses() {
        // C_now=50 → 分支① 0.25 ❌；C_prev=300 → 分支② (100-300)/200 = -1.0 ❌
        assertThat(UsageRatioRule.hit(50.0, 300.0, B, RATIO)).isFalse();
    }

    // ==================== C_prev 缺失：按「本轮增量 = 0」处理 ====================

    @Test
    @DisplayName("⚠️ C_prev 缺失：消耗 40% 不再命中（按 0 兜底时会命中 —— 这是 2026-09-30 修掉的 bug）")
    void missingPrev_doesNotTriggerAtFortyPercent() {
        // C_now=80 → 分支① 0.40 ❌
        // 若按 0 兜底：分支② (160-0)/200 = 0.80 ✅ ← 旧行为，会让门槛悄悄降到 40%
        // 现按 C_prev=C_now：分支② (160-80)/200 = 0.40 ❌
        assertThat(UsageRatioRule.hit(80.0, null, B, RATIO)).isFalse();
    }

    @Test
    @DisplayName("C_prev 缺失：增强使用率 == 使用率（不会虚高一倍）")
    void missingPrev_boostedRatioEqualsCurrentRatio() {
        assertThat(UsageRatioRule.boostedRatio(80.0, null, B))
                .isEqualTo(UsageRatioRule.currentRatio(80.0, B))
                .isEqualTo(0.4);
        assertThat(UsageRatioRule.boostedRatio(140.0, null, B))
                .isEqualTo(UsageRatioRule.currentRatio(140.0, B))
                .isEqualTo(0.7);
    }

    @Test
    @DisplayName("C_prev 缺失：退化成只判分支① —— 80% 仍然命中")
    void missingPrev_fallsBackToBranch1() {
        assertThat(UsageRatioRule.hit(160.0, null, B, RATIO)).isTrue();   // 正好 80%
        assertThat(UsageRatioRule.hit(159.0, null, B, RATIO)).isFalse();  // 79.5%
    }

    // ==================== 缺失 / 脏数据 ====================

    @Test
    @DisplayName("C_now 为 null → 不达标（两个分支都算不出来）")
    void nullStatCost_misses() {
        assertThat(UsageRatioRule.hit(null, 100.0, B, RATIO)).isFalse();
        assertThat(UsageRatioRule.currentRatio(null, B)).isZero();
        assertThat(UsageRatioRule.boostedRatio(null, 100.0, B)).isZero();
    }

    @Test
    @DisplayName("预算缺失 / 为 0 / 为负 → 不达标（与改造前的 calcBudgetUsedRatio 行为一致）")
    void badBudget_misses() {
        assertThat(UsageRatioRule.hit(160.0, 100.0, null, RATIO)).isFalse();
        assertThat(UsageRatioRule.hit(160.0, 100.0, 0L, RATIO)).isFalse();
        assertThat(UsageRatioRule.hit(160.0, 100.0, -1L, RATIO)).isFalse();
        assertThat(UsageRatioRule.currentRatio(160.0, 0L)).isZero();
        assertThat(UsageRatioRule.boostedRatio(160.0, 100.0, 0L)).isZero();
    }

    // ==================== 两个比值方法 ====================

    @Test
    @DisplayName("currentRatio = C_now / B")
    void currentRatio_formula() {
        assertThat(UsageRatioRule.currentRatio(160.0, B)).isEqualTo(0.8);
        assertThat(UsageRatioRule.currentRatio(50.0, B)).isEqualTo(0.25);
    }

    @Test
    @DisplayName("boostedRatio = (2×C_now − C_prev) / B")
    void boostedRatio_formula() {
        assertThat(UsageRatioRule.boostedRatio(140.0, 100.0, B)).isEqualTo(0.9);
    }
}
