package io.github.springai.harness.snapshot;

import io.github.springai.harness.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of {@link SnapshotProvider}.
 * Captures pre-operation snapshots in .snapshots/{snapshotId}/ and supports rewind operations.
 *
 * @author buyc
 */
@Slf4j
public class DefaultSnapshotProvider implements SnapshotProvider {

	private static final String SNAPSHOT_DIR = ".snapshots";
	private static final AtomicLong COUNTER = new AtomicLong(0);

	@Override
	public String createSnapshot(StorageProvider storage, String filePath, String action) throws IOException {
		if (storage == null || !StringUtils.hasText(filePath)) {
			return null;
		}

		// Only snapshot existing files (not directories)
		if (!storage.exists(filePath) || storage.isDirectory(filePath)) {
			return null;
		}

		long timestamp = System.currentTimeMillis();
		String snapshotId = timestamp + "_" + COUNTER.incrementAndGet();
		String snapshotPath = SNAPSHOT_DIR + "/" + snapshotId + "/" + filePath;
		String metaPath = SNAPSHOT_DIR + "/" + snapshotId + "/meta.txt";

		try {
			String originalContent = storage.readString(filePath);
			storage.writeString(snapshotPath, originalContent);

			String metaContent = String.format("filePath=%s\naction=%s\ntimestamp=%d\n", filePath, action, timestamp);
			storage.writeString(metaPath, metaContent);

			log.debug("Created snapshot {} for {} before {}", snapshotId, filePath, action);
			return snapshotId;
		} catch (Exception e) {
			log.warn("Failed to create snapshot for {}: {}", filePath, e.getMessage());
			return null;
		}
	}

	@Override
	public List<SnapshotInfo> listSnapshots(StorageProvider storage, String filterFilePath) throws IOException {
		List<SnapshotInfo> result = new ArrayList<>();
		if (storage == null || !storage.exists(SNAPSHOT_DIR)) {
			return result;
		}

		List<StorageProvider.Info> snapshotDirs = storage.listDirectory(SNAPSHOT_DIR);
		for (StorageProvider.Info dir : snapshotDirs) {
			if (!dir.isDirectory()) {
				continue;
			}

			String snapshotId = dir.path().endsWith("/")
					? dir.path().substring(0, dir.path().length() - 1)
					: dir.path();

			String metaPath = SNAPSHOT_DIR + "/" + snapshotId + "/meta.txt";
			if (!storage.exists(metaPath)) {
				continue;
			}

			try {
				String metaContent = storage.readString(metaPath);
				SnapshotMeta meta = parseMeta(metaContent);
				if (meta != null) {
					if (!StringUtils.hasText(filterFilePath) || filterFilePath.trim().equals(meta.filePath)) {
						String snapshotPath = SNAPSHOT_DIR + "/" + snapshotId + "/" + meta.filePath;
						result.add(new SnapshotInfo(snapshotId, meta.filePath, meta.action, snapshotPath, meta.timestamp));
					}
				}
			} catch (Exception e) {
				log.warn("Failed to read snapshot metadata for {}: {}", snapshotId, e.getMessage());
			}
		}

		result.sort(Comparator.comparingLong(SnapshotInfo::timestamp).reversed());
		return result;
	}

	@Override
	public String rewind(StorageProvider storage, String snapshotId) throws IOException {
		if (storage == null || !StringUtils.hasText(snapshotId)) {
			return "Error: snapshotId must not be empty.";
		}

		String cleanId = snapshotId.trim();
		String metaPath = SNAPSHOT_DIR + "/" + cleanId + "/meta.txt";

		if (!storage.exists(metaPath)) {
			return "Error: Snapshot not found: " + cleanId;
		}

		String metaContent = storage.readString(metaPath);
		SnapshotMeta meta = parseMeta(metaContent);
		if (meta == null || !StringUtils.hasText(meta.filePath)) {
			return "Error: Corrupted snapshot metadata for: " + cleanId;
		}

		String snapshotPath = SNAPSHOT_DIR + "/" + cleanId + "/" + meta.filePath;
		if (!storage.exists(snapshotPath)) {
			return "Error: Snapshot file content missing: " + cleanId;
		}

		// Create a snapshot of current state before rewinding
		createSnapshot(storage, meta.filePath, "REWIND");

		String restoredContent = storage.readString(snapshotPath);
		storage.writeString(meta.filePath, restoredContent);

		return String.format("Successfully rewound file '%s' to snapshot state [%s].", meta.filePath, cleanId);
	}

	private SnapshotMeta parseMeta(String content) {
		if (content == null || content.isBlank()) {
			return null;
		}

		String filePath = null;
		String action = "UNKNOWN";
		long timestamp = 0L;

		String[] lines = content.split("\n");
		for (String line : lines) {
			int idx = line.indexOf('=');
			if (idx > 0) {
				String key = line.substring(0, idx).trim();
				String val = line.substring(idx + 1).trim();
				if ("filePath".equals(key)) {
					filePath = val;
				} else if ("action".equals(key)) {
					action = val;
				} else if ("timestamp".equals(key)) {
					try {
						timestamp = Long.parseLong(val);
					} catch (NumberFormatException ignored) {
					}
				}
			}
		}

		return filePath != null ? new SnapshotMeta(filePath, action, timestamp) : null;
	}

	private record SnapshotMeta(String filePath, String action, long timestamp) {
	}
}
