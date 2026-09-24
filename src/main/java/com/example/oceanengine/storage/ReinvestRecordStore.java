package com.example.oceanengine.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 追投幂等流水存储（JSONL 文件 + 内存索引）。
 *
 * <p>所有写操作 synchronized 串行化；记录量小（每天几十~几百条），每次变更直接全量重写文件，
 * 保证磁盘内容与内存状态严格一致，进程重启后可完整恢复。</p>
 *
 * <p>索引：</p>
 * <ul>
 *   <li>{@code byKey}：key = roundId + ":" + orderId → 记录，用于「同一轮次内同一订单只投一次」；</li>
 *   <li>{@code pendingOrUnknownByOrder}：orderId → 最近一条 PENDING / UNKNOWN 记录，
 *       用于「重启后校验上次结果未知的追加是否已生效」。</li>
 * </ul>
 */
public class ReinvestRecordStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RECORD_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path filePath;

    /** 全部记录（顺序 = 写入顺序） */
    private final List<ReinvestRecord> records = new ArrayList<>();

    /** 本轮去重索引：roundId:orderId → 记录 */
    private final Map<String, ReinvestRecord> byKey = new LinkedHashMap<>();

    /** 未决记录索引：orderId → 最近一条 PENDING/UNKNOWN */
    private final Map<Long, ReinvestRecord> pendingOrUnknownByOrder = new LinkedHashMap<>();

    public ReinvestRecordStore(Path filePath) throws IOException {
        this.filePath = filePath;
        load();
    }

    // ==================== 查询 ====================

    /** 同一轮次内该订单是否已处理（含 PENDING/DONE/FAILED/UNKNOWN 任意状态） */
    public synchronized boolean alreadyProcessed(String roundId, Long orderId) {
        return byKey.containsKey(key(roundId, orderId));
    }

    /** 最近一条状态未决（PENDING/UNKNOWN）的记录，供重启校验使用；无则返回 null */
    public synchronized ReinvestRecord findLatestPendingOrUnknown(Long orderId) {
        return pendingOrUnknownByOrder.get(orderId);
    }

    /** 当前全部记录（只读快照） */
    public synchronized List<ReinvestRecord> all() {
        return new ArrayList<>(records);
    }

    /** 指定日期内已占用的追投资金；PENDING/DONE/UNKNOWN 均按可能已支出计算。 */
    public synchronized long committedAmountOn(LocalDate date, Long advertiserId, Long orderId) {
        return records.stream()
                .filter(this::isCommitted)
                .filter(r -> isOnDate(r, date))
                .filter(r -> advertiserId == null || advertiserId.equals(r.getAdvertiserId()))
                .filter(r -> orderId == null || orderId.equals(r.getOrderId()))
                .map(ReinvestRecord::getAmount)
                .filter(amount -> amount != null && amount > 0)
                .mapToLong(Long::longValue)
                .sum();
    }

    /** 指定日期内某订单已占用的追投次数；UNKNOWN 也计入，避免结果未知时继续花费。 */
    public synchronized long committedCountOn(LocalDate date, Long orderId) {
        return records.stream()
                .filter(this::isCommitted)
                .filter(r -> isOnDate(r, date))
                .filter(r -> orderId != null && orderId.equals(r.getOrderId()))
                .count();
    }

    /** 某订单最近一次可能已支出的追投时间。 */
    public synchronized LocalDateTime latestCommittedAt(Long orderId) {
        LocalDateTime latest = null;
        for (ReinvestRecord record : records) {
            if (!isCommitted(record) || orderId == null || !orderId.equals(record.getOrderId())) {
                continue;
            }
            LocalDateTime timestamp = parseTimestamp(record);
            if (timestamp != null && (latest == null || timestamp.isAfter(latest))) {
                latest = timestamp;
            }
        }
        return latest;
    }

    public Path getFilePath() {
        return filePath;
    }

    // ==================== 写入 ====================

    /** 落盘一条 PENDING 记录（执行追投前调用） */
    public synchronized ReinvestRecord markPending(String roundId, Long advertiserId, Long orderId,
                                                   Long amount, Double deliveryTime,
                                                   Long preTotalBudget) throws IOException {
        ReinvestRecord record = new ReinvestRecord(roundId, advertiserId, orderId,
                amount, deliveryTime, preTotalBudget, ReinvestRecord.Status.PENDING, null);
        add(record);
        return record;
    }

    /** 更新某条记录的状态（DONE/FAILED/UNKNOWN），同步未决索引并原子落盘。 */
    public synchronized void mark(ReinvestRecord record, ReinvestRecord.Status status,
                                  String message) throws IOException {
        ReinvestRecord.Status previousStatus = record.getStatus();
        String previousMessage = record.getMessage();
        ReinvestRecord previousPending = pendingOrUnknownByOrder.get(record.getOrderId());

        record.setStatus(status);
        record.setMessage(message);
        updatePendingIndex(record);
        try {
            persist();
        } catch (IOException e) {
            record.setStatus(previousStatus);
            record.setMessage(previousMessage);
            if (previousPending == null) {
                pendingOrUnknownByOrder.remove(record.getOrderId());
            } else {
                pendingOrUnknownByOrder.put(record.getOrderId(), previousPending);
            }
            throw e;
        }
    }

    // ==================== 内部 ====================

    private static String key(String roundId, Long orderId) {
        return roundId + ":" + orderId;
    }

    private void add(ReinvestRecord record) throws IOException {
        records.add(record);
        byKey.put(key(record.getRoundId(), record.getOrderId()), record);
        updatePendingIndex(record);
        try {
            persist();
        } catch (IOException e) {
            records.remove(record);
            byKey.remove(key(record.getRoundId(), record.getOrderId()));
            if (pendingOrUnknownByOrder.get(record.getOrderId()) == record) {
                pendingOrUnknownByOrder.remove(record.getOrderId());
            }
            throw e;
        }
    }

    /** 先写同目录临时文件并强制落盘，再原子替换正式 JSONL，避免崩溃留下半份流水。 */
    private void persist() throws IOException {
        Path parent = filePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path target = filePath.toAbsolutePath();
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            List<String> lines = new ArrayList<>(records.size());
            for (ReinvestRecord r : records) {
                lines.add(MAPPER.writeValueAsString(r));
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

    private void updatePendingIndex(ReinvestRecord record) {
        ReinvestRecord.Status status = record.getStatus();
        if (status == ReinvestRecord.Status.PENDING || status == ReinvestRecord.Status.UNKNOWN) {
            pendingOrUnknownByOrder.put(record.getOrderId(), record);
            return;
        }
        if (pendingOrUnknownByOrder.get(record.getOrderId()) == record) {
            pendingOrUnknownByOrder.remove(record.getOrderId());
        }
    }

    private boolean isCommitted(ReinvestRecord record) {
        return record.getStatus() == ReinvestRecord.Status.PENDING
                || record.getStatus() == ReinvestRecord.Status.DONE
                || record.getStatus() == ReinvestRecord.Status.UNKNOWN;
    }

    private boolean isOnDate(ReinvestRecord record, LocalDate date) {
        LocalDateTime timestamp = parseTimestamp(record);
        return timestamp != null && timestamp.toLocalDate().equals(date);
    }

    private LocalDateTime parseTimestamp(ReinvestRecord record) {
        if (record.getTimestamp() == null || record.getTimestamp().isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(record.getTimestamp(), RECORD_TS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 启动加载：逐行解析，坏行跳过并计数 */
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
            try {
                ReinvestRecord r = MAPPER.readValue(line, ReinvestRecord.class);
                records.add(r);
                byKey.put(key(r.getRoundId(), r.getOrderId()), r);
                if (r.getStatus() == ReinvestRecord.Status.PENDING
                        || r.getStatus() == ReinvestRecord.Status.UNKNOWN) {
                    pendingOrUnknownByOrder.put(r.getOrderId(), r);
                } else {
                    pendingOrUnknownByOrder.remove(r.getOrderId());
                }
            } catch (IOException e) {
                throw new IOException("追投流水损坏，拒绝启动；行号=" + lineNumber, e);
            }
        }
    }
}
