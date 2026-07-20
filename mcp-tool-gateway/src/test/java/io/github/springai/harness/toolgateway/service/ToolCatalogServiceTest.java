package io.github.springai.harness.toolgateway.service;

import io.github.springai.harness.toolgateway.catalog.ToolAnnotations;
import io.github.springai.harness.toolgateway.catalog.ToolCatalogProvider;
import io.github.springai.harness.toolgateway.catalog.ToolDefinition;
import io.github.springai.harness.toolgateway.filter.ToolPermissionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolCatalogServiceTest {

	@Mock
	private ToolCatalogProvider catalogProvider;

	@Mock
	private ToolPermissionFilter permissionFilter;

	private ToolCatalogService service;

	private ToolDefinition enabledTool;
	private ToolDefinition disabledTool;
	private Map<String, String> sampleHeaders;

	@BeforeEach
	void setUp() {
		service = new ToolCatalogService(catalogProvider, permissionFilter);
		sampleHeaders = Map.of("Authorization", "Bearer token");

		enabledTool = new ToolDefinition(
				"enabled_tool",
				"description 1",
				Map.of("type", "object"),
				null,
				new ToolAnnotations("Title", true, false, true, false),
				null,
				List.of("tag1"),
				true
		);

		disabledTool = new ToolDefinition(
				"disabled_tool",
				"description 2",
				null,
				null,
				null,
				null,
				null,
				false
		);
	}

	@Test
	void testListToolsSuccess() {
		when(catalogProvider.loadAll()).thenReturn(List.of(enabledTool, disabledTool));
		when(permissionFilter.filter(any(), eq(sampleHeaders))).thenReturn(List.of(enabledTool));

		List<Map<String, Object>> result = service.listTools(sampleHeaders);

		assertThat(result).hasSize(1);
		Map<String, Object> map = result.get(0);
		assertThat(map.get("name")).isEqualTo("enabled_tool");
		assertThat(map.get("description")).isEqualTo("description 1");
		assertThat(map.get("inputSchema")).isEqualTo(Map.of("type", "object"));
		
		@SuppressWarnings("unchecked")
		Map<String, Object> annotations = (Map<String, Object>) map.get("annotations");
		assertThat(annotations).isNotNull();
		assertThat(annotations.get("title")).isEqualTo("Title");
		assertThat(annotations.get("readOnlyHint")).isEqualTo(true);
	}

	@Test
	void testFindToolSuccess() {
		when(catalogProvider.findByName("enabled_tool")).thenReturn(Optional.of(enabledTool));
		when(permissionFilter.filter(any(), eq(sampleHeaders))).thenReturn(List.of(enabledTool));

		Optional<ToolDefinition> tool = service.findTool("enabled_tool", sampleHeaders);
		assertThat(tool).isPresent();
		assertThat(tool.get().name()).isEqualTo("enabled_tool");
	}

	@Test
	void testFindToolNotFoundOrDisabled() {
		when(catalogProvider.findByName("disabled_tool")).thenReturn(Optional.of(disabledTool));
		Optional<ToolDefinition> tool = service.findTool("disabled_tool", sampleHeaders);
		assertThat(tool).isEmpty();
	}

	@Test
	void testFindToolPermissionDenied() {
		when(catalogProvider.findByName("enabled_tool")).thenReturn(Optional.of(enabledTool));
		when(permissionFilter.filter(any(), eq(sampleHeaders))).thenReturn(Collections.emptyList());

		Optional<ToolDefinition> tool = service.findTool("enabled_tool", sampleHeaders);
		assertThat(tool).isEmpty();
	}
}
