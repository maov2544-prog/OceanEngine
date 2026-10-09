package com.aolandai.oceanengine.domain;

import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SuixintuiReinvestCandidateSelector} 的纯函数单测 ——
 * 整体消耗下限过滤（2026-09-30 新增）、商品 ID 提取（2026-10-04 新增）。
 *
 * <p>这些判定被抽成纯函数就是为了能这样直接测，不必去 mock 订单服务。</p>
 */
class SuixintuiReinvestCandidateSelectorTest {

    @Test
    @DisplayName("消耗正好等于下限 → 保留（边界含等于）")
    void exactlyAtLimit_kept() {
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(100.0, 100.0)).isTrue();
    }

    @Test
    @DisplayName("消耗高于下限 → 保留")
    void aboveLimit_kept() {
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(150.84, 100.0)).isTrue();
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(100.01, 100.0)).isTrue();
    }

    @Test
    @DisplayName("消耗低于下限 → 排除")
    void belowLimit_excluded() {
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(99.99, 100.0)).isFalse();
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(61.12, 100.0)).isFalse();
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(0.0, 100.0)).isFalse();
    }

    @Test
    @DisplayName("消耗为 null → 需要过滤时排除")
    void nullStatCost_excluded() {
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(null, 100.0)).isFalse();
    }

    @Test
    @DisplayName("下限 <= 0 → 不过滤，一律放行（含 null，交由使用率判定拒绝）")
    void disabledFilter_passesEverything() {
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(0.0, 0.0)).isTrue();
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(5.0, 0.0)).isTrue();
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(null, 0.0)).isTrue();
        assertThat(SuixintuiReinvestCandidateSelector.statCostOk(null, -1.0)).isTrue();
    }

    // ==================== 商品 ID 提取（2026-10-04） ====================

    @Test
    @DisplayName("productIdOf：正常取出 product_info.product_id（19 位 Long 不丢精度）")
    void productIdOf_extractsLong() {
        long expected = 3686032876161335490L;
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o =
                new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner()
                        .productInfo(new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerProductInfo()
                                .productId(expected));
        assertThat(SuixintuiReinvestCandidateSelector.productIdOf(o)).isEqualTo(expected);
    }

    @Test
    @DisplayName("productIdOf：productInfo 为 null → null（调用方回退全局门槛）")
    void productIdOf_nullProductInfo_returnsNull() {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o =
                new QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner();
        assertThat(SuixintuiReinvestCandidateSelector.productIdOf(o)).isNull();
    }

    @Test
    @DisplayName("productIdOf：订单本身为 null → null（防御，不抛异常）")
    void productIdOf_nullOrder_returnsNull() {
        assertThat(SuixintuiReinvestCandidateSelector.productIdOf(null)).isNull();
    }
}
