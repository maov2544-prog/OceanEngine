package com.aolandai.oceanengine.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「每轮每单整体消耗」快照存储（JSONL + 内存索引）。
 *
 * <p>只服务一件事：给追投筛选的第二分支提供<b>上一轮整体消耗</b>（C_prev）。
 * 官方接口只返回当前累计消耗，所以这个值必须由本服务自己按轮次记录。</p>
 *
 * <p>写入模型：每个订单<b>只保留最新一条</b>（下一轮覆盖），每次写入全量重写文件。
 * 记录量 = 订单数（几十~几百条），全量重写的代价可忽略，换来「磁盘与内存严格一致」。</p>
 *
 * <p>⚠️ <b>损坏策略：拒绝启动</b>（与 {@link ReinvestRecordStore} 一致，
 * 与 {@link com.aolandai.oceanengine.webhook.WebhookNotificationStore} 相反）。
 * 理由：快照缺失会让 C_prev 退化为 0 → 第二分支门槛从 80% 降到 40% 左右 → <b>多花钱</b>；
 * 而「拒绝启动」只是停摆，不会造成资金损失。两者代价不对等。</p>
 */
public class ConsumeSnapshotStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path filePath;

    /** 保留天数：超过这个天数没更新的条目会被清理，避免文件无限增长 */
    private final int retentionDays;

    /** orderId → 最新一条快照（顺序 = 首次出现顺序，便于人工查看） */
    private final Map<Long, ConsumeSnapshot> latest = new LinkedHashMap<>();

    public ConsumeSnapshotStore(Path filePath, int retentionDays) throws IOException {
        this.filePath = filePath;
        this.retentionDays = retentionDays <= 0 ? 7 : retentionDays;
        load();
    }

    // ==================== 查询 ====================

    /**
     * 该订单<b>上一轮</b>的整体消耗；没有记录时返回 {@code null}
     * （调用方按「本轮增量 = 0」处理，退化为只判使用率 —— 见
     * {@link com.aolandai.oceanengine.domain.UsageRatioRule#boostedRatio}）。
     */
    public synchronized Double statCostOf(Long orderId) {
        if (orderId == null) {
            return null;
        }
        ConsumeSnapshot snapshot = latest.get(orderId);
        return snapshot == null ? null : snapshot.getStatCost();
    }

    /** 当前快照条数（= 有记录的订单数） */
    public synchronized int size() {
        return latest.size();
    }

    /** 启动横幅用的一句话描述 */
    public synchronized String describe() {
        return "快照 " + latest.size() + " 单 / 保留 " + retentionDays + " 天 / " + filePath;
    }

    // ==================== 写入 ====================

    /**
     * 用本轮采集到的快照更新存储并落盘。
     *
     * <p>语义是「合并覆盖」而不是「整份替换」：本轮没看到的订单（比如已滑出时间窗口）
     * 会保留旧值，直到超过 {@code retentionDays} 被清理。</p>
     *
     * <p>本轮快照里 {@code statCost} 为 {@code null} 的条目会被<b>跳过</b> ——
     * 保留上一轮的值比写入 null 更有用（写 null 等于把 C_prev 抹掉）。</p>
     */
    public synchronized void saveAll(Collection<ConsumeSnapshot> snapshots) throws IOException {
        if (snapshots != null) {
            for (ConsumeSnapshot snapshot : snapshots) {
                if (snapshot == null || snapshot.getOrderId() == null
                        || snapshot.getStatCost() == null) {
                    continue;
                }
                latest.put(snapshot.getOrderId(), snapshot);
            }
        }
        purgeExpired();
        persist();
    }

    // ==================== 内部 ====================

    /** 清理超过保留天数的条目；时间解析失败的一并清掉（脏数据不留在内存里） */
    private void purgeExpired() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minusDays(retentionDays);
        latest.entrySet().removeIf(entry -> {
            LocalDateTime ts = parse(entry.getValue().getTimestamp());
            return ts == null || ts.isBefore(deadline);
        });
    }

    private static LocalDateTime parse(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(timestamp, TS);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** 全量重写：写临时文件 → force → 原子替换，与 ReinvestRecordStore 同一套做法 */
    private void persist() throws IOException {
        Path parent = filePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path target = filePath.toAbsolutePath();
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            List<String> lines = new ArrayList<>(latest.size());
            for (ConsumeSnapshot snapshot : latest.values()) {
                lines.add(MAPPER.writeValueAsString(snapshot));
            }
            Files.write(temp, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 启动加载：逐行解析，坏行直接拒绝启动（见类注释的资金风险说明） */
    private void load() throws IOException {
        if (!Files.exists(filePath)) {
            return;
        }
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            ConsumeSnapshot snapshot;
            try {
                snapshot = MAPPER.readValue(line, ConsumeSnapshot.class);
            } catch (IOException e) {
                throw new IOException("消耗快照损坏，拒绝启动；行号=" + lineNumber
                        + "。修复或删除该文件后重启（删除会让第二分支退化，可能多花钱，请先确认）", e);
            }
            if (snapshot.getOrderId() == null) {
                throw new IOException("消耗快照损坏（orderId 为空），拒绝启动；行号=" + lineNumber);
            }
            // 同一订单多行时保留最后一行（正常不会出现，全量重写保证唯一）
            latest.put(snapshot.getOrderId(), snapshot);
        }
    }

    /** 采集一条快照的便捷工厂（统一时间戳格式） */
    public static ConsumeSnapshot snapshotOf(Long orderId, Long advertiserId,
                                             Double statCost, String roundId) {
        return new ConsumeSnapshot(orderId, advertiserId, statCost, roundId,
                LocalDateTime.now().format(TS));
    }
}
