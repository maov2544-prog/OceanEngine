package com.aolandai.oceanengine.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoasRule} 的单测 —— 覆盖「两条同时成立才达标」的 AND 语义、两个边界（含等于）、
 * 只满足其一时不达标、阈值可小于 1、以及各种缺失/脏数据。
 *
 * <p>约定：B（投放总金额）= 200，消耗使用率下限 = 0.5，净成交门槛 = 2.0（对齐典型 ROI 门槛）。</p>
 */
class RoasRuleTest {

    private static final long B = 200L;
    private static final double MIN_USAGE = 0.5;
    private static final double THRESHOLD = 2.0;

    // ==================== 两条同时成立才达标 ====================

    @Test
    @DisplayName("两条都满足：使用率 75% 且 净成交率 250% → 达标")
    void bothPass_hits() {
        // 消耗=150 → 150/200 = 0.75 ≥ 0.5 ✓
        // 净成交=500 → 500/200 = 2.5 ≥ 2.0 ✓
        assertThat(RoasRule.hit(150.0, 500.0, B, MIN_USAGE, THRESHOLD)).isTrue();
    }

    @Test
    @DisplayName("只满足使用率、净成交不够 → 不达标（AND 语义，不是 OR）")
    void usagePassButNetFail_misses() {
        // 消耗=150 → 0.75 ✓；净成交=300 → 1.5 < 2.0 ✗
        assertThat(RoasRule.hit(150.0, 300.0, B, MIN_USAGE, THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("只满足净成交、使用率不够 → 不达标（AND 语义）")
    void netPassButUsageFail_misses() {
        // 消耗=80 → 0.40 < 0.5 ✗；净成交=500 → 2.5 ✓
        assertThat(RoasRule.hit(80.0, 500.0, B, MIN_USAGE, THRESHOLD)).isFalse();
    }

    // ==================== 边界（含等于） ====================

    @Test
    @DisplayName("使用率边界：正好 0.5 → 达标；0.499 → 不达标")
    void usageBoundary() {
        // 消耗=100 → 100/200 = 0.5（正好等于，含等于 → 达标）
        assertThat(RoasRule.hit(100.0, 500.0, B, MIN_USAGE, THRESHOLD)).isTrue();
        // 消耗=99.8 → 0.499 < 0.5
        assertThat(RoasRule.hit(99.8, 500.0, B, MIN_USAGE, THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("净成交率边界：正好等于门槛 → 达标；略低 → 不达标")
    void netSettleBoundary() {
        // 净成交=400 → 400/200 = 2.0（正好等于门槛 → 达标）
        assertThat(RoasRule.hit(150.0, 400.0, B, MIN_USAGE, THRESHOLD)).isTrue();
        // 净成交=399.8 → 1.999 < 2.0
        assertThat(RoasRule.hit(150.0, 399.8, B, MIN_USAGE, THRESHOLD)).isFalse();
    }

    // ==================== 阈值可小于 1（门槛不是 ROI 专用） ====================

    @Test
    @DisplayName("门槛 < 1：净成交率 0.5 满足门槛 0.5 / 0.2")
    void thresholdBelowOne() {
        // 消耗=100 → 0.5 ✓；净成交=100 → 0.5
        assertThat(RoasRule.hit(100.0, 100.0, B, MIN_USAGE, 0.5)).isTrue();
        assertThat(RoasRule.hit(100.0, 100.0, B, MIN_USAGE, 0.2)).isTrue();
        // 净成交=39.8 → 0.199 < 0.2
        assertThat(RoasRule.hit(100.0, 39.8, B, MIN_USAGE, 0.2)).isFalse();
    }

    @Test
    @DisplayName("门槛取商品级高门槛（如 6.0）时，净成交率不足即被拒")
    void highThreshold_rejects() {
        // 消耗=150 → 0.75 ✓；净成交=500 → 2.5 < 6.0 ✗
        assertThat(RoasRule.hit(150.0, 500.0, B, MIN_USAGE, 6.0)).isFalse();
        // 净成交=1200 → 6.0（正好等于高门槛）→ 达标
        assertThat(RoasRule.hit(150.0, 1200.0, B, MIN_USAGE, 6.0)).isTrue();
    }

    // ==================== 缺失 / 脏数据 ====================

    @Test
    @DisplayName("消耗缺失 → 不达标")
    void nullStatCost_misses() {
        assertThat(RoasRule.hit(null, 500.0, B, MIN_USAGE, THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("净成交金额缺失 → 不达标（拿不到就不追，保守）")
    void nullNetSettle_misses() {
        assertThat(RoasRule.hit(150.0, null, B, MIN_USAGE, THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("投放总金额缺失 / 为 0 / 为负 → 不达标")
    void badBudget_misses() {
        assertThat(RoasRule.hit(150.0, 500.0, null, MIN_USAGE, THRESHOLD)).isFalse();
        assertThat(RoasRule.hit(150.0, 500.0, 0L, MIN_USAGE, THRESHOLD)).isFalse();
        assertThat(RoasRule.hit(150.0, 500.0, -1L, MIN_USAGE, THRESHOLD)).isFalse();
    }

    // ==================== 两个比值方法 ====================

    @Test
    @DisplayName("usageRatio = 消耗 / 投放总金额；缺失时返回 0")
    void usageRatio_formula() {
        assertThat(RoasRule.usageRatio(150.0, B)).isEqualTo(0.75);
        assertThat(RoasRule.usageRatio(100.0, B)).isEqualTo(0.5);
        assertThat(RoasRule.usageRatio(null, B)).isZero();
        assertThat(RoasRule.usageRatio(150.0, 0L)).isZero();
    }

    @Test
    @DisplayName("netSettleRatio = 净成交金额 / 投放总金额；缺失时返回 0")
    void netSettleRatio_formula() {
        assertThat(RoasRule.netSettleRatio(500.0, B)).isEqualTo(2.5);
        assertThat(RoasRule.netSettleRatio(null, B)).isZero();
        assertThat(RoasRule.netSettleRatio(500.0, -1L)).isZero();
    }

    // ==================== 描述 ====================

    @Test
    @DisplayName("describe() 同时体现使用率下限与净成交门槛")
    void describe_containsBothThresholds() {
        assertThat(RoasRule.describe(MIN_USAGE, THRESHOLD))
                .contains("0.5")
                .contains("2.0");
    }
}
