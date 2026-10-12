package com.aolandai.oceanengine.launcher;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.model.SuixintuiUniOrderListRequest;
import com.aolandai.oceanengine.report.ReinvestReportProperties;
import com.aolandai.oceanengine.report.SuixintuiRebuildProperties;
import com.aolandai.oceanengine.report.SuixintuiRebuildService;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderService;
import com.aolandai.oceanengine.storage.ConsumeSnapshotStore;
import com.aolandai.oceanengine.storage.FinishedOrderStore;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecordStore;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderCreateService;
import com.aolandai.oceanengine.webhook.WebhookNotificationStore;
import com.bytedance.ads.ApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;

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

    // ==================== 订单复建（2026-10 新增） ====================

    /**
     * 复建幂等流水（JSONL），启动时自动加载。
     *
     * <p>⚠️ 该文件损坏会<b>拒绝启动</b>（与追投流水一致）：流水丢了会让已复建过的订单被
     * <b>再复建一遍</b> —— 那是重复花钱。停摆的代价小于乱花钱的代价。</p>
     */
    @Bean
    public SuixintuiRebuildRecordStore suixintuiRebuildRecordStore(
            SuixintuiRebuildProperties rebuildProps) throws IOException {
        return new SuixintuiRebuildRecordStore(rebuildProps.getRecordStorePath());
    }

    /**
     * 随心推订单详情服务（一单一请求，含 100ms 节流 + 瞬时重试）。
     *
     * <p>复建需要从详情接口取 5 个建单参数（amount/deliveryTime/bidType/qcpxMode/roiGoal），
     * 故独立装配成 Bean 供 {@code SuixintuiRebuildService} 注入。</p>
     *
     * <p>⚠️ Access Token 在 Bean 创建时获取一次并复用。若 Token 失效会由接口层报错，
     * 本轮复建跳过该单、下次再取（Token 换取逻辑本身带重试，见 {@link SuixintuiTokenClient}）。</p>
     */
    @Bean
    public SuixintuiOrderDetailService suixintuiRebuildOrderDetailService(
            SuixintuiReinvestProperties props) throws Exception {
        String token = SuixintuiTokenClient.getAccessToken();
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(props.getApiBaseUrl());
        apiClient.addDefaultHeader("Access-Token", token);
        return new SuixintuiOrderDetailService(apiClient, token);
    }

    /**
     * 随心推建单服务（<b>花钱</b>，但默认 dry-run）。
     *
     * <p>dry-run 开关跟随 {@code suixintui.reinvest.dry-run}（在服务内部读取），
     * 这里只负责装配。</p>
     */
    @Bean
    public SuixintuiOrderCreateService suixintuiOrderCreateService(
            SuixintuiReinvestProperties props) {
        return new SuixintuiOrderCreateService(props);
    }

    /**
     * 「在投冲突预检」的订单列表拉取器 —— 供 {@code SuixintuiRebuildService} 注入。
     *
     * <p>每次调用拉<b>一个账户</b>近 {@code finished-orders-lookback-days} 天的全量订单
     * （cursor 翻页在 {@link SuixintuiOrderService} 内部完成）。</p>
     *
     * <p>⚠️ <b>一次调用 = 一个账户一次列表请求</b>（服务内部按账户分组后每账户只调一次），
     * 这是预检的性能红线：官方对「频繁轮询」极敏感，超限会 5 分钟全失败。</p>
     *
     * <p>⚠️ 复用 {@link SuixintuiOrderService} 的现成列表拉取，不另写一套，避免口径漂移。</p>
     *
     * <p>返回原始列表条目，由 {@code SuixintuiRebuildService} 自行判定在投组合
     * （状态不在终态集合 + productId/awemeId 双字段 AND）。</p>
     */
    @Bean
    public SuixintuiRebuildService.InFlightOrderFetcher suixintuiRebuildInFlightOrderFetcher(
            SuixintuiReinvestProperties props,
            ReinvestReportProperties reportProps) throws Exception {
        String token = SuixintuiTokenClient.getAccessToken();
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(props.getApiBaseUrl());
        apiClient.addDefaultHeader("Access-Token", token);
        SuixintuiOrderService orderService = new SuixintuiOrderService(apiClient, token);

        int lookback = Math.max(1, reportProps.getFinishedOrdersLookbackDays());
        return advertiserId -> {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(lookback - 1L);
            SuixintuiUniOrderListRequest request = new SuixintuiUniOrderListRequest(advertiserId)
                    .orderCreateStartDate(startDate.toString())
                    .orderCreateEndDate(endDate.toString());
            return orderService.fetchUniPromotionOrders(request);
        };
    }
}
