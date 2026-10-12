package com.aolandai.oceanengine.suixintui;

import com.bytedance.ads.ApiException;

/**
 * 建随心推订单的<b>参数不合法</b>异常（对应 dingtou 的 {@code BizException.badRequest}，{@code code=2}）。
 *
 * <p>与普通 {@link ApiException}（接口/网络异常）区分开：本异常表示<b>请求根本没有发出去</b>
 * —— 参数在组装前就被 {@link SuixintuiConstraints} 拦下了。</p>
 *
 * <p>为什么单独建类：调用方需要能区分「真调了接口但被拒」和「本地就拦住了」——
 * 前者可能要排查接口口径变化，后者是配置/调用方参数写错了，处置完全不同。</p>
 */
public class SuixintuiParamException extends ApiException {

    private static final long serialVersionUID = 1L;

    /** 与 dingtou {@code ErrorCode.BAD_REQUEST} 对齐：参数错误 */
    public static final int CODE_BAD_REQUEST = 2;

    public SuixintuiParamException(String message) {
        super(CODE_BAD_REQUEST, message);
    }

    /** 业务错误码（恒为 {@link #CODE_BAD_REQUEST}） */
    public int errorCode() {
        return getCode();
    }
}
