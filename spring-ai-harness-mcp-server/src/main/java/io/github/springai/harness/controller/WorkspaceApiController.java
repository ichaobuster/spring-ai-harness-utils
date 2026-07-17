package io.github.springai.harness.controller;

import io.github.springai.harness.dto.FileItemDto;
import io.github.springai.harness.dto.WorkspaceSyncRequest;
import io.github.springai.harness.service.WorkspaceSyncService;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.snapshot.SnapshotInfo;
import io.github.springai.harness.snapshot.SnapshotProvider;
import io.github.springai.harness.storage.QuotaManager;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.function.ServerRequest;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for user workspace file and snapshot management.
 * Base path: /api/v1/workspace
 *
 * @author ichaobuster
 */
@RestController
@RequestMapping("/api/v1/workspace")
@Slf4j
public class WorkspaceApiController {

	@Autowired
	private StorageProviderFactory storageProviderFactory;

	@Autowired
	private SnapshotProvider snapshotProvider;

	@Autowired
	private WorkspaceSyncService workspaceSyncService;

	@Autowired
	private HarnessMcpServerProperties properties;

	@Autowired
	private QuotaManager quotaManager;

	protected StorageProvider getStorageProvider(HttpServletRequest request) {
		ServerRequest serverRequest = ServerRequest.create(request, Collections.emptyList());
		McpTransportContext context = McpTransportContext.create(Map.of(McpTransportContext.KEY, serverRequest));
		return storageProviderFactory.getStorageProvider(context);
	}

	@GetMapping("/files")
	public ResponseEntity<?> listFiles(
			HttpServletRequest request,
			@RequestParam(value = "path", required = false, defaultValue = "") String path) throws Exception {
		StorageProvider storage = getStorageProvider(request);
		List<StorageProvider.Info> items = storage.listDirectory(path);
		List<FileItemDto> dtoList = new ArrayList<>();
		for (StorageProvider.Info item : items) {
			dtoList.add(new FileItemDto(item.path(), item.isDirectory(), item.size(), item.lastModified()));
		}
		return ResponseEntity.ok(dtoList);
	}

	@GetMapping("/files/content")
	public ResponseEntity<?> getFileContent(
			HttpServletRequest request,
			@RequestParam("path") String path) throws Exception {
		StorageProvider storage = getStorageProvider(request);
		if (!storage.exists(path)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "File not found: " + path));
		}
		if (storage.isDirectory(path)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Path is a directory: " + path));
		}
		String content = storage.readString(path);
		return ResponseEntity.ok(content);
	}

	@GetMapping("/files/download")
	public ResponseEntity<?> downloadFile(
			HttpServletRequest request,
			@RequestParam("path") String path) throws Exception {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("path must not be empty");
		}
		StorageProvider storage = getStorageProvider(request);
		if (!storage.exists(path)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("error", "File not found: " + path));
		}
		if (storage.isDirectory(path)) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("error", "Path is a directory: " + path));
		}

		StorageProvider.Info info = storage.getInfo(path);
		String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
		if (filename.isBlank()) {
			filename = "download";
		}

		InputStream is = storage.readStream(path);
		InputStreamResource resource = new InputStreamResource(is);

		String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
				.replace("+", "%20");

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename)
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.contentLength(info.size())
				.body(resource);
	}


	@PostMapping("/files/upload")
	public ResponseEntity<?> uploadFile(
			HttpServletRequest request,
			@RequestParam("path") String path,
			@RequestParam("file") MultipartFile file) throws Exception {
		if (file == null || file.isEmpty()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("error", "Uploaded file is empty"));
		}
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("path must not be empty");
		}
		StorageProvider storage = getStorageProvider(request);
		snapshotProvider.createSnapshot(storage, path, "WRITE");
		try (InputStream is = file.getInputStream()) {
			storage.writeFile(path, is, file.getSize());
		}
		return ResponseEntity.ok(Map.of("message", "File uploaded successfully", "path", path));
	}

	@DeleteMapping("/files")
	public ResponseEntity<?> deleteFile(
			HttpServletRequest request,
			@RequestParam("path") String path,
			@RequestParam(value = "trash", required = false, defaultValue = "true") boolean trash) throws Exception {
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
	}

	@GetMapping("/snapshots")
	public ResponseEntity<?> listSnapshots(
			HttpServletRequest request,
			@RequestParam(value = "path", required = false) String path) throws Exception {
		StorageProvider storage = getStorageProvider(request);
		List<SnapshotInfo> snapshots = snapshotProvider.listSnapshots(storage, path);
		return ResponseEntity.ok(snapshots);
	}

	@PostMapping("/rewind/{snapshotId}")
	public ResponseEntity<?> rewind(
			HttpServletRequest request,
			@PathVariable("snapshotId") String snapshotId) throws Exception {
		StorageProvider storage = getStorageProvider(request);
		String result = snapshotProvider.rewind(storage, snapshotId);
		return ResponseEntity.ok(Map.of("message", result));
	}

	@PostMapping("/files/move")
	public ResponseEntity<?> moveFile(
			HttpServletRequest request,
			@RequestParam("fromPath") String fromPath,
			@RequestParam("toPath") String toPath) throws Exception {
		StorageProvider storage = getStorageProvider(request);
		if (!storage.exists(fromPath)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Source path not found: " + fromPath));
		}
		snapshotProvider.createSnapshot(storage, fromPath, "MOVE");
		storage.rename(fromPath, toPath);
		return ResponseEntity.ok(Map.of("message", "File moved successfully", "fromPath", fromPath, "toPath", toPath));
	}

	@PostMapping("/trash/empty")
	public ResponseEntity<?> emptyTrash(HttpServletRequest request) throws Exception {
		StorageProvider storage = getStorageProvider(request);
		storage.emptyTrash();
		return ResponseEntity.ok(Map.of("message", "Trash emptied successfully"));
	}

	@PostMapping("/directory")
	public ResponseEntity<?> createDirectory(
			HttpServletRequest request,
			@RequestParam("path") String path) throws Exception {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("path must not be empty");
		}
		StorageProvider storage = getStorageProvider(request);
		storage.createDirectory(path);
		return ResponseEntity.ok(Map.of("message", "Directory created successfully", "path", path));
	}

	@PostMapping("/files/trash")
	public ResponseEntity<?> trashFile(
			HttpServletRequest request,
			@RequestParam("path") String path) throws Exception {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("path must not be empty");
		}
		StorageProvider storage = getStorageProvider(request);
		if (!storage.exists(path)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("error", "Path not found: " + path));
		}
		snapshotProvider.createSnapshot(storage, path, "TRASH");
		storage.trash(path);
		return ResponseEntity.ok(Map.of("message", "Moved to trash successfully", "path", path));
	}

	@GetMapping("/quota")
	public ResponseEntity<?> getQuota(HttpServletRequest request) throws Exception {
		StorageProvider storage = getStorageProvider(request);
		long usedBytes = quotaManager.getUsedBytes(storage);
		long maxBytes = properties.getQuota().getMaxBytes();
		long remainingBytes = Math.max(0L, maxBytes - usedBytes);
		return ResponseEntity.ok(Map.of(
				"usedBytes", usedBytes,
				"maxBytes", maxBytes,
				"remainingBytes", remainingBytes,
				"enabled", properties.getQuota().isEnabled()
		));
	}



	@PostMapping("/sync")
	public void syncWorkspace(
			HttpServletRequest request,
			HttpServletResponse response,
			@RequestBody WorkspaceSyncRequest syncRequest) throws Exception {

		if (syncRequest == null || syncRequest.paths() == null || syncRequest.paths().isEmpty()) {
			response.sendError(HttpStatus.BAD_REQUEST.value(), "paths must not be empty");
			return;
		}

		StorageProvider storage = getStorageProvider(request);

		// Determine skillFullContent parameter: request value or fallback to property configuration
		boolean skillFull = syncRequest.skillFullContent() != null
				? syncRequest.skillFullContent()
				: properties.getSync().isSkillFullContent();

		// Parse and validate files
		List<String> filePaths = workspaceSyncService.resolveFilePaths(storage, syncRequest.paths(), skillFull);

		if (filePaths.isEmpty()) {
			response.sendError(HttpStatus.NOT_FOUND.value(), "No files found matching the requested paths");
			return;
		}

		// Set response headers and stream output ZIP
		response.setContentType("application/zip");
		response.setHeader("Content-Disposition", "attachment; filename=\"workspace-sync.zip\"");

		workspaceSyncService.writeZip(storage, filePaths, response.getOutputStream());
		response.flushBuffer();
	}
}
