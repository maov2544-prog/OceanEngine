package com.aolandai.oceanengine.report;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.domain.ProductRoiRule;
import com.aolandai.oceanengine.domain.SuixintuiReinvestCandidateSelector;
import com.aolandai.oceanengine.model.SuixintuiUniOrderListRequest;
import com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderService;
import com.aolandai.oceanengine.storage.FinishedOrder;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「已结束且 ROI 达标」订单的<b>拉取器</b> —— 供日报的「已结束达标订单」sheet 使用。
 *
 * <p>抽成独立类的理由：把「拉取 + 筛选口径」（「近 N 天创建 + 状态 ∈ 结束态 + ROI ≥ 阈值」）
 * 与「怎么落进日报 sheet」的排版逻辑解耦，各管一摊。若塞进报表服务里，筛选口径会和排版逻辑
 * 缠在一起，日后改口径容易改漏。</p>
 *
 * <p>⚠️ <b>接口硬约束</b>：官方列表接口只能按<b>创建时间</b>过滤，返回项里
 * <b>没有「结束时间」字段</b>；而且 {@code Filtering.status} 是<b>单值</b>不是列表 ——
 * 所以「近 N 天 + 3 个状态」需要 账户数 × 状态数 次请求。</p>
 */
@Component
public class FinishedOrderFetcher {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReinvestReportProperties reportProps;
    private final SuixintuiReinvestProperties reinvestProps;

    public FinishedOrderFetcher(ReinvestReportProperties reportProps,
                                SuixintuiReinvestProperties reinvestProps) {
        this.reportProps = reportProps;
        this.reinvestProps = reinvestProps;
    }

    /**
     * 拉取「创建时间在 {@code [reportDate - (N-1), reportDate]} 内、状态属于结束态、ROI ≥ 阈值」
     * 的全部订单。
     *
     * <p>判定口径与日报 sheet 完全一致：<b>只看 ROI</b>（按 {@code roi-metric} 口径），
     * 不判使用率 —— 已结束的订单没有「上一轮消耗」快照，使用率的分支②会退化成 40% 门槛，
     * 只会制造噪音。ROI 门槛<b>按商品分流</b>（2026-10-04 起），与追投筛选用同一份
     * {@link ProductRoiRule}，避免两处口径漂移。</p>
     *
     * @param reportDate 窗口终点（日报用报表归属日；提醒用「今天」）
     * @return 去重后的订单列表（同一订单被多个账户/状态捞到只保留一条）
     * @throws Exception token 获取失败 / 接口异常 —— 由调用方决定怎么兜
     */
    public List<FinishedOrder> fetch(LocalDate reportDate) throws Exception {
        String token = SuixintuiTokenClient.getAccessToken();
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(reinvestProps.getApiBaseUrl());
        apiClient.addDefaultHeader("Access-Token", token);
        SuixintuiOrderService orderService = new SuixintuiOrderService(apiClient, token);

        int lookback = Math.max(1, reportProps.getFinishedOrdersLookbackDays());
        LocalDate endDate = reportDate;
        LocalDate startDate = endDate.minusDays(lookback - 1L);

        List<QianchuanAwemeUniPromotionOrderGetV10FilteringStatus> statuses =
                reportProps.getFinishedOrdersStatuses();
        List<Long> advertiserIds = reinvestProps.getAdvertiserIds();
        ProductRoiRule productRoiRule = reinvestProps.buildProductRoiRule();
        String now = LocalDateTime.now().format(TS);

        Map<Long, FinishedOrder> byOrderId = new LinkedHashMap<>();
        int requests = 0;
        int roiRejected = 0;

        for (Long advertiserId : advertiserIds) {
            for (QianchuanAwemeUniPromotionOrderGetV10FilteringStatus status : statuses) {
                SuixintuiUniOrderListRequest request =
                        new SuixintuiUniOrderListRequest(advertiserId)
                                .status(status)
                                .orderField(QianchuanAwemeUniPromotionOrderGetV10OrderField
                                        .STAT_COST_FOR_ROI2)
                                .orderCreateStartDate(startDate.toString())
                                .orderCreateEndDate(endDate.toString());
                List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders =
                        orderService.fetchUniPromotionOrders(request);
                requests++;

                for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : orders) {
                    if (o == null || o.getOrderId() == null) {
                        continue;
                    }
                    // ROI 门槛按商品分流，与追投筛选共用同一份规则（口径必须一致，否则
                    // 「被追投的订单」和「日报里的达标订单」会对不上）
                    Long productId = SuixintuiReinvestCandidateSelector.productIdOf(o);
                    Double roi = SuixintuiReinvestCandidateSelector.roiOf(
                            o, reinvestProps.getRoiMetric());
                    if (!productRoiRule.roiOk(productId, roi)) {
                        roiRejected++;
                        continue;
                    }
                    byOrderId.putIfAbsent(o.getOrderId(), new FinishedOrder(
                            o.getOrderId(),
                            advertiserId,
                            o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeName(),
                            o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeShowId(),
                            // ⚠️ 复建建单要的字段：
                            //    aweme_id 取【列表】结构的 aweme_info.aweme_id（数值 UID）
                            //    productId 复用 productIdOf（已处理 product_info 为 null）
                            o.getAwemeInfo() == null ? null : o.getAwemeInfo().getAwemeId(),
                            productId,
                            roi,
                            o.getStatus() == null ? null : o.getStatus().getValue(),
                            o.getOrderCreateTime(),
                            now));
                }
            }
        }

        System.out.println("已结束订单拉取：账户 " + advertiserIds.size() + " × 状态 "
                + statuses.size() + " = " + requests + " 次请求（窗口 " + startDate + " ~ " + endDate
                + "），ROI 门槛 " + productRoiRule.describe()
                + "（口径 " + reinvestProps.getRoiMetric()
                + "）达标 " + byOrderId.size() + " 单，ROI 未达标跳过 " + roiRejected + " 单");
        return new ArrayList<>(byOrderId.values());
    }
}
