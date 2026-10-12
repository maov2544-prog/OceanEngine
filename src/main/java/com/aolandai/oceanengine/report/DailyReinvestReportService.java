package com.aolandai.oceanengine.report;

import com.aolandai.oceanengine.storage.FinishedOrder;
import com.aolandai.oceanengine.storage.ReinvestRecord;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每日追投明细表生成器。
 *
 * <p>流程：读 {@code .xls} 模板 → 把指定日期的追投流水<b>按订单聚合</b> → 逐行写入 →
 * 另存到「{@code {月份}追投日报}/{@code {日期}追投明细表.xls}」。</p>
 *
 * <p>表格列（模板第 1 行是表头，数据从第 2 行开始）：</p>
 * <ol>
 *   <li>A 订单ID —— 纯文本（19 位数字写成文本，避免 Excel 丢精度）</li>
 *   <li>B 抖音名</li>
 *   <li>C 抖音号 —— 纯文本</li>
 *   <li>D 投放账户 —— 账户名（取配置映射，未配则回退账户 ID）</li>
 *   <li>E 追投次数 —— 该订单当天累计次数</li>
 *   <li>F 追投金额 —— 该订单当天累计金额（元）</li>
 *   <li>G 时间 —— 报表日期</li>
 *   <li>H 追投合计金额 —— <b>只在第 2 行填当天所有订单的总合计</b>，其余行留空</li>
 * </ol>
 *
 * <p>设计上刻意<b>不调用任何外部接口</b>：抖音名/抖音号在追投时就已写进流水，
 * 账户名来自配置。所以接口故障、订单结束都不影响出报表。</p>
 */
@Component
public class DailyReinvestReportService {

    private final ReinvestReportProperties props;
    private final ReinvestRecordStore store;

    /**
     * 「已结束达标订单」采集器。允许为 {@code null}（单测 / 手工构造时）——
     * 为 null 或功能停用时跳过该 sheet 的数据采集，主报表照常生成。
     */
    private final FinishedOrderTracker finishedOrderTracker;

    /**
     * 「已结束达标订单」复建编排。允许为 {@code null}（单测 / 手工构造时）——
     * 为 null 时跳过复建（sheet 的「复建状态」列留空）。
     */
    private final SuixintuiRebuildService rebuildService;

    /** 单测 / 手工构造用：不采集「已结束达标订单」、不复建 */
    public DailyReinvestReportService(ReinvestReportProperties props, ReinvestRecordStore store) {
        this(props, store, null, null);
    }

    /** 单测 / 手工构造用：采集「已结束达标订单」但<b>不复建</b>（复建状态列留空） */
    public DailyReinvestReportService(ReinvestReportProperties props, ReinvestRecordStore store,
                                      FinishedOrderTracker finishedOrderTracker) {
        this(props, store, finishedOrderTracker, null);
    }

    /**
     * Spring 注入用（类里有多个构造器，必须显式标注，否则容器无法判断用哪个）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DailyReinvestReportService(ReinvestReportProperties props, ReinvestRecordStore store,
                                      FinishedOrderTracker finishedOrderTracker,
                                      SuixintuiRebuildService rebuildService) {
        this.props = props;
        this.store = store;
        this.finishedOrderTracker = finishedOrderTracker;
        this.rebuildService = rebuildService;
    }

    /** 生成结果，用于日志与调用方判断 */
    public record Result(LocalDate date, Path file, int orderCount,
                         long totalCount, long totalAmount, int newlyFinishedCount) {

        public String summarize() {
            return "日报 " + date + " | 订单 " + orderCount + " 个 | 追投 " + totalCount
                    + " 次 | 合计 " + totalAmount + " 元 | 已结束达标 " + newlyFinishedCount
                    + " 个 | 文件 " + file;
        }
    }

    /**
     * 生成指定日期的追投明细表。
     *
     * <p>主表（追投明细）<b>不调任何外部接口</b>；「已结束达标订单」sheet 需要查接口，
     * 但它<b>尽力而为</b>：采集失败只让该 sheet 留空，不影响主报表。</p>
     *
     * @param date 报表归属日期（通常为「昨天」）
     * @throws IOException 模板缺失、读写失败
     */
    public Result generate(LocalDate date) throws IOException {
        List<OrderRow> rows = aggregate(date);
        long totalCount = rows.stream().mapToLong(r -> r.count).sum();
        long totalAmount = rows.stream().mapToLong(r -> r.amount).sum();

        List<FinishedOrder> newlyFinished = collectNewlyFinished(date);

        // 复建：对本日新结束且达标的订单执行自动复建（尽力而为，失败绝不影响主报表）。
        // 「复建状态」/「复建订单ID」两列据此填充（文案/订单号由 RebuildResult 决定）。
        Map<Long, RebuildResult> rebuildStatus = rebuild(newlyFinished);

        Path out = props.resolveOutputFile(date);
        writeWorkbook(date, rows, totalAmount, newlyFinished, rebuildStatus, out);

        warnUnmappedAccounts(rows);
        return new Result(date, out, rows.size(), totalCount, totalAmount, newlyFinished.size());
    }

    /**
     * 执行复建（尽力而为）。复建是旁路功能，任何异常都只打日志、返回空状态，
     * <b>绝不允许影响日报主表生成</b> —— 与 {@link #collectNewlyFinished} 同一哲学。
     *
     * @return 原订单号 → 复建结果（含「已复建」文案与复建出的新订单号）
     */
    private Map<Long, RebuildResult> rebuild(List<FinishedOrder> newlyFinished) {
        if (rebuildService == null || newlyFinished == null || newlyFinished.isEmpty()) {
            return Map.of();
        }
        try {
            return rebuildService.rebuild(newlyFinished);
        } catch (Exception e) {
            System.out.println("[日报] 复建异常（跳过，主报表照常）：" + e);
            return Map.of();
        }
    }

    /**
     * 采集「本次新结束且 ROI 达标」的订单。
     *
     * <p>双重兜底：跟踪器内部已经吞了异常，这里再兜一层 —— 日报是旁路功能，
     * 不允许因为查接口出任何意外而让整份报表生不出来。</p>
     */
    private List<FinishedOrder> collectNewlyFinished(LocalDate date) {
        if (finishedOrderTracker == null || !props.isFinishedOrdersEnabled()) {
            return List.of();
        }
        try {
            return finishedOrderTracker.collectNewlyFinished(date);
        } catch (Exception e) {
            System.out.println("[日报] 已结束订单采集异常（sheet 留空，主报表照常）：" + e);
            return List.of();
        }
    }

    // ==================== 聚合 ====================

    /** 单个订单在当天的汇总行 */
    private static final class OrderRow {
        final Long orderId;
        Long advertiserId;
        String awemeName;
        String awemeShowId;
        long count;
        long amount;

        OrderRow(Long orderId, Long advertiserId) {
            this.orderId = orderId;
            this.advertiserId = advertiserId;
        }
    }

    /**
     * 按订单聚合当天的流水。
     *
     * <p>只计入 {@code include-statuses} 里的状态（默认 PENDING/DONE/UNKNOWN，
     * 即「可能已支出」的保守口径）；FAILED 不计入。</p>
     */
    private List<OrderRow> aggregate(LocalDate date) {
        Set<ReinvestRecord.Status> included = props.getIncludeStatuses() == null
                ? EnumSet.noneOf(ReinvestRecord.Status.class)
                : EnumSet.copyOf(props.getIncludeStatuses());

        Map<Long, OrderRow> byOrder = new LinkedHashMap<>();
        for (ReinvestRecord record : store.all()) {
            if (record.getOrderId() == null
                    || record.getStatus() == null
                    || !included.contains(record.getStatus())) {
                continue;
            }
            LocalDateTime timestamp = record.parsedTimestamp();
            if (timestamp == null || !timestamp.toLocalDate().equals(date)) {
                continue;
            }
            OrderRow row = byOrder.computeIfAbsent(record.getOrderId(),
                    id -> new OrderRow(id, record.getAdvertiserId()));
            row.count++;
            row.amount += record.getAmount() == null ? 0L : record.getAmount();
            // 名字取第一条非空的（同一订单的名字不会变，老记录可能没有）
            if (row.awemeName == null) {
                row.awemeName = record.getAwemeName();
            }
            if (row.awemeShowId == null) {
                row.awemeShowId = record.getAwemeShowId();
            }
            if (row.advertiserId == null) {
                row.advertiserId = record.getAdvertiserId();
            }
        }

        List<OrderRow> rows = new ArrayList<>(byOrder.values());
        // 按投放账户分组，组内按追投金额降序（账户间按账户 ID 升序，保证输出稳定可复现）
        rows.sort(Comparator
                .comparing((OrderRow r) -> r.advertiserId == null ? Long.MAX_VALUE : r.advertiserId)
                .thenComparing(Comparator.comparingLong((OrderRow r) -> r.amount).reversed())
                .thenComparing(r -> r.orderId));
        return rows;
    }

    // ==================== 写 xls ====================

    private void writeWorkbook(LocalDate date, List<OrderRow> rows, long totalAmount,
                               List<FinishedOrder> newlyFinished, Map<Long, RebuildResult> rebuildStatus,
                               Path out)
            throws IOException {
        Path template = props.getTemplatePath();
        if (template == null || !Files.exists(template)) {
            throw new IOException("日报模板不存在：" + template
                    + "（请确认 suixintui.report.template-path）");
        }
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (InputStream in = Files.newInputStream(template);
             Workbook workbook = WorkbookFactory.create(in)) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("日报模板没有工作表：" + template);
            }
            Sheet sheet = workbook.getSheetAt(0);
            // 模板第 1 行（index 0）是表头，原样保留；数据从 index 1 开始
            CellStyle textStyle = buildDataStyle(workbook, true);
            CellStyle numberStyle = buildDataStyle(workbook, false);

            int rowIndex = 1;
            for (OrderRow row : rows) {
                Row sheetRow = obtainRow(sheet, rowIndex);
                setText(sheetRow, 0, String.valueOf(row.orderId), textStyle);
                setText(sheetRow, 1, row.awemeName, textStyle);
                setText(sheetRow, 2, row.awemeShowId, textStyle);
                setText(sheetRow, 3, advertiserName(row.advertiserId), textStyle);
                setNumber(sheetRow, 4, row.count, numberStyle);
                setNumber(sheetRow, 5, row.amount, numberStyle);
                setText(sheetRow, 6, props.formatDate(date), textStyle);
                // H 列：只有第一条数据行（第 2 行）写「所有订单的总合计」，其余留空
                if (rowIndex == 1) {
                    setNumber(sheetRow, 7, totalAmount, numberStyle);
                }
                rowIndex++;
            }

            if (rows.isEmpty()) {
                // 当天没有追投也生成文件：只有表头 + H2 = 0，
                // 保证下游推送程序每天都能拿到一份文件，不必判断「文件不存在」。
                setNumber(obtainRow(sheet, 1), 7, 0L, numberStyle);
            }

            // 第二个 sheet：「已结束达标订单」（2026-09-30 新增）。
            // 只要功能启用就建 sheet（哪怕没有数据、只有表头），保持文件结构稳定，
            // 下游不必判断「sheet 存不存在」。
            if (props.isFinishedOrdersEnabled()) {
                writeFinishedSheet(workbook, newlyFinished, rebuildStatus);
            }

            try (OutputStream os = Files.newOutputStream(out)) {
                workbook.write(os);
            }
        }
    }

    /**
     * 写「已结束达标订单」sheet。
     *
     * <p>列：订单ID / 抖音名 / 抖音号 / 投放账户 / roi / 复建状态 / 复建订单ID。
     * 订单ID、抖音号、<b>复建订单ID</b> 都用<b>文本格式</b>（{@code @}）——
     * 19 位数字若按数值处理会被 Excel 丢精度。</p>
     *
     * <p>第 6/7 列（F/G）语义由 {@link RebuildResult} 决定：
     * F 列文案（已复建 / 已有投放中订单 / 超每日上限 / 参数缺失 / 复建失败），
     * G 列只在「已复建」（新订单号）与「在投冲突」（冲突订单号）时写号，其余留空。
     * 不在 {@code rebuildStatus} 里的订单两列都留空。</p>
     *
     * @param rebuildStatus 原订单号 → 复建结果
     */
    private void writeFinishedSheet(Workbook workbook, List<FinishedOrder> orders,
                                    Map<Long, RebuildResult> rebuildStatus) {
        String name = safeSheetName(workbook, props.getFinishedOrdersSheetName());
        Sheet sheet = workbook.createSheet(name);

        CellStyle headerStyle = buildHeaderStyle(workbook);
        CellStyle textStyle = buildDataStyle(workbook, true);
        CellStyle numberStyle = buildDataStyle(workbook, false);

        Row header = sheet.createRow(0);
        String[] columns = {"订单ID", "抖音名", "抖音号", "投放账户", "roi", "复建状态", "复建订单ID"};
        for (int i = 0; i < columns.length; i++) {
            setText(header, i, columns[i], headerStyle);
        }

        int rowIndex = 1;
        for (FinishedOrder order : orders) {
            Row sheetRow = obtainRow(sheet, rowIndex);
            setText(sheetRow, 0, String.valueOf(order.getOrderId()), textStyle);
            setText(sheetRow, 1, order.getAwemeName(), textStyle);
            setText(sheetRow, 2, order.getAwemeShowId(), textStyle);
            setText(sheetRow, 3, advertiserName(order.getAdvertiserId()), textStyle);
            setDouble(sheetRow, 4, order.getRoi(), numberStyle);
            // 第 6/7 列：复建状态 + 复建订单ID。业务判断都收敛在 RebuildResult 里，这里只取值。
            // ⚠️ 复建订单ID 与订单ID 一样必须写成文本，否则 19 位数字被 Excel 丢精度。
            // G 列语义：成功→新订单号；在投冲突→冲突订单号；其它失败→留空（orderIdLabel() 返回 null）。
            RebuildResult rebuilt = rebuildStatus == null ? null : rebuildStatus.get(order.getOrderId());
            setText(sheetRow, 5, rebuilt == null ? null : rebuilt.statusLabel(), textStyle);
            setText(sheetRow, 6, rebuilt == null ? null : rebuilt.orderIdLabel(), textStyle);
            rowIndex++;
        }

        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 20 * 256);
        sheet.setColumnWidth(2, 18 * 256);
        sheet.setColumnWidth(3, 24 * 256);
        sheet.setColumnWidth(4, 10 * 256);
        sheet.setColumnWidth(5, 12 * 256);
        sheet.setColumnWidth(6, 22 * 256);

        if (orders.isEmpty()) {
            System.out.println("[日报] 「" + name + "」sheet 本次没有新增订单（只写表头）。");
        }
    }

    /** 表头名冲突时加后缀，避免 {@code createSheet} 抛异常（Excel 工作表名必须唯一） */
    private static String safeSheetName(Workbook workbook, String wanted) {
        String base = (wanted == null || wanted.isBlank()) ? "已结束达标订单" : wanted.trim();
        // Excel 限制：≤31 字符，且不能含 [ ] : * ? / \
        base = base.replaceAll("[\\[\\]:*?/\\\\]", "_");
        if (base.length() > 31) {
            base = base.substring(0, 31);
        }
        if (workbook.getSheet(base) == null) {
            return base;
        }
        for (int i = 2; i < 100; i++) {
            String candidate = base + "(" + i + ")";
            if (workbook.getSheet(candidate) == null) {
                return candidate;
            }
        }
        return base + System.currentTimeMillis();
    }

    /** 表头样式：加粗 + 居中 + 四边细边框（模板 Sheet1 的表头是原样保留的，这里手工建新 sheet 的表头） */
    private static CellStyle buildHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static Row obtainRow(Sheet sheet, int index) {
        Row row = sheet.getRow(index);
        return row == null ? sheet.createRow(index) : row;
    }

    /**
     * 数据行样式：四边细边框（与模板表头一致），不加背景色。
     *
     * @param asText true 时把单元格格式设为文本（{@code @}），用于订单ID / 抖音号这类长数字串，
     *               避免 Excel 按数值处理而丢精度
     */
    private static CellStyle buildDataStyle(Workbook workbook, boolean asText) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        if (asText) {
            style.setDataFormat(workbook.createDataFormat().getFormat("@"));
        }
        return style;
    }

    private static void setText(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col, org.apache.poi.ss.usermodel.CellType.STRING);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void setNumber(Row row, int col, long value, CellStyle style) {
        Cell cell = row.createCell(col, org.apache.poi.ss.usermodel.CellType.NUMERIC);
        cell.setCellValue((double) value);
        cell.setCellStyle(style);
    }

    /** 写小数（roi）。值为 null 时留空，避免把「没有值」写成 0 误导阅读 */
    private static void setDouble(Row row, int col, Double value, CellStyle style) {
        if (value == null) {
            setText(row, col, "", style);
            return;
        }
        Cell cell = row.createCell(col, org.apache.poi.ss.usermodel.CellType.NUMERIC);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    // ==================== 账户名 ====================

    private String advertiserName(Long advertiserId) {
        if (advertiserId == null) {
            return "";
        }
        Map<Long, String> names = props.getAdvertiserNames();
        String name = names == null ? null : names.get(advertiserId);
        if (name != null && !name.isBlank()) {
            return name;
        }
        // 没配名字就退回账户 ID：报表仍然可用，日志里会提醒补配
        return String.valueOf(advertiserId);
    }

    private void warnUnmappedAccounts(List<OrderRow> rows) {
        Map<Long, String> names = props.getAdvertiserNames();
        List<Long> missing = rows.stream()
                .map(r -> r.advertiserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .filter(id -> names == null || names.get(id) == null || names.get(id).isBlank())
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            System.out.println("[日报提醒] 以下账户没配投放账户名，报表里显示的是账户 ID："
                    + missing + "；请在 suixintui.report.advertiser-names 里补上。");
        }
    }
}
