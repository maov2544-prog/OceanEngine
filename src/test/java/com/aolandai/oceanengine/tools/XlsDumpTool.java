package com.aolandai.oceanengine.tools;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把生成的追投明细表（.xls）原样打印出来，用于人工核对报表内容。
 *
 * <p>这是调试工具（只有 {@code main()}，没有 {@code @Test}），surefire 不会自动执行，
 * 需要显式指定类名运行。用法：</p>
 * <pre>
 * XlsDumpTool &lt;文件路径.xls&gt;
 * </pre>
 *
 * <p>会打印每个单元格的<b>类型</b>——订单 ID / 抖音号必须是 {@code STRING}
 * （否则 Excel 按数值处理会丢 19 位精度），次数 / 金额必须是 {@code NUMERIC}。</p>
 */
public final class XlsDumpTool {

    private XlsDumpTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: XlsDumpTool <文件路径.xls>");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        if (!Files.exists(file)) {
            System.err.println("文件不存在: " + file.toAbsolutePath());
            System.exit(1);
        }

        System.out.println("文件: " + file.toAbsolutePath() + "（" + Files.size(file) + " 字节）");
        try (InputStream in = Files.newInputStream(file);
             Workbook workbook = WorkbookFactory.create(in)) {
            System.out.println("工作表数: " + workbook.getNumberOfSheets());

            // 遍历全部工作表（日报现在有 2 个：追投明细 + 已结束达标订单）
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                System.out.println("---");
                System.out.println("工作表[" + s + "]: " + sheet.getSheetName()
                        + " | 物理行数: " + sheet.getPhysicalNumberOfRows()
                        + " | 末行号: " + (sheet.getLastRowNum() + 1));

                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    // 按该行实际有多少格来遍历，不再写死列数
                    int columns = Math.max(row.getLastCellNum(), 0);
                    StringBuilder sb = new StringBuilder();
                    boolean hasContent = false;
                    for (int c = 0; c < columns; c++) {
                        Cell cell = row.getCell(c);
                        String text = render(cell);
                        if (!text.isEmpty() && !"[空]".equals(text)) {
                            hasContent = true;
                        }
                        sb.append(columnName(c)).append('=').append(text);
                        if (c < columns - 1) {
                            sb.append(" | ");
                        }
                    }
                    if (hasContent) {
                        System.out.println("第" + (r + 1) + "行  " + sb);
                    }
                }
            }
        }
    }

    /** 0 → A、25 → Z、26 → AA …（支持超过 26 列的表） */
    private static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        int i = index;
        while (i >= 0) {
            sb.insert(0, (char) ('A' + i % 26));
            i = i / 26 - 1;
        }
        return sb.toString();
    }

    /** 渲染单元格：带类型前缀，便于确认「文本 vs 数值」 */
    private static String render(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        return switch (type) {
            case STRING -> "[文本]" + cell.getStringCellValue();
            case NUMERIC -> "[数值]" + trim(cell.getNumericCellValue());
            case BLANK -> "[空]";
            case FORMULA -> "[公式]" + cell.getCellFormula();
            case BOOLEAN -> "[布尔]" + cell.getBooleanCellValue();
            default -> "[" + type + "]" + cell;
        };
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
