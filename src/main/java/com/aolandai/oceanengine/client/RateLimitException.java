package com.aolandai.oceanengine.client;

import com.bytedance.ads.ApiException;

/**
 * 开放平台频控（限流）异常。
 *
 * <p>官方《频控限制》文档定义了 3 层频控，各有独立错误码：</p>
 * <ul>
 *   <li><b>40130 客户频控</b> —— 仅被平台标记为「不活跃账户」的客户受管控，
 *       被它拦截的请求<b>不占用</b>开发者维度的配额；</li>
 *   <li><b>40110 开发者频控</b> —— 本开发者账户 × 单接口的 QPS 配额用尽。
 *       ⚠️ 超限会触发<b>持续 5 分钟</b>的惩罚，<b>这 5 分钟内所有请求全部失败</b>；</li>
 *   <li><b>40100 接口总频控</b> —— 全平台所有开发者共享，由系统动态调整，
 *       峰期（整点/半点）容易出现。</li>
 * </ul>
 *
 * <p>因此限流错误<b>一律不做原地重试</b>：40130 重试没有意义，
 * 40110/40100 原地重试只会加重惩罚。由调用方决定是「只跳过当前账户」还是「中止整轮」。</p>
 *
 * @see ApiErrors#rateLimitOrNull(Long, String, String)
 */
public class RateLimitException extends ApiException {

    private static final long serialVersionUID = 1L;

    /** 被哪一层频控拦截 */
    public enum Scope {

        /** 客户频控（40130）：只有「不活跃账户」会被拦，且不占用开发者配额 */
        CUSTOMER(40130L, "客户频控",
                "该账户被平台标记为「不活跃账户」（90 天无活跃行为），"
                        + "官方建议把这类账户的查询间隔降到天级"),

        /** 开发者频控（40110）：超限后 5 分钟内所有请求都会失败 */
        DEVELOPER(40110L, "开发者频控",
                "本开发者账户在该接口的 QPS 配额已用尽，"
                        + "官方惩罚持续 5 分钟、期间所有请求都会失败，本轮应直接放弃"),

        /** 接口总频控（40100）：全平台共享，属峰期现象 */
        GLOBAL(40100L, "接口总频控",
                "全平台请求总量超限，属峰期现象，官方建议错峰（避开整点/半点）");

        private final long code;
        private final String label;
        private final String advice;

        Scope(long code, String label, String advice) {
            this.code = code;
            this.label = label;
            this.advice = advice;
        }

        public long code() {
            return code;
        }

        public String label() {
            return label;
        }

        public String advice() {
            return advice;
        }

        public static Scope of(long code) {
            for (Scope s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("不是频控错误码: " + code);
        }
    }

    private final Scope scope;
    private final long suggestedBackoffMillis;

    public RateLimitException(Scope scope, String message, long suggestedBackoffMillis) {
        super(message);
        this.scope = scope;
        this.suggestedBackoffMillis = suggestedBackoffMillis;
    }

    /** 返回官方频控错误码（40100 / 40110 / 40130） */
    @Override
    public int getCode() {
        return (int) scope.code();
    }

    public Scope scope() {
        return scope;
    }

    public long apiCode() {
        return scope.code();
    }

    /** 建议的等待时长（毫秒）。40130 为 0（只跳过账户，无需等待） */
    public long suggestedBackoffMillis() {
        return suggestedBackoffMillis;
    }

    /**
     * 是否只影响当前账户。
     *
     * <p>40130（客户频控）只针对单个 {@code advertiser_id}，且不占用开发者配额，
     * 因此跳过该账户、继续本轮其余账户是安全的；40100/40110 则必须中止整轮。</p>
     */
    public boolean isAccountScoped() {
        return scope == Scope.CUSTOMER;
    }

    /** 一行式说明，供日志使用 */
    public String describe() {
        StringBuilder sb = new StringBuilder()
                .append("限流 code=").append(scope.code())
                .append("（").append(scope.label()).append("）：").append(scope.advice());
        if (suggestedBackoffMillis > 0) {
            sb.append("；建议等待 ").append(suggestedBackoffMillis / 1000).append(" 秒");
        }
        return sb.toString();
    }
}
