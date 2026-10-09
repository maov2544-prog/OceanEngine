package com.aolandai.oceanengine.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 频控错误码识别与分类的单元测试。
 *
 * <p>错误码与处理策略来自官方《频控限制》文档：40130 客户频控 / 40110 开发者频控 / 40100 接口总频控，
 * 其中 40110 超限会触发持续 5 分钟的惩罚。</p>
 */
class ApiErrorsTest {

    @Test
    @DisplayName("三层频控错误码都能被识别，其他错误码不会误判")
    void isRateLimit_onlyMatchesTheThreeOfficialCodes() {
        assertThat(ApiErrors.isRateLimit(40100L)).isTrue();
        assertThat(ApiErrors.isRateLimit(40110L)).isTrue();
        assertThat(ApiErrors.isRateLimit(40130L)).isTrue();

        assertThat(ApiErrors.isRateLimit(0L)).isFalse();
        assertThat(ApiErrors.isRateLimit(40002L)).isFalse();
        assertThat(ApiErrors.isRateLimit(40000L)).isFalse();
        // 注意别把 401000 这类更长的码误判成 40100
        assertThat(ApiErrors.isRateLimit(401000L)).isFalse();
        assertThat(ApiErrors.isRateLimit(null)).isFalse();
    }

    @Test
    @DisplayName("错误码到频控层级的映射正确")
    void scopeOf_mapsEachCodeToItsLayer() {
        assertThat(ApiErrors.scopeOf(40130L)).isEqualTo(RateLimitException.Scope.CUSTOMER);
        assertThat(ApiErrors.scopeOf(40110L)).isEqualTo(RateLimitException.Scope.DEVELOPER);
        assertThat(ApiErrors.scopeOf(40100L)).isEqualTo(RateLimitException.Scope.GLOBAL);
    }

    @Test
    @DisplayName("40110 的退避时长覆盖官方 5 分钟惩罚期")
    void developerPenaltyBackoff_coversTheFiveMinutePenalty() {
        long backoff = ApiErrors.backoffOf(RateLimitException.Scope.DEVELOPER);

        assertThat(backoff)
                .as("必须不小于官方的 5 分钟惩罚期，否则重试必然全部失败")
                .isGreaterThanOrEqualTo(5 * 60_000L);
        assertThat(backoff).isEqualTo(ApiErrors.DEVELOPER_PENALTY_MILLIS);
    }

    @Test
    @DisplayName("40130 只影响单个账户，不需要等待；40100 错峰等 1 分钟")
    void backoffPerScope() {
        assertThat(ApiErrors.backoffOf(RateLimitException.Scope.CUSTOMER)).isZero();
        assertThat(ApiErrors.backoffOf(RateLimitException.Scope.GLOBAL)).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("非频控码不构造异常，频控码构造出的异常携带正确层级与退避")
    void rateLimitOrNull() {
        assertThat(ApiErrors.rateLimitOrNull(0L, "订单列表", "req-1")).isNull();
        assertThat(ApiErrors.rateLimitOrNull(40002L, "订单列表", "req-1")).isNull();
        assertThat(ApiErrors.rateLimitOrNull(null, "订单列表", "req-1")).isNull();

        RateLimitException e = ApiErrors.rateLimitOrNull(40110L, "随心推全域订单列表", "req-9");

        assertThat(e).isNotNull();
        assertThat(e.scope()).isEqualTo(RateLimitException.Scope.DEVELOPER);
        assertThat(e.apiCode()).isEqualTo(40110L);
        assertThat(e.getCode()).as("getCode() 应返回官方频控码").isEqualTo(40110);
        assertThat(e.suggestedBackoffMillis()).isEqualTo(ApiErrors.DEVELOPER_PENALTY_MILLIS);
        assertThat(e.getMessage())
                .contains("随心推全域订单列表")
                .contains("40110")
                .contains("开发者频控")
                .contains("req-9");
    }

    @Test
    @DisplayName("只有 40130 是「账户级」，40100/40110 必须中止整轮")
    void onlyCustomerScopeIsAccountScoped() {
        assertThat(ApiErrors.rateLimitOrNull(40130L, "x", null).isAccountScoped())
                .as("40130 只针对单个 advertiser_id，跳过该账户继续本轮是安全的")
                .isTrue();
        assertThat(ApiErrors.rateLimitOrNull(40110L, "x", null).isAccountScoped())
                .as("40110 有 5 分钟惩罚期，必须中止整轮")
                .isFalse();
        assertThat(ApiErrors.rateLimitOrNull(40100L, "x", null).isAccountScoped())
                .as("40100 是全平台限流，必须中止整轮")
                .isFalse();
    }

    @Test
    @DisplayName("describe() 给出可读的一行说明，含错误码与处理建议")
    void describeIsReadable() {
        RateLimitException developer = ApiErrors.rateLimitOrNull(40110L, "订单列表", null);
        assertThat(developer.describe())
                .contains("40110")
                .contains("开发者频控")
                .contains("5 分钟")
                .contains("330 秒");

        RateLimitException customer = ApiErrors.rateLimitOrNull(40130L, "订单列表", null);
        assertThat(customer.describe())
                .contains("40130")
                .contains("不活跃账户")
                .as("40130 不需要等待，不应出现「建议等待」")
                .doesNotContain("建议等待");
    }

    @Test
    @DisplayName("RateLimitException 是 ApiException 的子类，旧有 catch(ApiException) 仍能兜住")
    void rateLimitExceptionIsAnApiException() {
        RateLimitException e = ApiErrors.rateLimitOrNull(40100L, "订单列表", null);

        assertThat(e).isInstanceOf(com.bytedance.ads.ApiException.class);
        assertThat((com.bytedance.ads.ApiException) e)
                .as("super(message) 必须真的把消息存进去，否则日志会丢信息")
                .extracting(Throwable::getMessage)
                .asString()
                .contains("40100");
    }

    @Test
    @DisplayName("业务错误的判定口径保持不变")
    void isBusinessErrorKeepsOriginalRules() {
        assertThat(ApiErrors.isBusinessError("随心推全域订单列表 code=40002 message=x")).isTrue();
        assertThat(ApiErrors.isBusinessError("code=40000 参数错误")).isTrue();
        assertThat(ApiErrors.isBusinessError("Account 123 doesn't exist or the role is wrong")).isTrue();
        assertThat(ApiErrors.isBusinessError("仅支持创建商品全域")).isTrue();

        assertThat(ApiErrors.isBusinessError("随心推全域订单列表 code=40110")).isFalse();
        assertThat(ApiErrors.isBusinessError(null)).isFalse();
    }

    @Test
    @DisplayName("限流码不会被当成业务错误（否则会被直接抛出而不走限流分支）")
    void rateLimitCodesAreNotBusinessErrors() {
        for (long code : new long[]{40100L, 40110L, 40130L}) {
            RateLimitException e = ApiErrors.rateLimitOrNull(code, "订单列表", null);
            assertThat(ApiErrors.isBusinessError(e.getMessage()))
                    .as("限流码 %d 不能落入业务错误判定，否则会绕开限流处理逻辑", code)
                    .isFalse();
        }
    }
}
