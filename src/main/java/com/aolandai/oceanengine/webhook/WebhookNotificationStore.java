package com.aolandai.oceanengine.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 已通知流水存储（JSONL，追加写 + 内存索引）。
 *
 * <p>这是「同一订单同一天只通知一次」的唯一依据，<b>必须落盘</b>：
 * 只放内存的话，服务一重启就会把当天所有已达标订单再发一遍
 * （本项目服务会随机器重启、也会因为改配置被手动重启）。</p>
 *
 * <p>同时它让「重启补发」天然成立 —— 每轮扫描都会拿当天用量去比对这份流水，
 * 停服期间漏发的会在下一轮补上。</p>
 *
 * <h3>⚠️ 坏行处理与 {@code ReinvestRecordStore} 刻意不同</h3>
 * <p>{@code ReinvestRecordStore} 遇到损坏行会<b>拒绝启动</b>，因为流水损坏可能导致重复扣费。
 * 这里相反：<b>跳过坏行并告警，不阻断启动</b>。原因是严重性不对等 ——
 * 通知流水损坏最多导致重复发一次 webhook（下游收到两次同样的通知），
 * 而拒绝启动会让整个追投服务停摆，那才是真正的损失。</p>
 */
public class WebhookNotificationStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path filePath;

    /** 全部流水行（顺序 = 写入顺序） */
    private final List<WebhookLedgerEntry> entries = new ArrayList<>();

    /** 已成功通知的键：date|orderId */
    private final Set<String> notifiedKeys = new LinkedHashSet<>();

    public WebhookNotificationStore(Path filePath) throws IOException {
        this.filePath = filePath;
        load();
    }

    // ==================== 查询 ====================

    /** 该订单当天是否已经<b>成功</b>通知过（失败的不算，下一轮会重试） */
    public synchronized boolean alreadyNotified(LocalDate date, Long orderId) {
        return date != null && orderId != null && notifiedKeys.contains(key(date, orderId));
    }

    /** 当天已成功通知的订单数，用于日志 */
    public synchronized long notifiedCountOn(LocalDate date) {
        if (date == null) {
            return 0L;
        }
        String prefix = date.format(DATE) + "|";
        return notifiedKeys.stream().filter(k -> k.startsWith(prefix)).count();
    }

    /** 当前全部流水行（只读快照） */
    public synchronized List<WebhookLedgerEntry> all() {
        return new ArrayList<>(entries);
    }

    public Path getFilePath() {
        return filePath;
    }

    // ==================== 写入 ====================

    /**
     * 追加一行流水。
     *
     * <p>追加写而非全量重写：这个文件只增不改，且每轮最多写几行，
     * 没必要像 {@code ReinvestRecordStore} 那样每次原子重写整个文件。</p>
     */
    public synchronized void append(WebhookLedgerEntry entry) throws IOException {
        Path absolute = filePath.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = MAPPER.writeValueAsString(entry);
        Files.write(absolute, List.of(line), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        entries.add(entry);
        index(entry);
    }

    // ==================== 内部 ====================

    private static String key(LocalDate date, Long orderId) {
        return date.format(DATE) + "|" + orderId;
    }

    private void index(WebhookLedgerEntry entry) {
        if (entry.isSuccess() && entry.getDate() != null && entry.getOrderId() != null) {
            notifiedKeys.add(entry.getDate() + "|" + entry.getOrderId());
        }
    }

    /**
     * 启动加载：逐行解析，坏行跳过并计数（见类注释里「与 ReinvestRecordStore 的差异」）。
     */
    private void load() throws IOException {
        if (!Files.exists(filePath)) {
            return;
        }
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        int lineNumber = 0;
        int broken = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                WebhookLedgerEntry entry = MAPPER.readValue(line, WebhookLedgerEntry.class);
                entries.add(entry);
                index(entry);
            } catch (IOException e) {
                broken++;
                System.out.println("[webhook] 通知流水第 " + lineNumber + " 行无法解析，已跳过："
                        + e.getMessage());
            }
        }
        if (broken > 0) {
            System.out.println("[webhook] 通知流水共 " + broken + " 行损坏，已跳过；"
                    + "受影响的订单可能被重复通知一次（不影响追投）。文件=" + filePath);
        }
    }
}
