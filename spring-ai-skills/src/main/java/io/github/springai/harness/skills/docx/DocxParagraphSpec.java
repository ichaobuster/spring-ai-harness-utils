package io.github.springai.harness.skills.docx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 段落描述模型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocxParagraphSpec(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Paragraph style id (e.g., 'Normal', 'Heading1', 'Heading2', 'Title')")
        String style,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Horizontal alignment: LEFT, CENTER, RIGHT, BOTH")
        String alignment,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Ordered runs that make up the paragraph")
        List<DocxRunSpec> runs
) {
}
