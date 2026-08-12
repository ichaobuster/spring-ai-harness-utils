package io.github.springai.harness.skills.xlsx;

import io.github.springai.harness.tool.SkillsTool;
import io.github.springai.harness.util.SkillUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxToolsTest {

    private XlsxTools xlsxTools;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        xlsxTools = new XlsxTools();
    }

    private String path(String name) {
        return tempDir.resolve(name).toAbsolutePath().toString();
    }

    @Test
    void testCreateXlsxAndReadPreview() {
        String targetFile = path("test_create.xlsx");

        List<CellSpec> cells = List.of(
                new CellSpec("A1", "Item", null, new CellStyleSpec("Arial", (short) 12, true, false, "0,0,255", null, null, "CENTER", null), "Header cell"),
                new CellSpec("B1", "Amount", null, new CellStyleSpec("Arial", (short) 12, true, false, "0,0,255", null, null, "CENTER", null), null),
                new CellSpec("A2", "Product A", null, null, null),
                new CellSpec("B2", 100, null, new CellStyleSpec(null, null, null, null, null, null, "$#,##0", null, null), null),
                new CellSpec("A3", "Product B", null, null, null),
                new CellSpec("B3", 200, null, new CellStyleSpec(null, null, null, null, null, null, "$#,##0", null, null), null),
                new CellSpec("A4", "Total", null, new CellStyleSpec("Arial", (short) 10, true, false, null, null, null, null, null), null),
                new CellSpec("B4", null, "=SUM(B2:B3)", new CellStyleSpec("Arial", (short) 10, true, false, null, "255,255,0", "$#,##0", null, null), null)
        );

        List<SheetSpec> sheets = List.of(
                new SheetSpec("Financial Summary", cells, Map.of("A", 20, "B", 15), 0, 1)
        );

        String createResult = xlsxTools.createXlsx(targetFile, sheets);
        assertThat(createResult).contains("Successfully created XLSX file");
        assertThat(Files.exists(Path.of(targetFile))).isTrue();

        // Test Preview
        String preview = xlsxTools.readXlsxPreview(targetFile, 10);
        assertThat(preview).contains("Sheet: Financial Summary");
        assertThat(preview).contains("Product A");
        assertThat(preview).contains("Product B");
    }

    @Test
    void testReadXlsxSheet() {
        String targetFile = path("test_read.xlsx");
        List<SheetSpec> sheets = List.of(
                new SheetSpec("Data", List.of(
                        new CellSpec("A1", "X", null, null, null),
                        new CellSpec("B1", null, "=10+20", null, null)
                ), null, null, null)
        );
        xlsxTools.createXlsx(targetFile, sheets);

        // Read value mode
        String valueModeResult = xlsxTools.readXlsxSheet(targetFile, "Data", 1, 10, false);
        assertThat(valueModeResult).contains("A1: X");

        // Read formula mode
        String formulaModeResult = xlsxTools.readXlsxSheet(targetFile, "Data", 1, 10, true);
        assertThat(formulaModeResult).contains("B1: =10+20");
    }

    @Test
    void testEditXlsxCells() {
        String targetFile = path("test_edit.xlsx");
        List<SheetSpec> initialSheets = List.of(
                new SheetSpec("Sheet1", List.of(new CellSpec("A1", "Old Value", null, null, null)), null, null, null)
        );
        xlsxTools.createXlsx(targetFile, initialSheets);

        List<SheetSpec> editSheets = List.of(
                new SheetSpec("Sheet1", List.of(
                        new CellSpec("A1", "New Value", null, null, null),
                        new CellSpec("A2", "Added Cell", null, null, null)
                ), null, null, null)
        );
        String editResult = xlsxTools.editXlsxCells(targetFile, editSheets);
        assertThat(editResult).contains("Successfully edited XLSX file");

        String preview = xlsxTools.readXlsxPreview(targetFile, 10);
        assertThat(preview).contains("New Value");
        assertThat(preview).contains("Added Cell");
    }

    @Test
    void testEvaluateXlsxFormulas() {
        String targetFile = path("test_eval.xlsx");
        List<SheetSpec> sheets = List.of(
                new SheetSpec("Calc", List.of(
                        new CellSpec("A1", 10, null, null, null),
                        new CellSpec("A2", 0, null, null, null),
                        new CellSpec("A3", null, "=A1/A2", null, null),
                        new CellSpec("B1", 5, null, null, null),
                        new CellSpec("B2", 15, null, null, null),
                        new CellSpec("B3", null, "=SUM(B1:B2)", null, null)
                ), null, null, null)
        );
        xlsxTools.createXlsx(targetFile, sheets);

        String evalResult = xlsxTools.evaluateXlsxFormulas(targetFile, true);
        assertThat(evalResult).contains("errors_found");
        assertThat(evalResult).contains("#DIV/0!");
        assertThat(evalResult).contains("totalFormulas");
        assertThat(evalResult).doesNotContain("total_formulas");
        assertThat(evalResult).contains("errorSummary");

        // Formulas must remain formulas after writeBack=true (cached values only)
        String formulaMode = xlsxTools.readXlsxSheet(targetFile, "Calc", 1, 10, true);
        assertThat(formulaMode).contains("A3: =A1/A2");
        assertThat(formulaMode).contains("B3: =SUM(B1:B2)");

        String valueMode = xlsxTools.readXlsxSheet(targetFile, "Calc", 1, 10, false);
        assertThat(valueMode).contains("B3: 20");
    }

    @Test
    void testConvertCsvToXlsx() throws IOException {
        String csvFile = path("input.csv");
        Files.writeString(Path.of(csvFile), "Name,Age,Score\nAlice,30,95.5\nBob,25,88.0\n");

        String xlsxFile = path("output.xlsx");
        String result = xlsxTools.convertCsvToXlsx(csvFile, xlsxFile, ",", "Scores");
        assertThat(result).contains("Successfully converted CSV to XLSX");
        assertThat(Files.exists(Path.of(xlsxFile))).isTrue();

        String preview = xlsxTools.readXlsxPreview(xlsxFile, 10);
        assertThat(preview).contains("Scores");
        assertThat(preview).contains("Alice");
        assertThat(preview).contains("95.5");
    }

    @Test
    void testSkillUtilLoadClassPath() {
        List<SkillsTool.Skill> skills = SkillUtil.loadClassPath("classpath*:skills/xlsx/SKILL.md");
        assertThat(skills).isNotEmpty();
        SkillsTool.Skill xlsxSkill = skills.stream()
                .filter(s -> "xlsx".equals(s.name()))
                .findFirst()
                .orElse(null);
        assertThat(xlsxSkill).isNotNull();
        assertThat(xlsxSkill.frontMatter()).containsKey("tool-calls");
        assertThat(xlsxSkill.frontMatter().get("tool-calls").toString()).contains("readXlsxPreview");
    }

    // --- Branch Coverage Tests ---

    @Test
    void testCreateXlsxEmptyAndNullSheets() {
        assertThat(xlsxTools.createXlsx("file.xlsx", null)).contains("Error: sheets parameter cannot be empty.");
        assertThat(xlsxTools.createXlsx("file.xlsx", List.of())).contains("Error: sheets parameter cannot be empty.");
    }

    @Test
    void testEditXlsxCellsEmptyAndNullSheets() {
        String file = path("exist_edit.xlsx");
        xlsxTools.createXlsx(file, List.of(new SheetSpec("Sheet1", List.of(), null, null, null)));
        assertThat(xlsxTools.editXlsxCells(file, null)).contains("Error: sheets parameter cannot be empty.");
        assertThat(xlsxTools.editXlsxCells(file, List.of())).contains("Error: sheets parameter cannot be empty.");
    }

    @Test
    void testValidationFileNotExistsAndTooLarge() throws IOException {
        assertThatThrownBy(() -> xlsxTools.readXlsxPreview(path("non_existent.xlsx"), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File does not exist on local filesystem");

        Path huge = tempDir.resolve("huge.xlsx");
        // Create a sparse-like large file efficiently is OS-dependent; write slightly over limit via mock-size alternative:
        // Use a real file and temporarily lower is not available — write 0-byte then use Files with a custom path check.
        // Write a file larger than 50MB would be heavy; instead create empty file and verify size check with a dedicated tiny helper file
        // by writing 51MB would be slow. Create file with RandomAccessFile setLength.
        try (var raf = new java.io.RandomAccessFile(huge.toFile(), "rw")) {
            raf.setLength(51L * 1024 * 1024);
        }
        assertThatThrownBy(() -> xlsxTools.readXlsxPreview(huge.toAbsolutePath().toString(), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File size exceeds 50MB safety limit");
    }

    @Test
    void testPreviewEmptySheetAndRowHiding() {
        String file = path("preview_test.xlsx");
        // Create empty sheet and a sheet with 15 rows
        List<CellSpec> cells = List.of(
                new CellSpec("A1", "Row 1", null, null, null),
                new CellSpec("A15", "Row 15", null, null, null)
        );
        List<SheetSpec> sheets = List.of(
                new SheetSpec("EmptySheet", List.of(), null, null, null),
                new SheetSpec("DataSheet", cells, null, null, null)
        );
        xlsxTools.createXlsx(file, sheets);

        // Test maxRowsPerSheet null (defaults to 10) & capping
        String preview = xlsxTools.readXlsxPreview(file, null);
        assertThat(preview).contains("*(Empty sheet)*");
        assertThat(preview).contains("more rows hidden");

        // Test maxRowsPerSheet > 50 capped at 50
        String previewCapped = xlsxTools.readXlsxPreview(file, 100);
        assertThat(previewCapped).contains("DataSheet");
    }

    @Test
    void testReadXlsxSheetPaginationAndMissingSheet() {
        String file = path("read_sheet_test.xlsx");
        List<SheetSpec> sheets = List.of(
                new SheetSpec("SheetA", List.of(
                        new CellSpec("A1", true, null, null, null),
                        new CellSpec("A2", 12.345, null, null, null)
                ), null, null, null)
        );
        xlsxTools.createXlsx(file, sheets);

        // Non-existent sheet
        String missingResult = xlsxTools.readXlsxSheet(file, "MissingSheet", 1, 10, false);
        assertThat(missingResult).contains("Error: Sheet 'MissingSheet' not found");

        // Invalid startRow (<=0) and null endRow
        String result = xlsxTools.readXlsxSheet(file, "SheetA", -5, null, false);
        assertThat(result).contains("A1: true");
        assertThat(result).contains("A2: 12.345");
    }

    @Test
    void testCreateXlsxStylesColorsHexAndRgb() throws IOException {
        String file = path("styles_test.xlsx");
        List<CellSpec> cells = List.of(
                new CellSpec(null, "Skipped Cell", null, null, null), // null cellRef branch
                new CellSpec("A1", "Text", null, new CellStyleSpec("Courier New", (short) 14, true, true, "#FF0000", "#FFFF00", "$#,##0", "RIGHT", "TOP"), "Comment 1"),
                new CellSpec("B1", 99L, null, new CellStyleSpec(null, null, false, false, "0,128,0", "0,255,255", null, "INVALID_ALIGN", "BOTTOM"), null),
                new CellSpec("C1", 45.67, null, new CellStyleSpec(null, null, false, false, null, null, null, "CENTER", "CENTER"), null)
        );
        List<SheetSpec> sheets = List.of(
                new SheetSpec(null, cells, Map.of("A", 15), 1, 1) // null sheetName -> default "Sheet1"
        );

        String createResult = xlsxTools.createXlsx(file, sheets);
        assertThat(createResult).contains("Successfully created XLSX file");

        String preview = xlsxTools.readXlsxPreview(file, 10);
        assertThat(preview).contains("Sheet: Sheet1");
        assertThat(preview).contains("Text");

        try (var is = Files.newInputStream(Path.of(file));
             Workbook workbook = new XSSFWorkbook(is)) {
            Sheet sheet = workbook.getSheet("Sheet1");
            Cell cellA1 = sheet.getRow(0).getCell(0);
            Font font = workbook.getFontAt(cellA1.getCellStyle().getFontIndex());
            assertThat(font).isInstanceOf(XSSFFont.class);
            XSSFColor color = ((XSSFFont) font).getXSSFColor();
            assertThat(color).isNotNull();
            byte[] rgb = color.getRGB();
            assertThat(rgb).isNotNull();
            assertThat(rgb[0] & 0xFF).isEqualTo(255);
            assertThat(rgb[1] & 0xFF).isEqualTo(0);
            assertThat(rgb[2] & 0xFF).isEqualTo(0);
        }
    }

    @Test
    void testEditXlsxCellsDefaultSheetAndNullSheetName() {
        String file = path("edit_default.xlsx");
        List<SheetSpec> initial = List.of(
                new SheetSpec("Sheet1", List.of(new CellSpec("A1", "Init", null, null, null)), null, null, null)
        );
        xlsxTools.createXlsx(file, initial);

        // Edit with null sheetName -> targets sheet 0
        List<SheetSpec> edits = List.of(
                new SheetSpec(null, List.of(
                        new CellSpec(null, "Skip", null, null, null),
                        new CellSpec("A1", "Updated Init", null, null, "New Comment"),
                        new CellSpec("B1", "New Cell", null, null, null)
                ), null, null, null),
                new SheetSpec("CreatedSheet", List.of(new CellSpec("A1", "First", null, null, null)), null, null, null)
        );

        String editResult = xlsxTools.editXlsxCells(file, edits);
        assertThat(editResult).contains("Successfully edited XLSX file");

        String preview = xlsxTools.readXlsxPreview(file, 10);
        assertThat(preview).contains("Updated Init");
        assertThat(preview).contains("CreatedSheet");
    }

    @Test
    void testEvaluateXlsxFormulasNoWriteBackAndVariousErrors() {
        String file = path("eval_errors.xlsx");
        List<SheetSpec> sheets = List.of(
                new SheetSpec("Errors", List.of(
                        new CellSpec("A1", null, "=1/0", null, null),       // #DIV/0!
                        new CellSpec("A2", null, "=SQRT(-1)", null, null),  // #NUM!
                        new CellSpec("A3", "#REF!", null, null, null)       // String containing #REF!
                ), null, null, null)
        );
        xlsxTools.createXlsx(file, sheets);

        // Test evaluate writeBack = false
        String evalResultNoWriteBack = xlsxTools.evaluateXlsxFormulas(file, false);
        assertThat(evalResultNoWriteBack).contains("errors_found");
        assertThat(evalResultNoWriteBack).contains("#DIV/0!");

        // Test evaluate writeBack = null (defaults to true)
        String evalResultDefaultWriteBack = xlsxTools.evaluateXlsxFormulas(file, null);
        assertThat(evalResultDefaultWriteBack).contains("errors_found");
    }

    @Test
    void testConvertCsvToXlsxTabDelimiterAndTypes() throws IOException {
        String tsvFile = path("data.tsv");
        Files.writeString(Path.of(tsvFile), "ID\tActive\tRate\tLabel\n101\ttrue\t12.34\tSample\n102\tfalse\t50\tAnother\n");

        String xlsxFile = path("data_converted.xlsx");
        String result = xlsxTools.convertCsvToXlsx(tsvFile, xlsxFile, "\t", null); // null sheetName -> defaults to "Data"
        assertThat(result).contains("Successfully converted CSV to XLSX");
        assertThat(Files.exists(Path.of(xlsxFile))).isTrue();

        String preview = xlsxTools.readXlsxPreview(xlsxFile, 10);
        assertThat(preview).contains("Sheet: Data");
        assertThat(preview).contains("101");
        assertThat(preview).contains("12.34");
    }

    @Test
    void testNoArgConstructor() {
        XlsxTools noArg = new XlsxTools();
        assertThat(noArg).isNotNull();
    }
}
