package com.aolandai.oceanengine.report;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * 日报触发入口：定时（默认每日 00:01 生成<b>前一天</b>的报表）+ 启动时的补生成 / 手动生成。
 *
 * <p>三条触发路径：</p>
 * <ol>
 *   <li>{@link #scheduledReport()} —— {@code @Scheduled(cron)}，正常路径；</li>
 *   <li>启动补生成 —— 进程在 00:01 时不在（重启/宕机）会漏一天，
 *       所以启动时若「昨日报表」不存在就补一次（{@code catch-up-on-start}）；</li>
 *   <li>{@code run-now} —— 部署后不等 00:01 就能立刻出一份来验证。</li>
 * </ol>
 *
 * <p><b>日报失败绝不能影响追投主流程</b>：所有异常都在 {@link #generateSafely} 里被兜住，
 * 只打印到 stderr，不向外抛。</p>
 *
 * <p>实现 {@link ApplicationRunner} 且 {@code @Order(1)}：{@code ReinvestApplication} 本身也是
 * runner 且没有 {@code @Order}（= {@code LOWEST_PRECEDENCE}），所以本类<b>先于</b>它执行。
 * 这样 {@code run-once=true} 模式下「跑一轮就 System.exit」之前，
 * {@code run-now} 仍有机会生成报表（这正是部署后验证要用的组合）。</p>
 */
@Component
@Order(1)
public class DailyReinvestReportScheduler implements ApplicationRunner {

    private final ReinvestReportProperties props;
    private final DailyReinvestReportService service;

    public DailyReinvestReportScheduler(ReinvestReportProperties props,
                                       DailyReinvestReportService service) {
        this.props = props;
        this.service = service;
    }

    /**
     * 每日定时生成「前一天」的报表。
     *
     * <p>cron 用占位符取配置，改时间只改 yml（要重启服务生效）。{@code enabled=false} 时直接返回，
     * 因为 {@code @Scheduled} 无法被配置关掉。</p>
     */
    @Scheduled(cron = "${suixintui.report.cron:0 1 0 * * ?}")
    public void scheduledReport() {
        if (!props.isEnabled()) {
            return;
        }
        generateSafely(LocalDate.now().minusDays(1), "定时");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            System.out.println("[日报] 已停用（suixintui.report.enabled=false），不生成也不补生成。");
            return;
        }

        if (props.isRunNow()) {
            LocalDate date = props.getRunNowDate() != null
                    ? props.getRunNowDate()
                    : LocalDate.now().minusDays(1);
            generateSafely(date, "run-now 手动触发");
            return;
        }

        if (!props.isCatchUpOnStart()) {
            return;
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Path expected = props.resolveOutputFile(yesterday);
        if (Files.exists(expected)) {
            System.out.println("[日报] 昨日（" + yesterday + "）报表已存在，跳过补生成：" + expected);
        } else {
            generateSafely(yesterday, "启动补生成");
        }
    }

    /** 生成一次并兜住所有异常——日报是旁路功能，不允许影响追投 */
    private void generateSafely(LocalDate date, String trigger) {
        System.out.println("[日报] 开始生成（" + trigger + "）：" + date);
        try {
            DailyReinvestReportService.Result result = service.generate(date);
            System.out.println("[日报] 完成：" + result.summarize());
        } catch (Exception e) {
            System.err.println("[日报] 生成失败（" + trigger + "，日期 " + date + "）：" + e);
        }
    }
}
