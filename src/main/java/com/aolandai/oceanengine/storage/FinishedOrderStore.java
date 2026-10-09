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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「已结束达标订单」的<b>七天底表</b>（JSONL，每次全量覆盖）。
 *
 * <p>用途：官方接口只能按创建时间过滤、没有结束时间字段，所以「当天结束的订单」无法直接查。
 * 做法是每天拉一次「近 N 天 + 结束态 + ROI 达标」的全量，与这张底表对比，
 * <b>多出来的就是新结束的</b>，然后用本次全量覆盖底表。</p>
 *
 * <p>所以本存储的语义是「<b>整份替换</b>」而不是「合并追加」——
 * 这是它和 {@link ConsumeSnapshotStore}（按订单覆盖、保留窗口外的旧值）最大的区别。</p>
 *
 * <p>⚠️ <b>损坏策略：宽松</b>（跳过坏行并计数，不拒绝启动）。
 * 理由：底表丢了最多让日报 sheet 多列一批历史订单，<b>不涉及资金</b>；
 * 为它拒绝启动不划算。（对比：{@link ConsumeSnapshotStore} 丢了会让追投门槛从 80% 降到 40%，
 * 那个必须严格。）</p>
 */
public class FinishedOrderStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;

    /** orderId → 底表条目（顺序 = 上次写入顺序） */
    private final Map<Long, FinishedOrder> latest = new LinkedHashMap<>();

    /** 启动时文件是否已存在 —— 用来判断「是否首次运行」（首次只建底表、不输出新增） */
    private final boolean established;

    /** 加载时跳过的坏行数（用于日志提示） */
    private final int skippedBadLines;

    public FinishedOrderStore(Path filePath) throws IOException {
        this.filePath = filePath;
        this.established = Files.exists(filePath);
        this.skippedBadLines = load();
    }

    // ==================== 查询 ====================

    /** 底表是否已经建立过（首次运行时为 false → 只建底表、不输出「新增」） */
    public boolean isEstablished() {
        return established;
    }

    /** 加载时跳过的坏行数 */
    public int getSkippedBadLines() {
        return skippedBadLines;
    }

    /** 底表里全部订单 ID（对比「新增」用） */
    public synchronized Set<Long> orderIds() {
        return new LinkedHashSet<>(latest.keySet());
    }

    /** 按订单 ID 取底表条目；没有返回 null（排查用） */
    public synchronized FinishedOrder find(Long orderId) {
        return orderId == null ? null : latest.get(orderId);
    }

    /** 底表条目数 */
    public synchronized int size() {
        return latest.size();
    }

    /** 启动横幅用的一句话描述 */
    public synchronized String describe() {
        return (established ? "已建立" : "未建立") + " / " + latest.size() + " 单 / " + filePath
                + (skippedBadLines > 0 ? "（加载时跳过坏行 " + skippedBadLines + " 条）" : "");
    }

    // ==================== 写入 ====================

    /**
     * 用本次拉取到的全量<b>整份替换</b>底表并落盘。
     *
     * <p>⚠️ 调用方必须在「拉取成功」之后才调用本方法。拉取失败时调用会把底表清空，
     * 导致下一次把所有订单都当成「新增」。</p>
     */
    public synchronized void replaceAll(Collection<FinishedOrder> orders) throws IOException {
        latest.clear();
        if (orders != null) {
            for (FinishedOrder order : orders) {
                if (order == null || order.getOrderId() == null) {
                    continue;
                }
                latest.put(order.getOrderId(), order);
            }
        }
        persist();
    }

    // ==================== 内部 ====================

    /** 全量重写：写临时文件 → force → 原子替换（与其它存储同一套做法） */
    private void persist() throws IOException {
        Path parent = filePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path target = filePath.toAbsolutePath();
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            List<String> lines = new ArrayList<>(latest.size());
            for (FinishedOrder order : latest.values()) {
                lines.add(MAPPER.writeValueAsString(order));
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
     * 启动加载：逐行解析，<b>坏行跳过</b>（不拒绝启动）。
     *
     * @return 跳过的坏行数
     */
    private int load() throws IOException {
        if (!Files.exists(filePath)) {
            return 0;
        }
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        int skipped = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                FinishedOrder order = MAPPER.readValue(line, FinishedOrder.class);
                if (order.getOrderId() == null) {
                    skipped++;
                    continue;
                }
                latest.put(order.getOrderId(), order);
            } catch (IOException e) {
                skipped++;
            }
        }
        return skipped;
    }
}
