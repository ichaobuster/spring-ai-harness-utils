package io.github.springai.harness.toolgateway.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 从 JSON 资源文件（例如 classpath:tool-catalog.json）读取工具定义的实现。
 * 具有简单的内存缓存与手动重新加载功能。
 *
 * @author ichaobuster
 */
@Slf4j
public class JsonResourceToolCatalogProvider implements ToolCatalogProvider {

	private final String catalogPath;
	private final ObjectMapper objectMapper;
	private final ResourceLoader resourceLoader;
	private final List<ToolDefinition> cache = new CopyOnWriteArrayList<>();
	private volatile boolean initialized = false;

	public JsonResourceToolCatalogProvider(String catalogPath, ObjectMapper objectMapper, ResourceLoader resourceLoader) {
		this.catalogPath = catalogPath;
		this.objectMapper = objectMapper;
		this.resourceLoader = resourceLoader;
	}

	@Override
	public List<ToolDefinition> loadAll() {
		if (!initialized) {
			synchronized (this) {
				if (!initialized) {
					reload();
					initialized = true;
				}
			}
		}
		return cache;
	}

	@Override
	public Optional<ToolDefinition> findByName(String toolName) {
		if (toolName == null || toolName.isBlank()) {
			return Optional.empty();
		}
		return loadAll().stream()
				.filter(tool -> toolName.equals(tool.name()))
				.findFirst();
	}

	/**
	 * 重新从 JSON 文件中加载工具目录定义。
	 */
	public synchronized void reload() {
		log.info("Loading MCP tool catalog from path: {}", catalogPath);
		try {
			Resource resource = resourceLoader.getResource(catalogPath);
			if (!resource.exists()) {
				log.warn("MCP tool catalog resource does not exist: {}", catalogPath);
				cache.clear();
				return;
			}
			try (InputStream inputStream = resource.getInputStream()) {
				List<ToolDefinition> tools = objectMapper.readValue(inputStream, new TypeReference<List<ToolDefinition>>() {});
				cache.clear();
				if (tools != null) {
					cache.addAll(tools);
				}
				log.info("Successfully loaded {} tool definitions from catalog", cache.size());
			}
		} catch (Exception e) {
			log.error("Failed to load MCP tool catalog from resource: " + catalogPath, e);
		}
	}
}
