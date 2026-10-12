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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「随心推订单复建」幂等流水存储（JSONL 文件 + 内存索引）。
 *
 * <p>用途：保证<b>一个原订单最多真复建一次</b>。复建是<b>花钱</b>操作（会新建出真实投放订单），
 * 重复复建的代价是双份预算，所以本存储与 {@link ReinvestRecordStore} 同样采取
 * <b>严格损坏策略</b>：文件损坏时<b>拒绝启动</b>（宁可停摆，不可重花钱）。</p>
 *
 * <h2>⚠️ 幂等判定认「已处理」：真建成功 + 在投冲突跳过</h2>
 * <p>{@link #isRebuilt(Long)} 看流水中是否存在<b>已处理</b>（{@link SuixintuiRebuildRecord#isSettled()}）的记录：
 * 真建成功（{@code newOrderId != null && !dryRun}）或在投冲突跳过（{@code SKIPPED_INFLIGHT}）。
 * dry-run 记录（{@code dryRun=true}）只作审计，<b>不阻挡</b>后续真复建 —— 详见
 * {@link SuixintuiRebuildRecord} 的类注释。</p>
 * <p>⚠️ 但「每日上限」计数（{@link #effectiveCountOn(LocalDate)}）<b>只算真建成功</b>：
 * 在投冲突跳过没真建单、没花钱，不占额度。</p>
 *
 * <h2>追加式</h2>
 * <p>与 {@link ReinvestRecordStore} 一致：只追加、不修改，
 * 每次追加后全量重写文件（记录量小，每天最多几十条），先写临时文件再原子替换，避免崩溃留下半份流水。
 * 写入失败会回滚内存变更，保持内存与磁盘一致。</p>
 */
public class SuixintuiRebuildRecordStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;

    /** 全部记录（顺序 = 写入顺序） */
    private final List<SuixintuiRebuildRecord> records = new ArrayList<>();

    /** sourceOrderId → 已处理的记录（isSettled()：真建成功 或 在投冲突跳过），用于幂等判定 */
    private final Map<Long, SuixintuiRebuildRecord> effectiveBySource = new LinkedHashMap<>();

    public SuixintuiRebuildRecordStore(Path filePath) throws IOException {
        this.filePath = filePath;
        load();
    }

    // ==================== 查询 ====================

    /**
     * 该原订单是否<b>已经处理过</b>（幂等判定的唯一入口）。
     *
     * <p>「已处理」包括两类记录：① 真建成功；② 在投冲突跳过（老板口径：永久标记，不再重试）。
     * ⚠️ dry-run 记录不算数 —— 见类注释。{@code sourceOrderId} 为 null 时返回 false。</p>
     */
    public synchronized boolean isRebuilt(Long sourceOrderId) {
        return sourceOrderId != null && effectiveBySource.containsKey(sourceOrderId);
    }

    /** 该原订单已处理的那条记录；没有则返回 null */
    public synchronized SuixintuiRebuildRecord findEffective(Long sourceOrderId) {
        return sourceOrderId == null ? null : effectiveBySource.get(sourceOrderId);
    }

    /** 当前全部记录（只读快照） */
    public synchronized List<SuixintuiRebuildRecord> all() {
        return new ArrayList<>(records);
    }

    /**
     * 指定日期内<b>真建成功</b>的复建条数（用于「每日上限」计数）。
     *
     * <p>⚠️ 只算真建成功（{@code !dryRun && newOrderId != null}）。<b>在投冲突跳过不算</b> ——
     * 它没真建单、没花钱，不该占用每日额度（详见 {@link SuixintuiRebuildRecord#countsTowardDailyLimit()}）。</p>
     */
    public synchronized long effectiveCountOn(LocalDate date) {
        return records.stream()
                .filter(SuixintuiRebuildRecord::countsTowardDailyLimit)
                .filter(r -> {
                    LocalDateTime ts = r.parsedRebuiltAt();
                    return ts != null && ts.toLocalDate().equals(date);
                })
                .count();
    }

    /** 记录总数（审计用） */
    public synchronized int size() {
        return records.size();
    }

    public Path getFilePath() {
        return filePath;
    }

    /** 启动横幅用的一句话描述 */
    public synchronized String describe() {
        return records.size() + " 条 / 已处理 " + effectiveBySource.size() + " 单 / " + filePath;
    }

    // ==================== 写入 ====================

    /**
     * 追加一条复建记录并原子落盘。
     *
     * <p>「已处理」的记录（{@link SuixintuiRebuildRecord#isSettled()}：真建成功 或 在投冲突跳过）
     * 会进入幂等索引；dry-run 记录只留档。写盘失败会回滚内存变更并抛出。</p>
     */
    public synchronized SuixintuiRebuildRecord append(SuixintuiRebuildRecord record)
            throws IOException {
        if (record == null || record.getSourceOrderId() == null) {
            throw new IllegalArgumentException("复建流水记录缺少 sourceOrderId，拒绝写入");
        }
        records.add(record);
        boolean indexed = false;
        if (record.isSettled()) {
            effectiveBySource.put(record.getSourceOrderId(), record);
            indexed = true;
        }
        try {
            persist();
        } catch (IOException e) {
            records.remove(record);
            if (indexed) {
                effectiveBySource.remove(record.getSourceOrderId());
            }
            throw e;
        }
        return record;
    }

    // ==================== 内部 ====================

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
            for (SuixintuiRebuildRecord r : records) {
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

    /**
     * 启动加载：逐行解析，<b>坏行拒绝启动</b>（与 {@link ReinvestRecordStore} 同策略）。
     *
     * <p>理由：流水丢了会让已经真复建过的订单被再次复建 —— 那是<b>重复花钱</b>。
     * 停摆的代价小于乱花钱的代价，所以这里不宽容。</p>
     */
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
                SuixintuiRebuildRecord r = MAPPER.readValue(line, SuixintuiRebuildRecord.class);
                records.add(r);
                if (r.isSettled()) {
                    effectiveBySource.put(r.getSourceOrderId(), r);
                }
            } catch (IOException e) {
                throw new IOException("复建流水损坏，拒绝启动；行号=" + lineNumber, e);
            }
        }
    }
}
