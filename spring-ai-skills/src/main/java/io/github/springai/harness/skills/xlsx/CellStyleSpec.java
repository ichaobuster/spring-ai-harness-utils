package io.github.springai.harness.skills.xlsx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 单元格样式描述模型
 *
 * @param fontName          字体名称 (如 "Arial", "Times New Roman")
 * @param fontSize          字号 (如 10, 12, 14)
 * @param bold              是否加粗
 * @param italic            是否斜体
 * @param fontColorRgb      字体 RGB 颜色，格式如 "0,0,255" 或 "#0000FF"
 * @param fillColorRgb      填充 RGB 颜色，格式如 "255,255,0" 或 "#FFFF00"
 * @param dataFormat        数字格式 (如 "$#,##0", "0.0%", "0.0x", "yyyy-mm-dd")
 * @param alignment         水平对齐方式 ("LEFT", "CENTER", "RIGHT")
 * @param verticalAlignment 垂直对齐方式 ("TOP", "CENTER", "BOTTOM")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CellStyleSpec(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Font name (e.g., 'Arial', 'Times New Roman')")
        String fontName,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Font size in points (e.g., 10, 12, 14)")
        Short fontSize,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Whether text is bold")
        Boolean bold,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Whether text is italic")
        Boolean italic,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Font RGB color string (e.g., '0,0,255' or '#0000FF')")
        String fontColorRgb,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Background fill RGB color string (e.g., '255,255,0' or '#FFFF00')")
        String fillColorRgb,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Excel number data format string (e.g., '$#,##0', '0.0%', '0.0x', 'yyyy-mm-dd')")
        String dataFormat,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Horizontal alignment ('LEFT', 'CENTER', 'RIGHT')")
        String alignment,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Vertical alignment ('TOP', 'CENTER', 'BOTTOM')")
        String verticalAlignment
) {
}
