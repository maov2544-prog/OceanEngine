package com.aolandai.oceanengine.scheduler;

import com.aolandai.oceanengine.domain.SuixintuiReinvestCandidateSelector.RoiMetric;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 随心推追投定时任务配置（前缀 {@code suixintui.reinvest}）。
 *
 * <p>账户按「档次」分组，<b>每个档次有自己的单账户日限额</b>；档次是账户的唯一来源，
 * 没有单独的顶层账户列表。</p>
 *
 * <p>对应 application.yml 示例：</p>
 * <pre>
 * suixintui:
 *   reinvest:
 *     advertiser-tiers:
 *       - name: 10000档
 *         max-amount-per-advertiser-per-day: 10000
 *         advertiser-ids: [1779106144470100, 1778533513439244]
 *       - name: 20000档
 *         max-amount-per-advertiser-per-day: 20000
 *         advertiser-ids: [1823379540880396]
 *       - name: 15000档            # 预留档，账户暂时为空
 *         max-amount-per-advertiser-per-day: 15000
 *         advertiser-ids: []
 *     interval-ms: 600000        # 每 10 分钟一轮
 *     window-days: 7             # 拉近 7 天创建订单
 *     amount: 200                # 每单追加 200 元
 *     delivery-time: 24          # 追加 24 小时（官方允许 24/48/72/96/120/144/168）
 *     min-roi: 2.0
 *     min-budget-used-ratio: 0.5
 *     roi-metric: SETTLE_ROI     # OVERALL_PAY_ROI / SETTLE_ROI（净成交）
 *     product-roi-overrides:     # 商品级 ROI 门槛（不配 = 全部用 min-roi）
 *       3686032876161335490: 6
 *     record-store-path: D:/oceanengine/data/reinvest-records.jsonl
 *     dry-run: true              # 演练模式不真实追投；生产设 false
 *     run-once: false            # 启动后只跑一轮就退出（手动验证用）
 * </pre>
 *
 * <p>⚠️ 档次与账户列表在 YAML 里是<b>列表</b>，外部配置会<b>整体替换</b>而不是合并 ——
 * 在目标机 {@code config/application.yml} 里写 {@code advertiser-tiers} 时必须写全，
 * 漏掉的档次/账户会被静默丢弃。（{@code product-roi-overrides} 是 <b>Map</b>，
 * 整体不写会回退成「空」，同样要小心。）</p>
 */
@Validated
@ConfigurationProperties(prefix = "suixintui.reinvest")
public class SuixintuiReinvestProperties {

    /** 千川 OpenAPI 地址，默认官方生产地址。 */
    @NotBlank
    private String apiBaseUrl = "https://api.oceanengine.com";

    /**
     * 账户档次。每档自带单账户日限额与账户列表。
     *
     * <p>这是账户的<b>唯一</b>来源：{@link #getAdvertiserIds()} 由它展平派生。
     * 档次顺序即每轮的账户扫描顺序，先扫到的账户在单轮额度上优先。</p>
     */
    @NotEmpty(message = "advertiser-tiers 不能为空：账户只能由档次定义，至少配置一个档次")
    @Valid
    private List<AdvertiserTier> advertiserTiers = List.of();

    /** 轮次间隔（毫秒），默认 10 分钟 */
    @Positive
    private long intervalMs = 600_000L;

    /** 启动后多久跑第一轮（毫秒），默认 10 秒 */
    @Min(0)
    private long initialDelayMs = 10_000L;

    /** 每次拉取「创建时间近 N 天」的订单 */
    @Positive
    private int windowDays = 7;

    /** 每单追加金额（元），默认 200 */
    @Positive
    private long amount = 200L;

    /** 追加投放时长（小时），必填，允许 24/48/72/96/120/144/168，默认 24 */
    @NotNull
    private Double deliveryTime = 24.0;

    /** 净成交 ROI 下限 */
    @DecimalMin("0.0")
    private double minRoi = 2.0;

    /**
     * 整体消耗下限（元）：低于这个值的订单<b>一律不追投</b>（2026-09-30 老板要求）。
     *
     * <p>理由：消耗太少的订单还没跑出有效数据，追投意义不大；而且它们很容易
     * 因为「本轮增量占总预算比例高」而被分支②误触发
     * （{@code (2×C_now − C_prev)/B}，消耗越小这个比值越容易虚高）。</p>
     *
     * <p>判定放在 ROI 筛选同一步（{@code 消耗 < minStatCost} 直接排除，含边界：
     * 正好等于下限<b>保留</b>）。消耗为 null 时同样排除。</p>
     */
    @DecimalMin("0.0")
    private double minStatCost = 100.0;

    /** 整体消耗 / 投放总金额 下限 */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minBudgetUsedRatio = 0.5;

    /**
     * 第二条路径（ROAS）开关（2026-10-05 老板需求）。
     *
     * <p>启用后，追投筛选在原有「使用率」判定之外，<b>并行</b>增加一条路径（OR，不替代）：</p>
     * <pre>
     *   消耗 / 投放总金额       &gt;= {@link #roasMinUsageRatio}   且
     *   净成交金额 / 投放总金额 &gt;= ROI 门槛（复用 {@link #minRoi} + {@link #productRoiOverrides}）
     * </pre>
     *
     * <p>为 {@code false} 时第二条路径完全不参与判定，行为与改造前<b>完全一致</b>。
     * 判定逻辑见 {@link com.aolandai.oceanengine.domain.RoasRule}。</p>
     */
    private boolean roasRuleEnabled = true;

    /**
     * 第二条路径（ROAS）的消耗使用率下限（如 0.5 表示 消耗/投放总金额 ≥ 50%）。
     *
     * <p>只有 {@link #roasRuleEnabled} 为 true 时生效。净成交金额/投放总金额 的门槛不在这里配，
     * 而是复用 ROI 门槛（{@link #minRoi} + {@link #productRoiOverrides}）。</p>
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double roasMinUsageRatio = 0.5;

    /** ROI 口径，默认净成交 ROI */
    private RoiMetric roiMetric = RoiMetric.SETTLE_ROI;

    /**
     * 商品级 ROI 门槛覆盖（2026-10-04 老板需求）：少量特定商品要求更高的 ROI 才允许追投。
     *
     * <p>key = 商品 ID（{@code product_info.product_id}），value = 该商品的 ROI 门槛。
     * 例如生产要配：</p>
     * <pre>
     * product-roi-overrides:
     *   3686032876161335490: 6
     * </pre>
     *
     * <p>判定口径：命中 → 用配的值；<b>未命中（含拿不到商品 ID）→ 回退全局 {@link #minRoi}</b>。
     * 配的值可以高于也可以低于全局值（纯覆盖，不校验方向）。</p>
     *
     * <p>⚠️ <b>默认为空 = 功能不生效</b>，行为与改造前完全一致 —— 刻意如此，
     * 生产配置漏写这个 key 时不会静默改变追投行为。</p>
     *
     * <p>⚠️ 这是 <b>Map</b>（不是 List），但外部配置同样是<b>整体替换</b>而不是合并；
     * 单独一个 key 写错不会影响其它 key，但整个 key 不写就回退到「空」。
     * 启动横幅会打印实际生效的映射，见 {@code ProductRoiRule#describe()}。</p>
     */
    private Map<Long, Double> productRoiOverrides = new LinkedHashMap<>();

    /** 单订单每日最多占用的追投资金（元，PENDING/UNKNOWN 也计入） */
    @Positive
    private long maxAmountPerOrderPerDay = 600L;

    /** 单轮最多占用的追投资金（元），所有账户共享 */
    @Positive
    private long maxAmountPerRound = 1_000L;

    /** 单订单每日最多追投次数 */
    @Positive
    private int maxReinvestCountPerOrderPerDay = 3;

    /** 同一订单两次追投的最小间隔（分钟） */
    @Min(1)
    private long cooldownMinutes = 60L;

    /** 幂等流水文件路径 */
    @NotNull
    private Path recordStorePath = Path.of("data", "reinvest-records.jsonl");

    /**
     * 「每轮每单整体消耗」快照文件路径。
     *
     * <p>追投筛选的分支②要算 {@code (2×本轮消耗 − 上一轮消耗)/投放总金额}，
     * 而官方接口只给当前累计消耗，所以上一轮的值必须自己存。</p>
     *
     * <p>⚠️ 与流水一样必须写<b>绝对路径</b>：相对路径会随工作目录漂移，重启后读不到。
     * （C_prev 全部缺失不会再多花钱 —— 按「本轮增量 = 0」处理，只会退化成保守的分支①，
     * 见 {@link com.aolandai.oceanengine.domain.UsageRatioRule}；但仍会导致分支② 失效。）</p>
     */
    @NotNull
    private Path consumeSnapshotPath = Path.of("data", "reinvest-consume-snapshot.jsonl");

    /** 快照保留天数：超过这个天数没更新的条目会被清理，避免文件无限增长 */
    @Min(1)
    private int snapshotRetentionDays = 7;

    /** 演练模式：只打印计划不真实追投；生产部署需显式设为 false */
    private boolean dryRun = true;

    /** 单轮模式：启动后执行一轮立即退出（手动验证 / 定时器外调用） */
    private boolean runOnce = false;

    /** 进程锁路径；同一主机只允许一个实例执行轮次。 */
    @NotNull
    private Path instanceLockPath = Path.of("data", "reinvest.lock");

    /**
     * 追投阈值回调（webhook）。
     *
     * <p>某订单「当日」追投次数或金额达到阈值时向下游 POST 一次 JSON。
     * <b>只通知，不改变追投行为</b> —— 阈值低于日上限时，通知之后订单仍会继续被追投。</p>
     */
    @Valid
    private Webhook webhook = new Webhook();

    // ==================== 账户档次 ====================

    /**
     * 一个账户档次：一组共享同一个「单账户日限额」的千川账户。
     *
     * <p>{@code advertiserIds} 允许为空 —— 用于预留档（先占好金额，账户后补）。</p>
     */
    public static class AdvertiserTier {

        /** 档次名称，仅用于日志与报错可读性，例如「10000档」 */
        @NotBlank(message = "档次名称不能为空")
        private String name;

        /** 本档内每个账户的每日最多占用追投资金（元，PENDING/UNKNOWN 也计入） */
        @Positive(message = "档次日限额必须为正数")
        private long maxAmountPerAdvertiserPerDay;

        /** 本档包含的千川业务账户 ID；可为空（预留档） */
        private List<Long> advertiserIds = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getMaxAmountPerAdvertiserPerDay() {
            return maxAmountPerAdvertiserPerDay;
        }

        public void setMaxAmountPerAdvertiserPerDay(long maxAmountPerAdvertiserPerDay) {
            this.maxAmountPerAdvertiserPerDay = maxAmountPerAdvertiserPerDay;
        }

        public List<Long> getAdvertiserIds() {
            return advertiserIds;
        }

        public void setAdvertiserIds(List<Long> advertiserIds) {
            this.advertiserIds = advertiserIds;
        }

        @Override
        public String toString() {
            return name + "(日限额 " + maxAmountPerAdvertiserPerDay + " 元, 账户 "
                    + (advertiserIds == null ? "[]" : advertiserIds) + ")";
        }
    }

    // ==================== 追投阈值回调（webhook） ====================

    /**
     * 追投阈值回调配置（前缀 {@code suixintui.reinvest.webhook}）。
     *
     * <p>语义：某订单在<b>当日</b>的追投次数或追投金额达到阈值时，向下游地址 POST 一次 JSON，
     * 用于触发另一个程序的功能。</p>
     *
     * <ul>
     *   <li>计数口径与资金护栏一致：PENDING / DONE / UNKNOWN 都算，FAILED 不算；</li>
     *   <li>同一订单同一天只通知一次；已通知记录<b>落盘</b>，重启不重复发，且会补发漏发的；</li>
     *   <li>{@code dry-run=true} 时<b>不发送</b>（演练不应触发下游程序）；</li>
     *   <li>发送失败只记日志、下一轮重试，<b>绝不影响追投轮次</b>。</li>
     * </ul>
     */
    public static class Webhook {

        /** 是否启用。false 时不做任何扫描与请求。 */
        private boolean enabled = false;

        /** 下游地址，必须 http/https 开头；启用时必填。 */
        private String url;

        /** 当日追投次数阈值（达到即触发，含等于） */
        @Positive
        private int countThreshold = 5;

        /** 当日追投金额阈值（元，达到即触发，含等于） */
        @Positive
        private long amountThreshold = 1000L;

        /** 单次请求超时（毫秒） */
        @Positive
        private int timeoutMs = 5000;

        /**
         * 已通知流水（JSONL）。
         *
         * <p>必须用绝对路径：相对路径会随工作目录漂移，重启后读不到已通知记录，
         * 会把当天所有已达标订单<b>再发一遍</b>。</p>
         */
        @NotNull
        private Path notifyLogPath = Path.of("data", "reinvest-webhook.jsonl");

        /** 可选自定义请求头（对方要求鉴权时用），例如 {@code Authorization: Bearer xxx} */
        private Map<String, String> headers = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getCountThreshold() {
            return countThreshold;
        }

        public void setCountThreshold(int countThreshold) {
            this.countThreshold = countThreshold;
        }

        public long getAmountThreshold() {
            return amountThreshold;
        }

        public void setAmountThreshold(long amountThreshold) {
            this.amountThreshold = amountThreshold;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Path getNotifyLogPath() {
            return notifyLogPath;
        }

        public void setNotifyLogPath(Path notifyLogPath) {
            this.notifyLogPath = notifyLogPath;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        @Override
        public String toString() {
            return (enabled ? "启用 → " + url : "未启用")
                    + "(阈值 " + countThreshold + " 次 / " + amountThreshold + " 元)";
        }
    }

    /** 全部账户，按档次顺序展平并去重。派生值，只读。 */
    public List<Long> getAdvertiserIds() {        if (advertiserTiers == null) {
            return List.of();
        }
        return advertiserTiers.stream()
                .filter(Objects::nonNull)
                .map(AdvertiserTier::getAdvertiserIds)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 查账户所属档次。
     *
     * @return 该账户的档次；未在任何档次中配置时返回 {@code null}
     */
    public AdvertiserTier tierOf(Long advertiserId) {
        if (advertiserId == null || advertiserTiers == null) {
            return null;
        }
        for (AdvertiserTier tier : advertiserTiers) {
            if (tier != null && tier.getAdvertiserIds() != null
                    && tier.getAdvertiserIds().contains(advertiserId)) {
                return tier;
            }
        }
        return null;
    }

    /** 每个档次的账户个数与日限额概览，用于启动日志。 */
    public String describeTiers() {
        if (advertiserTiers == null || advertiserTiers.isEmpty()) {
            return "(未配置档次)";
        }
        return advertiserTiers.stream()
                .map(t -> t == null ? "(空档次)"
                        : t.getName() + "=" + (t.getAdvertiserIds() == null
                                ? 0 : t.getAdvertiserIds().size()) + " 个账户/日上限 "
                                + t.getMaxAmountPerAdvertiserPerDay() + " 元")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // ==================== getter / setter ====================

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public List<AdvertiserTier> getAdvertiserTiers() {
        return advertiserTiers;
    }

    public void setAdvertiserTiers(List<AdvertiserTier> advertiserTiers) {
        this.advertiserTiers = advertiserTiers;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getWindowDays() {
        return windowDays;
    }

    public void setWindowDays(int windowDays) {
        this.windowDays = windowDays;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public Double getDeliveryTime() {
        return deliveryTime;
    }

    public void setDeliveryTime(Double deliveryTime) {
        this.deliveryTime = deliveryTime;
    }

    public double getMinRoi() {
        return minRoi;
    }

    public void setMinRoi(double minRoi) {
        this.minRoi = minRoi;
    }

    public double getMinStatCost() {
        return minStatCost;
    }

    public void setMinStatCost(double minStatCost) {
        this.minStatCost = minStatCost;
    }

    public double getMinBudgetUsedRatio() {
        return minBudgetUsedRatio;
    }

    public void setMinBudgetUsedRatio(double minBudgetUsedRatio) {
        this.minBudgetUsedRatio = minBudgetUsedRatio;
    }

    public boolean isRoasRuleEnabled() {
        return roasRuleEnabled;
    }

    public void setRoasRuleEnabled(boolean roasRuleEnabled) {
        this.roasRuleEnabled = roasRuleEnabled;
    }

    public double getRoasMinUsageRatio() {
        return roasMinUsageRatio;
    }

    public void setRoasMinUsageRatio(double roasMinUsageRatio) {
        this.roasMinUsageRatio = roasMinUsageRatio;
    }

    public RoiMetric getRoiMetric() {
        return roiMetric;
    }

    public void setRoiMetric(RoiMetric roiMetric) {
        this.roiMetric = roiMetric;
    }

    public Map<Long, Double> getProductRoiOverrides() {
        return productRoiOverrides;
    }

    public void setProductRoiOverrides(Map<Long, Double> productRoiOverrides) {
        this.productRoiOverrides = productRoiOverrides;
    }

    /**
     * 商品级 ROI 覆盖规则的启动自检。
     *
     * <p>只拦<b>明确写错</b>的：商品 ID 为空、阈值为 null 或 &lt;= 0。
     * <b>不校验</b>「阈值是否高于全局」—— 需求确认「都能配」（可以配得比全局低），
     * 所以这里只管合法性，不管方向。</p>
     *
     * <p>⚠️ 校验的是「配了的内容是否合法」，<b>校验不出「漏配商品」</b> ——
     * 漏掉的商品会静默回退全局门槛。要发现漏配只能人工核对启动横幅打印的映射。</p>
     */
    @AssertTrue(message = "product-roi-overrides 里的商品 ID 不能为空，ROI 阈值必须大于 0"
            + "（格式 product-roi-overrides: {商品ID: ROI阈值}）")
    public boolean isProductRoiOverridesWellFormed() {
        if (productRoiOverrides == null || productRoiOverrides.isEmpty()) {
            return true;
        }
        for (Map.Entry<Long, Double> e : productRoiOverrides.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造商品级 ROI 覆盖规则（把配置翻译成领域对象，供筛选/日报复用同一份口径）。
     *
     * <p>放在这里而不是让各处自己 new，是为了让「未命中回退 {@link #minRoi}」这个口径只有一处实现。</p>
     */
    public com.aolandai.oceanengine.domain.ProductRoiRule buildProductRoiRule() {
        return new com.aolandai.oceanengine.domain.ProductRoiRule(productRoiOverrides, minRoi);
    }

    public long getMaxAmountPerOrderPerDay() {
        return maxAmountPerOrderPerDay;
    }

    public void setMaxAmountPerOrderPerDay(long maxAmountPerOrderPerDay) {
        this.maxAmountPerOrderPerDay = maxAmountPerOrderPerDay;
    }

    public long getMaxAmountPerRound() {
        return maxAmountPerRound;
    }

    public void setMaxAmountPerRound(long maxAmountPerRound) {
        this.maxAmountPerRound = maxAmountPerRound;
    }

    public int getMaxReinvestCountPerOrderPerDay() {
        return maxReinvestCountPerOrderPerDay;
    }

    public void setMaxReinvestCountPerOrderPerDay(int maxReinvestCountPerOrderPerDay) {
        this.maxReinvestCountPerOrderPerDay = maxReinvestCountPerOrderPerDay;
    }

    public long getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void setCooldownMinutes(long cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
    }

    public Path getRecordStorePath() {
        return recordStorePath;
    }

    public void setRecordStorePath(Path recordStorePath) {
        this.recordStorePath = recordStorePath;
    }

    public Path getConsumeSnapshotPath() {
        return consumeSnapshotPath;
    }

    public void setConsumeSnapshotPath(Path consumeSnapshotPath) {
        this.consumeSnapshotPath = consumeSnapshotPath;
    }

    public int getSnapshotRetentionDays() {
        return snapshotRetentionDays;
    }

    public void setSnapshotRetentionDays(int snapshotRetentionDays) {
        this.snapshotRetentionDays = snapshotRetentionDays;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isRunOnce() {
        return runOnce;
    }

    public void setRunOnce(boolean runOnce) {
        this.runOnce = runOnce;
    }

    public Path getInstanceLockPath() {
        return instanceLockPath;
    }

    public void setInstanceLockPath(Path instanceLockPath) {
        this.instanceLockPath = instanceLockPath;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public void setWebhook(Webhook webhook) {
        this.webhook = webhook;
    }

    /** webhook 概览，用于启动日志（让「到底有没有生效」一眼可见） */
    public String describeWebhook() {
        if (webhook == null || !webhook.isEnabled()) {
            return "未启用";
        }
        return "启用 → " + webhook.getUrl()
                + "（当日阈值 " + webhook.getCountThreshold() + " 次 / "
                + webhook.getAmountThreshold() + " 元）"
                + (dryRun ? "，dry-run 期间不发送" : "");
    }

    /**
     * 第二条路径（ROAS）概览，用于启动日志 / 每轮横幅。
     *
     * <p>关闭时明确说「未启用」，避免「配了但没生效」被误以为生效（与 webhook 的写法一致）。</p>
     */
    public String describeRoasRule() {
        if (!roasRuleEnabled) {
            return "未启用";
        }
        return "启用，消耗/投放总金额≥" + roasMinUsageRatio
                + " 且 净成交金额/投放总金额≥各自ROI门槛";
    }

    // ==================== 启动自检 ====================

    /**
     * 启动时拒绝会让限额失效的配置：非法的追加时长、小于单笔金额的上限、档次与账户配置错误。
     *
     * <p>校验不了的：档次里「漏写某个账户」无法检测（列表整体替换，没有基线可比），
     * 只能靠启动日志打印的分档概览人工核对。</p>
     */
    @AssertTrue(message = "资金上限/档次配置不合法：日限额不得小于单笔追投金额，"
            + "delivery-time 必须为官方允许值，档次不得为空、名称必填、账户不得跨档重复")
    public boolean isSafetyLimitsConsistent() {
        boolean deliveryTimeAllowed = deliveryTime != null
                && List.of(24.0, 48.0, 72.0, 96.0, 120.0, 144.0, 168.0)
                .contains(deliveryTime);
        boolean limitsPositive = amount > 0
                && maxAmountPerOrderPerDay > 0
                && maxAmountPerRound > 0
                && maxReinvestCountPerOrderPerDay > 0
                && cooldownMinutes > 0;
        return deliveryTimeAllowed
                && limitsPositive
                && tiersConsistent()
                && maxAmountPerOrderPerDay >= amount
                && maxAmountPerRound >= amount
                && (dryRun || instanceLockPath != null);
    }

    /**
     * 档次配置自检：档次非空、每档名称非空且日限额 ≥ 单笔金额、账户 ID 合法且不跨档重复、
     * 展平后至少有一个账户。
     */
    private boolean tiersConsistent() {
        if (advertiserTiers == null || advertiserTiers.isEmpty()) {
            return false;
        }
        int declared = 0;
        for (AdvertiserTier tier : advertiserTiers) {
            if (tier == null || tier.getName() == null || tier.getName().isBlank()) {
                return false;
            }
            // 同时保证 > 0（amount 已确认为正）与「日限额 ≥ 单笔金额」
            if (tier.getMaxAmountPerAdvertiserPerDay() < amount) {
                return false;
            }
            List<Long> ids = tier.getAdvertiserIds();
            if (ids == null) {
                return false;
            }
            for (Long id : ids) {
                if (id == null || id <= 0) {
                    return false;
                }
            }
            declared += ids.size();
        }
        List<Long> flattened = getAdvertiserIds();
        // 展平后数量不变 ⇒ 没有账户同时出现在两个档次里；非空 ⇒ 确实有账户要投
        return !flattened.isEmpty() && flattened.size() == declared;
    }

    /**
     * webhook 自检：启用时必须有 http(s) 地址。
     *
     * <p>刻意让「启用了但没填地址」在<b>启动阶段</b>就失败：否则会退化成每轮都往空地址发请求、
     * 只在日志里报错，很难发现。阈值与超时的正数校验由 {@code @Positive} 负责。</p>
     */
    @AssertTrue(message = "webhook 配置不合法：enabled=true 时必须配置 http:// 或 https:// 开头的 url")
    public boolean isWebhookConsistent() {
        if (webhook == null || !webhook.isEnabled()) {
            return true;
        }
        String url = webhook.getUrl();
        return url != null && !url.isBlank()
                && (url.startsWith("http://") || url.startsWith("https://"));
    }
}
