package com.example.oceanengine.scheduler;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 健康检查指示器：{@code GET /actuator/health} 可查看进程存活与最近一轮追投状态。
 *
 * <p>用于部署环境（systemd / 云监控 / 手动 curl）确认进程在正常执行轮次，
 * 而不是"活着但已经卡死"。</p>
 */
@Component
public class ReinvestHealthIndicator implements HealthIndicator {

    private final SuixintuiReinvestScheduler scheduler;

    public ReinvestHealthIndicator(SuixintuiReinvestScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Health health() {
        ReinvestRoundResult last = scheduler.getLastResult();
        if (last == null) {
            return Health.up()
                    .withDetail("status", "启动中，尚未执行轮次")
                    .build();
        }
        Health.Builder health = last.getError() == null ? Health.up() : Health.down();
        return health
                .withDetail("lastRound", last.summarize())
                .build();
    }
}
