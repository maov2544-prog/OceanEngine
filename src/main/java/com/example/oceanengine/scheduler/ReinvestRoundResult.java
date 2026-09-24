package com.example.oceanengine.scheduler;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 一轮追投的执行结果汇总（用于日志与健康检查展示）。
 */
public class ReinvestRoundResult {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 轮次 ID */
    private String roundId;

    private final LocalDateTime startTime = LocalDateTime.now();

    private final List<AccountResult> accountResults = new ArrayList<>();

    private String error;

    /** 设置轮次 ID */
    public void setRoundId(String roundId) {
        this.roundId = roundId;
    }

    /** 追加一个账户的执行结果 */
    public void addAccount(AccountResult r) {
        accountResults.add(r);
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getRoundId() {
        return roundId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public List<AccountResult> getAccountResults() {
        return accountResults;
    }

    public String getError() {
        return error;
    }

    /** 单账户一轮结果 */
    public static class AccountResult {
        public final Long advertiserId;
        public final int ordersTotal;      // 列表返回总数
        public final int candidates;       // 筛选出的追投候选
        public final int success;          // 追投成功
        public final int failed;           // 业务失败
        public final int unknown;          // 网络异常/超时（结果未知）
        public final int skipped;          // 幂等跳过（本轮已处理 / 重启校验已生效）
        public final int dryRun;           // 演练模式跳过
        public final long elapsedMs;

        public AccountResult(Long advertiserId, int ordersTotal, int candidates,
                             int success, int failed, int unknown,
                             int skipped, int dryRun, long elapsedMs) {
            this.advertiserId = advertiserId;
            this.ordersTotal = ordersTotal;
            this.candidates = candidates;
            this.success = success;
            this.failed = failed;
            this.unknown = unknown;
            this.skipped = skipped;
            this.dryRun = dryRun;
            this.elapsedMs = elapsedMs;
        }

        @Override
        public String toString() {
            return "账户 " + advertiserId
                    + ": 列表 " + ordersTotal + " 条, 候选 " + candidates + " 条"
                    + ", 成功 " + success + ", 失败 " + failed + ", 结果未知 " + unknown
                    + ", 幂等跳过 " + skipped + ", 演练跳过 " + dryRun
                    + ", 耗时 " + elapsedMs + "ms";
        }
    }

    /** 汇总行（用于日志与健康检查） */
    public String summarize() {
        int candidates = 0, success = 0, failed = 0, unknown = 0, skipped = 0, dryRun = 0;
        for (AccountResult r : accountResults) {
            candidates += r.candidates;
            success += r.success;
            failed += r.failed;
            unknown += r.unknown;
            skipped += r.skipped;
            dryRun += r.dryRun;
        }
        return "轮次 " + roundId
                + " @" + startTime.format(TS)
                + " 账户数 " + accountResults.size()
                + " | 候选 " + candidates
                + " 成功 " + success
                + " 失败 " + failed
                + " 未知 " + unknown
                + " 幂等跳过 " + skipped
                + (dryRun > 0 ? " 演练跳过 " + dryRun : "")
                + (error != null ? " | 错误: " + error : "");
    }
}
