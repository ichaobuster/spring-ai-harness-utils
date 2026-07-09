package io.github.springai.harness.storage;

import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Manager for workspace storage quota.
 * Tracks usage, validates limit and updates capacity using .storage file.
 *
 * @author ichaobuster
 */
@Slf4j
public class QuotaManager {

	private final HarnessMcpServerProperties.QuotaProperties quotaProperties;

	public QuotaManager(HarnessMcpServerProperties.QuotaProperties quotaProperties) {
		this.quotaProperties = quotaProperties;
	}

	public boolean isTrashIncluded() {
		return quotaProperties.isIncludeTrash();
	}

	public boolean isSnapshotsIncluded() {
		return quotaProperties.isIncludeSnapshots();
	}

	public String getMetaFile() {
		return quotaProperties.getMetaFile();
	}

	/**
	 * 获取已用容量（字节）。
	 * 如果元文件不存在、过期或损坏，将触发全量计算。
	 *
	 * @param storage 底层存储提供者（应避免传入包装了容量校验的外部提供者，防递归）
	 * @return 已用字节数
	 */
	public synchronized long getUsedBytes(StorageProvider storage) {
		String metaFile = quotaProperties.getMetaFile();
		try {
			if (storage.exists(metaFile) && !storage.isDirectory(metaFile)) {
				String content = storage.readString(metaFile);
				StorageMeta meta = parseMeta(content);
				if (meta != null && !isExpired(meta.calculatedAt())) {
					return meta.usedBytes();
				}
			}
		} catch (Exception e) {
			log.warn("读取容量元文件失败，将进行全量重计算: {}", e.getMessage());
		}

		return fullRecalculation(storage);
	}

	/**
	 * 校验写入 deltaBytes 大小后是否超限。
	 *
	 * @param storage 底层存储提供者
	 * @param deltaBytes 新增的字节数（可能为负数，但负数或零不进行校验）
	 * @throws QuotaExceededException 如果容量超限
	 */
	public void checkQuota(StorageProvider storage, long deltaBytes) {
		if (deltaBytes <= 0) {
			return;
		}
		long maxBytes = quotaProperties.getMaxBytes();
		long usedBytes = getUsedBytes(storage);
		if (usedBytes + deltaBytes > maxBytes) {
			throw new QuotaExceededException(
					String.format("Storage quota exceeded. Limit: %d bytes, Used: %d bytes, Requested: %d bytes.",
							maxBytes, usedBytes, deltaBytes),
					usedBytes, maxBytes, deltaBytes);
		}
	}

	/**
	 * 增量更新已用容量。
	 * 同时更新过期时间戳。
	 *
	 * @param storage 底层存储提供者
	 * @param deltaBytes 增量字节数
	 */
	public synchronized void updateUsedBytes(StorageProvider storage, long deltaBytes) {
		if (deltaBytes == 0) {
			return;
		}
		long usedBytes = getUsedBytes(storage);
		long newUsedBytes = Math.max(0L, usedBytes + deltaBytes);

		StorageMeta newMeta = new StorageMeta(newUsedBytes, System.currentTimeMillis());
		try {
			writeMeta(storage, newMeta);
		} catch (Exception e) {
			log.error("更新容量元文件失败: {}", e.getMessage(), e);
		}
	}

	/**
	 * 全量重新计算已用容量并更新元文件。
	 *
	 * @param storage 底层存储提供者
	 * @return 计算得到的总字节数
	 */
	public synchronized long fullRecalculation(StorageProvider storage) {
		log.info("触发工作空间全量容量重计算");
		try {
			List<String> excludePrefixes = getExcludePrefixes();
			long totalSize = storage.calculateTotalSize(excludePrefixes);
			StorageMeta meta = new StorageMeta(totalSize, System.currentTimeMillis());
			writeMeta(storage, meta);
			return totalSize;
		} catch (Exception e) {
			log.error("全量容量计算失败，默认返回 0: {}", e.getMessage(), e);
			return 0L;
		}
	}

	private List<String> getExcludePrefixes() {
		List<String> excludes = new ArrayList<>();
		if (!quotaProperties.isIncludeSnapshots()) {
			excludes.add(".snapshots/");
		}
		if (!quotaProperties.isIncludeTrash()) {
			excludes.add(".trash/");
		}
		// 元文件自身不计入容量
		excludes.add(quotaProperties.getMetaFile());
		return excludes;
	}

	private boolean isExpired(long calculatedAt) {
		Duration interval = quotaProperties.getRecalculationInterval();
		if (interval == null || interval.isNegative() || interval.isZero()) {
			return false;
		}
		return System.currentTimeMillis() - calculatedAt > interval.toMillis();
	}

	private void writeMeta(StorageProvider storage, StorageMeta meta) throws IOException {
		String content = String.format("usedBytes=%d\ncalculatedAt=%d\n", meta.usedBytes(), meta.calculatedAt());
		storage.writeString(quotaProperties.getMetaFile(), content);
	}

	private StorageMeta parseMeta(String content) {
		if (!StringUtils.hasText(content)) {
			return null;
		}
		Long usedBytes = null;
		Long calculatedAt = null;
		String[] lines = content.split("\n");
		for (String line : lines) {
			int idx = line.indexOf('=');
			if (idx > 0) {
				String key = line.substring(0, idx).trim();
				String val = line.substring(idx + 1).trim();
				if ("usedBytes".equals(key)) {
					try {
						usedBytes = Long.parseLong(val);
					} catch (NumberFormatException ignored) {}
				} else if ("calculatedAt".equals(key)) {
					try {
						calculatedAt = Long.parseLong(val);
					} catch (NumberFormatException ignored) {}
				}
			}
		}
		if (usedBytes != null && calculatedAt != null) {
			return new StorageMeta(usedBytes, calculatedAt);
		}
		return null;
	}

	public record StorageMeta(long usedBytes, long calculatedAt) {}
}
