package io.github.springai.harness.controller;

import io.github.springai.harness.dto.FileItemDto;
import io.github.springai.harness.snapshot.SnapshotInfo;
import io.github.springai.harness.snapshot.SnapshotProvider;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.function.ServerRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for user workspace file and snapshot management.
 * Base path: /api/v1/workspace
 *
 * @author buyc
 */
@RestController
@RequestMapping("/api/v1/workspace")
@Slf4j
public class WorkspaceApiController {

	@Autowired
	private StorageProviderFactory storageProviderFactory;

	@Autowired
	private SnapshotProvider snapshotProvider;

	protected StorageProvider getStorageProvider(HttpServletRequest request) {
		ServerRequest serverRequest = ServerRequest.create(request, Collections.emptyList());
		McpTransportContext context = McpTransportContext.create(Map.of(McpTransportContext.KEY, serverRequest));
		return storageProviderFactory.getStorageProvider(context);
	}

	@GetMapping("/files")
	public ResponseEntity<?> listFiles(
			HttpServletRequest request,
			@RequestParam(value = "path", required = false, defaultValue = "") String path) {
		try {
			StorageProvider storage = getStorageProvider(request);
			List<StorageProvider.Info> items = storage.listDirectory(path);
			List<FileItemDto> dtoList = new ArrayList<>();
			for (StorageProvider.Info item : items) {
				dtoList.add(new FileItemDto(item.path(), item.isDirectory(), item.size(), item.lastModified()));
			}
			return ResponseEntity.ok(dtoList);
		} catch (Exception e) {
			log.error("Failed to list workspace files for path '{}': {}", path, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/files/content")
	public ResponseEntity<?> getFileContent(
			HttpServletRequest request,
			@RequestParam("path") String path) {
		try {
			StorageProvider storage = getStorageProvider(request);
			if (!storage.exists(path)) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File not found: " + path));
			}
			if (storage.isDirectory(path)) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Path is a directory: " + path));
			}
			String content = storage.readString(path);
			return ResponseEntity.ok(content);
		} catch (Exception e) {
			log.error("Failed to read file content for '{}': {}", path, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	@PostMapping("/files/upload")
	public ResponseEntity<?> uploadFile(
			HttpServletRequest request,
			@RequestParam("path") String path,
			@RequestBody String content) {
		try {
			StorageProvider storage = getStorageProvider(request);
			snapshotProvider.createSnapshot(storage, path, "WRITE");
			storage.writeString(path, content != null ? content : "");
			return ResponseEntity.ok(Map.of("message", "File uploaded successfully", "path", path));
		} catch (Exception e) {
			log.error("Failed to upload file to '{}': {}", path, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	@DeleteMapping("/files")
	public ResponseEntity<?> deleteFile(
			HttpServletRequest request,
			@RequestParam("path") String path,
			@RequestParam(value = "trash", required = false, defaultValue = "true") boolean trash) {
		try {
			StorageProvider storage = getStorageProvider(request);
			if (!storage.exists(path)) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Path not found: " + path));
			}
			snapshotProvider.createSnapshot(storage, path, "TRASH");
			if (trash) {
				storage.trash(path);
				return ResponseEntity.ok(Map.of("message", "Moved to trash successfully", "path", path));
			} else {
				storage.delete(path);
				return ResponseEntity.ok(Map.of("message", "Deleted file successfully", "path", path));
			}
		} catch (Exception e) {
			log.error("Failed to delete file '{}': {}", path, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	@GetMapping("/snapshots")
	public ResponseEntity<?> listSnapshots(
			HttpServletRequest request,
			@RequestParam(value = "path", required = false) String path) {
		try {
			StorageProvider storage = getStorageProvider(request);
			List<SnapshotInfo> snapshots = snapshotProvider.listSnapshots(storage, path);
			return ResponseEntity.ok(snapshots);
		} catch (Exception e) {
			log.error("Failed to list snapshots: {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	@PostMapping("/rewind/{snapshotId}")
	public ResponseEntity<?> rewind(
			HttpServletRequest request,
			@PathVariable("snapshotId") String snapshotId) {
		try {
			StorageProvider storage = getStorageProvider(request);
			String result = snapshotProvider.rewind(storage, snapshotId);
			if (result.startsWith("Error:")) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", result));
			}
			return ResponseEntity.ok(Map.of("message", result));
		} catch (Exception e) {
			log.error("Failed to rewind snapshot '{}': {}", snapshotId, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	@PostMapping("/files/move")
	public ResponseEntity<?> moveFile(
			HttpServletRequest request,
			@RequestParam("fromPath") String fromPath,
			@RequestParam("toPath") String toPath) {
		try {
			StorageProvider storage = getStorageProvider(request);
			if (!storage.exists(fromPath)) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Source path not found: " + fromPath));
			}
			snapshotProvider.createSnapshot(storage, fromPath, "MOVE");
			storage.rename(fromPath, toPath);
			return ResponseEntity.ok(Map.of("message", "File moved successfully", "fromPath", fromPath, "toPath", toPath));
		} catch (Exception e) {
			log.error("Failed to move file from '{}' to '{}': {}", fromPath, toPath, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}
}
