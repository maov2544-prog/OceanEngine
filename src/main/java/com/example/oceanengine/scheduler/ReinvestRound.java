package com.example.oceanengine.scheduler;

import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.example.oceanengine.client.SuixintuiTokenClient;
import com.example.oceanengine.domain.ReinvestSafetyGuard;
import com.example.oceanengine.domain.SuixintuiReinvestCandidateSelector;
import com.example.oceanengine.domain.SuixintuiReinvestCandidateSelector.CandidateOrder;
import com.example.oceanengine.model.SuixintuiUniOrderListRequest;
import com.example.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.example.oceanengine.service.suixintui.SuixintuiOrderService;
import com.example.oceanengine.service.suixintui.SuixintuiReinvestService;
import com.example.oceanengine.storage.ReinvestRecord;
import com.example.oceanengine.storage.ReinvestRecordStore;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随心推追投单轮执行器：一次完整「扫描 → 筛选 → 追投 → 汇总」。
 *
 * <p>执行流程（每轮）：</p>
 * <ol>
 *   <li>每轮生成 roundId，重新获取 Access Token（自建服务端签发，实时获取）；</li>
 *   <li>遍历配置的千川账户：拉近 N 天创建的「商品全域-投放中」订单列表（每账户一次列表请求）；</li>
 *   <li>{@link SuixintuiReinvestCandidateSelector} 内存筛选
 *       （投放中 + 支持追投 + 净成交 ROI ≥ 阈值 + 整体消耗/投放总金额 ≥ 阈值）；</li>
 *   <li>对候选订单执行幂等追投（见 {@link #reinvestOne}），失败/超时<b>不重试</b>，下一轮自然重新评估；</li>
 *   <li>输出每账户 + 整体汇总日志。</li>
 * </ol>
 *
 * <p>幂等保障（官方接口无幂等键，全部依赖本地流水）：</p>
 * <ul>
 *   <li>同一 roundId 内同一订单只执行一次（{@link ReinvestRecordStore#alreadyProcessed}）；</li>
 *   <li>跨轮允许重复追投（只要订单仍满足条件就继续加，这是策略要求）；</li>
 *   <li>进程重启后，对遗留 PENDING/UNKNOWN 记录先调详情校验：预算已含上次追加额
 *       → 标记 DONE 本轮跳过（防止重启+响应丢失导致双投）；预算未涨 → 视为未生效，正常重投。</li>
 * </ul>
 */
@Component
public class ReinvestRound {

    private static final DateTimeFormatter ROUND_TS =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 业务失败（code != 0）抛出的异常统一以该前缀开头（见 SuixintuiReinvestService） */
    private static final String BIZ_FAIL_PREFIX = "随心推全域订单加预算 code=";

    private final SuixintuiReinvestProperties props;
    private final ReinvestRecordStore store;
    private final ReinvestSafetyGuard safetyGuard;

    public ReinvestRound(SuixintuiReinvestProperties props, ReinvestRecordStore store) {
        this.props = props;
        this.store = store;
        this.safetyGuard = new ReinvestSafetyGuard(props, store);
    }

    /** 执行一轮，返回汇总结果 */
    public ReinvestRoundResult execute() {
        ReinvestRoundResult result = new ReinvestRoundResult();

        String roundId = "R" + ROUND_TS.format(java.time.LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(1000, 10000);
        result.setRoundId(roundId);

        // ========== 1. Token + 底层服务（每轮刷新） ==========
        String token;
        ApiClient apiClient;
        try {
            token = SuixintuiTokenClient.getAccessToken();
            apiClient = new ApiClient();
            apiClient.setBasePath(props.getApiBaseUrl());
            apiClient.addDefaultHeader("Access-Token", token);
        } catch (Exception e) {
            result.setError("获取 Access Token 失败: " + e.getMessage());
            System.out.println("[轮次中止] " + result.getError());
            return result;
        }

        SuixintuiOrderService orderService = new SuixintuiOrderService(apiClient, token);
        SuixintuiOrderDetailService detailService = new SuixintuiOrderDetailService(apiClient, token);
        SuixintuiReinvestService reinvestService = new SuixintuiReinvestService(apiClient, token);
        SuixintuiReinvestCandidateSelector selector =
                new SuixintuiReinvestCandidateSelector(orderService, detailService);

        System.out.println();
        System.out.println("=== 随心推追投轮次 " + roundId
                + (props.isDryRun() ? "（DRY-RUN 演练，不真实追投）" : "（真实执行）") + " ===");
        System.out.println("配置: 账户 " + props.getAdvertiserIds()
                + ", 窗口 " + props.getWindowDays() + " 天"
                + ", 每单 +" + props.getAmount() + " 元 / " + props.getDeliveryTime() + " 小时"
                + ", ROI≥" + props.getMinRoi() + "（" + props.getRoiMetric() + "）"
                + ", 消耗/投放总金额≥" + (props.getMinBudgetUsedRatio() * 100) + "%");

        // ========== 2. 逐账户扫描 + 追投 ==========
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(props.getWindowDays() - 1L);
        long roundReservedAmount = 0L;

        for (Long advertiserId : props.getAdvertiserIds()) {
            long accountStart = System.currentTimeMillis();
            System.out.println();
            System.out.println("--- 账户 " + advertiserId
                    + "（创建时间 " + startDate + " ~ " + endDate + "） ---");

            int ordersTotal;
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders;
            try {
                SuixintuiUniOrderListRequest request =
                        new SuixintuiUniOrderListRequest(advertiserId)
                                .status(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.DELIVERY_OK)
                                .orderField(QianchuanAwemeUniPromotionOrderGetV10OrderField.STAT_COST_FOR_ROI2)
                                .orderCreateStartDate(startDate.toString())
                                .orderCreateEndDate(endDate.toString());
                orders = orderService.fetchUniPromotionOrders(request);
                ordersTotal = orders.size();
            } catch (Exception e) {
                System.out.println("  [账户失败] 拉取订单列表异常: " + e.getMessage());
                result.addAccount(new ReinvestRoundResult.AccountResult(
                        advertiserId, 0, 0, 0, 0, 0, 0, 0,
                        System.currentTimeMillis() - accountStart));
                continue;
            }

            List<CandidateOrder> candidates;
            try {
                candidates = selector.findReinvestCandidates(
                        orders, advertiserId,
                        props.getMinRoi(), props.getMinBudgetUsedRatio(), props.getRoiMetric());
            } catch (Exception e) {
                System.out.println("  [账户失败] 候选筛选异常: " + e.getMessage());
                result.addAccount(new ReinvestRoundResult.AccountResult(
                        advertiserId, ordersTotal, 0, 0, 0, 0, 0, 0,
                        System.currentTimeMillis() - accountStart));
                continue;
            }

            int success = 0, failed = 0, unknown = 0, skipped = 0, dryRunSkip = 0;
            for (CandidateOrder c : candidates) {
                // 本轮已处理（防御；串行轮次下一般不会发生）
                if (store.alreadyProcessed(roundId, c.orderId)) {
                    skipped++;
                    continue;
                }
                Outcome out = reinvestOne(roundId, advertiserId, c, roundReservedAmount,
                        detailService, reinvestService);
                if (out.reserveRoundAmount) {
                    roundReservedAmount += props.getAmount();
                }
                switch (out.status) {
                    case SUCCESS -> success++;
                    case FAILED -> failed++;
                    case UNKNOWN -> unknown++;
                    case SKIPPED_EFFECTIVE, SKIPPED_UNRESOLVED, SKIPPED_SAFETY -> skipped++;
                    case DRY_RUN -> dryRunSkip++;
                }
            }

            ReinvestRoundResult.AccountResult ar = new ReinvestRoundResult.AccountResult(
                    advertiserId, ordersTotal, candidates.size(),
                    success, failed, unknown, skipped, dryRunSkip,
                    System.currentTimeMillis() - accountStart);
            result.addAccount(ar);
            System.out.println("  " + ar);
        }

        System.out.println();
        System.out.println("[汇总] " + result.summarize());
        return result;
    }

    // ==================== 单订单幂等追投 ====================

    private enum OutcomeStatus {
        SUCCESS, FAILED, UNKNOWN, SKIPPED_EFFECTIVE, SKIPPED_UNRESOLVED, SKIPPED_SAFETY, DRY_RUN
    }

    private static final class Outcome {
        final OutcomeStatus status;
        final boolean reserveRoundAmount;

        Outcome(OutcomeStatus status) {
            this(status, false);
        }

        Outcome(OutcomeStatus status, boolean reserveRoundAmount) {
            this.status = status;
            this.reserveRoundAmount = reserveRoundAmount;
        }
    }

    /**
     * 对单个候选订单执行幂等追投。
     *
     * <p>顺序：重启校验（遗留 PENDING/UNKNOWN 是否已生效）→ 落盘 PENDING → 调接口
     * → 成功 DONE / 业务失败 FAILED / 网络异常 UNKNOWN。dry-run 只打印不落盘、不调用。</p>
     */
    private Outcome reinvestOne(String roundId, Long advertiserId, CandidateOrder c,
                                long roundReservedAmount,
                                SuixintuiOrderDetailService detailService,
                                SuixintuiReinvestService reinvestService) {
        // ---- 1. 重启校验：上次结果未知（PENDING/UNKNOWN）是否其实已生效 ----
        ReinvestRecord pending = store.findLatestPendingOrUnknown(c.orderId);
        if (pending != null) {
            try {
                Long current = detailService.getTotalBudget(c.orderId, advertiserId);
                if (current != null && pending.getPreTotalBudget() != null
                        && current >= pending.getPreTotalBudget() + pending.getAmount()) {
                    store.mark(pending, ReinvestRecord.Status.DONE,
                            "重启校验：当前预算 " + current + " 已包含上次追加额，判定已生效");
                    System.out.printf("  [幂等] order_id=%s 上次结果未知但预算已生效（%s → %s），本轮跳过%n",
                            c.orderId, pending.getPreTotalBudget(), current);
                    return new Outcome(OutcomeStatus.SKIPPED_EFFECTIVE);
                }
                System.out.printf("  [安全跳过] order_id=%s 仍有 %s 未决记录，当前预算未确认增长，禁止自动重投%n",
                        c.orderId, pending.getStatus());
                return new Outcome(OutcomeStatus.SKIPPED_UNRESOLVED);
            } catch (Exception e) {
                System.out.println("  [安全跳过] order_id=" + c.orderId
                        + " 未决记录校验失败，禁止自动重投: " + e.getMessage());
                return new Outcome(OutcomeStatus.SKIPPED_UNRESOLVED);
            }
        }

        // ---- 2. 资金护栏：任何真实或演练计划都先经过限额与冷却检查 ----
        ReinvestSafetyGuard.Decision decision = safetyGuard.evaluate(
                advertiserId, c.orderId, roundReservedAmount, java.time.LocalDateTime.now());
        if (!decision.allowed()) {
            System.out.println("  [安全跳过] order_id=" + c.orderId + " " + decision.reason());
            return new Outcome(OutcomeStatus.SKIPPED_SAFETY);
        }

        // ---- 3. 演练模式：只打印计划 ----
        if (props.isDryRun()) {
            System.out.printf("  [DRY-RUN] order_id=%s roi=%.2f 消耗=%.2f 投放总金额=%s 将追投 +%d 元 / %.0f 小时%n",
                    c.orderId,
                    c.roi == null ? 0.0 : c.roi,
                    c.statCost == null ? 0.0 : c.statCost,
                    c.budget,
                    props.getAmount(), props.getDeliveryTime());
            return new Outcome(OutcomeStatus.DRY_RUN, true);
        }

        // ---- 3. 真实执行：落盘 PENDING → 调接口 → 更新状态 ----
        try {
            ReinvestRecord record = store.markPending(roundId, advertiserId, c.orderId,
                    props.getAmount(), props.getDeliveryTime(), c.budget);
            try {
                reinvestService.addBudget(advertiserId, c.orderId,
                        props.getAmount(), props.getDeliveryTime());
                store.mark(record, ReinvestRecord.Status.DONE, "OK");
                System.out.printf("  [追投成功] order_id=%s +%d 元 / %.0f 小时%n",
                        c.orderId, props.getAmount(), props.getDeliveryTime());
                return new Outcome(OutcomeStatus.SUCCESS, true);
            } catch (ApiException e) {
                String msg = e.getMessage() == null ? "null" : e.getMessage();
                if (msg.startsWith(BIZ_FAIL_PREFIX)) {
                    // 业务失败：记录失败原因，下一轮重新评估
                    store.mark(record, ReinvestRecord.Status.FAILED, msg);
                    System.out.println("  [追投失败] order_id=" + c.orderId + " 原因: " + msg);
                    return new Outcome(OutcomeStatus.FAILED, true);
                }
                // 网络异常/超时：结果未知，不重试；后续轮次只核验，不自动再次提交
                store.mark(record, ReinvestRecord.Status.UNKNOWN, msg);
                System.out.println("  [结果未知] order_id=" + c.orderId + " 网络异常（不重试，后续只核验）: " + msg);
                return new Outcome(OutcomeStatus.UNKNOWN, true);
            }
        } catch (Exception e) {
            // 流水落盘失败等本地异常：本轮跳过，避免无记录地调用接口
            System.out.println("  [本地异常] order_id=" + c.orderId + " " + e.getMessage());
            return new Outcome(OutcomeStatus.FAILED);
        }
    }
}
