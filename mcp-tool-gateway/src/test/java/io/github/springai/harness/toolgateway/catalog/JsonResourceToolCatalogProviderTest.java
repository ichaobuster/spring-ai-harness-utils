package io.github.springai.harness.toolgateway.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JsonResourceToolCatalogProviderTest {

	private ObjectMapper objectMapper;
	private ResourceLoader resourceLoader;
	private JsonResourceToolCatalogProvider provider;

	@BeforeEach
	void setUp() {
		objectMapper = new ObjectMapper();
		resourceLoader = new DefaultResourceLoader();
		provider = new JsonResourceToolCatalogProvider("classpath:tool-catalog.json", objectMapper, resourceLoader);
	}

	@Test
	void testLoadAllSuccess() {
		List<ToolDefinition> tools = provider.loadAll();
		assertThat(tools).isNotEmpty();
		assertThat(tools).hasSize(3);

		ToolDefinition first = tools.get(0);
		assertThat(first.name()).isEqualTo("weather_query");
		assertThat(first.isEnabled()).isTrue();
		assertThat(first.annotations().readOnlyHint()).isTrue();
		assertThat(first.httpEndpoint().methodOrDefault()).isEqualTo("GET");
	}

	@Test
	void testFindByName() {
		Optional<ToolDefinition> tool = provider.findByName("text_translate");
		assertThat(tool).isPresent();
		assertThat(tool.get().name()).isEqualTo("text_translate");

		Optional<ToolDefinition> notFound = provider.findByName("non_existent");
		assertThat(notFound).isEmpty();
	}

	@Test
	void testReload() {
		provider.loadAll();
		provider.reload();
		List<ToolDefinition> tools = provider.loadAll();
		assertThat(tools).hasSize(3);
	}

	@Test
	void testFileNotFoundGraceful() {
		JsonResourceToolCatalogProvider invalidProvider = new JsonResourceToolCatalogProvider(
				"classpath:non-existent.json", objectMapper, resourceLoader);
		List<ToolDefinition> tools = invalidProvider.loadAll();
		assertThat(tools).isEmpty();
	}
}
