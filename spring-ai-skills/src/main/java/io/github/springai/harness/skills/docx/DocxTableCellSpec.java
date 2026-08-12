package io.github.springai.harness.skills.docx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 表格单元格描述模型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocxTableCellSpec(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Paragraphs inside the cell")
        List<DocxParagraphSpec> paragraphs,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Cell width in DXA (twentieths of a point). 1440 DXA = 1 inch")
        Integer widthDxa,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Cell shading color as '#RRGGBB' or 'r,g,b'")
        String shading
) {
}
