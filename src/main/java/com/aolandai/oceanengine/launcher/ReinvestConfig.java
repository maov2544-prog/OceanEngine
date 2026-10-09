package com.aolandai.oceanengine.launcher;

import com.aolandai.oceanengine.report.ReinvestReportProperties;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.storage.ConsumeSnapshotStore;
import com.aolandai.oceanengine.storage.FinishedOrderStore;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import com.aolandai.oceanengine.webhook.WebhookNotificationStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 追投服务 Bean 装配（把有构造依赖的存储组件独立配置，避免与启动类形成循环依赖）。
 */
@Configuration
public class ReinvestConfig {

    /** 幂等流水存储（JSONL 文件），启动时自动加载历史记录 */
    @Bean
    public ReinvestRecordStore reinvestRecordStore(SuixintuiReinvestProperties props) throws IOException {
        return new ReinvestRecordStore(props.getRecordStorePath());
    }

    /**
     * 「每轮每单整体消耗」快照存储（JSONL），启动时自动加载。
     *
     * <p>追投筛选的分支②要算 {@code (2×本轮消耗 − 上一轮消耗)/投放总金额}，
     * 而官方接口只返回当前累计消耗 —— 上一轮的值只能由本服务自己按轮次记录。</p>
     *
     * <p>⚠️ 该文件损坏会<b>拒绝启动</b>（与流水一致）：快照丢失会让 C_prev 退化为兜底值
     * （默认 0）→ 分支②门槛从 80% 降到 40% → 可能多花钱。停摆的代价小于乱花钱的代价。</p>
     */
    @Bean
    public ConsumeSnapshotStore consumeSnapshotStore(SuixintuiReinvestProperties props)
            throws IOException {
        return new ConsumeSnapshotStore(props.getConsumeSnapshotPath(),
                props.getSnapshotRetentionDays());
    }

    /**
     * 「已结束达标订单」的七天底表（JSONL，覆盖式），启动时自动加载。
     *
     * <p>用途：官方接口只能按创建时间过滤、没有结束时间字段，所以「当天结束的订单」无法直接查。
     * 每天拉一次「近 N 天 + 结束态 + ROI 达标」的全量，与这张底表对比取「新增」，
     * 再用本次全量覆盖它。</p>
     *
     * <p>⚠️ 该文件损坏<b>不会</b>拒绝启动（跳过坏行即可）：底表丢了最多让日报 sheet
     * 多列一批历史订单，不涉及资金。</p>
     */
    @Bean
    public FinishedOrderStore finishedOrderStore(ReinvestReportProperties reportProps)
            throws IOException {
        return new FinishedOrderStore(reportProps.getFinishedOrdersStorePath());
    }

    /**
     * webhook 已通知流水（JSONL），启动时自动加载。
     *
     * <p>即使 webhook 未启用也会建这个 Bean —— 这样配置改动不需要重启就能被读到，
     * 且启动日志能报出「历史已通知多少条」。</p>
     */
    @Bean
    public WebhookNotificationStore webhookNotificationStore(SuixintuiReinvestProperties props)
            throws IOException {
        return new WebhookNotificationStore(props.getWebhook().getNotifyLogPath());
    }
}
