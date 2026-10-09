package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.RateLimitException;
import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.aolandai.oceanengine.model.SuixintuiUniOrderListRequest;
import com.aolandai.oceanengine.service.suixintui.SuixintuiOrderService;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.api.QianchuanAwemeUniPromotionOrderReportGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10OrderField;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10PageSize;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10ResponseData;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10ResponseDataAdListInner;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10ResponseDataPageInfo;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderReportGetV10TimeGranularity;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读验证探针：抽验「order ↔ ad」对应关系的可靠性 + 订单报表覆盖率 + 口径吻合率。
 *
 * <p>拟用「订单报表」接口（{@code QianchuanAwemeUniPromotionOrderReportGetV10Api}）按 ad_id
 * 取订单真实 pay ROI，前提是 order 能稳定映射到 ad_id，且报表口径与订单列表口径一致。
 * 本探针在全部 6 账户、近 7 天投放中的全部订单上验证这三点。</p>
 *
 * <p><b>只读</b>：只调订单列表 + 订单报表（均 GET），不调任何写接口。只跑一次，不重试。</p>
 */
public final class OrderAdMappingProbe {

    private static final String DEFAULT_CONFIG_PATH = "D:\\oceanengine\\config\\application.yml";
    private static final String DEFAULT_OUTPUT_PATH = "target\\order-ad-mapping.txt";
    private static final int WINDOW_DAYS = 7;
    private static final String API_BASE_URL = "https://api.oceanengine.com";

    /** 兜底账户（与 RoiMetricCompareProbe 一致，来源同 config） */
    private static final long[] FALLBACK_ADVERTISER_IDS = {
            1_779_106_144_470_100L, 1_778_533_513_439_244L, 1_776_098_375_039_040L,
            1_771_298_760_893_511L, 1_795_656_569_312_393L, 1_823_379_540_880_396L,
    };

    /** 全局累计 */
    private static final class Stats {
        int total;
        int nullAd;
        final Map<Long, List<Long>> adToOrders = new LinkedHashMap<>();
        int covered;
        int notCovered;
        int coveredZeroCost;
        int coveredPosCost;
        int notCoveredZeroCost;
        int notCoveredPosCost;
        final List<String> notCoveredSamples = new ArrayList<>();
        final List<String> notCoveredPosSamples = new ArrayList<>();
        int compared;
        int matched;         // 差值 < 1%
        final List<String> mismatches = new ArrayList<>();
        final List<String> matchedSamples = new ArrayList<>();
    }

    private OrderAdMappingProbe() {
    }

    public static void main(String[] args) {
        String configPath = DEFAULT_CONFIG_PATH;
        String outputPath = DEFAULT_OUTPUT_PATH;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if (a.startsWith("--config=")) {
                configPath = a.substring("--config=".length());
            } else if (a.startsWith("--out=")) {
                outputPath = a.substring("--out=".length());
            }
        }

        StringBuilder report = new StringBuilder();
        int exit = run(report, configPath);

        System.out.print(report);
        try {
            Path out = Paths.get(outputPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                w.write(report.toString());
            }
            System.out.println("（完整报告已写入 UTF-8 文件：" + out.toAbsolutePath() + "）");
        } catch (Exception e) {
            System.out.println("（写输出文件失败：" + e.getMessage() + "）");
        }
        System.exit(exit);
    }

    private static int run(StringBuilder report, String configPath) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(WINDOW_DAYS - 1L);
        List<Long> advertiserIds = loadAdvertiserIds(configPath, report);

        report.append("=== order↔ad 映射可靠性 + 订单报表覆盖率 + 口径吻合率 抽验（只读） ===\n");
        report.append("账户: ").append(advertiserIds.size()).append(" 个 -> ").append(advertiserIds).append("\n");
        report.append("窗口: ").append(startDate).append(" ~ ").append(endDate)
                .append("（status=DELIVERY_OK 投放中；报表 time_granularity=DAILY）\n\n");

        String token;
        ApiClient apiClient;
        try {
            token = SuixintuiTokenClient.getAccessToken();
            apiClient = new ApiClient();
            apiClient.setBasePath(API_BASE_URL);
            apiClient.addDefaultHeader("Access-Token", token);
        } catch (Exception e) {
            report.append("[致命] 获取 Token 失败: ").append(e.getMessage()).append("\n");
            return 1;
        }
        SuixintuiOrderService orderService = new SuixintuiOrderService(apiClient, token);
        QianchuanAwemeUniPromotionOrderReportGetV10Api reportApi =
                new QianchuanAwemeUniPromotionOrderReportGetV10Api();
        reportApi.setApiClient(apiClient);

        Stats st = new Stats();
        boolean aborted = false;

        for (Long advertiserId : advertiserIds) {
            report.append("--- 账户 ").append(advertiserId).append(" ---\n");

            // 1) 订单列表
            List<QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner> orders;
            try {
                SuixintuiUniOrderListRequest req = new SuixintuiUniOrderListRequest(advertiserId)
                        .status(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.DELIVERY_OK)
                        .orderField(QianchuanAwemeUniPromotionOrderGetV10OrderField.STAT_COST_FOR_ROI2)
                        .orderCreateStartDate(startDate.toString())
                        .orderCreateEndDate(endDate.toString());
                orders = orderService.fetchUniPromotionOrders(req);
            } catch (RateLimitException e) {
                report.append("  [限流").append(e.isAccountScoped() ? "跳过" : "中止").append("] ")
                        .append(e.describe()).append("\n");
                if (!e.isAccountScoped()) {
                    aborted = true;
                    break;
                }
                continue;
            } catch (Exception e) {
                report.append("  [失败] 拉取订单列表异常: ").append(e.getMessage()).append("\n");
                continue;
            }
            report.append("  订单数: ").append(orders.size()).append("\n");

            // 2) 订单报表（分页；返回 ad×日期 行）——每账户仅此一个接口，分页拉全
            Map<Long, double[]> reportByAd = new HashMap<>(); // adId -> [sumCost, sumSettleAmount, rows]
            int reportRows = 0;
            try {
                long page = 1;
                long totalPage = 1;
                do {
                    QianchuanAwemeUniPromotionOrderReportGetV10Response resp =
                            reportApi.openApiV10QianchuanAwemeUniPromotionOrderReportGetGet(
                                    advertiserId,
                                    startDate.toString(),
                                    endDate.toString(),
                                    QianchuanAwemeUniPromotionOrderReportGetV10TimeGranularity.DAILY,
                                    null, null, null,
                                    page,
                                    QianchuanAwemeUniPromotionOrderReportGetV10PageSize.NUMBER_100);
                    Long code = resp.getCode();
                    if (code == null || code != 0L) {
                        report.append("  [报表失败] code=").append(code)
                                .append(" msg=").append(resp.getMessage()).append("\n");
                        break;
                    }
                    QianchuanAwemeUniPromotionOrderReportGetV10ResponseData data = resp.getData();
                    List<QianchuanAwemeUniPromotionOrderReportGetV10ResponseDataAdListInner> rows =
                            data == null ? null : data.getAdList();
                    if (rows != null) {
                        for (QianchuanAwemeUniPromotionOrderReportGetV10ResponseDataAdListInner r : rows) {
                            if (r.getAdId() == null) {
                                continue;
                            }
                            double[] acc = reportByAd.computeIfAbsent(r.getAdId(), k -> new double[3]);
                            acc[0] += r.getStatCostForRoi2() == null ? 0.0 : r.getStatCostForRoi2();
                            acc[1] += r.getTotalOrderSettleAmountForRoi21h() == null
                                    ? 0.0 : r.getTotalOrderSettleAmountForRoi21h();
                            acc[2] += 1;
                            reportRows++;
                        }
                    }
                    QianchuanAwemeUniPromotionOrderReportGetV10ResponseDataPageInfo pi =
                            data == null ? null : data.getPageInfo();
                    totalPage = pi == null || pi.getTotalPage() == null ? 1 : pi.getTotalPage();
                    page++;
                } while (page <= totalPage);
            } catch (RateLimitException e) {
                report.append("  [限流").append(e.isAccountScoped() ? "跳过" : "中止").append("] 报表: ")
                        .append(e.describe()).append("\n");
                if (!e.isAccountScoped()) {
                    aborted = true;
                    break;
                }
            } catch (Exception e) {
                report.append("  [失败] 订单报表异常: ").append(e.getMessage()).append("\n");
            }
            report.append("  报表 ad×日期 行数: ").append(reportRows)
                    .append("，distinct ad: ").append(reportByAd.size()).append("\n");

            // 3) 逐单核查
            int accNullAd = 0;
            int accCovered = 0;
            int accCompared = 0;
            int accMatched = 0;
            int accPosTotal = 0;
            int accCoveredPos = 0;
            for (QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInner o : orders) {
                st.total++;
                Long adId = o.getAdId();
                QianchuanAwemeUniPromotionOrderGetV10ResponseDataOrderListInnerStatsInfo s = o.getStatsInfo();
                Double orderRoi = s == null ? null : s.getTotalPrepayAndPaySettleRoi21h();
                Double orderCost = s == null ? null : s.getStatCostForRoi2();

                if (adId == null || adId == 0L) {
                    st.nullAd++;
                    accNullAd++;
                    continue;
                }
                st.adToOrders.computeIfAbsent(adId, k -> new ArrayList<>()).add(o.getOrderId());

                double[] acc = reportByAd.get(adId);
                boolean hasCost = orderCost != null && orderCost > 0;
                if (hasCost) {
                    accPosTotal++;
                }
                if (acc == null) {
                    st.notCovered++;
                    if (hasCost) {
                        st.notCoveredPosCost++;
                        if (st.notCoveredPosSamples.size() < 100) {
                            st.notCoveredPosSamples.add("账户=" + advertiserId + " order=" + o.getOrderId()
                                    + " ad=" + adId + " 订单settleRoi=" + fmt(orderRoi)
                                    + " 订单cost=" + fmt(orderCost));
                        }
                    } else {
                        st.notCoveredZeroCost++;
                        if (st.notCoveredSamples.size() < 10) {
                            st.notCoveredSamples.add("账户=" + advertiserId + " order=" + o.getOrderId()
                                    + " ad=" + adId + " 订单settleRoi=" + fmt(orderRoi)
                                    + " 订单cost=" + fmt(orderCost));
                        }
                    }
                    continue;
                }
                st.covered++;
                accCovered++;
                if (hasCost) {
                    st.coveredPosCost++;
                    accCoveredPos++;
                } else {
                    st.coveredZeroCost++;
                }

                // 口径吻合：报表按消耗加权还原 settle ROI
                if (orderRoi != null && acc[0] > 0) {
                    double reportRoi = acc[1] / acc[0];
                    st.compared++;
                    accCompared++;
                    double diff = Math.abs(orderRoi - reportRoi);
                    double denom = Math.max(Math.max(Math.abs(orderRoi), Math.abs(reportRoi)), 1e-9);
                    double diffPct = diff / denom * 100.0;
                    String line = "账户=" + advertiserId + " order=" + o.getOrderId() + " ad=" + adId
                            + " 订单settleRoi=" + String.format("%.4f", orderRoi)
                            + " 报表加权settleRoi=" + String.format("%.4f", reportRoi)
                            + " Σsettle金额=" + String.format("%.2f", acc[1])
                            + " Σ消耗=" + String.format("%.2f", acc[0])
                            + " 差值=" + String.format("%.4f", diff)
                            + String.format("(%.2f%%)", diffPct);
                    if (diffPct < 1.0) {
                        st.matched++;
                        accMatched++;
                        if (st.matchedSamples.size() < 5) {
                            st.matchedSamples.add(line);
                        }
                    } else if (st.mismatches.size() < 200) {
                        st.mismatches.add(line);
                    }
                }
            }
            report.append("  ① ad_id 为 null/0 的订单: ").append(accNullAd).append("\n");
            report.append("  ② 命中报表 ad_id 的订单: ").append(accCovered)
                    .append(" / ").append(orders.size())
                    .append(orders.isEmpty() ? "" : String.format("（%.1f%%）", 100.0 * accCovered / orders.size()))
                    .append("；其中消耗>0 的命中: ").append(accCoveredPos)
                    .append(" / ").append(accPosTotal)
                    .append(accPosTotal == 0 ? "" : String.format("（%.1f%%）", 100.0 * accCoveredPos / accPosTotal))
                    .append("\n");
            report.append("  ③ 可对比且差值<1% 的订单: ").append(accMatched)
                    .append(" / ").append(accCompared).append("\n\n");
        }

        appendGlobal(report, st, aborted);
        return aborted ? 1 : 0;
    }

    private static void appendGlobal(StringBuilder report, Stats st, boolean aborted) {
        report.append("==================== 全局统计 ====================\n");
        if (aborted) {
            report.append("⚠️ 本次抽验被限流中止，以下仅覆盖已成功处理的账户，非全量。\n");
        }
        report.append("订单总数: ").append(st.total).append("\n\n");

        // ① ad_id 可靠性
        int dupAd = 0;
        List<String> dupSamples = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : st.adToOrders.entrySet()) {
            if (e.getValue().size() > 1) {
                dupAd++;
                if (dupSamples.size() < 20) {
                    dupSamples.add("ad_id=" + e.getKey() + " 被 " + e.getValue().size()
                            + " 个订单共用: " + e.getValue());
                }
            }
        }
        report.append("① ad_id 可靠性\n");
        report.append("   订单数: ").append(st.total).append("\n");
        report.append("   ad_id 为 null/0 的订单数: ").append(st.nullAd)
                .append(pct(st.nullAd, st.total)).append("\n");
        report.append("   distinct ad_id 数: ").append(st.adToOrders.size()).append("\n");
        report.append("   被多个订单共用的 ad_id 数: ").append(dupAd)
                .append(dupAd == 0 ? "  ← 无重复：本样本 order↔ad 为 1:1" : "  ← ⚠️ 存在一对多！")
                .append("\n");
        for (String d : dupSamples) {
            report.append("     ").append(d).append("\n");
        }

        // ② 覆盖率
        report.append("\n② 报表覆盖率\n");
        report.append("   命中报表的订单数: ").append(st.covered).append(" / ").append(st.total)
                .append(pct(st.covered, st.total)).append("\n");
        report.append("   未命中报表的订单数: ").append(st.notCovered)
                .append(pct(st.notCovered, st.total)).append("\n");
        report.append("   —— 按「订单整体消耗」拆分 ——\n");
        report.append("   命中：消耗>0 的 ").append(st.coveredPosCost)
                .append(" 单，消耗=0 的 ").append(st.coveredZeroCost).append(" 单\n");
        report.append("   未命中：消耗>0 的 ").append(st.notCoveredPosCost)
                .append(" 单，消耗=0 的 ").append(st.notCoveredZeroCost).append(" 单\n");
        int posTotal = st.coveredPosCost + st.notCoveredPosCost;
        report.append("   ★ 仅看「消耗>0 的有效订单」覆盖率: ").append(st.coveredPosCost)
                .append(" / ").append(posTotal).append(pct(st.coveredPosCost, posTotal)).append("\n");
        if (!st.notCoveredPosSamples.isEmpty()) {
            report.append("   ⚠️ 未命中且消耗>0 的订单（这些才是真问题，全部列出）:\n");
            for (String x : st.notCoveredPosSamples) {
                report.append("     ").append(x).append("\n");
            }
        }
        if (!st.notCoveredSamples.isEmpty()) {
            report.append("   未命中且消耗=0 的样例（最多 10 条，属正常：无消耗不进报表）:\n");
            for (String x : st.notCoveredSamples) {
                report.append("     ").append(x).append("\n");
            }
        }

        // ③ 口径吻合率
        report.append("\n③ 口径吻合率（报表按消耗加权 settle ROI vs 订单列表 settle ROI）\n");
        report.append("   可对比订单数: ").append(st.compared).append("\n");
        report.append("   差值 < 1% 的订单数: ").append(st.matched).append(" / ").append(st.compared)
                .append(pct(st.matched, st.compared)).append("\n");
        report.append("   差值 ≥ 1% 的订单数: ").append(st.mismatches.size())
                .append(st.mismatches.size() >= 200 ? "（截断至 200 条）" : "").append("\n");
        if (!st.matchedSamples.isEmpty()) {
            report.append("   —— 吻合样例（前 5 条）——\n");
            for (String x : st.matchedSamples) {
                report.append("     ").append(x).append("\n");
            }
        }
        if (!st.mismatches.isEmpty()) {
            report.append("   —— ⚠️ 不吻合样本（全部）——\n");
            for (String x : st.mismatches) {
                report.append("     ").append(x).append("\n");
            }
        }
        report.append("=================================================\n");
    }

    private static String pct(int a, int b) {
        return b == 0 ? "" : String.format("（%.2f%%）", 100.0 * a / b);
    }

    private static String fmt(Double v) {
        return v == null ? "null" : String.format("%.4f", v);
    }

    private static List<Long> loadAdvertiserIds(String configPath, StringBuilder report) {
        try {
            Path path = Paths.get(configPath);
            if (!Files.isReadable(path)) {
                report.append("[提示] 配置不可读，用兜底账户列表\n");
                return fallback();
            }
            Object loaded;
            try (InputStream in = Files.newInputStream(path)) {
                loaded = new Yaml().load(in);
            }
            List<Long> ids = extract(loaded);
            return ids.isEmpty() ? fallback() : ids;
        } catch (Exception e) {
            report.append("[提示] 解析配置失败(").append(e.getMessage()).append(")，用兜底账户列表\n");
            return fallback();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Long> extract(Object root) {
        List<Long> ids = new ArrayList<>();
        if (!(root instanceof Map)) {
            return ids;
        }
        Object sx = ((Map<String, Object>) root).get("suixintui");
        if (!(sx instanceof Map)) {
            return ids;
        }
        Object ri = ((Map<String, Object>) sx).get("reinvest");
        if (!(ri instanceof Map)) {
            return ids;
        }
        Object tiers = ((Map<String, Object>) ri).get("advertiser-tiers");
        if (!(tiers instanceof List)) {
            return ids;
        }
        for (Object t : (List<Object>) tiers) {
            if (!(t instanceof Map)) {
                continue;
            }
            Object adv = ((Map<String, Object>) t).get("advertiser-ids");
            if (!(adv instanceof List)) {
                continue;
            }
            for (Object idObj : (List<Object>) adv) {
                if (idObj instanceof Number) {
                    long v = ((Number) idObj).longValue();
                    if (!ids.contains(v)) {
                        ids.add(v);
                    }
                }
            }
        }
        return ids;
    }

    private static List<Long> fallback() {
        List<Long> ids = new ArrayList<>();
        for (long id : FALLBACK_ADVERTISER_IDS) {
            ids.add(id);
        }
        return ids;
    }
}
