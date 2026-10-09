package com.aolandai.oceanengine.domain;

import com.aolandai.oceanengine.client.RateLimitException;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderService;
import com.aolandai.oceanengine.storage.ConsumeSnapshotStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 追投候选订单筛选业务类（领域层）。
 *
 * <p>业务链路（追投动作本身暂不实现）：</p>
 * <ol>
 *   <li>拉取某千川账户下全部「随心推全域-商品订单」；
 *       筛选出 <b>投放中（DELIVERY_OK）、支持追加预算（support_add_budget=true）、
 *       整体消耗 ≥ {@code minStatCost}（默认 100 元）且 ROI ≥ 门槛</b>的订单；
 *       ROI 门槛<b>按商品分流</b>：命中 {@link ProductRoiRule} 的商品用该商品配的门槛，
 *       其余回退全局 {@code minRoi}（2026-10-04 起支持）；</li>
 *   <li>对筛选结果逐单调用订单详情接口，取<b>投放总金额</b>
 *       （创建预算 delivery_setting.amount + 追加金额 add_amount_info.add_amount）；</li>
 *   <li>计算使用率，筛出达标的订单作为追投候选。<b>使用率判定为两分支 OR</b>
 *       （见 {@link UsageRatioRule}）：① 当前使用率 {@code C_now/B ≥ 阈值}；
 *       ② 累计+本轮增量 {@code (2×C_now − C_prev)/B ≥ 阈值}。
 *       分支②需要「上一轮整体消耗」，由 {@link ConsumeSnapshotStore} 提供。</li>
 *   <li><b>第二条路径（ROAS，2026-10-05 新增）</b>与使用率判定是 <b>OR</b> 关系（不替代）：
 *       消耗/投放总金额 ≥ {@code roasMinUsageRatio} 且 净成交金额/投放总金额 ≥ 门槛
 *       （门槛复用商品级 ROI 门槛），见 {@link RoasRule}。开关由构造参数 {@code roasRuleEnabled} 控制，
 *       关闭时行为与改造前完全一致。</li>
 * </ol>
 *
 * <p>依赖底层服务：{@link SuixintuiOrderService}（订单列表）、
 * {@link SuixintuiOrderDetailService}（订单详情/预算）。
 * 本类只做业务筛选编排，不直接调 SDK 接口。</p>
 *
 * <p>注意：ROI 口径可通过 {@link RoiMetric} 选择；详情接口为「一单一请求」，
 * 订单量大时耗时较长，请自行评估。金额单位均为元（官方口径：
 * delivery_setting.amount / add_amount_info.add_amount / stat_cost_for_roi2）。</p>
 */
public class SuixintuiReinvestCandidateSelector {

    /** ROI 口径 */
    public enum RoiMetric {
        /** 整体支付 ROI：total_prepay_and_pay_order_roi2 */
        OVERALL_PAY_ROI,
        /** 净成交 ROI：total_prepay_and_pay_settle_roi2_1h */
        SETTLE_ROI
    }

    /** 追投候选订单（含筛选所需字段） */
    public static class CandidateOrder {
        public final Long orderId;
        public final Long adId;
        /** 商品 ID（product_info.product_id），用于商品级 ROI 门槛与日志排查 */
        public final Long productId;
        /** 抖音号（aweme_info.aweme_show_id） */
        public final String awemeShowId;
        /** 抖音名（aweme_info.aweme_name），日报要用，追投时一并写进流水 */
        public final String awemeName;
        public final Double roi;             // 按所选口径的 ROI
        /** 本单实际适用的 ROI 门槛（命中了商品级覆盖就是覆盖值，否则是全局 min-roi） */
        public final double minRoiUsed;
        public final Double statCost;        // 整体消耗（stats_info.stat_cost_for_roi2）
        /** 上一轮的整体消耗（来自 {@link ConsumeSnapshotStore} 的快照）；没有记录时为 null */
        public final Double prevStatCost;
        public final Long budget;            // 投放总金额（创建预算+追加金额：delivery_setting.amount + add_amount_info.add_amount）
        public final double budgetUsedRatio; // 分支①：整体消耗 / 投放金额
        /** 分支②：(2×整体消耗 − 上一轮整体消耗) / 投放金额 */
        public final double boostedRatio;
        /**
         * 本单是否<b>仅</b>靠第二条路径（ROAS）纳入（未走原使用率路径）。
         *
         * <p>仅用于日志与每轮统计（「第二条路径单独纳入了几单」）。第二条路径未启用时恒为 false。</p>
         */
        public final boolean admittedByRoas;

        /** 兼容旧签名：默认不标记为「仅靠 ROAS 纳入」（等价于 {@code admittedByRoas = false}）。 */
        public CandidateOrder(Long orderId, Long adId, Long productId,
                              String awemeShowId, String awemeName,
                              Double roi, double minRoiUsed, Double statCost,
                              Double prevStatCost, Long budget,
                              double budgetUsedRatio, double boostedRatio) {
            this(orderId, adId, productId, awemeShowId, awemeName, roi, minRoiUsed,
                    statCost, prevStatCost, budget, budgetUsedRatio, boostedRatio, false);
        }

        public CandidateOrder(Long orderId, Long adId, Long productId,
                              String awemeShowId, String awemeName,
                              Double roi, double minRoiUsed, Double statCost,
                              Double prevStatCost, Long budget,
                              double budgetUsedRatio, double boostedRatio,
                              boolean admittedByRoas) {
            this.orderId = orderId;
            this.adId = adId;
            this.productId = productId;
            this.awemeShowId = awemeShowId;
            this.awemeName = awemeName;
            this.roi = roi;
            this.minRoiUsed = minRoiUsed;
            this.statCost = statCost;
            this.prevStatCost = prevStatCost;
            this.budget = budget;
            this.budgetUsedRatio = budgetUsedRatio;
            this.boostedRatio = boostedRatio;
            this.admittedByRoas = admittedByRoas;
        }

        @Override
        public String toString() {
            return String.format(
                    "CandidateOrder{orderId=%s, adId=%s, productId=%s, awemeShowId=%s, awemeName=%s, "
                            + "roi=%.2f, ROI门槛=%.2f, statCost=%.2f, prevStatCost=%s, budget=%s, "
                            + "使用率=%.2f%%, 增强使用率=%.2f%%, 纳入路径=%s}",
                    orderId, adId, productId, awemeShowId, awemeName,
                    roi == null ? 0.0 : roi,
                    minRoiUsed,
                    statCost == null ? 0.0 : statCost,
                    prevStatCost == null ? "无" : String.format("%.2f", prevStatCost),
                    budget,
                    budgetUsedRatio * 100,
                    boostedRatio * 100,
                    admittedByRoas ? "ROAS(仅第二条)" : "使用率");
        }
    }

    private final SuixintuiOrderService orderService;
    private final SuixintuiOrderDetailService detailService;

    /** 上一轮消耗快照（只读）。C_prev 缺失时按「本轮增量 = 0」处理，见 {@link UsageRatioRule}。 */
    private final ConsumeSnapshotStore snapshotStore;

    /** 整体消耗下限（元）：低于它的订单直接排除，不参与追投（0 = 不过滤） */
    private final double minStatCost;

    /**
     * 商品级 ROI 门槛规则（纯函数）。为空规则时全部商品回退全局 {@code minRoi}，
     * 行为与改造前完全一致 —— 见 {@link ProductRoiRule#EMPTY}。
     */
    private final ProductRoiRule productRoiRule;

    /**
     * 第二条路径（ROAS）开关（2026-10-05 老板需求）：见 {@link RoasRule}。
     *
     * <p>为 {@code false} 时第二条路径完全不参与判定，行为与改造前<b>完全一致</b>。</p>
     */
    private final boolean roasRuleEnabled;

    /** 第二条路径（ROAS）的消耗使用率下限（如 0.5 表示消耗/投放总金额 ≥ 50%） */
    private final double roasMinUsageRatio;

    /** 探针用：不做消耗下限过滤、无快照、无商品级覆盖、第二条路径关闭（手工调试时想看全量） */
    public SuixintuiReinvestCandidateSelector(SuixintuiOrderService orderService,
                                              SuixintuiOrderDetailService detailService) {
        this(orderService, detailService, null, 0.0, ProductRoiRule.EMPTY);
    }

    /** 兼容旧签名：无商品级覆盖（等价于 {@link ProductRoiRule#EMPTY}），第二条路径关闭 */
    public SuixintuiReinvestCandidateSelector(SuixintuiOrderService orderService,
                                              SuixintuiOrderDetailService detailService,
                                              ConsumeSnapshotStore snapshotStore,
                                              double minStatCost) {
        this(orderService, detailService, snapshotStore, minStatCost, ProductRoiRule.EMPTY);
    }

    /**
     * 兼容旧签名：<b>第二条路径（ROAS）默认关闭</b>，行为与改造前完全一致。
     *
     * <p>生产链路（{@code ReinvestRound}）走下面的完整版构造器，显式传入开关与阈值。</p>
     */
    public SuixintuiReinvestCandidateSelector(SuixintuiOrderService orderService,
                                              SuixintuiOrderDetailService detailService,
                                              ConsumeSnapshotStore snapshotStore,
                                              double minStatCost,
                                              ProductRoiRule productRoiRule) {
        this(orderService, detailService, snapshotStore, minStatCost, productRoiRule, false, 0.5);
    }

    /**
     * 完整版构造器。
     *
     * @param roasRuleEnabled 是否启用第二条路径（ROAS）；false = 行为与改造前完全一致
     * @param roasMinUsageRatio 第二条路径的消耗使用率下限（如 0.5）
     */
    public SuixintuiReinvestCandidateSelector(SuixintuiOrderService orderService,
                                              SuixintuiOrderDetailService detailService,
                                              ConsumeSnapshotStore snapshotStore,
                                              double minStatCost,
                                              ProductRoiRule productRoiRule,
                                              boolean roasRuleEnabled,
                                              double roasMinUsageRatio) {
        this.orderService = orderService;
        this.detailService = detailService;
        this.snapshotStore = snapshotStore;
        this.minStatCost = minStatCost;
        this.productRoiRule = productRoiRule == null ? ProductRoiRule.EMPTY : productRoiRule;
        this.roasRuleEnabled = roasRuleEnabled;
        this.roasMinUsageRatio = roasMinUsageRatio;
    }

    // ==================== 入口 ====================

    /**
     * 默认筛选：投放中 + 整体消耗 ≥ 构造器下限 + 使用率/ROAS 达标。
     *
     * <p>⚠️ 这里写死的 {@code minRoi=2.0} <b>已不再生效</b>（历史遗留）：ROI 门槛由构造器传入的
     * {@link ProductRoiRule} 决定（全局 {@code min-roi} + 商品级 {@code product-roi-overrides}）。</p>
     */
    public List<CandidateOrder> findReinvestCandidates(Long advertiserId) throws ApiException {
        return findReinvestCandidates(advertiserId, 2.0, 0.8, RoiMetric.SETTLE_ROI);
    }

    /**
     * 指定消耗使用率阈值（ROI 口径默认净成交 ROI）。
     *
     * <p>⚠️ {@code minRoi} <b>已不再生效</b>（历史遗留）：ROI 门槛由构造器传入的
     * {@link ProductRoiRule} 决定（全局 {@code min-roi} + 商品级 {@code product-roi-overrides}）。
     * {@code minBudgetUsedRatio} 仍有效。</p>
     */
    public List<CandidateOrder> findReinvestCandidates(Long advertiserId,
                                                       double minRoi,
                                                       double minBudgetUsedRatio) throws ApiException {
        return findReinvestCandidates(advertiserId, minRoi, minBudgetUsedRatio, RoiMetric.SETTLE_ROI);
    }

    /**
     * 完整参数版（内部先拉订单列表，再走列表版筛选）。
     *
     * <p>⚠️ {@code minRoi} <b>已不再生效</b>（历史遗留）：ROI 门槛由构造器传入的
     * {@link ProductRoiRule} 决定（全局 {@code min-roi} + 商品级 {@code product-roi-overrides}）。
     * 该形参仅为兼容旧调用点保留，本方法与下游的列表版都不会读取它。</p>
     *
     * @param advertiserId       千川业务账户 ID
     * @param minRoi             ⚠️ <b>已不再生效（历史遗留，勿依赖）</b>：ROI 门槛由构造器传入的
     *                           {@link ProductRoiRule} 决定（全局 {@code min-roi} + 商品级
     *                           {@code product-roi-overrides}）。保留形参仅为不改方法签名。
     * @param minBudgetUsedRatio <b>有效</b>：整体消耗/投放金额 下限（如 0.8 表示 ≥ 80%）
     * @param metric             ROI 口径（整体支付 ROI 或 净成交 ROI）
     * @return 追投候选订单列表（按预算使用率降序）
     */
    public List<CandidateOrder> findReinvestCandidates(Long advertiserId,
                                                       double minRoi,
                                                       double minBudgetUsedRatio,
                                                       RoiMetric metric) throws ApiException {
        // 先拉全部商品全域订单，再走列表版筛选（详情只对 ROI 达标订单调用）
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                orderService.fetchUniPromotionOrders(advertiserId);
        System.out.println("订单列表返回: " + orders.size() + " 条");
        return findReinvestCandidates(orders, advertiserId, minRoi, minBudgetUsedRatio, metric);
    }

    /**
     * 列表版：调用方已拉取订单列表时复用，避免重复拉列表。
     *
     * <p>流程：① 内存筛选「投放中 + ROI ≥ 阈值」；② 仅对筛选结果逐单调详情接口取预算；
     * ③ 计算整体消耗/投放金额，筛出 ≥ 阈值的订单。</p>
     *
     * <p>⚠️ <b>ROI 门槛的实际来源</b>：由构造器传入的 {@link ProductRoiRule} 决定
     * （全局 {@code min-roi} + 商品级 {@code product-roi-overrides}），
     * <b>不是</b>下面这个 {@code minRoi} 形参。</p>
     *
     * @param orders             已拉取的全量商品全域订单列表
     * @param advertiserId       千川业务账户 ID（详情接口必填）
     * @param minRoi             ⚠️ <b>已不再生效（历史遗留，勿依赖）</b>：方法体从未读取它。
     *                           ROI 门槛由构造器传入的 {@link ProductRoiRule} 决定
     *                           （全局 {@code min-roi} + 商品级 {@code product-roi-overrides}）。
     *                           保留该形参是为了<b>不改方法签名、避免改动既有调用点</b>。
     * @param minBudgetUsedRatio <b>有效</b>：整体消耗/投放金额 下限，经 {@link UsageRatioRule#hit}
     *                           参与判定（与第二条路径 ROAS 取 OR）
     * @param metric             ROI 口径
     * @return 追投候选订单列表（按预算使用率降序）
     */
    public List<CandidateOrder> findReinvestCandidates(
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders,
            Long advertiserId,
            double minRoi,
            double minBudgetUsedRatio,
            RoiMetric metric) throws ApiException {

        // 1. 内存筛选：投放中 + 支持追加预算 + 整体消耗 ≥ 下限 + ROI ≥ 阈值
        //    ROI 阈值按商品分流：命中 productRoiOverrides 的商品用该商品的门槛，其余用全局 minRoi
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> roiOk =
                new ArrayList<>();
        int notSupportBudget = 0;
        int lowStatCost = 0;
        int roiRejected = 0;
        int productOverrideHit = 0;
        for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : orders) {
            if (o.getStatus() != QianchuanAwemeUniPromotionOrderGetV10DataOrderListStatus.DELIVERY_OK) {
                continue;
            }
            // 不支持追加预算（追投）的订单直接排除，避免详情循环做无效请求
            if (o.getSupportAddBudget() == null || !o.getSupportAddBudget()) {
                notSupportBudget++;
                continue;
            }
            // 整体消耗低于下限的订单不追投：消耗太少没跑出有效数据，
            // 而且它们的分支② ((2×C_now − C_prev)/B) 很容易因为「增量占比虚高」而误触发。
            Double statCost = o.getStatsInfo() == null
                    ? null : o.getStatsInfo().getStatCostForRoi2();
            if (!statCostOk(statCost, minStatCost)) {
                lowStatCost++;
                continue;
            }
            Long productId = productIdOf(o);
            double roiThreshold = productRoiRule.minRoiFor(productId);
            if (!productRoiRule.isEmpty() && productId != null
                    && productRoiRule.asMap().containsKey(productId)) {
                productOverrideHit++;
            }
            Double roi = roiOf(o, metric);
            if (productRoiRule.roiOk(productId, roi)) {
                roiOk.add(o);
                // System.out.println("  [INFO] order_id=" + o.getOrderId() + " ROI=" + roi + " 达标");
            } else {
                roiRejected++;
            }
        }
        System.out.println("投放中且支持追投且整体消耗 ≥ " + minStatCost + " 元且 ROI ≥ 各自门槛: "
                + roiOk.size()
                + " 条（不支持追投跳过 " + notSupportBudget + " 条，消耗 < " + minStatCost
                + " 元跳过 " + lowStatCost + " 条，ROI 未达标跳过 " + roiRejected + " 条）"
                + "，ROI 口径 " + metric
                + "，门槛：" + productRoiRule.describe()
                + (productOverrideHit > 0 ? "（本轮命中商品级门槛 " + productOverrideHit + " 单）" : "")
                + "，将对这 " + roiOk.size() + " 条调用详情接口");

        // 3. 逐单取投放金额（预算），按「使用率」或「第二条路径(ROAS)」两条路径取 OR 筛选
        List<CandidateOrder> candidates = new ArrayList<>();
        int detailFailed = 0;
        // 仅靠第二条路径(ROAS)纳入的订单数（未走原使用率路径）—— 用于日志/每轮统计
        int roasOnlyCount = 0;
        for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : roiOk) {
            try {
                Long budget = detailService.getTotalBudget(o.getOrderId(), advertiserId);

                Double statCost = o.getStatsInfo() == null
                        ? null : o.getStatsInfo().getStatCostForRoi2();

                // 上一轮整体消耗（C_prev）：取自快照；没有记录时为 null，
                // UsageRatioRule 会按「本轮增量 = 0」处理（分支② 退化成分支①，不会放宽门槛）
                Double prevStatCost = snapshotStore == null
                        ? null : snapshotStore.statCostOf(o.getOrderId());

                // 净成交金额（第二条路径用）：stats_info.total_order_settle_amount_for_roi2_1h。
                // ⚠️ 刻意取原始金额字段，而不是用 roi×消耗反算 —— 反算会引入浮点误差。
                Double netSettleAmount = o.getStatsInfo() == null
                        ? null : o.getStatsInfo().getTotalOrderSettleAmountForRoi21h();

                double usedRatio = UsageRatioRule.currentRatio(statCost, budget);
                double boostedRatio = UsageRatioRule.boostedRatio(
                        statCost, prevStatCost, budget);
                double netSettleRatio = RoasRule.netSettleRatio(netSettleAmount, budget);

                Double roi = roiOf(o, metric);
                Long productId = productIdOf(o);
                double roiThreshold = productRoiRule.minRoiFor(productId);

                // 路径①（原口径）：使用率判定，两分支 OR（见 UsageRatioRule）
                boolean usageHit = UsageRatioRule.hit(
                        statCost, prevStatCost, budget, minBudgetUsedRatio);
                // 路径②（新增，2026-10-05）：ROAS —— 消耗/投放总金额 ≥ roasMinUsageRatio
                //   且 净成交金额/投放总金额 ≥ 门槛（门槛复用商品级 ROI 门槛 roiThreshold）
                boolean roasHit = roasRuleEnabled && RoasRule.hit(
                        statCost, netSettleAmount, budget, roasMinUsageRatio, roiThreshold);

                System.out.printf("  [流程] order_id=%s roi=%.2f ROI门槛=%.2f 整体消耗=%.2f 上轮消耗=%s "
                                + "投放总金额=%s 使用率=%.2f%% 增强使用率=%.2f%% "
                                + "净成交金额=%s 净成交率=%.2f%% 纳入路径=%s%n",
                        o.getOrderId(),
                        roi == null ? 0.0 : roi,
                        roiThreshold,
                        statCost == null ? 0.0 : statCost,
                        prevStatCost == null
                                ? "无(增量按0)" : String.format("%.2f", prevStatCost),
                        budget,
                        usedRatio * 100,
                        boostedRatio * 100,
                        netSettleAmount == null
                                ? "无" : String.format("%.2f", netSettleAmount),
                        netSettleRatio * 100,
                        pathLabel(usageHit, roasHit));

                // 两条路径取 OR（任一成立即纳入）；roasRuleEnabled=false 时 roasHit 恒为 false，
                // 判定退化为「仅使用率」，行为与改造前完全一致。
                if (!usageHit && !roasHit) {
                    continue;
                }
                boolean admittedByRoas = roasHit && !usageHit;
                if (admittedByRoas) {
                    roasOnlyCount++;
                }

                candidates.add(new CandidateOrder(
                        o.getOrderId(),
                        o.getAdId(),
                        productId,
                        o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeShowId(),
                        o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeName(),
                        roi,
                        roiThreshold,
                        statCost,
                        prevStatCost,
                        budget,
                        usedRatio,
                        boostedRatio,
                        admittedByRoas));
            } catch (RateLimitException e) {
                // 限流不能吞：详情接口是「一单一请求」，继续循环只会把配额打得更狠。
                // 上抛给轮次层，由它决定跳过账户还是中止整轮。
                System.out.println("  [限流中止] order_id=" + o.getOrderId() + " " + e.describe());
                throw e;
            } catch (ApiException e) {
                detailFailed++;
                System.out.println("  [WARN] order_id=" + o.getOrderId()
                        + " 详情查询失败: " + e.getMessage());
            }
        }

        // 按预算使用率降序（最需要追投的排前面）
        candidates.sort((a, b) -> Double.compare(b.budgetUsedRatio, a.budgetUsedRatio));

        System.out.println("使用率达标（分支①当前使用率 OR 分支②累计+本轮增量，阈值 "
                + (minBudgetUsedRatio * 100) + "%）或 第二条路径(ROAS)：共 " + candidates.size()
                + " 条（详情失败跳过 " + detailFailed + " 条）"
                + (roasRuleEnabled
                        ? "；其中仅靠第二条路径(ROAS)纳入 " + roasOnlyCount + " 条"
                        : "；第二条路径(ROAS)未启用"));
        return candidates;
    }

    // ==================== 工具 ====================

    /**
     * 把两条路径的命中情况翻成日志文案。
     *
     * @param usageHit 路径①（使用率）是否达标
     * @param roasHit  路径②（ROAS）是否达标
     */
    private static String pathLabel(boolean usageHit, boolean roasHit) {
        if (usageHit && roasHit) {
            return "使用率+ROAS";
        }
        if (usageHit) {
            return "使用率";
        }
        if (roasHit) {
            return "ROAS(仅第二条)";
        }
        return "未达标";
    }

    /**
     * 整体消耗是否达到追投下限（2026-09-30 新增的过滤条件）。
     *
     * <p>纯函数，单独抽出来是为了能直接单测 —— 它和 {@link UsageRatioRule} 一样属于「判定规则」，
     * 不该被 IO 包裹着没法测。</p>
     *
     * <p>口径：{@code minStatCost <= 0} 表示不过滤（此时连消耗为 {@code null} 的也放行，
     * 交由后续使用率判定拒绝）；消耗为 {@code null} 且需要过滤时视为未达标；
     * <b>边界含等于</b>（正好等于下限的订单<b>保留</b>）。</p>
     */
    static boolean statCostOk(Double statCost, double minStatCost) {
        if (minStatCost <= 0) {
            return true;
        }
        return statCost != null && statCost >= minStatCost;
    }

    /**
     * 取订单对应的商品 ID（{@code product_info.product_id}）。
     *
     * <p>用于商品级 ROI 门槛分流。返回 {@code null} 表示接口没给商品信息 ——
     * 此时 {@link ProductRoiRule#minRoiFor} 会回退全局门槛（保守：宁可少投，不误投）。</p>
     */
    public static Long productIdOf(
            QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o) {
        if (o == null || o.getProductInfo() == null) {
            return null;
        }
        return o.getProductInfo().getProductId();
    }

    /**
     * 按口径取订单 ROI。
     *
     * <p>{@code public} 是为了让日报模块（「已结束达标订单」sheet）复用同一份口径提取逻辑 ——
     * 两处各写一遍迟早会漂移。</p>
     */
    public static Double roiOf(QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o,
                               RoiMetric metric) {
        QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo s = o.getStatsInfo();
        if (s == null) {
            return null;
        }
        // System.out.println("order_id=" + o.getOrderId() + " 口径 " + metric + " ROI="
        //         + (metric == RoiMetric.SETTLE_ROI
        //         ? s.getTotalPrepayAndPaySettleRoi21h()
        //         : s.getTotalPrepayAndPayOrderRoi2()));

        return metric == RoiMetric.SETTLE_ROI
                ? s.getTotalPrepayAndPaySettleRoi21h()
                : s.getTotalPrepayAndPayOrderRoi2();
    }

    /**
     * 计算整体消耗/投放金额。
     * 预算缺失、为 0 或消耗缺失时返回 0（视为未达标，由调用方阈值过滤）。
     *
     * @deprecated 已迁到 {@link UsageRatioRule#currentRatio}，这里保留只为兼容旧调用点。
     */
    @Deprecated
    private static double calcBudgetUsedRatio(Double statCost, Long budget) {
        return UsageRatioRule.currentRatio(statCost, budget);
    }
}
