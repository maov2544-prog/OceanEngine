package com.aolandai.oceanengine.domain;

import com.aolandai.oceanengine.suixintui.Decimals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SuixintuiConstraints} 边界值单测 —— 这些约束错了<b>不会编译失败</b>，
 * 只会表现为「接口报 40000」或更糟的「静默按错误预算建单（花钱）」，所以必须钉死。
 */
class SuixintuiConstraintsTest {

    // ==================== amount ====================

    @Test
    @DisplayName("amount 边界：100 合法 / 5000000 合法 / 99 非法 / 5000010 非法")
    void amountRange() {
        assertTrue(SuixintuiConstraints.isAmountAllowed(100L));
        assertTrue(SuixintuiConstraints.isAmountAllowed(5_000_000L));
        assertFalse(SuixintuiConstraints.isAmountAllowed(99L));
        assertFalse(SuixintuiConstraints.isAmountAllowed(5_000_010L));
    }

    @Test
    @DisplayName("amount 必须是 10 的倍数：105 非法 / 110 合法")
    void amountStep() {
        assertFalse(SuixintuiConstraints.isAmountAllowed(105L));
        assertTrue(SuixintuiConstraints.isAmountAllowed(110L));
        assertNotNull(SuixintuiConstraints.amountProblem(105L));
        assertTrue(SuixintuiConstraints.amountProblem(105L).contains("105"));
        assertNull(SuixintuiConstraints.amountProblem(100L));
    }

    // ==================== deliveryTime ====================

    @Test
    @DisplayName("delivery_time 是白名单不是范围：168 合法 / 3 非法 / 36 非法")
    void deliveryTimeWhitelist() {
        assertTrue(SuixintuiConstraints.isDeliveryTimeAllowed(168.0));
        assertTrue(SuixintuiConstraints.isDeliveryTimeAllowed(24.0));
        assertTrue(SuixintuiConstraints.isDeliveryTimeAllowed(2.0));
        assertFalse(SuixintuiConstraints.isDeliveryTimeAllowed(3.0));
        assertFalse(SuixintuiConstraints.isDeliveryTimeAllowed(36.0));
    }

    @Test
    @DisplayName("delivery_time 提示里列出允许值（去掉 .0）")
    void deliveryTimeDescribe() {
        String desc = SuixintuiConstraints.describeDeliveryTimes();
        assertTrue(desc.startsWith("2 / 6 / 12"), desc);
        assertTrue(desc.endsWith("168"), desc);
        assertFalse(desc.contains(".0"), desc);
    }

    // ==================== roiGoal ====================

    @Test
    @DisplayName("roi_goal 边界：0.01 合法 / 10000 合法 / 0 非法 / 10000.5 非法")
    void roiGoalRange() {
        assertNull(SuixintuiConstraints.roiGoalProblem(0.01, "MANUAL_BID"));
        assertNull(SuixintuiConstraints.roiGoalProblem(10_000.0, "MANUAL_BID"));
        assertNotNull(SuixintuiConstraints.roiGoalProblem(0.0, "MANUAL_BID"));
        assertNotNull(SuixintuiConstraints.roiGoalProblem(10_000.5, "MANUAL_BID"));
    }

    @Test
    @DisplayName("★ roi_goal 最多两位小数：1.7 合法 / 1.75 合法 / 1.234 非法（用 BigDecimal.valueOf 才不会误判）")
    void roiGoalScale() {
        assertNull(SuixintuiConstraints.roiGoalProblem(1.7, "MANUAL_BID"));
        assertNull(SuixintuiConstraints.roiGoalProblem(1.75, "MANUAL_BID"));
        assertNotNull(SuixintuiConstraints.roiGoalProblem(1.234, "MANUAL_BID"));
    }

    @Test
    @DisplayName("★ ROI 条件必填：MANUAL_BID 时 roi_goal 不能为空；AUTO_BID 可空")
    void roiGoalConditionallyRequired() {
        assertNotNull(SuixintuiConstraints.roiGoalProblem(null, "MANUAL_BID"));
        assertNull(SuixintuiConstraints.roiGoalProblem(null, "AUTO_BID"));
        assertTrue(SuixintuiConstraints.isRoiGoalRequired("MANUAL_BID"));
        assertFalse(SuixintuiConstraints.isRoiGoalRequired("AUTO_BID"));
    }

    @Test
    @DisplayName("Decimals：1.7 → 1 位，1.75 → 2 位，2.0 → 0 位（去尾随零）")
    void decimalsScale() {
        assertEquals(1, Decimals.scaleOf(1.7));
        assertEquals(2, Decimals.scaleOf(1.75));
        assertEquals(0, Decimals.scaleOf(2.0));
        assertEquals("10000", Decimals.plain(10_000.0));
    }
}
