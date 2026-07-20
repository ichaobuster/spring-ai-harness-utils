package io.github.springai.harness.toolgateway.filter;

import io.github.springai.harness.toolgateway.catalog.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AllowAllToolPermissionFilterTest {

	@Test
	void testFilterAllowsAll() {
		AllowAllToolPermissionFilter filter = new AllowAllToolPermissionFilter();
		ToolDefinition tool = new ToolDefinition("test_tool", "desc", null, null, null, null, null, true);

		List<ToolDefinition> result = filter.filter(List.of(tool), Map.of("Authorization", "Bearer token"));
		assertThat(result).hasSize(1);
		assertThat(result.get(0).name()).isEqualTo("test_tool");
	}

	@Test
	void testFilterWithNullList() {
		AllowAllToolPermissionFilter filter = new AllowAllToolPermissionFilter();
		List<ToolDefinition> result = filter.filter(null, Map.of("Authorization", "Bearer token"));
		assertThat(result).isEmpty();
	}
}
