package com.aolandai.oceanengine.report;

import com.aolandai.oceanengine.storage.FinishedOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FinishedOrderTracker#newlyFinished} 的单测 —— 增量对比的纯函数部分。
 *
 * <p>这是整个「已结束达标订单」功能的核心逻辑（底表对比取新增），
 * 抽成静态纯函数就是为了能这样直接测，不必去 mock 接口和 token。</p>
 */
class FinishedOrderTrackerTest {

    private static FinishedOrder order(long orderId) {
        return new FinishedOrder(orderId, 999L, "抖音名", "aweme_" + orderId, 2.0, "FINISHED",
                "2026-09-23 09:00:00", "2026-09-24 00:01:00");
    }

    @Test
    @DisplayName("底表为空 → 全部算新增（首次运行会走这条，但调用方会拦掉不输出）")
    void emptyBaselineMeansEverythingIsNew() {
        List<FinishedOrder> result = FinishedOrderTracker.newlyFinished(
                Set.of(), List.of(order(1L), order(2L)));

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("底表已有的订单不算新增")
    void existingOrdersAreNotNew() {
        List<FinishedOrder> result = FinishedOrderTracker.newlyFinished(
                Set.of(1L, 2L, 3L), List.of(order(1L), order(2L), order(3L)));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("只返回底表里没有的 —— 这就是「当天新结束的」")
    void returnsOnlyOrdersMissingFromBaseline() {
        List<FinishedOrder> result = FinishedOrderTracker.newlyFinished(
                Set.of(1L, 2L), List.of(order(1L), order(2L), order(3L), order(4L)));

        assertThat(result).extracting(FinishedOrder::getOrderId)
                .containsExactly(3L, 4L);
    }

    @Test
    @DisplayName("保持输入顺序（写进 sheet 的行序稳定、可复现）")
    void preservesInputOrder() {
        List<FinishedOrder> result = FinishedOrderTracker.newlyFinished(
                Set.of(), List.of(order(30L), order(10L), order(20L)));

        assertThat(result).extracting(FinishedOrder::getOrderId)
                .containsExactly(30L, 10L, 20L);
    }

    @Test
    @DisplayName("同一次拉取里重复出现的同一订单只算一条（多账户/多状态可能捞到同一订单）")
    void deduplicatesWithinCurrentBatch() {
        List<FinishedOrder> result = FinishedOrderTracker.newlyFinished(
                Set.of(), List.of(order(1L), order(1L), order(2L)));

        assertThat(result).extracting(FinishedOrder::getOrderId)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("orderId 为 null 的条目跳过，不抛异常")
    void skipsNullOrderId() {
        FinishedOrder bad = new FinishedOrder(null, 999L, "n", "a", 2.0, "FINISHED",
                "2026-09-23 09:00:00", "2026-09-24 00:01:00");

        List<FinishedOrder> result = FinishedOrderTracker.newlyFinished(
                Set.of(), java.util.Arrays.asList(bad, order(1L)));

        assertThat(result).extracting(FinishedOrder::getOrderId).containsExactly(1L);
    }

    @Test
    @DisplayName("底表为 null 时当作空处理，不抛 NPE")
    void nullBaselineIsTreatedAsEmpty() {
        List<FinishedOrder> result = FinishedOrderTracker.newlyFinished(null, List.of(order(1L)));

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("空输入返回空列表")
    void emptyInputReturnsEmptyList() {
        assertThat(FinishedOrderTracker.newlyFinished(Set.of(), List.of())).isEmpty();
    }
}
