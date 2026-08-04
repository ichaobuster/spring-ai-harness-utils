package io.github.springai.harness.skills.xlsx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 单元格数据描述模型
 *
 * @param cellRef 单元格坐标 (如 "A1", "B10")
 * @param value   单元格字面值 (字符串/数值/布尔)
 * @param formula 公式表达式 (如 "=SUM(B2:B9)", 不需要 '=' 也可以，代码自动处理)
 * @param style   单元格样式规范
 * @param comment 单元格批注文本
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CellSpec(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Cell coordinate reference (e.g., 'A1', 'B10')")
        String cellRef,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Cell literal value (String, Number, or Boolean)")
        Object value,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Formula expression (e.g., '=SUM(B2:B9)'). Note: Do not use Excel 365 dynamic array functions like XLOOKUP/SORT/FILTER.")
        String formula,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Cell formatting style specification")
        CellStyleSpec style,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional cell comment text")
        String comment
) {
}
