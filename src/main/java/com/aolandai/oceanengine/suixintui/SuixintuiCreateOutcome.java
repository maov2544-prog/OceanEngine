package com.aolandai.oceanengine.suixintui;

import java.util.List;

/**
 * 建随心推的结果。
 *
 * <p>⚠️ {@code dryRun=true} 时 {@code orderId} 为空 —— <b>没有真的建单</b>，
 * 只把组装好的请求体回显出来供人工核对（尤其是 {@code amount} / {@code deliveryTime} 的单位）。</p>
 *
 * @param dryRun      是否是演练（true = 没真建）
 * @param orderId     建成的随心推订单 ID；dry-run 时为 {@code null}
 * @param requestBody 组装好的请求体 JSON（dry-run 核对用；成功时也带上便于对账）
 * @param warnings    过程中的异常情况
 */
public record SuixintuiCreateOutcome(
        boolean dryRun,
        Long orderId,
        String requestBody,
        List<String> warnings) {

    public SuixintuiCreateOutcome {
        requestBody = requestBody == null ? "" : requestBody;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 是否真的建成了（非演练且拿到订单 ID）。 */
    public boolean created() {
        return !dryRun && orderId != null;
    }
}
