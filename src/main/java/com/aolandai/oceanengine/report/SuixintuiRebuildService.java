package com.aolandai.oceanengine.report;

import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderDetailService;
import com.aolandai.oceanengine.storage.FinishedOrder;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecord;
import com.aolandai.oceanengine.storage.SuixintuiRebuildRecordStore;
import com.aolandai.oceanengine.suixintui.SuixintuiCreateOutcome;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderCreateService;
import com.aolandai.oceanengine.suixintui.SuixintuiOrderParam;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「已结束且 ROI 达标」订单的<b>自动复建</b>编排。
 *
 * <p>需求（老板 2026-10 逐条确认）：每天日报抓完「已结束且 ROI 达标」的订单后，
 * 把这些订单按<b>原单参数照抄</b>重新建一个随心推订单。</p>
 *
 * <h2>参数来源</h2>
 * <ul>
 *   <li>列表接口（已在 {@link FinishedOrder} 里）：{@code advertiserId / awemeId / productId}</li>
 *   <li>详情接口（本类现取）：{@code amount / deliveryTime / bidType / qcpxMode / roiGoal}</li>
 * </ul>
 *
 * <h2>关键约束</h2>
 * <ol>
 *   <li><b>幂等</b>：一个原订单最多真复建一次，永不复建第二次（{@link SuixintuiRebuildRecordStore#isRebuilt}）。
 *       dry-run 记录不算数，详见 {@link SuixintuiRebuildRecord}。</li>
 *   <li><b>每日上限</b>：最多真复建 {@link SuixintuiRebuildProperties#getMaxOrdersPerDay()} 单，
 *       超限<b>跳过</b>（不是终止），日志留痕。</li>
 *   <li><b>开关跟随 dry-run</b>：{@code suixintui.reinvest.dry-run=true} 时只组装请求体打印、不真建。</li>
 *   <li><b>限流立即停止</b>：详情/建单被 40100/40110/40130 拦截时立刻停止本轮（不重试轰炸），已建成的保留。</li>
 *   <li><b>失败不写流水</b>：详情失败 / 建单失败都不写流水，下次还能重试。</li>
 *   <li><b>在投冲突预检</b>（2026-10 新增）：建单前先按账户批量查「该「商品+达人」组合是否已有在投单」，
 *       命中则跳过（不建单、不写流水）。千川拒绝重复建单的判据是
 *       <b>「同一（productId + awemeId）组合已有正在投放的订单」</b>，返回笼统的
 *       {@code code=40000 message=商品校验不通过}。预检把这类冲突从「报错+日志噪音」变为「优雅跳过」。</li>
 * </ol>
 *
 * <h2>尽力而为</h2>
 * <p>本类是日报的旁路功能：任何单条失败都只记日志并继续，<b>绝不向外抛</b>影响日报主表。</p>
 */
@Component
public class SuixintuiRebuildService {

    private final SuixintuiRebuildProperties rebuildProps;
    private final SuixintuiReinvestProperties reinvestProps;
    private final SuixintuiRebuildRecordStore recordStore;
    private final SuixintuiOrderDetailService detailService;
    private final SuixintuiOrderCreateService createService;
    private final InFlightOrderFetcher inFlightOrderFetcher;

    /**
     * 「拉取某账户近期订单列表」的函数式抽象 —— 用于<b>在投冲突预检</b>。
     *
     * <p>把它抽出来（而不是直接在服务里 new {@code SuixintuiOrderService}）有两个理由：</p>
     * <ol>
     *   <li><b>可测</b>：单测能塞一个假实现，完全不碰网络（项目里已有
     *       {@link SuixintuiOrderCreateService.FetcherProvider} 这样的函数式注入先例）。</li>
     *   <li><b>复用现成拉取</b>：生产实现委托给 {@code SuixintuiOrderService} / {@code FinishedOrderFetcher}
     *       的列表拉取方式，不另写一套，避免口径漂移。</li>
     * </ol>
     *
     * <p>返回值是<b>原始列表条目</b>（{@link QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner}），
     * 由调用方自行判定在投组合。一次调用 = 一个账户的近 N 天全量订单（方法内部负责翻页）。</p>
     */
    @FunctionalInterface
    public interface InFlightOrderFetcher {
        List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> fetch(long advertiserId)
                throws Exception;
    }

    /**
     * 生产用构造器：注入在投冲突预检的列表拉取器。
     *
     * <p>⚠️ 标注 {@code @Autowired}：本类有两个构造器，Spring 需要明确选这一个
     * （兼容构造器仅给旧单测/旧调用点用）。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SuixintuiRebuildService(SuixintuiRebuildProperties rebuildProps,
                                   SuixintuiReinvestProperties reinvestProps,
                                   SuixintuiRebuildRecordStore recordStore,
                                   SuixintuiOrderDetailService detailService,
                                   SuixintuiOrderCreateService createService,
                                   InFlightOrderFetcher inFlightOrderFetcher) {
        this.rebuildProps = rebuildProps;
        this.reinvestProps = reinvestProps;
        this.recordStore = recordStore;
        this.detailService = detailService;
        this.createService = createService;
        this.inFlightOrderFetcher = inFlightOrderFetcher;
    }

    /**
     * 兼容构造器（<b>无预检</b>）：不注入列表拉取器时跳过在投冲突预检，行为退化为改造前。
     *
     * <p>保留它主要为既有单测/旧调用点平滑过渡；生产装配走上面那个 6 参构造器。</p>
     */
    public SuixintuiRebuildService(SuixintuiRebuildProperties rebuildProps,
                                   SuixintuiReinvestProperties reinvestProps,
                                   SuixintuiRebuildRecordStore recordStore,
                                   SuixintuiOrderDetailService detailService,
                                   SuixintuiOrderCreateService createService) {
        this(rebuildProps, reinvestProps, recordStore, detailService, createService, null);
    }

    /**
     * 对本日新结束的订单执行复建。
     *
     * <p>传入顺序被保留（复建按此顺序消费每日上限）。返回「原订单号 → {@link RebuildResult}」，
     * 供日报 sheet 写两列：「复建状态」（「已复建」）与「复建订单ID」（新订单号）。
     * 只有<b>真复建成功</b>（含幂等命中）的原订单才进入返回值；dry-run / 超限 / 失败的一律不放，
     * 日报对应两列留空。</p>
     *
     * @param newlyFinished 本日新结束且 ROI 达标的订单
     * @return 原订单号 → 复建结果（含新订单号）；无复建时为空 Map
     */
    public Map<Long, RebuildResult> rebuild(List<FinishedOrder> newlyFinished) {
        Map<Long, RebuildResult> status = new LinkedHashMap<>();
        if (newlyFinished == null || newlyFinished.isEmpty()) {
            return status;
        }

        boolean dryRun = reinvestProps.isDryRun();
        LocalDate today = LocalDate.now();
        int maxPerDay = rebuildProps.getMaxOrdersPerDay();
        long alreadyToday = recordStore.effectiveCountOn(today);
        int builtThisRun = 0;
        int skippedIdempotent = 0;
        int skippedLimit = 0;
        int skippedInvalid = 0;
        int skippedInflight = 0;

        System.out.println("[复建] 开始本轮复建：新结束订单 " + newlyFinished.size()
                + " 单，今日已真复建 " + alreadyToday + " / 上限 " + maxPerDay
                + "，模式 " + (dryRun ? "dry-run（只打印不真建）" : "真建"));

        // ⓪ 在投冲突预检：按账户批量只拉一次列表，构建「已在投的(账户,商品,达人)组合」集合。
        //    ⚠️ 关键设计：绝不每单一次请求 —— 官方对「频繁轮询」极敏感，超限会 5 分钟全失败。
        //    故按 advertiserId 分组，每账户只调一次（详见 fetchInflightCombos）。
        PrecheckResult precheck = fetchInflightCombos(newlyFinished);
        if (precheck.rateLimited()) {
            // 预检列表被限流 → 立即停本轮（与详情/建单的限流处理一致，不轰炸）
            System.out.println("[复建] 在投预检被限流，本轮复建中止（已建成保留）");
            return status;
        }
        Map<InflightKey, Long> inflightCombos = precheck.inflightCombos();

        for (FinishedOrder order : newlyFinished) {
            if (order == null || order.getOrderId() == null) {
                continue;
            }
            Long sourceOrderId = order.getOrderId();

            // ① 幂等：已处理过（真建成功 或 之前因在投冲突被永久标记）就跳过。
            //    日报要显示「是哪一单复建的 / 撞的是哪一单」，所以从流水里取回信息：
            //    - 真建 → 写新订单号；- 在投冲突跳过 → 写冲突订单号。
            if (recordStore.isRebuilt(sourceOrderId)) {
                skippedIdempotent++;
                SuixintuiRebuildRecord previous = recordStore.findEffective(sourceOrderId);
                if (previous != null) {
                    if (SuixintuiRebuildRecord.RESULT_SKIPPED_INFLIGHT.equals(previous.effectiveResult())) {
                        status.put(sourceOrderId, RebuildResult.skippedInflight(previous.getConflictOrderId()));
                    } else if (previous.getNewOrderId() != null) {
                        status.put(sourceOrderId, RebuildResult.rebuilt(previous.getNewOrderId()));
                    }
                }
                continue;
            }

            // ② 在投冲突：同（账户+商品+达人）组合已有在投单 → 跳过（不建单）。
            //    ⚠️ 但按老板口径【写流水永久标记】，后续永不再尝试这个原单
            //    （老板已明确接受"万一冲突是暂时的就永久漏一次"的代价）。
            Long conflictOrderId = null;
            if (order.getAdvertiserId() != null && order.getProductId() != null
                    && order.getAwemeId() != null) {
                conflictOrderId = inflightCombos.get(new InflightKey(
                        order.getAdvertiserId(), order.getProductId(), order.getAwemeId()));
            }
            if (conflictOrderId != null) {
                skippedInflight++;
                String reason = "同一（商品+达人）组合已有在投订单（账户 " + order.getAdvertiserId()
                        + "，商品 " + order.getProductId() + "，达人 " + order.getAwemeId()
                        + "，冲突订单 " + conflictOrderId + "），避免撞 code=40000 商品校验不通过。";
                System.out.println("[复建] 跳过原订单 " + sourceOrderId + "：" + reason + "（永久标记，不再重试）");
                // 写流水（SKIPPED_INFLIGHT）永久标记为已处理。参数尽量照抄源单（详情未取，用列表值）。
                SuixintuiRebuildRecord skipRecord = SuixintuiRebuildRecord.skippedInflight(
                        sourceOrderId, conflictOrderId, order.getAdvertiserId(),
                        order.getAwemeId(), order.getProductId(), null, null,
                        null, null, null);
                try {
                    recordStore.append(skipRecord);
                } catch (Exception e) {
                    System.out.println("[复建] 原订单 " + sourceOrderId + " 冲突流水写入失败："
                            + e.getMessage() + "（下次仍会被当成新单重试）");
                }
                status.put(sourceOrderId, RebuildResult.skippedInflight(conflictOrderId));
                continue;
            }

            // ③ 每日上限：真建设计上限（dry-run 不占用上限，因为它没真花钱/真建单）
            if (!dryRun && alreadyToday + builtThisRun >= maxPerDay) {
                skippedLimit++;
                status.put(sourceOrderId, RebuildResult.skippedLimit());
                continue;
            }

            // ④ 组装参数：先校验列表带来的三个 Long 字段非空（可能为 null）
            String invalidReason = validateListFields(order);
            if (invalidReason != null) {
                skippedInvalid++;
                System.out.println("[复建] 跳过原订单 " + sourceOrderId + "：" + invalidReason);
                status.put(sourceOrderId, RebuildResult.skippedInvalid());
                continue;
            }

            // ⑤ 详情接口拿 5 个参数
            QianchuanAwemeUniPromotionOrderDetailV10ResponseDataDeliverySetting setting;
            try {
                QianchuanAwemeUniPromotionOrderDetailV10ResponseData detail =
                        detailService.getOrderDetail(sourceOrderId, order.getAdvertiserId());
                setting = detail == null ? null : detail.getDeliverySetting();
            } catch (RateLimitException e) {
                System.out.println("[复建] 详情接口被限流，立即停止本轮复建（已建成保留）：" + e.describe());
                break;
            } catch (Exception e) {
                System.out.println("[复建] 原订单 " + sourceOrderId + " 详情获取失败（跳过，下次可重试）：" + e);
                status.put(sourceOrderId, RebuildResult.failed());
                continue;
            }
            if (setting == null) {
                System.out.println("[复建] 原订单 " + sourceOrderId + " 详情 delivery_setting 为空，跳过");
                status.put(sourceOrderId, RebuildResult.skippedInvalid());
                continue;
            }
            if (setting.getAmount() == null || setting.getDeliveryTime() == null) {
                System.out.println("[复建] 原订单 " + sourceOrderId + " 详情缺 amount/deliveryTime，跳过");
                status.put(sourceOrderId, RebuildResult.skippedInvalid());
                continue;
            }

            SuixintuiOrderParam param = new SuixintuiOrderParam(
                    order.getAdvertiserId(),
                    order.getAwemeId(),
                    order.getProductId(),
                    setting.getAmount(),
                    setting.getDeliveryTime(),
                    setting.getBidType() == null ? null : setting.getBidType().getValue(),
                    setting.getQcpxMode() == null ? null : setting.getQcpxMode().getValue(),
                    setting.getRoiGoal());

            // ⑥ 建单（复用 SuixintuiOrderCreateService：dry-run / 参数预校验都在里面）
            SuixintuiCreateOutcome outcome;
            try {
                outcome = createService.create(param, dryRun);
            } catch (RateLimitException e) {
                System.out.println("[复建] 建单接口被限流，立即停止本轮复建（已建成保留）：" + e.describe());
                break;
            } catch (Exception e) {
                System.out.println("[复建] 原订单 " + sourceOrderId + " 建单失败（不写流水，下次可重试）：" + e);
                status.put(sourceOrderId, RebuildResult.failed());
                continue;
            }

            // ⑦ 写流水：真建成功 → 记 newOrderId；dry-run → 记审计（不阻挡后续真建）
            SuixintuiRebuildRecord record = new SuixintuiRebuildRecord(
                    sourceOrderId, outcome.orderId(), order.getAdvertiserId(),
                    order.getAwemeId(), order.getProductId(),
                    param.amount(), param.deliveryTime(), param.bidType(),
                    param.qcpxMode(), param.roiGoal(), dryRun);
            try {
                recordStore.append(record);
            } catch (Exception e) {
                // 流水写失败只影响幂等（下次可能重复），本轮订单已建成，如实记录但继续
                System.out.println("[复建] 原订单 " + sourceOrderId + " 流水写入失败："
                        + e.getMessage() + "（该单本次已处理，但流水未生效）");
            }

            if (outcome.created()) {
                builtThisRun++;
                status.put(sourceOrderId, RebuildResult.rebuilt(outcome.orderId()));
                System.out.println("[复建] 原订单 " + sourceOrderId + " → 新订单 "
                        + outcome.orderId() + " 建成");
            } else {
                System.out.println("[复建] 【dry-run】原订单 " + sourceOrderId
                        + " 已组装请求体（未真建）");
            }
        }

        System.out.println("[复建] 本轮结束：真建 " + builtThisRun + " 单，幂等跳过 " + skippedIdempotent
                + " 单，在投冲突跳过 " + skippedInflight + " 单，超限跳过 " + skippedLimit
                + " 单，字段缺失跳过 " + skippedInvalid + " 单"
                + (skippedLimit > 0
                        ? "（今日复建已达上限 " + maxPerDay + " 单，后续 " + skippedLimit + " 单已跳过）"
                        : ""));
        return status;
    }

    // ==================== 在投冲突预检 ====================

    /**
     * 「(账户, 商品, 达人)」组合键 —— 在投冲突判定的比对单位（三个字段必须<b>同时</b>相等）。
     */
    private record InflightKey(long advertiserId, long productId, long awemeId) {
    }

    /**
     * 预检：查出一批订单里，哪些「(账户,商品,达人)组合」当前<b>已有在投订单</b>，
     * 以及那个在投订单的订单号（用于日报 G 列展示冲突单）。
     *
     * <h2>为什么按账户批量（性能红线）</h2>
     * <p>官方对「频繁轮询」极其敏感，超限会 5 分钟全失败。若对每个订单各调一次订单列表，
     * 10 单就是 10 次请求。故本方法<b>按 {@code advertiserId} 分组，每个账户只调一次</b>
     * {@link InflightOrderFetcher#fetch}（一次拉近 N 天全量，方法内部翻页）。</p>
     *
     * <h2>判定「在投」</h2>
     * <p>状态<b>不在</b>终态集合 {@code {FINISHED, FROZEN, OVER, FAILED, TIMEOUT, UNPAIDCANCEL}}
     * 即视为在投。⚠️ FROZEN 计入终态：实测同组合有一条 23 天前「人工主动结束」的 FROZEN 单，
     * 但同组合随后仍成功建过单 —— 千川只看<b>真正在投放</b>的单，FROZEN 不构成冲突。</p>
     * <p>同一组合若有<b>多个</b>在投单，取<b>最后遍历到</b>的那个（顺序由接口决定，不做额外排序 ——
     * 日报只作提示，任取一个都可接受）。</p>
     *
     * <h2>异常处理（与建单失败的策略区分）</h2>
     * <ul>
     *   <li><b>限流</b>（{@link RateLimitException}）→ 记录到 {@link PrecheckResult#rateLimited()}，
     *       由调用方 {@code break} 停本轮（与现有详情/建单的限流处理一致，不轰炸）。</li>
     *   <li><b>其它异常</b> → 记日志后<b>放行</b>（把该账户视为「查不到在投冲突」）：
     *       预检是<b>优化、不是正确性必需</b> —— 真冲突时建单接口自己会拒（行为退化为改造前），
     *       若因预检失败而跳过全部订单，反而会漏掉本可成功的复建。</li>
     * </ul>
     */
    private PrecheckResult fetchInflightCombos(List<FinishedOrder> orders) {
        Map<InflightKey, Long> inflight = new HashMap<>();
        if (inFlightOrderFetcher == null) {
            // 未注入预检器（兼容构造器 / 单测未配）→ 跳过预检，行为退化为改造前
            return new PrecheckResult(inflight, false, false);
        }

        // 按账户分组：每个账户只拉一次列表
        Set<Long> advertiserIds = new HashSet<>();
        for (FinishedOrder o : orders) {
            if (o != null && o.getAdvertiserId() != null) {
                advertiserIds.add(o.getAdvertiserId());
            }
        }

        int requests = 0;
        for (Long advertiserId : advertiserIds) {
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> list;
            try {
                list = inFlightOrderFetcher.fetch(advertiserId);
                requests++;
            } catch (RateLimitException e) {
                // 限流：立即停本轮（与详情/建单的限流处理一致，不轰炸）
                System.out.println("[复建] 在投预检：账户 " + advertiserId
                        + " 列表被限流，立即停止本轮复建（已建成保留）：" + e.describe());
                return new PrecheckResult(inflight, true, false);
            } catch (Exception e) {
                // 非限流异常：放行（不预检该账户）。理由见方法注释「异常处理」。
                System.out.println("[复建] 在投预检：账户 " + advertiserId
                        + " 拉取失败（放行、不预检该账户，真正冲突时由建单接口兜底）：" + e);
                continue;
            }
            if (list == null) {
                continue;
            }
            for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : list) {
                if (o == null || o.getStatus() == null) {
                    continue;
                }
                if (isFinalStatus(o.getStatus().getValue())) {
                    continue;
                }
                Long pid = o.getProductInfo() == null ? null : o.getProductInfo().getProductId();
                Long aid = o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeId();
                if (pid == null || aid == null) {
                    continue;
                }
                inflight.put(new InflightKey(advertiserId, pid, aid), o.getOrderId());
            }
        }

        System.out.println("[复建] 在投预检：账户 " + advertiserIds.size()
                + " 个，列表请求 " + requests + " 次（每账户 1 次），在投组合 " + inflight.size() + " 个");
        return new PrecheckResult(inflight, false, true);
    }

    /**
     * 预检结果：在投组合 → 冲突订单号映射 + 是否被限流（限流时调用方需中止本轮）。
     */
    private record PrecheckResult(Map<InflightKey, Long> inflightCombos,
                                  boolean rateLimited,
                                  boolean executed) {
    }

    /**
     * 终态判定：这些状态<b>不</b>算「在投」。
     *
     * <p>{@code FROZEN} 明确计入终态（理由见 {@link #fetchInflightCombos}）。</p>
     */
    private static boolean isFinalStatus(String status) {
        if (status == null) {
            return false;
        }
        switch (status) {
            case "FINISHED":
            case "FROZEN":
            case "OVER":
            case "FAILED":
            case "TIMEOUT":
            case "UNPAIDCANCEL":
                return true;
            default:
                return false;
        }
    }

    /**
     * 校验列表接口带来的三个 Long 字段非空。
     *
     * <p>⚠️ {@link SuixintuiOrderParam} 的 {@code advertiserId/awemeId/productId} 是 {@code long}
     * 基本类型，而 {@link FinishedOrder} 里是 {@code Long} 包装类型（可能为 null），
     * 拆箱前必须判空，否则 NPE。</p>
     *
     * @return 不合法原因；合法返回 {@code null}
     */
    private static String validateListFields(FinishedOrder order) {
        if (order.getAdvertiserId() == null) {
            return "列表缺 advertiserId";
        }
        if (order.getAwemeId() == null) {
            return "列表缺 awemeId（达人 UID）";
        }
        if (order.getProductId() == null) {
            return "列表缺 productId";
        }
        return null;
    }
}
