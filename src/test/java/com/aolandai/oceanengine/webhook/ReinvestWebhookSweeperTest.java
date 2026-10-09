package com.aolandai.oceanengine.webhook;

import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.storage.ReinvestRecord;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阈值扫描器测试。
 *
 * <p>覆盖三类容易出错的点：① 计数口径必须与资金护栏一致；② 同一订单同一天不能重复发；
 * ③ 重启后既不重复发、也要补发漏发的。</p>
 */
public class ReinvestWebhookSweeperTest {

    @TempDir
    Path tempDir;

    private static final long ORDER = 7_690_526_648_109_645_860L;
    private static final long OTHER_ORDER = 7_690_526_648_109_645_861L;
    private static final long ADV = 1_823_379_540_880_396L;

    /** 记录下所有发出去的通知，便于断言 */
    private final List<WebhookNotification> sent = new ArrayList<>();

    // ==================== 阈值判定 ====================

    @Test
    void sweep_whenBelowBothThresholds_sendsNothing() throws Exception {
        SweeperFixture f = fixture(5, 1000L, false, 4, 10L, ReinvestRecord.Status.DONE);

        ReinvestWebhookSweeper.SweepResult result = f.sweeper.sweep();

        assertThat(sent).isEmpty();
        assertThat(result.sent()).isZero();
        assertThat(result.matchedOrders()).isZero();
    }

    @Test
    void sweep_whenOnlyCountReached_reportsCount() throws Exception {
        // 5 次 × 10 元 = 50 元，只有次数达标
        SweeperFixture f = fixture(5, 1000L, false, 5, 10L, ReinvestRecord.Status.DONE);

        f.sweeper.sweep();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getReason()).isEqualTo(WebhookNotification.Reason.COUNT);
        assertThat(sent.get(0).getReinvestCount()).isEqualTo(5L);
        assertThat(sent.get(0).getReinvestAmount()).isEqualTo(50L);
    }

    @Test
    void sweep_whenOnlyAmountReached_reportsAmount() throws Exception {
        // 4 次 × 300 元 = 1200 元 ≥ 1000，但次数 4 < 5
        SweeperFixture f = fixture(5, 1000L, false, 4, 300L, ReinvestRecord.Status.DONE);

        f.sweeper.sweep();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getReason()).isEqualTo(WebhookNotification.Reason.AMOUNT);
    }

    @Test
    void sweep_whenBothReached_reportsCountAndAmount() throws Exception {
        // 当前生产参数（amount=200、阈值 5 次/1000 元）正好落在这个分支
        SweeperFixture f = fixture(5, 1000L, false, 5, 200L, ReinvestRecord.Status.DONE);

        f.sweeper.sweep();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getReason())
                .isEqualTo(WebhookNotification.Reason.COUNT_AND_AMOUNT);
        assertThat(sent.get(0).getReinvestAmount()).isEqualTo(1000L);
    }

    // ==================== 计数口径 ====================

    @Test
    void sweep_ignoresFailedRecords_likeTheSafetyGuard() throws Exception {
        // 6 条记录里 2 条 FAILED → 已占用只有 4 次，不该触发
        SweeperFixture f = fixture(5, 1000L, false, 0, 10L, ReinvestRecord.Status.DONE);
        f.add(4, 10L, ReinvestRecord.Status.DONE);
        f.add(2, 10L, ReinvestRecord.Status.FAILED);

        ReinvestWebhookSweeper.SweepResult result = f.sweeper.sweep();

        assertThat(sent).isEmpty();
        assertThat(result.scannedOrders()).isEqualTo(1);   // 订单本身在用量表里
        assertThat(result.matchedOrders()).isZero();
    }

    @Test
    void sweep_countsPendingAndUnknown() throws Exception {
        // PENDING / UNKNOWN 与资金护栏一样按「可能已支出」计入
        SweeperFixture f = fixture(5, 1000L, false, 0, 10L, ReinvestRecord.Status.DONE);
        f.add(3, 10L, ReinvestRecord.Status.PENDING);
        f.add(2, 10L, ReinvestRecord.Status.UNKNOWN);

        f.sweeper.sweep();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getReinvestCount()).isEqualTo(5L);
    }

    // ==================== 幂等 / 补发 ====================

    @Test
    void sweep_doesNotSendTwiceForSameOrderSameDay() throws Exception {
        SweeperFixture f = fixture(5, 1000L, false, 5, 200L, ReinvestRecord.Status.DONE);

        f.sweeper.sweep();
        f.sweeper.sweep();
        f.sweeper.sweep();

        assertThat(sent).as("同一订单同一天只发一次").hasSize(1);
    }

    @Test
    void sweep_retriesWhenSendFails() throws Exception {
        SuixintuiReinvestProperties props = props(5, 1000L, false);
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        addRecords(store, 5, 200L, ReinvestRecord.Status.DONE);
        WebhookNotificationStore ledger =
                new WebhookNotificationStore(tempDir.resolve("webhook.jsonl"));

        boolean[] firstAttempt = {true};
        WebhookSender flaky = n -> {
            sent.add(n);
            if (firstAttempt[0]) {
                firstAttempt[0] = false;
                return WebhookSender.Result.fail(500, "boom");
            }
            return WebhookSender.Result.ok(200);
        };

        ReinvestWebhookSweeper sweeper = new ReinvestWebhookSweeper(props, store, ledger, flaky);

        ReinvestWebhookSweeper.SweepResult first = sweeper.sweep();
        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.sent()).isZero();

        // 失败的不算「已通知」，下一轮必须重试
        ReinvestWebhookSweeper.SweepResult second = sweeper.sweep();
        assertThat(second.sent()).isEqualTo(1);
        assertThat(sent).hasSize(2);
    }

    @Test
    void sweep_afterRestart_doesNotResendAlreadyNotifiedOrder() throws Exception {
        Path recordsPath = tempDir.resolve("records.jsonl");
        Path ledgerPath = tempDir.resolve("webhook.jsonl");

        SuixintuiReinvestProperties props = props(5, 1000L, false);

        // 第一次运行：发出并落盘
        ReinvestRecordStore store1 = new ReinvestRecordStore(recordsPath);
        addRecords(store1, 5, 200L, ReinvestRecord.Status.DONE);
        new ReinvestWebhookSweeper(props, store1,
                new WebhookNotificationStore(ledgerPath),
                n -> {
                    sent.add(n);
                    return WebhookSender.Result.ok(200);
                }).sweep();
        assertThat(sent).hasSize(1);

        // 模拟重启：全新实例，从磁盘加载流水
        ReinvestRecordStore store2 = new ReinvestRecordStore(recordsPath);
        WebhookNotificationStore ledger2 = new WebhookNotificationStore(ledgerPath);
        ReinvestWebhookSweeper restarted = new ReinvestWebhookSweeper(props, store2, ledger2,
                n -> {
                    sent.add(n);
                    return WebhookSender.Result.ok(200);
                });

        restarted.sweep();

        assertThat(sent).as("重启后不能把当天已通知的订单再发一遍").hasSize(1);
        assertThat(ledger2.alreadyNotified(java.time.LocalDate.now(), ORDER)).isTrue();
    }

    @Test
    void sweep_afterRestart_backfillsOrderMissedWhileDown() throws Exception {
        Path recordsPath = tempDir.resolve("records.jsonl");
        Path ledgerPath = tempDir.resolve("webhook.jsonl");

        SuixintuiReinvestProperties props = props(5, 1000L, false);
        ReinvestRecordStore store = new ReinvestRecordStore(recordsPath);
        addRecords(store, 5, 200L, ReinvestRecord.Status.DONE);

        // 流水里只有「另一个订单」的通知记录 → 本订单属于停服期间漏发的，应补发
        WebhookNotificationStore ledger = new WebhookNotificationStore(ledgerPath);
        ledger.append(successEntry(OTHER_ORDER));

        new ReinvestWebhookSweeper(props, store, ledger, n -> {
            sent.add(n);
            return WebhookSender.Result.ok(200);
        }).sweep();

        assertThat(sent).as("停服期间漏发的要在下一轮补上").hasSize(1);
        assertThat(sent.get(0).getOrderId()).isEqualTo(ORDER);
    }

    // ==================== 开关 ====================

    @Test
    void sweep_skipsWhenDryRun() throws Exception {
        SweeperFixture f = fixture(5, 1000L, true, 5, 200L, ReinvestRecord.Status.DONE);

        ReinvestWebhookSweeper.SweepResult result = f.sweeper.sweep();

        assertThat(sent).as("dry-run 期间不触发下游程序").isEmpty();
        assertThat(result).isEqualTo(ReinvestWebhookSweeper.SweepResult.NONE);
    }

    @Test
    void sweep_skipsWhenDisabled() throws Exception {
        SuixintuiReinvestProperties props = props(5, 1000L, false);
        props.getWebhook().setEnabled(false);
        ReinvestRecordStore store = new ReinvestRecordStore(tempDir.resolve("records.jsonl"));
        addRecords(store, 5, 200L, ReinvestRecord.Status.DONE);

        ReinvestWebhookSweeper sweeper = new ReinvestWebhookSweeper(props, store,
                new WebhookNotificationStore(tempDir.resolve("webhook.jsonl")),
                n -> {
                    sent.add(n);
                    return WebhookSender.Result.ok(200);
                });

        assertThat(sweeper.sweep()).isEqualTo(ReinvestWebhookSweeper.SweepResult.NONE);
        assertThat(sent).isEmpty();
    }

    // ==================== 夹具 ====================

    private record SweeperFixture(ReinvestWebhookSweeper sweeper, ReinvestRecordStore store,
                                  WebhookNotificationStore ledger) {

        /** 追加记录，roundId 自动递增以免撞上同一轮的幂等键 */
        void add(int count, long amountEach, ReinvestRecord.Status status) throws Exception {
            addRecords(store, count, amountEach, status);
        }
    }

    private SweeperFixture fixture(int countThreshold, long amountThreshold, boolean dryRun,
                                   int records, long amountEach, ReinvestRecord.Status status)
            throws Exception {
        SuixintuiReinvestProperties props = props(countThreshold, amountThreshold, dryRun);
        ReinvestRecordStore store = new ReinvestRecordStore(
                tempDir.resolve("records-" + System.nanoTime() + ".jsonl"));
        if (records > 0) {
            addRecords(store, records, amountEach, status);
        }
        WebhookNotificationStore ledger = new WebhookNotificationStore(
                tempDir.resolve("webhook-" + System.nanoTime() + ".jsonl"));
        ReinvestWebhookSweeper sweeper = new ReinvestWebhookSweeper(props, store, ledger, n -> {
            sent.add(n);
            return WebhookSender.Result.ok(200);
        });
        return new SweeperFixture(sweeper, store, ledger);
    }

    private SuixintuiReinvestProperties props(int countThreshold, long amountThreshold, boolean dryRun) {
        SuixintuiReinvestProperties p = new SuixintuiReinvestProperties();
        p.setDryRun(dryRun);
        SuixintuiReinvestProperties.Webhook w = new SuixintuiReinvestProperties.Webhook();
        w.setEnabled(true);
        w.setUrl("https://example.invalid/hook");
        w.setCountThreshold(countThreshold);
        w.setAmountThreshold(amountThreshold);
        w.setNotifyLogPath(tempDir.resolve("webhook.jsonl"));
        p.setWebhook(w);
        return p;
    }

    /** 直接往流水里塞记录：先落 PENDING 再改成目标状态（PENDING 时不用改） */
    private static void addRecords(ReinvestRecordStore store, int count, long amountEach,
                                   ReinvestRecord.Status status) throws Exception {
        for (int i = 0; i < count; i++) {
            ReinvestRecord r = store.markPending("round-" + System.nanoTime() + "-" + i,
                    ADV, ORDER, amountEach, 24.0, 100L, "测试抖音名", "MS4wLjABAAAA");
            if (status != ReinvestRecord.Status.PENDING) {
                store.mark(r, status, "test");
            }
        }
    }

    private static WebhookLedgerEntry successEntry(long orderId) {
        WebhookLedgerEntry e = new WebhookLedgerEntry();
        e.setDate(java.time.LocalDate.now().toString());
        e.setOrderId(orderId);
        e.setReason("COUNT_AND_AMOUNT");
        e.setReinvestCount(5);
        e.setReinvestAmount(1000);
        e.setSentAt(java.time.OffsetDateTime.now().toString());
        e.setSuccess(true);
        e.setHttpStatus(200);
        e.setDetail("OK");
        return e;
    }
}
