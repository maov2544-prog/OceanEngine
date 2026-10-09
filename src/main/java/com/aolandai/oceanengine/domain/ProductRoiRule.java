package com.aolandai.oceanengine.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品级 ROI 门槛覆盖规则 —— <b>纯函数，不碰 IO</b>，便于单测全覆盖。
 *
 * <p>业务背景（2026-10-04 老板需求）：绝大多数订单用统一门槛（{@code min-roi}，生产为 1.8）；
 * 但<b>少量特定商品</b>要求更高的 ROI 才允许追投，例如商品 {@code 3686032876161335490}
 * 必须 ROI ≥ 6。这个门槛是<b>商品属性</b>，不是账户属性，也不随时间变化。</p>
 *
 * <p>判定口径（需求确认 2026-10-04）：</p>
 * <ul>
 *   <li>命中配置的商品 → 用<b>该商品配的</b>阈值；</li>
 *   <li>未命中的商品（含 {@code productId} 为 null）→ 回退到<b>全局</b> {@code min-roi}；</li>
 *   <li>配的阈值<b>可以高于也可以低于</b>全局值（"都能配"，纯覆盖，不做"只能更高"的校验）；</li>
 *   <li>边界<b>含等于</b>：{@code roi >= 门槛} 即放行（与全局 ROI 判定一致）。</li>
 * </ul>
 *
 * <p>映射为空 = 功能不生效，行为与改造前<b>完全一致</b>（需求确认 ⑤-a）。
 * 这是刻意的兜底：生产配置漏写这个 key 时，不会莫名其妙改变追投行为。</p>
 *
 * <p>⚠️ 配置来源是 <b>Map</b>（{@code product-roi-overrides}），外部配置是<b>整体替换</b>而不是合并 ——
 * 写漏的商品会被静默丢弃（与 {@code advertiser-tiers} 同一个坑）。所以启动横幅必须把
 * 实际生效的映射打印出来，供人工核对，见 {@link #describe()}。</p>
 */
public final class ProductRoiRule {

    /** 空规则：任何商品都回退全局门槛（生产没配 {@code product-roi-overrides} 时就是这个） */
    public static final ProductRoiRule EMPTY =
            new ProductRoiRule(Collections.emptyMap(), 0.0);

    /** 商品 ID → ROI 门槛。用 LinkedHashMap 保持配置书写顺序，日志里好核对 */
    private final Map<Long, Double> overrides;

    /** 全局 ROI 门槛（未命中商品时回退用） */
    private final double defaultMinRoi;

    /**
     * @param overrides     商品 ID → ROI 门槛；为 null 视为空
     * @param defaultMinRoi 全局 ROI 门槛（回退值）
     */
    public ProductRoiRule(Map<Long, Double> overrides, double defaultMinRoi) {
        this.overrides = overrides == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(overrides);
        this.defaultMinRoi = defaultMinRoi;
    }

    /** 本商品实际适用的 ROI 门槛 */
    public double minRoiFor(Long productId) {
        if (productId == null) {
            return defaultMinRoi;
        }
        Double override = overrides.get(productId);
        return override == null ? defaultMinRoi : override;
    }

    /**
     * 该商品的 ROI 是否达标。
     *
     * <p>{@code roi} 为 null 视为不达标（与 {@code SuixintuiReinvestCandidateSelector} 原逻辑一致：
     * 拿不到 ROI 就不追）。</p>
     */
    public boolean roiOk(Long productId, Double roi) {
        if (roi == null) {
            return false;
        }
        return roi >= minRoiFor(productId);
    }

    /** 是否配了任何商品级覆盖（没配 = 功能不生效） */
    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    /** 命中的商品个数 */
    public int size() {
        return overrides.size();
    }

    /** 只读视图，供日志/自检用 */
    public Map<Long, Double> asMap() {
        return Collections.unmodifiableMap(overrides);
    }

    /**
     * 启动横幅用的一行概览。
     *
     * <p>没配时返回「未配置（全部商品用全局门槛 X）」，让"功能没生效"在日志里也能一眼看出来 ——
     * 避免配错了却以为生效了（与 {@code 分档:} 一行的用意相同）。</p>
     */
    public String describe() {
        if (overrides.isEmpty()) {
            return "未配置（全部商品用全局门槛 " + defaultMinRoi + "）";
        }
        return overrides.entrySet().stream()
                .map(e -> e.getKey() + "→" + e.getValue())
                .collect(Collectors.joining(", "))
                + "（其余商品用全局门槛 " + defaultMinRoi + "）";
    }
}
