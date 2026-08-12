package io.github.springai.harness.skills.docx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 页面设置描述模型（默认 A4）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocxPageSetupSpec(
        @JsonProperty(required = false)
        @JsonPropertyDescription("Page width in DXA (default A4: 11906). 1440 DXA = 1 inch")
        Integer widthDxa,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Page height in DXA (default A4: 16838)")
        Integer heightDxa,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Top margin in DXA (default 1440 = 1 inch)")
        Integer marginTopDxa,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Bottom margin in DXA (default 1440)")
        Integer marginBottomDxa,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Left margin in DXA (default 1440)")
        Integer marginLeftDxa,

        @JsonProperty(required = false)
        @JsonPropertyDescription("Right margin in DXA (default 1440)")
        Integer marginRightDxa,

        @JsonProperty(required = false)
        @JsonPropertyDescription("If true, swap width/height for landscape")
        Boolean landscape
) {
}
