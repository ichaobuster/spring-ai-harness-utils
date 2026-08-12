package io.github.springai.harness.skills.xlsx;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.Map;

/**
 * 公式重算与错误检测结果模型（JSON 字段使用 camelCase，与 SKILL.md / Jackson 默认序列化一致）
 *
 * @param status        执行状态 ("success" | "errors_found")
 * @param totalFormulas 公式总数
 * @param totalErrors   错误总数
 * @param errorSummary  按错误类型分组的明细 (如 "#DIV/0!": { "count": 2, "locations": ["Sheet1!B10", "Sheet1!B11"] })
 * @param error         若执行出现致命异常则填充此字段
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormulaEvaluationResult(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Execution status ('success' or 'errors_found')")
        String status,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Total number of formula cells in the workbook")
        Integer totalFormulas,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Total number of formula errors found")
        Integer totalErrors,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Summary of error locations grouped by error type")
        Map<String, ErrorGroup> errorSummary,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Fatal error message if evaluation failed")
        String error
) {

    public static FormulaEvaluationResult error(String errorMessage) {
        return new FormulaEvaluationResult(null, null, null, null, errorMessage);
    }

    public static FormulaEvaluationResult success(int totalFormulas, int totalErrors, Map<String, ErrorGroup> errorSummary) {
        String status = totalErrors == 0 ? "success" : "errors_found";
        return new FormulaEvaluationResult(status, totalFormulas, totalErrors, errorSummary, null);
    }

    public record ErrorGroup(
            @JsonProperty(required = true)
            @JsonPropertyDescription("Total count of cells with this error type")
            int count,

            @JsonProperty(required = false)
            @JsonPropertyDescription("List of cell locations (e.g. ['Sheet1!B10']) up to 100 entries")
            List<String> locations,

            @JsonProperty(required = false)
            @JsonPropertyDescription("Number of cell locations omitted if count > 100")
            Integer locationsTruncated
    ) {
    }
}
