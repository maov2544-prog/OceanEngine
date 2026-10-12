package com.aolandai.oceanengine.launcher;

import com.aolandai.oceanengine.report.ReinvestReportProperties;
import com.aolandai.oceanengine.report.SuixintuiRebuildProperties;
import com.aolandai.oceanengine.scheduler.ReinvestRoundResult;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestScheduler;
import com.aolandai.oceanengine.storage.ConsumeSnapshotStore;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 随心推追投服务启动入口。
 *
 * <p>运行方式：</p>
 * <ul>
 *   <li>常驻模式（生产）：{@code java -jar oceanengine-1.0-SNAPSHOT.jar}
 *       —— 启动后每 {@code suixintui.reinvest.interval-ms} 执行一轮；</li>
 *   <li>单轮模式（手动验证）：追加参数 {@code --suixintui.reinvest.run-once=true}，
 *       执行一轮后自动退出。</li>
 * </ul>
 *
 * <p>⚠️ 默认 dry-run=true 只演练不真实追投；确认无误后把配置里的
 * {@code dry-run} 改为 false 再部署。</p>
 */
@SpringBootApplication(scanBasePackages = "com.aolandai.oceanengine")
@EnableScheduling
@EnableConfigurationProperties({SuixintuiReinvestProperties.class, ReinvestReportProperties.class,
        SuixintuiRebuildProperties.class})
public class ReinvestApplication implements ApplicationRunner {

    private final SuixintuiReinvestProperties props;
    private final ReinvestReportProperties reportProps;
    private final SuixintuiReinvestScheduler scheduler;
    private final ReinvestRecordStore store;
    private final ConsumeSnapshotStore consumeSnapshotStore;

    public ReinvestApplication(SuixintuiReinvestProperties props,
                               ReinvestReportProperties reportProps,
                               SuixintuiReinvestScheduler scheduler,
                               ReinvestRecordStore store,
                               ConsumeSnapshotStore consumeSnapshotStore) {
        this.props = props;
        this.reportProps = reportProps;
        this.scheduler = scheduler;
        this.store = store;
        this.consumeSnapshotStore = consumeSnapshotStore;
    }

    public static void main(String[] args) {
        SpringApplication.run(ReinvestApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("===== 随心推追投服务启动 =====");
        System.out.println("dry-run=" + props.isDryRun()
                + ", 账户 " + props.getAdvertiserIds().size() + " 个 / "
                + props.getAdvertiserTiers().size() + " 档");
        System.out.println("分档: " + props.describeTiers());
        System.out.println("资金上限: 单轮 " + props.getMaxAmountPerRound() + " 元（所有账户共享）"
                + ", 单订单/天 " + props.getMaxAmountPerOrderPerDay() + " 元"
                + ", 单账户/天 见上面的分档");
        System.out.println("每单 +" + props.getAmount() + " 元 / " + props.getDeliveryTime() + " 小时"
                + ", 每 " + (props.getIntervalMs() / 1000) + " 秒一轮"
                + ", 流水文件=" + store.getFilePath().toAbsolutePath());
        System.out.println("筛选: ROI≥" + props.getMinRoi() + "（" + props.getRoiMetric() + "）"
                + ", 整体消耗≥" + props.getMinStatCost() + " 元"
                + ", 使用率≥" + (props.getMinBudgetUsedRatio() * 100) + "%"
                + " 或 增强使用率(2×本轮-上轮)/投放总金额≥"
                + (props.getMinBudgetUsedRatio() * 100) + "%"
                + "（上轮消耗缺失按「本轮增量=0」处理，退化为只判使用率）");
        System.out.println("商品级 ROI 门槛: " + props.buildProductRoiRule().describe());
        System.out.println("第二条路径(ROAS): " + props.describeRoasRule());
        System.out.println("消耗快照: " + consumeSnapshotStore.describe());
        System.out.println("日报: " + reportProps.describe());
        System.out.println("webhook: " + props.describeWebhook());
        if (props.isDryRun()) {
            System.out.println("⚠️  当前为演练模式（dry-run=true），不会真实追投。");
        }

        if (props.isRunOnce()) {
            System.out.println("===== 单轮模式：执行一轮后退出 =====");
            ReinvestRoundResult result = scheduler.runRoundNow();
            System.out.println("轮次结束: " + result.summarize());
            System.exit(0);
        }
    }
}
