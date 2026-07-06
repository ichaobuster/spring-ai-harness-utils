package io.github.springai.harness.controller;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.dto.FileItemDto;
import io.github.springai.harness.dto.WorkspaceInfoDto;
import io.github.springai.harness.storage.AliyunOssStorage;
import io.github.springai.harness.storage.StorageProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for administrator workspace management.
 * Base path: /api/v1/admin
 *
 * @author buyc
 */
@RestController
@RequestMapping("/api/v1/admin")
@Slf4j
public class AdminApiController {

	private static final String ADMIN_HEADER = "X-Admin-Token";

	@Autowired
	private OSS ossClient;

	@Autowired
	private HarnessMcpServerProperties properties;

	private void checkAdminAuth(HttpServletRequest request) {
		String token = request.getHeader(ADMIN_HEADER);
		if (token == null || !token.equals(properties.getAdminToken())) {
			throw new SecurityException("Invalid or missing " + ADMIN_HEADER);
		}
	}

	@GetMapping("/workspaces")
	public ResponseEntity<?> listWorkspaces(HttpServletRequest request) {
		try {
			checkAdminAuth(request);

			String prefix = properties.getOssPrefix();
			ListObjectsRequest listObjectsRequest = new ListObjectsRequest(properties.getOssBucket())
					.withPrefix(prefix)
					.withDelimiter("/");

			ObjectListing listing = ossClient.listObjects(listObjectsRequest);
			List<WorkspaceInfoDto> result = new ArrayList<>();

			for (String commonPrefix : listing.getCommonPrefixes()) {
				String sub = commonPrefix.substring(prefix.length());
				String key = sub.endsWith("/") ? sub.substring(0, sub.length() - 1) : sub;

				String[] parts = key.split("-");
				String system = parts.length > 0 ? parts[0] : "unknown";
				String agent = parts.length > 1 ? parts[1] : "unknown";
				String user = parts.length > 2 ? parts[2] : "unknown";

				result.add(new WorkspaceInfoDto(key, system, agent, user, commonPrefix));
			}

			return ResponseEntity.ok(result);
		} catch (SecurityException e) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Failed to list workspaces for admin: {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	protected StorageProvider createStorageProvider(String workspaceKey) {
		String workspacePrefix = properties.getOssPrefix() + workspaceKey + "/";
		return new AliyunOssStorage(ossClient, properties.getOssBucket(), workspacePrefix);
	}

	@GetMapping("/workspaces/{workspaceKey}/files")
	public ResponseEntity<?> listWorkspaceFiles(
			HttpServletRequest request,
			@PathVariable("workspaceKey") String workspaceKey,
			@RequestParam(value = "path", required = false, defaultValue = "") String path) {
		try {
			checkAdminAuth(request);

			StorageProvider storage = createStorageProvider(workspaceKey);

			List<StorageProvider.Info> items = storage.listDirectory(path);
			List<FileItemDto> dtoList = new ArrayList<>();
			for (StorageProvider.Info item : items) {
				dtoList.add(new FileItemDto(item.path(), item.isDirectory(), item.size(), item.lastModified()));
			}

			return ResponseEntity.ok(dtoList);
		} catch (SecurityException e) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Failed to list files for admin workspace '{}': {}", workspaceKey, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	@DeleteMapping("/workspaces/{workspaceKey}/files")
	public ResponseEntity<?> deleteWorkspaceFile(
			HttpServletRequest request,
			@PathVariable("workspaceKey") String workspaceKey,
			@RequestParam("path") String path) {
		try {
			checkAdminAuth(request);

			StorageProvider storage = createStorageProvider(workspaceKey);

			if (!storage.exists(path)) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Path not found: " + path));
			}

			storage.delete(path);
			return ResponseEntity.ok(Map.of("message", "File deleted successfully", "workspaceKey", workspaceKey, "path", path));
		} catch (SecurityException e) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Failed to delete file for admin workspace '{}': {}", workspaceKey, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}

	@PostMapping("/workspaces/{workspaceKey}/files/move")
	public ResponseEntity<?> moveWorkspaceFile(
			HttpServletRequest request,
			@PathVariable("workspaceKey") String workspaceKey,
			@RequestParam("fromPath") String fromPath,
			@RequestParam("toPath") String toPath) {
		try {
			checkAdminAuth(request);

			StorageProvider storage = createStorageProvider(workspaceKey);
			if (!storage.exists(fromPath)) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Source path not found: " + fromPath));
			}

			storage.rename(fromPath, toPath);
			return ResponseEntity.ok(Map.of("message", "File moved successfully", "workspaceKey", workspaceKey, "fromPath", fromPath, "toPath", toPath));
		} catch (SecurityException e) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			log.error("Failed to move file for admin workspace '{}' from '{}' to '{}': {}", workspaceKey, fromPath, toPath, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
		}
	}
}
