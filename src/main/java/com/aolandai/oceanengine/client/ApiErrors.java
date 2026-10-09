package com.aolandai.oceanengine.client;

/**
 * 开放平台错误码分类工具。
 *
 * <p>频控规则来自官方《频控限制》文档（2026-09-29 核对）：
 * <a href="https://open.oceanengine.com/labels/11/docs/1696710758159360">
 * https://open.oceanengine.com/labels/11/docs/1696710758159360</a>。</p>
 *
 * <p>各服务原先各自维护一份 {@code isBusinessError}，容易漂移；此处统一，
 * 并补上此前完全缺失的<b>限流错误码识别</b>。</p>
 */
public final class ApiErrors {

    /** 接口总频控：全平台所有开发者共享，系统动态调整，峰期（整点/半点）易触发 */
    public static final long CODE_GLOBAL_RATE_LIMIT = 40100L;

    /** 开发者频控：本开发者账户 × 单接口的 QPS 配额。超限触发 5 分钟惩罚 */
    public static final long CODE_DEVELOPER_RATE_LIMIT = 40110L;

    /** 客户频控：仅「不活跃账户」受管控，被拦的请求不占用开发者配额 */
    public static final long CODE_CUSTOMER_RATE_LIMIT = 40130L;

    /**
     * 40110 的惩罚时长。
     *
     * <p>官方原文：超过「开发者QPS配额 × 100 次/分钟」会触发<b>持续 5 分钟</b>的报错惩罚，
     * 期间所有请求全部失败。这里多留 30 秒缓冲，避免卡在惩罚的尾巴上。</p>
     */
    public static final long DEVELOPER_PENALTY_MILLIS = 5 * 60_000L + 30_000L;

    /** 40100 是系统动态限流，错峰即可，等 1 分钟足够 */
    public static final long GLOBAL_THROTTLE_BACKOFF_MILLIS = 60_000L;

    private ApiErrors() {
    }

    /** 是否是三层频控之一（40100 / 40110 / 40130） */
    public static boolean isRateLimit(Long code) {
        return code != null
                && (code == CODE_GLOBAL_RATE_LIMIT
                        || code == CODE_DEVELOPER_RATE_LIMIT
                        || code == CODE_CUSTOMER_RATE_LIMIT);
    }

    /** 按错误码取频控层级；调用前请先用 {@link #isRateLimit(Long)} 判断 */
    public static RateLimitException.Scope scopeOf(long code) {
        return RateLimitException.Scope.of(code);
    }

    /** 该层级对应的建议等待时长 */
    public static long backoffOf(RateLimitException.Scope scope) {
        return switch (scope) {
            case CUSTOMER -> 0L;
            case DEVELOPER -> DEVELOPER_PENALTY_MILLIS;
            case GLOBAL -> GLOBAL_THROTTLE_BACKOFF_MILLIS;
        };
    }

    /**
     * 业务性错误（参数错误 / 账户类型不适用 / 接口不支持）：重试无意义。
     *
     * <p>沿用历史判定口径，只是集中到此处，避免各服务各写一份。</p>
     */
    public static boolean isBusinessError(String message) {
        return message != null
                && (message.contains("code=40002")
                        || message.contains("code=40000")
                        || message.contains("doesn't exist or the role is wrong")
                        || message.contains("仅支持创建商品全域"));
    }

    /**
     * 命中频控错误码时构造 {@link RateLimitException}，否则返回 {@code null}。
     *
     * @param code      接口返回的业务错误码
     * @param context   调用场景描述，如「随心推全域订单列表」
     * @param requestId 接口返回的 request_id，便于向平台追查
     */
    public static RateLimitException rateLimitOrNull(Long code, String context, String requestId) {
        if (!isRateLimit(code)) {
            return null;
        }
        RateLimitException.Scope scope = scopeOf(code);
        StringBuilder msg = new StringBuilder()
                .append(context)
                .append(" 被").append(scope.label())
                .append("拦截 code=").append(code);
        if (requestId != null) {
            msg.append(" request_id=").append(requestId);
        }
        return new RateLimitException(scope, msg.toString(), backoffOf(scope));
    }
}
