package io.github.springai.harness.skills.docx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 文档块描述模型（段落 / 表格 / 分页符）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocxBlockSpec(
        @JsonProperty(required = true)
        @JsonPropertyDescription("Block type: 'paragraph', 'table', or 'pageBreak'")
        String type,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Paragraph payload when type=paragraph")
        DocxParagraphSpec paragraph,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Table payload when type=table")
        DocxTableSpec table
) {
}
