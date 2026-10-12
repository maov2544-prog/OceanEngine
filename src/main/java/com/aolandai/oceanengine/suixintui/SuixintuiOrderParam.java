package com.aolandai.oceanengine.suixintui;

import com.aolandai.oceanengine.domain.SuixintuiConstraints;

/**
 * 建一个随心推订单所需的参数（A 场景）。
 *
 * <p>对应千川接口 {@code /qianchuan/aweme/uni_promotion/order/create/} 的请求体：
 * 其中 {@code advertiserId} / {@code awemeId} / {@code productId} 是<b>必填</b>，
 * {@code amount} 与 {@code deliveryTime} 也是<b>必填</b>
 * （其余 {@code bidType} / {@code qcpxMode} / {@code roiGoal} 可选）。</p>
 *
 * <p>⚠️ <b>数值硬约束</b>（官方文档，2026-10-05 确认，规则见 {@link SuixintuiConstraints}）：
 * {@code amount} 单位<b>元</b>、必须落在 <b>[100, 5000000]</b> 且为 <b>10 的倍数</b>；
 * {@code deliveryTime} 单位<b>小时</b>、<b>只能是白名单</b>
 * 2/6/12/24/48/72/96/120/144/168；
 * {@code roiGoal} <b>最多两位小数</b>、范围 <b>[0.01, 10000]</b>，且
 * <b>{@code bidType=MANUAL_BID} 时必填</b>。<b>校验在 {@code SuixintuiOrderCreateService} 里统一做</b>
 * （连 dry-run 也不放行非法参数），所以本 record 不做构造期校验。</p>
 *
 * @param advertiserId 投放账户 ID
 * @param awemeId      达人的<b>数值 UID</b>（<b>不是</b>抖音号字符串；来自订单列表
 *                     {@code aweme_info.aweme_id}）
 * @param productId    商品 ID（来自订单列表 {@code product_info.product_id}）
 * @param amount       预算，<b>单位：元</b>（[100, 5000000] 且 10 的倍数）
 * @param deliveryTime 投放时长，<b>单位：小时</b>（白名单 10 个值）
 * @param bidType      出价方式：{@code AUTO_BID} / {@code MANUAL_BID}；{@code null} = 不传
 * @param qcpxMode     乘方模式：{@code QCPX_MODE_ON} / {@code QCPX_MODE_OFF} /
 *                     {@code QCPX_MODE_DEFAULT}（⚠️ 不是 {@code ON}/{@code OFF}）；{@code null} = 不传
 * @param roiGoal      支付 ROI 目标；{@code null} = 不传，但
 *                     <b>{@code bidType=MANUAL_BID} 时不可为 null</b>（官方条件必填）
 */
public record SuixintuiOrderParam(
        long advertiserId,
        long awemeId,
        long productId,
        long amount,
        double deliveryTime,
        String bidType,
        String qcpxMode,
        Double roiGoal) {
}
