package com.example.oceanengine.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 随心推追投定时调度入口。
 *
 * <p>使用 <b>fixedDelay</b>（上一轮结束后再等 N 毫秒）而非 fixedRate：
 * 天然保证轮次串行、不重叠——这是幂等的前提之一（避免两轮并发同时追投同一订单）。</p>
 *
 * <p>频率来自配置 {@code suixintui.reinvest.interval-ms}（默认 10 分钟），
 * 启动后 {@code initial-delay-ms}（默认 10 秒）执行第一轮。</p>
 */
@Component
public class SuixintuiReinvestScheduler {

    private final SuixintuiReinvestProperties props;
    private final ReinvestRound round;

    /** 最近一轮结果（供健康检查 / 手动触发后读取） */
    private volatile ReinvestRoundResult lastResult;

    public SuixintuiReinvestScheduler(SuixintuiReinvestProperties props, ReinvestRound round) {
        this.props = props;
        this.round = round;
    }

    @Scheduled(
            fixedDelayString = "${suixintui.reinvest.interval-ms:600000}",
            initialDelayString = "${suixintui.reinvest.initial-delay-ms:10000}")
    public void scheduledRound() {
        // 单轮模式（run-once）由 ApplicationRunner 手动执行，调度不重复触发
        if (props.isRunOnce()) {
            return;
        }
        runRoundNow();
    }

    /** 立即执行一轮（供调度、手动触发、单轮模式共用），返回并缓存结果 */
    public ReinvestRoundResult runRoundNow() {
        ReinvestRoundResult result;
        try (RoundLock ignored = RoundLock.acquire(props.getInstanceLockPath())) {
            if (ignored == null) {
                result = new ReinvestRoundResult();
                result.setError("已有其他实例持有轮次锁，本轮跳过");
                lastResult = result;
                return result;
            }
            result = round.execute();
        } catch (Exception e) {
            System.err.println("[轮次异常] " + e.getMessage());
            result = new ReinvestRoundResult();
            result.setError(e.getMessage());
        }
        lastResult = result;
        return result;
    }

    /** 最近一轮结果；尚未执行过任何轮次时为 null */
    public ReinvestRoundResult getLastResult() {
        return lastResult;
    }

    private static final class RoundLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private RoundLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static RoundLock acquire(Path path) throws IOException {
            Path absolute = path.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileChannel channel = FileChannel.open(absolute,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return null;
                }
                return new RoundLock(channel, lock);
            } catch (java.nio.channels.OverlappingFileLockException e) {
                channel.close();
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
