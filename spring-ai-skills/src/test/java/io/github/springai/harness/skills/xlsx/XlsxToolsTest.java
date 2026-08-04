package io.github.springai.harness.skills.xlsx;

import io.github.springai.harness.storage.LocalFileStorage;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.tool.SkillsTool;
import io.github.springai.harness.util.SkillUtil;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XlsxToolsTest {

    private XlsxTools xlsxTools;
    private StorageProvider storageProvider;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        storageProvider = LocalFileStorage.builder()
                .baseDir(tempDir)
                .build();
        xlsxTools = XlsxTools.builder()
                .storageProvider(storageProvider)
                .build();
    }

    @Test
    void testCreateXlsxAndReadPreview() {
        String targetFile = "test_create.xlsx";

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
        assertThat(storageProvider.exists(targetFile)).isTrue();

        // Test Preview
        String preview = xlsxTools.readXlsxPreview(targetFile, 10);
        assertThat(preview).contains("Sheet: Financial Summary");
        assertThat(preview).contains("Product A");
        assertThat(preview).contains("Product B");
    }

    @Test
    void testReadXlsxSheet() {
        String targetFile = "test_read.xlsx";
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
        String targetFile = "test_edit.xlsx";
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
        String targetFile = "test_eval.xlsx";
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
    }

    @Test
    void testConvertCsvToXlsx() throws IOException {
        String csvFile = "input.csv";
        storageProvider.writeString(csvFile, "Name,Age,Score\nAlice,30,95.5\nBob,25,88.0\n");

        String xlsxFile = "output.xlsx";
        String result = xlsxTools.convertCsvToXlsx(csvFile, xlsxFile, ",", "Scores");
        assertThat(result).contains("Successfully converted CSV to XLSX");
        assertThat(storageProvider.exists(xlsxFile)).isTrue();

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
        String file = "exist_edit.xlsx";
        xlsxTools.createXlsx(file, List.of(new SheetSpec("Sheet1", List.of(), null, null, null)));
        assertThat(xlsxTools.editXlsxCells(file, null)).contains("Error: sheets parameter cannot be empty.");
        assertThat(xlsxTools.editXlsxCells(file, List.of())).contains("Error: sheets parameter cannot be empty.");
    }

    @Test
    void testValidationFileNotExistsAndTooLarge() throws IOException {
        assertThatThrownBy(() -> xlsxTools.readXlsxPreview("non_existent.xlsx", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File does not exist in storage");

        StorageProvider mockProvider = mock(StorageProvider.class);
        when(mockProvider.exists("huge.xlsx")).thenReturn(true);
        when(mockProvider.getInfo("huge.xlsx")).thenReturn(new StorageProvider.Info("huge.xlsx", true, false, 60 * 1024 * 1024L, System.currentTimeMillis()));

        XlsxTools toolsWithMock = XlsxTools.builder().storageProvider(mockProvider).build();
        assertThatThrownBy(() -> toolsWithMock.readXlsxPreview("huge.xlsx", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File size exceeds 50MB safety limit");
    }

    @Test
    void testPreviewEmptySheetAndRowHiding() {
        String file = "preview_test.xlsx";
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
        String file = "read_sheet_test.xlsx";
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
    void testCreateXlsxStylesColorsHexAndRgb() {
        String file = "styles_test.xlsx";
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
    }

    @Test
    void testEditXlsxCellsDefaultSheetAndNullSheetName() {
        String file = "edit_default.xlsx";
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
        String file = "eval_errors.xlsx";
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
        String tsvFile = "data.tsv";
        storageProvider.writeString(tsvFile, "ID\tActive\tRate\tLabel\n101\ttrue\t12.34\tSample\n102\tfalse\t50\tAnother\n");

        String xlsxFile = "data_converted.xlsx";
        String result = xlsxTools.convertCsvToXlsx(tsvFile, xlsxFile, "\t", null); // null sheetName -> defaults to "Data"
        assertThat(result).contains("Successfully converted CSV to XLSX");
        assertThat(storageProvider.exists(xlsxFile)).isTrue();

        String preview = xlsxTools.readXlsxPreview(xlsxFile, 10);
        assertThat(preview).contains("Sheet: Data");
        assertThat(preview).contains("101");
        assertThat(preview).contains("12.34");
    }

    @Test
    void testConstructorsAndBuilderFallback() {
        // Test no-arg constructor
        XlsxTools noArg = new XlsxTools();
        assertThat(noArg).isNotNull();

        // Test null StorageProvider fallback
        XlsxTools nullProvider = new XlsxTools(null);
        assertThat(nullProvider).isNotNull();
    }
}
