package com.example.oceanengine.launcher;

import com.example.oceanengine.scheduler.ReinvestRoundResult;
import com.example.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.example.oceanengine.scheduler.SuixintuiReinvestScheduler;
import com.example.oceanengine.storage.ReinvestRecordStore;
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
 *   <li>常驻模式（生产）：{@code java -jar oceanengine-demo-1.0-SNAPSHOT.jar}
 *       —— 启动后每 {@code suixintui.reinvest.interval-ms} 执行一轮；</li>
 *   <li>单轮模式（手动验证）：追加参数 {@code --suixintui.reinvest.run-once=true}，
 *       执行一轮后自动退出。</li>
 * </ul>
 *
 * <p>⚠️ 默认 dry-run=true 只演练不真实追投；确认无误后把配置里的
 * {@code dry-run} 改为 false 再部署。</p>
 */
@SpringBootApplication(scanBasePackages = "com.example.oceanengine")
@EnableScheduling
@EnableConfigurationProperties(SuixintuiReinvestProperties.class)
public class ReinvestApplication implements ApplicationRunner {

    private final SuixintuiReinvestProperties props;
    private final SuixintuiReinvestScheduler scheduler;
    private final ReinvestRecordStore store;

    public ReinvestApplication(SuixintuiReinvestProperties props,
                               SuixintuiReinvestScheduler scheduler,
                               ReinvestRecordStore store) {
        this.props = props;
        this.scheduler = scheduler;
        this.store = store;
    }

    public static void main(String[] args) {
        SpringApplication.run(ReinvestApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("===== 随心推追投服务启动 =====");
        System.out.println("dry-run=" + props.isDryRun()
                + ", 账户=" + props.getAdvertiserIds()
                + ", 每单 +" + props.getAmount() + " 元 / " + props.getDeliveryTime() + " 小时"
                + ", 每 " + (props.getIntervalMs() / 1000) + " 秒一轮"
                + ", 流水文件=" + store.getFilePath().toAbsolutePath());
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
