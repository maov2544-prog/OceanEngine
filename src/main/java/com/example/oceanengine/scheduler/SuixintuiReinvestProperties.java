package com.example.oceanengine.scheduler;

import com.example.oceanengine.domain.SuixintuiReinvestCandidateSelector.RoiMetric;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/**
 * 随心推追投定时任务配置（前缀 {@code suixintui.reinvest}）。
 *
 * <p>对应 application.yml 示例：</p>
 * <pre>
 * suixintui:
 *   reinvest:
 *     advertiser-ids: [1779106144470100]
 *     interval-ms: 600000        # 每 10 分钟一轮
 *     window-days: 7             # 拉近 7 天创建订单
 *     amount: 200                # 每单追加 200 元
 *     delivery-time: 24          # 追加 24 小时（官方允许 24/48/72/96/120/144/168）
 *     min-roi: 2.0
 *     min-budget-used-ratio: 0.5
 *     roi-metric: SETTLE_ROI     # OVERALL_PAY_ROI / SETTLE_ROI（净成交）
 *     record-store-path: ./data/reinvest-records.jsonl
 *     dry-run: true              # 演练模式不真实追投；生产设 false
 *     run-once: false            # 启动后只跑一轮就退出（手动验证用）
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "suixintui.reinvest")
public class SuixintuiReinvestProperties {

    /** 千川 OpenAPI 地址，默认官方生产地址。 */
    @NotBlank
    private String apiBaseUrl = "https://api.oceanengine.com";

    /** 固定的一批千川业务账户 ID（随心推投放账户 id） */
    @NotEmpty
    private List<Long> advertiserIds = List.of(1779106144470100L);

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

    /** 整体消耗 / 投放总金额 下限 */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minBudgetUsedRatio = 0.5;

    /** ROI 口径，默认净成交 ROI */
    private RoiMetric roiMetric = RoiMetric.SETTLE_ROI;

    /** 单订单每日最多占用的追投资金（元，PENDING/UNKNOWN 也计入） */
    @Positive
    private long maxAmountPerOrderPerDay = 600L;

    /** 单账户每日最多占用的追投资金（元，PENDING/UNKNOWN 也计入） */
    @Positive
    private long maxAmountPerAdvertiserPerDay = 5_000L;

    /** 单轮最多占用的追投资金（元） */
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

    /** 演练模式：只打印计划不真实追投；生产部署需显式设为 false */
    private boolean dryRun = true;

    /** 单轮模式：启动后执行一轮立即退出（手动验证 / 定时器外调用） */
    private boolean runOnce = false;

    /** 进程锁路径；同一主机只允许一个实例执行轮次。 */
    @NotNull
    private Path instanceLockPath = Path.of("data", "reinvest.lock");

    // ==================== getter / setter ====================

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public List<Long> getAdvertiserIds() {
        return advertiserIds;
    }

    public void setAdvertiserIds(List<Long> advertiserIds) {
        this.advertiserIds = advertiserIds;
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

    public double getMinBudgetUsedRatio() {
        return minBudgetUsedRatio;
    }

    public void setMinBudgetUsedRatio(double minBudgetUsedRatio) {
        this.minBudgetUsedRatio = minBudgetUsedRatio;
    }

    public RoiMetric getRoiMetric() {
        return roiMetric;
    }

    public void setRoiMetric(RoiMetric roiMetric) {
        this.roiMetric = roiMetric;
    }

    public long getMaxAmountPerOrderPerDay() {
        return maxAmountPerOrderPerDay;
    }

    public void setMaxAmountPerOrderPerDay(long maxAmountPerOrderPerDay) {
        this.maxAmountPerOrderPerDay = maxAmountPerOrderPerDay;
    }

    public long getMaxAmountPerAdvertiserPerDay() {
        return maxAmountPerAdvertiserPerDay;
    }

    public void setMaxAmountPerAdvertiserPerDay(long maxAmountPerAdvertiserPerDay) {
        this.maxAmountPerAdvertiserPerDay = maxAmountPerAdvertiserPerDay;
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

    /** 启动时拒绝金额上限小于单笔金额、或非法追加时长的配置。 */
    @AssertTrue(message = "资金上限不得小于单笔追投金额，delivery-time 必须为官方允许值")
    public boolean isSafetyLimitsConsistent() {
        boolean deliveryTimeAllowed = deliveryTime != null
                && List.of(24.0, 48.0, 72.0, 96.0, 120.0, 144.0, 168.0)
                .contains(deliveryTime);
        boolean limitsPositive = amount > 0
                && maxAmountPerOrderPerDay > 0
                && maxAmountPerAdvertiserPerDay > 0
                && maxAmountPerRound > 0
                && maxReinvestCountPerOrderPerDay > 0
                && cooldownMinutes > 0;
        boolean uniqueAdvertisers = advertiserIds != null
                && advertiserIds.stream().allMatch(id -> id != null && id > 0)
                && new HashSet<>(advertiserIds).size() == advertiserIds.size();
        return deliveryTimeAllowed
                && limitsPositive
                && uniqueAdvertisers
                && maxAmountPerOrderPerDay >= amount
                && maxAmountPerAdvertiserPerDay >= amount
                && maxAmountPerRound >= amount
                && (dryRun || instanceLockPath != null);
    }
}
