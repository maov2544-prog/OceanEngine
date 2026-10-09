package com.aolandai.oceanengine.webhook;

import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

/**
 * 追投阈值扫描器：每轮结束后检查「当日」用量，对达到阈值的订单发送 webhook。
 *
 * <p>为什么做成「每轮统一扫描」而不是挂在单笔追投成功之后：</p>
 * <ol>
 *   <li><b>重启补发天然成立</b> —— 停服期间漏发的，下一轮扫描会补上；</li>
 *   <li><b>限流中止整轮时也能发</b> —— 已经投出去的那些订单照样要通知；</li>
 *   <li>逻辑只有一处，不必在追投路径里插入分支。</li>
 * </ol>
 *
 * <p>幂等由 {@link WebhookNotificationStore} 保证：已成功通知过的订单不会再发。</p>
 */
@Component
public class ReinvestWebhookSweeper {

    /** 一次扫描的结果，用于日志与测试断言 */
    public record SweepResult(int scannedOrders, int matchedOrders, int sent, int failed,
                              int skippedAlreadyNotified) {

        public static final SweepResult NONE = new SweepResult(0, 0, 0, 0, 0);

        public String summarize() {
            return "webhook 扫描 | 当日订单 " + scannedOrders + " 个 | 达标 " + matchedOrders
                    + " 个 | 已发 " + sent + " | 失败 " + failed
                    + " | 已通知过跳过 " + skippedAlreadyNotified;
        }
    }

    private final SuixintuiReinvestProperties props;
    private final ReinvestRecordStore store;
    private final WebhookNotificationStore ledger;
    private final WebhookSender sender;

    public ReinvestWebhookSweeper(SuixintuiReinvestProperties props,
                                  ReinvestRecordStore store,
                                  WebhookNotificationStore ledger,
                                  WebhookSender sender) {
        this.props = props;
        this.store = store;
        this.ledger = ledger;
        this.sender = sender;
    }

    /** 扫描今天 */
    public SweepResult sweep() {
        return sweep(LocalDate.now());
    }

    /**
     * 扫描指定日期。
     *
     * <p>不抛异常是刻意的：调用方是轮次收尾逻辑，通知出问题不能影响追投。</p>
     */
    public SweepResult sweep(LocalDate date) {
        SuixintuiReinvestProperties.Webhook cfg = props.getWebhook();
        if (cfg == null || !cfg.isEnabled() || date == null) {
            return SweepResult.NONE;
        }
        if (props.isDryRun()) {
            // 演练模式不触发下游程序。
            // dry-run 下 reinvestOne 根本不写流水，正常情况下这里本来就是空的；
            // 再挡一道是为了防「先真实跑过、之后又切回 dry-run」时把历史用量误发出去。
            return SweepResult.NONE;
        }

        Map<Long, ReinvestRecordStore.OrderUsage> usage = store.committedUsageOn(date);
        int matched = 0;
        int sent = 0;
        int failed = 0;
        int skipped = 0;

        for (ReinvestRecordStore.OrderUsage u : usage.values()) {
            WebhookNotification.Reason reason = reasonOf(u, cfg);
            if (reason == null) {
                continue;
            }
            matched++;
            if (ledger.alreadyNotified(date, u.orderId())) {
                skipped++;
                continue;
            }

            WebhookNotification notification = WebhookNotification.of(
                    date, u.orderId(), u.advertiserId(), u.awemeName(), u.awemeShowId(),
                    u.count(), u.amount(), reason, props.isDryRun());
            WebhookSender.Result result = sender.send(notification);

            try {
                ledger.append(WebhookLedgerEntry.of(notification, result));
            } catch (IOException e) {
                // 流水写不进去不能把已经发出去的通知吞掉：只告警。
                // 代价是该订单下一轮可能被重复通知一次（宁可重复，不可漏发）。
                System.out.println("[webhook] 通知流水写入失败（本次已发送，下轮可能重复通知）: "
                        + e.getMessage());
            }

            if (result.success()) {
                sent++;
                System.out.printf("  [webhook] 已通知 order_id=%s 当日 %d 次 / %d 元（%s）→ HTTP %d%n",
                        u.orderId(), u.count(), u.amount(), reason, result.httpStatus());
            } else {
                failed++;
                System.out.printf("  [webhook] 通知失败 order_id=%s %s（下一轮自动重试）%n",
                        u.orderId(), result.detail());
            }
        }

        SweepResult sweepResult = new SweepResult(usage.size(), matched, sent, failed, skipped);
        if (sent > 0 || failed > 0) {
            System.out.println("  " + sweepResult.summarize());
        }
        return sweepResult;
    }

    /**
     * 判定触发原因；未达阈值返回 {@code null}。
     *
     * <p>口径：次数或金额<b>任一达到</b>即触发（含等于）。</p>
     */
    private static WebhookNotification.Reason reasonOf(ReinvestRecordStore.OrderUsage u,
                                                       SuixintuiReinvestProperties.Webhook cfg) {
        boolean countHit = u.count() >= cfg.getCountThreshold();
        boolean amountHit = u.amount() >= cfg.getAmountThreshold();
        if (countHit && amountHit) {
            return WebhookNotification.Reason.COUNT_AND_AMOUNT;
        }
        if (countHit) {
            return WebhookNotification.Reason.COUNT;
        }
        if (amountHit) {
            return WebhookNotification.Reason.AMOUNT;
        }
        return null;
    }
}
