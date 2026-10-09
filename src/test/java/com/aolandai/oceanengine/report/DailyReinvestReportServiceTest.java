package com.aolandai.oceanengine.report;

import com.aolandai.oceanengine.storage.FinishedOrder;
import com.aolandai.oceanengine.storage.ReinvestRecord;
import com.aolandai.oceanengine.storage.ReinvestRecordStore;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日报生成器的回归测试。
 *
 * <p>用 {@code src/test/resources/report/daily-reinvest-template.xls}（真实模板的副本）作夹具，
 * 所以不依赖目标机上的文件，任何机器上都能跑。</p>
 *
 * <p>覆盖点：同单多笔聚合 / 状态口径 / 跨天排除 / 未配账户名回退 / 表头保留 /
 * H2 写总合计且其余行留空 / 月份文件夹自动创建 / 空数据日也出文件。</p>
 */
class DailyReinvestReportServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 23);

    /**
     * 8 条流水，故意覆盖各种边界：
     * <ul>
     *   <li>同单两笔（orderId ...1111）→ 应聚合成 1 行、次数 2、金额 400</li>
     *   <li>FAILED（...4444）→ 不计入</li>
     *   <li>前一天 23:00（...5555）→ 不计入</li>
     *   <li>未配账户名的账户 1999999999999999999 → 「投放账户」列回退成账户 ID</li>
     *   <li>PENDING / DONE / UNKNOWN 三种状态都计入</li>
     * </ul>
     * 期望：5 个订单 / 6 次 / 合计 1200 元。
     */
    private static final String[] SEED = {
            rec("R1", 1779106144470100L, 7411111111111111111L, "DONE", "小明优选", "xm_yx_001",
                    "2026-09-23 10:15:00"),
            rec("R2", 1779106144470100L, 7411111111111111111L, "DONE", "小明优选", "xm_yx_001",
                    "2026-09-23 11:20:00"),
            rec("R3", 1779106144470100L, 7422222222222222222L, "DONE", "大壮零食铺", "dz_ls_002",
                    "2026-09-23 12:00:00"),
            rec("R4", 1823379540880396L, 7433333333333333333L, "PENDING", "母婴严选", "my_yx_003",
                    "2026-09-23 13:00:00"),
            rec("R5", 1823379540880396L, 7444444444444444444L, "FAILED", "宝宝树旗舰", "bb_s_004",
                    "2026-09-23 14:00:00"),
            rec("R6", 1778533513439244L, 7455555555555555555L, "DONE", "童装世界", "tz_sj_005",
                    "2026-09-22 23:00:00"),
            rec("R7", 1776098375039040L, 7466666666666666666L, "UNKNOWN", "青少年装备", "qs_n_006",
                    "2026-09-23 15:00:00"),
            rec("R8", 1999999999999999999L, 7477777777777777777L, "DONE", "未配名账户", "wpm_007",
                    "2026-09-23 16:00:00"),
    };

    @TempDir
    Path tempDir;

    // ==================== 正常路径 ====================

    @Test
    void aggregatesPerOrderAndWritesGrandTotalIntoH2() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        DailyReinvestReportService service =
                new DailyReinvestReportService(props, store(SEED));

        DailyReinvestReportService.Result result = service.generate(DATE);

        // 目录名/文件名按模板拼出来，且月份文件夹是自动创建的
        Path expected = tempDir.resolve("2026-09追投日报").resolve("2026-09-23追投明细表.xls");
        assertThat(result.file()).isEqualTo(expected);
        assertThat(Files.exists(expected)).isTrue();

        assertThat(result.orderCount()).isEqualTo(5);
        assertThat(result.totalCount()).isEqualTo(6);
        assertThat(result.totalAmount()).isEqualTo(1_200L);

        try (Workbook workbook = open(expected)) {
            Sheet sheet = workbook.getSheetAt(0);

            // 表头原样保留
            assertThat(text(sheet, 0, 0)).isEqualTo("订单ID");
            assertThat(text(sheet, 0, 7)).isEqualTo("追投合计金额");

            // 排序：账户 ID 升序 → 组内金额降序
            assertThat(text(sheet, 1, 0)).isEqualTo("7466666666666666666");
            assertThat(text(sheet, 1, 1)).isEqualTo("青少年装备");
            assertThat(text(sheet, 1, 2)).isEqualTo("qs_n_006");
            assertThat(text(sheet, 1, 3)).isEqualTo("官方【青少年-商品】");
            assertThat(number(sheet, 1, 4)).isEqualTo(1.0);
            assertThat(number(sheet, 1, 5)).isEqualTo(200.0);
            assertThat(text(sheet, 1, 6)).isEqualTo("2026-09-23");
            // H2 = 全部订单总合计
            assertThat(number(sheet, 1, 7)).isEqualTo(1_200.0);

            // 同单两笔聚合成一行：次数 2、金额 400
            assertThat(text(sheet, 2, 0)).isEqualTo("7411111111111111111");
            assertThat(number(sheet, 2, 4)).isEqualTo(2.0);
            assertThat(number(sheet, 2, 5)).isEqualTo(400.0);
            // H 列只在第 2 行填，其余数据行必须留空
            assertThat(sheet.getRow(2).getCell(7)).isNull();

            assertThat(text(sheet, 3, 0)).isEqualTo("7422222222222222222");

            // PENDING 计入
            assertThat(text(sheet, 4, 0)).isEqualTo("7433333333333333333");
            assertThat(text(sheet, 4, 3)).isEqualTo("官方【母婴商品】");

            // 未配账户名 → 回退显示账户 ID
            assertThat(text(sheet, 5, 0)).isEqualTo("7477777777777777777");
            assertThat(text(sheet, 5, 3)).isEqualTo("1999999999999999999");

            // 只有 5 行数据（第 2~6 行），且 FAILED / 跨天的两条都不在
            assertThat(dataEndsAfter(sheet)).isEqualTo(6);
            assertThat(allOrderIds(sheet))
                    .containsExactly(
                            "7466666666666666666",
                            "7411111111111111111",
                            "7422222222222222222",
                            "7433333333333333333",
                            "7477777777777777777");
        }
    }

    @Test
    void orderIdAndAwemeShowIdAreWrittenAsTextSoExcelKeepsPrecision() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        DailyReinvestReportService service =
                new DailyReinvestReportService(props, store(SEED));

        Path file = service.generate(DATE).file();

        try (Workbook workbook = open(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            // 19 位订单 ID 必须是文本单元格，否则 Excel 会按 double 处理而丢精度
            assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            // 次数 / 金额是数值，下游能直接求和
            assertThat(sheet.getRow(1).getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
        }
    }

    // ==================== 边界 ====================

    @Test
    void dayWithoutAnyReinvestStillProducesAFileWithHeaderAndZeroTotal() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        DailyReinvestReportService service =
                new DailyReinvestReportService(props, store(SEED));

        LocalDate emptyDay = LocalDate.of(2026, 9, 20);
        DailyReinvestReportService.Result result = service.generate(emptyDay);

        assertThat(result.orderCount()).isZero();
        assertThat(result.totalAmount()).isZero();
        assertThat(Files.exists(result.file())).isTrue();

        try (Workbook workbook = open(result.file())) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(text(sheet, 0, 0)).isEqualTo("订单ID");
            // 没有数据行，H2 = 0，下游拿到文件就不用判断「文件不存在」
            assertThat(number(sheet, 1, 7)).isZero();
        }
    }

    @Test
    void includeStatusesCanBeNarrowedToDoneOnly() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        props.setIncludeStatuses(List.of(ReinvestRecord.Status.DONE));
        DailyReinvestReportService service =
                new DailyReinvestReportService(props, store(SEED));

        DailyReinvestReportService.Result result = service.generate(DATE);

        // DONE 只剩 4 笔：...1111 两笔 + ...2222 一笔 + 未配名账户一笔 → 3 个订单 / 800 元
        assertThat(result.orderCount()).isEqualTo(3);
        assertThat(result.totalCount()).isEqualTo(4);
        assertThat(result.totalAmount()).isEqualTo(800L);
    }

    @Test
    void missingTemplateFailsTheReportButIsReportedClearly() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        props.setTemplatePath(tempDir.resolve("不存在的模板.xls"));
        DailyReinvestReportService service =
                new DailyReinvestReportService(props, store(SEED));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> service.generate(DATE))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("日报模板不存在");
    }

    // ==================== 已结束达标订单 sheet（2026-09-30 新增） ====================

    @Test
    void writesNewlyFinishedOrdersIntoSecondSheet() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        FinishedOrderTracker tracker = mock(FinishedOrderTracker.class);
        when(tracker.collectNewlyFinished(DATE)).thenReturn(List.of(
                finished(7690526648109645860L, 1779106144470100L, "小明优选", "xm_yx_001",
                        2.35, "FINISHED"),
                finished(7690526648109645861L, 1823379540880396L, "母婴严选", "my_yx_003",
                        1.81, "OVER")));

        DailyReinvestReportService service =
                new DailyReinvestReportService(props, store(SEED), tracker);
        DailyReinvestReportService.Result result = service.generate(DATE);

        assertThat(result.newlyFinishedCount()).isEqualTo(2);

        try (Workbook workbook = open(result.file())) {
            // 主表 + 新 sheet
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            Sheet sheet = workbook.getSheet("已结束达标订单");
            assertThat(sheet).isNotNull();

            // 表头
            assertThat(text(sheet, 0, 0)).isEqualTo("订单ID");
            assertThat(text(sheet, 0, 1)).isEqualTo("抖音名");
            assertThat(text(sheet, 0, 2)).isEqualTo("抖音号");
            assertThat(text(sheet, 0, 3)).isEqualTo("投放账户");
            assertThat(text(sheet, 0, 4)).isEqualTo("roi");

            // 数据行：订单ID / 抖音号是文本（防精度丢失），roi 是数值
            assertThat(text(sheet, 1, 0)).isEqualTo("7690526648109645860");
            assertThat(sheet.getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(text(sheet, 1, 1)).isEqualTo("小明优选");
            assertThat(text(sheet, 1, 2)).isEqualTo("xm_yx_001");
            assertThat(text(sheet, 1, 3)).isEqualTo("婴童【青少年-商品】");
            assertThat(number(sheet, 1, 4)).isEqualTo(2.35);
            assertThat(sheet.getRow(1).getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);

            assertThat(text(sheet, 2, 0)).isEqualTo("7690526648109645861");
            assertThat(text(sheet, 2, 3)).isEqualTo("官方【母婴商品】");
            assertThat(number(sheet, 2, 4)).isEqualTo(1.81);
        }
    }

    @Test
    void finishedOrdersSheetIsCreatedEvenWithoutDataSoStructureStaysStable() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        FinishedOrderTracker tracker = mock(FinishedOrderTracker.class);
        when(tracker.collectNewlyFinished(DATE)).thenReturn(List.of());

        DailyReinvestReportService service =
                new DailyReinvestReportService(props, store(SEED), tracker);
        DailyReinvestReportService.Result result = service.generate(DATE);

        assertThat(result.newlyFinishedCount()).isZero();

        try (Workbook workbook = open(result.file())) {
            Sheet sheet = workbook.getSheet("已结束达标订单");
            assertThat(sheet).isNotNull();
            assertThat(text(sheet, 0, 0)).isEqualTo("订单ID");
            // 只有表头，没有数据行
            assertThat(sheet.getRow(1)).isNull();
        }
    }

    @Test
    void finishedOrdersSheetCanBeDisabled() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        props.setFinishedOrdersEnabled(false);

        DailyReinvestReportService service = new DailyReinvestReportService(props, store(SEED));
        DailyReinvestReportService.Result result = service.generate(DATE);

        assertThat(result.newlyFinishedCount()).isZero();
        try (Workbook workbook = open(result.file())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheet("已结束达标订单")).isNull();
        }
    }

    @Test
    void sheetNameIsSanitizedAndClampedToExcelLimits() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        props.setFinishedOrdersSheetName("一个/非常:长的*名字?带[非法]字符而且超过三十一个字符的表名");

        DailyReinvestReportService service = new DailyReinvestReportService(props, store(SEED));
        Path file = service.generate(DATE).file();

        try (Workbook workbook = open(file)) {
            String name = workbook.getSheetName(1);
            assertThat(name.length()).isLessThanOrEqualTo(31);
            assertThat(name).doesNotContain("/", ":", "*", "?", "[", "]");
        }
    }

    @Test
    void trackerFailureLeavesSheetEmptyButDoesNotBreakTheReport() throws Exception {
        ReinvestReportProperties props = props(tempDir);
        FinishedOrderTracker tracker = mock(FinishedOrderTracker.class);
        when(tracker.collectNewlyFinished(DATE))
                .thenThrow(new RuntimeException("模拟接口炸了"));

        DailyReinvestReportService service =
                new DailyReinvestReportService(props, store(SEED), tracker);
        DailyReinvestReportService.Result result = service.generate(DATE);

        // 主报表照常产出
        assertThat(result.orderCount()).isEqualTo(5);
        assertThat(result.totalAmount()).isEqualTo(1_200L);
        assertThat(result.newlyFinishedCount()).isZero();

        try (Workbook workbook = open(result.file())) {
            assertThat(workbook.getSheet("已结束达标订单")).isNotNull();
        }
    }

    // ==================== 辅助 ====================

    private static FinishedOrder finished(long orderId, long advertiserId, String awemeName,
                                          String awemeShowId, double roi, String status) {
        return new FinishedOrder(orderId, advertiserId, awemeName, awemeShowId, roi, status,
                "2026-09-23 09:00:00", "2026-09-24 00:01:00");
    }

    private ReinvestReportProperties props(Path outputDir) throws Exception {
        ReinvestReportProperties props = new ReinvestReportProperties();
        props.setTemplatePath(templatePath());
        props.setOutputDir(outputDir);
        Map<Long, String> names = new LinkedHashMap<>();
        names.put(1779106144470100L, "婴童【青少年-商品】");
        names.put(1776098375039040L, "官方【青少年-商品】");
        names.put(1823379540880396L, "官方【母婴商品】");
        props.setAdvertiserNames(names);
        return props;
    }

    /** 模板夹具路径（真实模板的副本，随测试资源走） */
    private static Path templatePath() throws Exception {
        return Path.of(DailyReinvestReportServiceTest.class
                .getResource("/report/daily-reinvest-template.xls").toURI());
    }

    private ReinvestRecordStore store(String[] lines) throws Exception {
        Path file = tempDir.resolve("records.jsonl");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return new ReinvestRecordStore(file);
    }

    private static Workbook open(Path file) throws Exception {
        InputStream in = Files.newInputStream(file);
        return WorkbookFactory.create(in);
    }

    private static String text(Sheet sheet, int row, int col) {
        Cell cell = sheet.getRow(row).getCell(col);
        return cell == null ? null : cell.getStringCellValue();
    }

    private static double number(Sheet sheet, int row, int col) {
        Cell cell = sheet.getRow(row).getCell(col);
        return cell == null ? Double.NaN : cell.getNumericCellValue();
    }

    private static List<String> allOrderIds(Sheet sheet) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= dataEndsAfter(sheet) - 1; i++) {
            Row row = sheet.getRow(i);
            if (row != null && row.getCell(0) != null) {
                ids.add(row.getCell(0).getStringCellValue());
            }
        }
        return ids;
    }

    /**
     * 最后一条数据行的行号（1-based）+ 1，即「数据结束后第一个行号」。
     *
     * <p>不能用 {@code getLastRowNum()}：模板里可能存在物理行记录但内容为空的行，
     * 那会让它返回一个很大的值。这里按「A 列是否有订单 ID」来判断真实数据边界。</p>
     */
    private static int dataEndsAfter(Sheet sheet) {
        int last = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || row.getCell(0) == null) {
                continue;
            }
            if (row.getCell(0).getCellType() == CellType.BLANK) {
                continue;
            }
            last = i;
        }
        return last + 1;
    }

    /** 造一行 JSONL，字段名必须与 {@link ReinvestRecord} 的属性名一致 */
    private static String rec(String roundId, long advertiserId, long orderId,
                              String status, String awemeName, String awemeShowId,
                              String timestamp) {
        return "{\"roundId\":\"" + roundId + "\""
                + ",\"advertiserId\":" + advertiserId
                + ",\"orderId\":" + orderId
                + ",\"amount\":200"
                + ",\"deliveryTime\":24.0"
                + ",\"preTotalBudget\":5000"
                + ",\"awemeName\":\"" + awemeName + "\""
                + ",\"awemeShowId\":\"" + awemeShowId + "\""
                + ",\"status\":\"" + status + "\""
                + ",\"message\":null"
                + ",\"timestamp\":\"" + timestamp + "\"}";
    }
}
