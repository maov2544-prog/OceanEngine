package com.aolandai.oceanengine.report;

import com.aolandai.oceanengine.storage.ReinvestRecord;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderGetV10FilteringStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 每日追投明细表（日报）配置，前缀 {@code suixintui.report}。
 *
 * <p>日报做两件事：① 每日定点把<b>前一日</b>的追投流水按订单聚合成一张表；
 * ② 基于 {@code .xls} 模板填写后另存到「按月分文件夹」的路径下。</p>
 *
 * <p>对应 application.yml 示例：</p>
 * <pre>
 * suixintui:
 *   report:
 *     enabled: true
 *     cron: "0 1 0 * * ?"                 # 每日 00:01
 *     template-path: D:/oceanengine/data/currentreport/每日追投明细表模板.xls
 *     output-dir: D:/oceanengine/data
 *     folder-pattern: "{month}追投日报"     # → 2026-09追投日报
 *     file-name-pattern: "{date}追投明细表.xls"   # → 2026-09-23追投明细表.xls
 *     date-pattern: yyyy-MM-dd            # 「时间」列写什么格式
 *     include-statuses: [PENDING, DONE, UNKNOWN]
 *     advertiser-names:
 *       1823379540880396: 官方【母婴商品】
 *     catch-up-on-start: true
 *     run-now: false
 * </pre>
 *
 * <p>所有字段都能从目标机 {@code config/application.yml} 覆盖，改参数<b>不需要重新打包</b>。</p>
 */
@Validated
@ConfigurationProperties(prefix = "suixintui.report")
public class ReinvestReportProperties {

    /** 是否启用日报（关掉后既不生成也不补跑，适合临时停用） */
    private boolean enabled = true;

    /** 生成时机（Spring 6 段 cron：秒 分 时 日 月 周），默认每日 00:01:00 */
    @NotBlank
    private String cron = "0 1 0 * * ?";

    /**
     * .xls 模板路径。日报读它、填数据、另存——模板里的表头与样式会被原样保留。
     *
     * <p>⚠️ 模板缺失<b>不会</b>导致启动失败（那样会连累追投本身），
     * 只在生成报表时报错并在日志里说明。</p>
     */
    @NotNull
    private Path templatePath = Path.of("D:/oceanengine/data/currentreport/每日追投明细表模板.xls");

    /** 报表根目录；其下按 {@link #folderPattern} 再分「月份文件夹」 */
    @NotNull
    private Path outputDir = Path.of("D:/oceanengine/data");

    /** 月份文件夹名模板，占位符 {@code {month}} = yyyy-MM */
    @NotBlank
    private String folderPattern = "{month}追投日报";

    /** 文件名模板，占位符 {@code {date}} = yyyy-MM-dd、{@code {month}} = yyyy-MM */
    @NotBlank
    private String fileNamePattern = "{date}追投明细表.xls";

    /** 「时间」列的日期格式 */
    @NotBlank
    private String datePattern = "yyyy-MM-dd";

    /**
     * 哪些状态的流水计入报表。
     *
     * <p>默认保守口径：PENDING（已落盘待调用）、DONE（成功）、UNKNOWN（网络超时，可能已生效）
     * 都算——与资金护栏一致，避免「实际花了钱但报表里没有」。
     * FAILED 表示接口明确业务失败，不计入。</p>
     */
    @NotNull
    private List<ReinvestRecord.Status> includeStatuses =
            List.of(ReinvestRecord.Status.PENDING,
                    ReinvestRecord.Status.DONE,
                    ReinvestRecord.Status.UNKNOWN);

    /**
     * 账户 ID → 投放账户名的映射，用于填「投放账户」列。
     *
     * <p>写在这里是为了让报表生成<b>不依赖任何外部接口</b>；没配到的账户会回退显示账户 ID，
     * 并在日志里列出来提醒补配。</p>
     */
    private Map<Long, String> advertiserNames = new LinkedHashMap<>();

    /** 启动时若「昨日报表」不存在则补生成一次（避免 00:01 时进程不在而漏一天） */
    private boolean catchUpOnStart = true;

    /** 置 true 时启动即生成一次报表，用于部署后立即验证（生产保持 false） */
    private boolean runNow = false;

    /** 配合 {@link #runNow}：指定生成哪一天，不填则默认「昨天」 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate runNowDate;

    // ==================== 已结束达标订单 sheet（2026-09-30 新增） ====================

    /**
     * 是否在日报里生成「已结束达标订单」sheet。
     *
     * <p>⚠️ 这个 sheet 需要<b>查接口</b>（遍历账户 × 状态），与主报表「不调任何外部接口」的设计不同。
     * 拉取失败只让该 sheet 留空并打日志，<b>绝不阻塞主报表</b>。</p>
     */
    private boolean finishedOrdersEnabled = true;

    /** 新 sheet 的名称（Excel 限制：≤31 字符，且不能含 {@code [ ] : * ? / \}） */
    @NotBlank
    private String finishedOrdersSheetName = "已结束达标订单";

    /**
     * 「已结束达标订单」的<b>七天底表</b>路径（覆盖式 JSONL，每次全量替换）。
     *
     * <p>⚠️ 必须用绝对路径：相对路径会随工作目录漂移，重启后读不到，
     * 会导致所有订单都被当成「新增」而灌进日报 sheet。</p>
     */
    @NotNull
    private Path finishedOrdersStorePath =
            Path.of("D:/oceanengine/data/report-finished-orders.jsonl");

    /** 底表回看窗口（天）：拉取「创建时间近 N 天」的结束态订单。接口只能按创建时间过滤，见跟踪器注释 */
    @Min(1)
    private int finishedOrdersLookbackDays = 7;

    /**
     * 视为「已结束」的订单状态（FINISHED 投放完成 / FROZEN 投放终止 / OVER 投放结束）。
     *
     * <p>业务口径：<b>只要「投放完成 FINISHED」</b>，投放终止与投放结束不算正常跑完，不统计。
     * 所以默认值只有 FINISHED —— 与 {@code application.yml} 保持一致，
     * 避免「配置里没写这个 key 时回退成三状态」的静默差异。</p>
     *
     * <p>⚠️ 这是<b>列表</b>，外部配置里是<b>整体替换</b>而不是合并 —— 要改就写全，
     * 漏掉的会被静默丢弃（与 {@code advertiser-tiers} 同一个坑）。</p>
     */
    @NotEmpty
    private List<QianchuanAwemeUniPromotionOrderGetV10FilteringStatus> finishedOrdersStatuses =
            List.of(QianchuanAwemeUniPromotionOrderGetV10FilteringStatus.FINISHED);

    // ==================== 路径解析 ====================

    /** 该日期对应的月份文件夹，例如 {@code D:/oceanengine/data/2026-09追投日报} */
    public Path resolveFolder(LocalDate date) {
        return outputDir.resolve(expand(folderPattern, date));
    }

    /** 该日期对应的报表文件名，例如 {@code 2026-09-23追投明细表.xls} */
    public String resolveFileName(LocalDate date) {
        return expand(fileNamePattern, date);
    }

    /** 该日期对应的报表完整路径 */
    public Path resolveOutputFile(LocalDate date) {
        return resolveFolder(date).resolve(resolveFileName(date));
    }

    /** 「时间」列要写的文本，例如 {@code 2026-09-23} */
    public String formatDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern(datePattern));
    }

    private static String expand(String pattern, LocalDate date) {
        return pattern
                .replace("{date}", date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                .replace("{month}", date.format(DateTimeFormatter.ofPattern("yyyy-MM")));
    }

    // ==================== 自检 ====================

    /**
     * 启动自检：cron 必须是合法的 6 段 Spring cron。
     *
     * <p><b>无论 enabled 与否都校验</b>：{@code @Scheduled} 的 cron 值在容器启动时就要解析，
     * 留空或写错会让启动以一个难懂的占位符解析异常结束。这里先拦住，
     * 报出来的是「配置写错了」而不是「cron 解析失败」。</p>
     *
     * <p>注意<b>不校验</b>模板文件是否存在——模板缺失只影响日报，
     * 不该连累追投服务起不来。</p>
     */
    @AssertTrue(message = "report.cron 必须是合法的 6 段 cron 表达式（秒 分 时 日 月 周），不能留空")
    public boolean isCronWellFormed() {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        if (cron.trim().split("\\s+").length != 6) {
            return false;
        }
        try {
            org.springframework.scheduling.support.CronExpression.parse(cron);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 供启动日志用的一行概览。输出路径用「今天」渲染成真实路径，方便直接核对目录。 */
    public String describe() {
        if (!enabled) {
            return "已停用（enabled=false）";
        }
        String statuses = includeStatuses == null ? "[]"
                : includeStatuses.stream().map(Enum::name).collect(Collectors.joining("/"));
        return "cron=" + cron
                + ", 输出示例=" + resolveOutputFile(LocalDate.now())
                + ", 模板=" + templatePath
                + ", 计入状态=" + statuses
                + ", 已配账户名 " + (advertiserNames == null ? 0 : advertiserNames.size()) + " 个";
    }

    // ==================== getter / setter ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public Path getTemplatePath() {
        return templatePath;
    }

    public void setTemplatePath(Path templatePath) {
        this.templatePath = templatePath;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(Path outputDir) {
        this.outputDir = outputDir;
    }

    public String getFolderPattern() {
        return folderPattern;
    }

    public void setFolderPattern(String folderPattern) {
        this.folderPattern = folderPattern;
    }

    public String getFileNamePattern() {
        return fileNamePattern;
    }

    public void setFileNamePattern(String fileNamePattern) {
        this.fileNamePattern = fileNamePattern;
    }

    public String getDatePattern() {
        return datePattern;
    }

    public void setDatePattern(String datePattern) {
        this.datePattern = datePattern;
    }

    public List<ReinvestRecord.Status> getIncludeStatuses() {
        return includeStatuses;
    }

    public void setIncludeStatuses(List<ReinvestRecord.Status> includeStatuses) {
        this.includeStatuses = includeStatuses;
    }

    public Map<Long, String> getAdvertiserNames() {
        return advertiserNames;
    }

    public void setAdvertiserNames(Map<Long, String> advertiserNames) {
        this.advertiserNames = advertiserNames;
    }

    public boolean isCatchUpOnStart() {
        return catchUpOnStart;
    }

    public void setCatchUpOnStart(boolean catchUpOnStart) {
        this.catchUpOnStart = catchUpOnStart;
    }

    public boolean isRunNow() {
        return runNow;
    }

    public void setRunNow(boolean runNow) {
        this.runNow = runNow;
    }

    public LocalDate getRunNowDate() {
        return runNowDate;
    }

    public void setRunNowDate(LocalDate runNowDate) {
        this.runNowDate = runNowDate;
    }

    public boolean isFinishedOrdersEnabled() {
        return finishedOrdersEnabled;
    }

    public void setFinishedOrdersEnabled(boolean finishedOrdersEnabled) {
        this.finishedOrdersEnabled = finishedOrdersEnabled;
    }

    public String getFinishedOrdersSheetName() {
        return finishedOrdersSheetName;
    }

    public void setFinishedOrdersSheetName(String finishedOrdersSheetName) {
        this.finishedOrdersSheetName = finishedOrdersSheetName;
    }

    public Path getFinishedOrdersStorePath() {
        return finishedOrdersStorePath;
    }

    public void setFinishedOrdersStorePath(Path finishedOrdersStorePath) {
        this.finishedOrdersStorePath = finishedOrdersStorePath;
    }

    public int getFinishedOrdersLookbackDays() {
        return finishedOrdersLookbackDays;
    }

    public void setFinishedOrdersLookbackDays(int finishedOrdersLookbackDays) {
        this.finishedOrdersLookbackDays = finishedOrdersLookbackDays;
    }

    public List<QianchuanAwemeUniPromotionOrderGetV10FilteringStatus> getFinishedOrdersStatuses() {
        return finishedOrdersStatuses;
    }

    public void setFinishedOrdersStatuses(
            List<QianchuanAwemeUniPromotionOrderGetV10FilteringStatus> finishedOrdersStatuses) {
        this.finishedOrdersStatuses = finishedOrdersStatuses;
    }
}
