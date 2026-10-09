package com.aolandai.oceanengine.report;

import com.aolandai.oceanengine.storage.FinishedOrder;
import com.aolandai.oceanengine.storage.FinishedOrderStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「已结束且 ROI 达标」订单的<b>增量跟踪器</b> —— 供日报的「已结束达标订单」sheet 使用。
 *
 * <p><b>为什么需要底表（增量对比）：</b>官方列表接口只能按<b>创建时间</b>过滤，
 * 返回项里<b>没有「结束时间」字段</b>，所以「当天结束的订单」无法直接查询。
 * 采用老板 2026-09-30 定的做法：</p>
 *
 * <ol>
 *   <li>每天日报生成前，拉一次「近 N 天创建 + 状态 ∈ 结束态 + ROI ≥ 阈值」的全量
 *       （拉取逻辑在 {@link FinishedOrderFetcher}，与提醒功能共用）；</li>
 *   <li>与上次的<b>七天底表</b>对比，<b>多出来的就是新结束的</b> → 写进日报 sheet；</li>
 *   <li>用本次全量<b>覆盖</b>底表。</li>
 * </ol>
 *
 * <p>⚠️ <b>拉取失败时绝不覆盖底表</b> —— 否则下次会把全部订单都当成「新增」。</p>
 *
 * <p>⚠️ <b>首次运行</b>（底表不存在）只建底表、不输出「新增」：因为日报的语义是「每日新增」，
 * 把近 N 天的历史订单一股脑塞进当天的 sheet 会误导。日志里会明确说明。</p>
 *
 * <p>⚠️ 本类<b>只给日报用</b>。提醒功能走 {@link FinishedOrderFetcher} 直接拉取，
 * <b>不要复用本类</b> —— 它会覆盖底表，会打乱日报的 diff 基线。</p>
 */
@Component
public class FinishedOrderTracker {

    private final ReinvestReportProperties reportProps;
    private final FinishedOrderFetcher fetcher;
    private final FinishedOrderStore store;

    public FinishedOrderTracker(ReinvestReportProperties reportProps,
                               FinishedOrderFetcher fetcher,
                               FinishedOrderStore store) {
        this.reportProps = reportProps;
        this.fetcher = fetcher;
        this.store = store;
    }

    /**
     * 拉取 → 与底表对比 → 覆盖底表 → 返回「新结束」的订单。
     *
     * <p>任何失败都只打日志并返回空列表，<b>绝不向外抛</b> —— 日报是旁路功能，
     * 不允许因为查接口失败而影响主报表的生成。</p>
     *
     * @param reportDate 报表归属日期（通常是「昨天」），近 N 天窗口以它为终点
     * @return 新结束且 ROI 达标的订单；首次运行 / 功能停用 / 拉取失败时为空列表
     */
    public List<FinishedOrder> collectNewlyFinished(LocalDate reportDate) {
        if (!reportProps.isFinishedOrdersEnabled()) {
            return List.of();
        }

        boolean firstRun = !store.isEstablished();
        Set<Long> previousIds = store.orderIds();

        List<FinishedOrder> current;
        try {
            current = fetcher.fetch(reportDate);
        } catch (Exception e) {
            System.out.println("[日报] 已结束订单拉取失败（本次 sheet 留空，底表未改动）：" + e);
            return List.of();
        }

        List<FinishedOrder> newly = newlyFinished(previousIds, current);

        try {
            store.replaceAll(current);
        } catch (IOException e) {
            // 底表写失败只影响「下次的对比基线」，sheet 数据已经算出来了，照常输出
            System.out.println("[日报] 已结束订单底表写入失败（不影响本次 sheet）：" + e.getMessage());
        }

        if (firstRun) {
            System.out.println("[日报] 已结束订单底表首次建立（" + current.size()
                    + " 单），本次不输出「新增」；下一次日报起正常输出。");
            return List.of();
        }

        System.out.println("[日报] 已结束订单：本次拉取 " + current.size() + " 单（底表原有 "
                + previousIds.size() + " 单），新增 " + newly.size() + " 单");
        return newly;
    }

    // ==================== 纯函数（可单测） ====================

    /**
     * 从本次拉取结果里挑出「底表里没有的」—— 即新结束的订单。
     *
     * <p>纯函数，不碰 IO，便于单测。输入顺序被保留（写进 sheet 的顺序稳定）。</p>
     */
    static List<FinishedOrder> newlyFinished(Set<Long> previousIds,
                                             List<FinishedOrder> current) {
        Set<Long> previous = previousIds == null ? Set.of() : previousIds;
        List<FinishedOrder> result = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (FinishedOrder order : current) {
            if (order == null || order.getOrderId() == null) {
                continue;
            }
            if (previous.contains(order.getOrderId())) {
                continue;
            }
            // 同一次拉取里重复出现只算一条（多账户/多状态可能捞到同一订单）
            if (!seen.add(order.getOrderId())) {
                continue;
            }
            result.add(order);
        }
        return result;
    }
}
