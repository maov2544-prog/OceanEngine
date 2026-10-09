package com.aolandai.oceanengine.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProductRoiRule} 的单测 —— 覆盖命中/未命中、边界（含等于）、
 * 阈值可高可低、空规则兜底、以及各种缺失/脏数据。
 *
 * <p>约定：全局门槛 = 1.8，商品 {@code 3686032876161335490} 覆盖成 6.0
 * （对齐生产实际配置）。</p>
 */
class ProductRoiRuleTest {

    private static final long PRODUCT_HIGH = 3686032876161335490L;
    private static final long PRODUCT_OTHER = 1111111111111111111L;
    private static final double DEFAULT_MIN_ROI = 1.8;

    private static ProductRoiRule rule(Map<Long, Double> overrides, double defaultMinRoi) {
        return new ProductRoiRule(overrides, defaultMinRoi);
    }

    /** 生产口径：只有 PRODUCT_HIGH 覆盖成 6.0 */
    private static ProductRoiRule productionLike() {
        Map<Long, Double> m = new LinkedHashMap<>();
        m.put(PRODUCT_HIGH, 6.0);
        return rule(m, DEFAULT_MIN_ROI);
    }

    // ==================== 命中覆盖 ====================

    @Test
    @DisplayName("命中商品：ROI 门槛用覆盖值 6.0")
    void override_hit_usesOverrideThreshold() {
        assertThat(productionLike().minRoiFor(PRODUCT_HIGH)).isEqualTo(6.0);
    }

    @Test
    @DisplayName("命中商品：ROI=5.9 不达标、ROI=6.0 达标（边界含等于）")
    void override_hit_boundaryInclusive() {
        ProductRoiRule r = productionLike();
        assertThat(r.roiOk(PRODUCT_HIGH, 5.9)).isFalse();
        assertThat(r.roiOk(PRODUCT_HIGH, 6.0)).isTrue();
        assertThat(r.roiOk(PRODUCT_HIGH, 6.01)).isTrue();
    }

    @Test
    @DisplayName("命中商品：ROI=2.0 在全局门槛之上但在商品门槛之下 → 不达标（这就是需求要的效果）")
    void override_hit_roiAboveGlobalButBelowOverride_rejected() {
        ProductRoiRule r = productionLike();
        // 2.0 >= 1.8（全局）但 < 6.0（商品）→ 该商品不追投
        assertThat(r.roiOk(PRODUCT_HIGH, 2.0)).isFalse();
    }

    // ==================== 未命中回退全局 ====================

    @Test
    @DisplayName("未配置的商品：回退全局门槛 1.8")
    void miss_fallsBackToGlobal() {
        ProductRoiRule r = productionLike();
        assertThat(r.minRoiFor(PRODUCT_OTHER)).isEqualTo(DEFAULT_MIN_ROI);
        assertThat(r.roiOk(PRODUCT_OTHER, 1.79)).isFalse();
        assertThat(r.roiOk(PRODUCT_OTHER, 1.8)).isTrue();
    }

    @Test
    @DisplayName("productId 为 null（接口没给商品信息）：回退全局门槛，不放宽也不收紧")
    void nullProductId_fallsBackToGlobal() {
        ProductRoiRule r = productionLike();
        assertThat(r.minRoiFor(null)).isEqualTo(DEFAULT_MIN_ROI);
        assertThat(r.roiOk(null, 1.8)).isTrue();
        assertThat(r.roiOk(null, 1.79)).isFalse();
    }

    // ==================== 空规则（功能不生效） ====================

    @Test
    @DisplayName("空规则（生产没配 product-roi-overrides）：全部商品都用全局门槛，行为与改造前一致")
    void emptyRule_allUseGlobal() {
        ProductRoiRule r = ProductRoiRule.EMPTY;
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.minRoiFor(PRODUCT_HIGH)).isEqualTo(0.0);   // EMPTY 的 defaultMinRoi=0
        assertThat(r.minRoiFor(PRODUCT_OTHER)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("overrides 传 null 视为空规则（不抛异常）")
    void nullOverrides_treatedAsEmpty() {
        ProductRoiRule r = rule(null, DEFAULT_MIN_ROI);
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.minRoiFor(PRODUCT_HIGH)).isEqualTo(DEFAULT_MIN_ROI);
    }

    @Test
    @DisplayName("空 Map 视为不生效，但回退值仍是全局门槛")
    void emptyMap_fallsBackToGlobal() {
        ProductRoiRule r = rule(new HashMap<>(), DEFAULT_MIN_ROI);
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.minRoiFor(PRODUCT_HIGH)).isEqualTo(DEFAULT_MIN_ROI);
    }

    // ==================== 阈值可以低于全局（需求 ④「都能配」） ====================

    @Test
    @DisplayName("覆盖值低于全局门槛也可以生效（不做「只能更高」的校验）")
    void override_lowerThanGlobal_allowed() {
        Map<Long, Double> m = new LinkedHashMap<>();
        m.put(PRODUCT_HIGH, 1.0);
        ProductRoiRule r = rule(m, DEFAULT_MIN_ROI);
        assertThat(r.minRoiFor(PRODUCT_HIGH)).isEqualTo(1.0);
        assertThat(r.roiOk(PRODUCT_HIGH, 1.2)).isTrue();  // 全局 1.8 会拒，这里放行
    }

    // ==================== 多商品 ====================

    @Test
    @DisplayName("多个商品各自用各自的门槛，互不影响")
    void multipleProducts_eachUsesOwnThreshold() {
        Map<Long, Double> m = new LinkedHashMap<>();
        m.put(PRODUCT_HIGH, 6.0);
        m.put(PRODUCT_OTHER, 3.0);
        ProductRoiRule r = rule(m, DEFAULT_MIN_ROI);

        assertThat(r.size()).isEqualTo(2);
        assertThat(r.roiOk(PRODUCT_HIGH, 4.0)).isFalse();   // < 6
        assertThat(r.roiOk(PRODUCT_OTHER, 4.0)).isTrue();   // >= 3
        assertThat(r.roiOk(999L, 4.0)).isTrue();            // 未配 → 1.8
    }

    // ==================== ROI 缺失 ====================

    @Test
    @DisplayName("ROI 为 null 一律不达标（拿不到 ROI 就不追，与原逻辑一致）")
    void nullRoi_neverOk() {
        ProductRoiRule r = productionLike();
        assertThat(r.roiOk(PRODUCT_HIGH, null)).isFalse();
        assertThat(r.roiOk(PRODUCT_OTHER, null)).isFalse();
        assertThat(r.roiOk(null, null)).isFalse();
    }

    // ==================== 防御性拷贝 / 描述 ====================

    @Test
    @DisplayName("构造后修改原 Map 不影响规则（防御性拷贝）")
    void defensiveCopy() {
        Map<Long, Double> m = new LinkedHashMap<>();
        m.put(PRODUCT_HIGH, 6.0);
        ProductRoiRule r = rule(m, DEFAULT_MIN_ROI);
        m.put(PRODUCT_OTHER, 99.0);   // 事后改动
        assertThat(r.size()).isEqualTo(1);
        assertThat(r.minRoiFor(PRODUCT_OTHER)).isEqualTo(DEFAULT_MIN_ROI);
    }

    @Test
    @DisplayName("asMap() 返回只读视图")
    void asMap_isUnmodifiable() {
        ProductRoiRule r = productionLike();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> r.asMap().put(PRODUCT_OTHER, 9.0));
    }

    @Test
    @DisplayName("describe()：配了打印映射，没配明确说「未配置」")
    void describe_mentionsConfiguredOrNot() {
        ProductRoiRule r = productionLike();
        assertThat(r.describe())
                .contains(String.valueOf(PRODUCT_HIGH))
                .contains("6.0")
                .contains("1.8");

        assertThat(ProductRoiRule.EMPTY.describe()).contains("未配置");
    }
}
