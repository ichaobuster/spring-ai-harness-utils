package io.github.springai.harness.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HarnessToolUtil
 *
 * @author ichaobuster
 */
public class HarnessToolUtil {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	public static List<ToolCallback> toHarnessToolCallbacks(ToolCallback[] originToolCallbacks, Path workspaceDir, String pathParam) {
		return toHarnessToolCallbacks(List.of(originToolCallbacks), workspaceDir, pathParam);
	}

	public static List<ToolCallback> toHarnessToolCallbacks(List<ToolCallback> originToolCallbacks, Path workspaceDir, String pathParam) {
		return originToolCallbacks.stream().map(t -> new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return t.getToolDefinition();
			}

			@Override
			public String call(String toolInput) {
				if (StringUtils.hasText(toolInput)) {
					try {
						Map<String, Object> toolInputAsMap = OBJECT_MAPPER.readValue(toolInput, Map.class);
						String pathText = (String) toolInputAsMap.get(pathParam);
						if (StringUtils.hasText(pathText)) {
							Path inputPath = Path.of(pathText).normalize();
							if (!inputPath.isAbsolute()) {
								return "The %s must be an absolute path. Current value: %s".formatted(pathParam, pathText);
							}
							if (!inputPath.startsWith(workspaceDir.normalize().toAbsolutePath())) {
								return "Access to path '%s' is strictly prohibited because it is outside the workspace '%s'."
										.formatted(pathText, workspaceDir.toString());
							}
						}
					} catch (Exception e) {
						logger.debug("Failed to parse tool input as map: " + toolInput, e);
					}
				}

				return t.call(toolInput);
			}
		}).collect(Collectors.toList());
	}

}
