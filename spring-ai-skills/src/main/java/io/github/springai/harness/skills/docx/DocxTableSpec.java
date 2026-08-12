package io.github.springai.harness.skills.docx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 表格描述模型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocxTableSpec(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Column widths in DXA; should sum to table width. 1440 DXA = 1 inch")
        List<Integer> columnWidthsDxa,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Table rows")
        List<DocxTableRowSpec> rows
) {
}
