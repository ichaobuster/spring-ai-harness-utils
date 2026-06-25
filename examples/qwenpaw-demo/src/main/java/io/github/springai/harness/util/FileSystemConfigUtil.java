package io.github.springai.harness.util;

import io.github.springai.harness.storage.StorageProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * FileSystemConfigUtil
 *
 * @author ichaobuster
 */
@Slf4j
public class FileSystemConfigUtil {

	private static ObjectMapper OBJECT_MAPPER;

	private FileSystemConfigUtil() {
	}

	private static ObjectMapper getObjectMapper() {
		if (OBJECT_MAPPER != null) {
			return OBJECT_MAPPER;
		}
		OBJECT_MAPPER = new ObjectMapper();
		OBJECT_MAPPER.registerModule(new JavaTimeModule());
		return OBJECT_MAPPER;
	}

	public static <T> T loadFromFile(StorageProvider storageProvider, String fileName, Class<T> configType, T defaultConfig) {
		if (!storageProvider.exists(fileName)) {
			return defaultConfig;
		}
		try {
			String configStr = storageProvider.readString(fileName);
			return getObjectMapper().readValue(configStr, configType);
		} catch (IOException e) {
			log.error("Failed to read config from file: " + fileName, e);
			return defaultConfig;
		}
	}

	public static void writeConfigIntoFile(StorageProvider storageProvider, String fileName, Object config) {
		try {
			String configStr = getObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(config);
			storageProvider.writeString(fileName, configStr);
		} catch (IOException e) {
			log.error("Failed to write config file: " + fileName, e);
		}
	}

}
