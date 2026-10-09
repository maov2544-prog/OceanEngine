package com.aolandai.oceanengine.webhook;

/**
 * webhook 发送器。
 *
 * <p>抽成接口有两个目的：① 让阈值扫描逻辑能脱离 HTTP 做单元测试；
 * ② 将来要换成别的投递方式（消息队列等）时不用改调用方。</p>
 */
public interface WebhookSender {

    /**
     * 发送一条通知。
     *
     * <p>约定：<b>不抛异常</b>。网络错误、超时、非 2xx 都通过返回值表达 ——
     * 调用方是追投轮次的收尾逻辑，不能被通知失败拖垮。</p>
     */
    Result send(WebhookNotification notification);

    /** 发送结果 */
    record Result(boolean success, int httpStatus, String detail) {

        /** 没有拿到 HTTP 状态码（连接失败 / 超时 / 中断）时用这个值 */
        public static final int NO_RESPONSE = -1;

        public static Result ok(int httpStatus) {
            return new Result(true, httpStatus, "OK");
        }

        public static Result fail(int httpStatus, String detail) {
            return new Result(false, httpStatus, detail);
        }
    }
}
