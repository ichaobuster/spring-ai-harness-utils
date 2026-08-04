package io.github.springai.harness.skills.xlsx;

import io.github.springai.harness.storage.LocalFileStorage;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.tool.SkillsTool;
import io.github.springai.harness.util.SkillUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
