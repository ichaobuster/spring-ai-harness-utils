package io.github.springai.harness.skills.xlsx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.Map;

/**
 * 工作表 (Sheet) 描述模型
 *
 * @param sheetName     工作表名称
 * @param cells         单元格列表
 * @param columnWidths  列宽 Map (如 {"A": 15, "B": 20})
 * @param freezeCol     冻结列数 (如 1 冻结第一列)
 * @param freezeRow     冻结行数 (如 1 冻结第一行/表头)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SheetSpec(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Worksheet name (e.g., 'Sheet1', 'Financial Summary')")
        String sheetName,

        @JsonProperty(required = false)
        @JsonPropertyDescription("List of cell specifications containing coordinates, values, formulas, and styles")
        List<CellSpec> cells,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Column widths map in character units (e.g., {'A': 20, 'B': 15})")
        Map<String, Integer> columnWidths,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Number of columns to freeze on the left (e.g., 1 to freeze column A)")
        Integer freezeCol,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Number of rows to freeze at the top (e.g., 1 to freeze header row)")
        Integer freezeRow
) {
}
