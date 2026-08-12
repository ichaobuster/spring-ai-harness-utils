package io.github.springai.harness.skills.docx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 文本 Run 描述模型
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocxRunSpec(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Run text content")
        String text,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Whether text is bold")
        Boolean bold,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Whether text is italic")
        Boolean italic,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Whether text is underlined")
        Boolean underline,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Font name (e.g., 'Arial', 'Times New Roman')")
        String fontName,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Font size in points (e.g., 11, 12, 14)")
        Integer fontSize,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Font color as '#RRGGBB' or 'r,g,b'")
        String color,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional break type: TEXT (line break) or PAGE (page break)")
        String breakType,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Optional local filesystem image path (absolute or relative) to embed in this run")
        String imagePath,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Image width in pixels (default 320) when imagePath is set")
        Integer imageWidthPx,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Image height in pixels (default 240) when imagePath is set")
        Integer imageHeightPx
) {
}
