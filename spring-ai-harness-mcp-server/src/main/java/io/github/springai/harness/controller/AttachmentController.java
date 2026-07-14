package io.github.springai.harness.controller;

import io.github.springai.harness.dto.AttachmentDto;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.function.ServerRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 附件上传及管理 REST API 控制器。
 * 基础路径: /api/v1/workspace/attachments
 *
 * @author ichaobuster
 */
@RestController
@RequestMapping("/api/v1/workspace/attachments")
@Slf4j
public class AttachmentController {

	@Autowired
	private StorageProviderFactory storageProviderFactory;

	@Autowired
	private HarnessMcpServerProperties properties;

	/**
	 * 从 HttpServletRequest 中解析工作空间 StorageProvider。
	 */
	protected StorageProvider getStorageProvider(HttpServletRequest request) {
		ServerRequest serverRequest = ServerRequest.create(request, Collections.emptyList());
		McpTransportContext context = McpTransportContext.create(Map.of(McpTransportContext.KEY, serverRequest));
		return storageProviderFactory.getStorageProvider(context);
	}

	/**
	 * 上传单个附件接口。
	 *
	 * @param request        HTTP 请求
	 * @param file           上传的二进制文件
	 * @param conversationId 可选的会话标识
	 * @return 上传成功的附件元数据
	 */
	@PostMapping
	public ResponseEntity<?> uploadAttachment(
			HttpServletRequest request,
			@RequestParam("file") MultipartFile file,
			@RequestParam(value = "conversationId", required = false) String conversationId) throws Exception {

		if (file == null || file.isEmpty()) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(Map.of("error", "Uploaded file is empty"));
		}

		StorageProvider storage = getStorageProvider(request);

		// 1. 确定 conversationId
		String convId = StringUtils.hasText(conversationId)
				? conversationId.trim()
				: properties.getAttachment().getDefaultConversationId();

		// 2. 净化原始文件名，防止目录穿越和特殊字符问题
		String originalName = file.getOriginalFilename();
		if (originalName == null || originalName.isBlank()) {
			originalName = "unnamed";
		}
		String sanitizedName = originalName.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
		sanitizedName = sanitizedName.replace("..", "_");

		// 3. 生成唯一的 UUID 目录，避免同名冲突
		String uuid = UUID.randomUUID().toString();

		// 4. 组装存储路径：attachments/conversationId/uuid/sanitizedName
		String basePath = properties.getAttachment().getBasePath();
		String path = basePath + "/" + convId + "/" + uuid + "/" + sanitizedName;

		log.info("开始上传附件到工作空间路径: {}, 大小: {} 字节", path, file.getSize());

		// 5. 写入文件（通过 Quota 校验装饰器写入二进制流）
		try (InputStream is = file.getInputStream()) {
			storage.writeFile(path, is, file.getSize());
		}

		long now = System.currentTimeMillis();
		AttachmentDto dto = new AttachmentDto(uuid, convId, sanitizedName, path, file.getSize(), now);
		return ResponseEntity.ok(dto);
	}

	/**
	 * 列举工作空间下的所有附件（支持按 conversationId 过滤）。
	 *
	 * @param request        HTTP 请求
	 * @param conversationId 可选的会话过滤标识
	 * @return 附件元数据列表
	 */
	@GetMapping
	public ResponseEntity<?> listAttachments(
			HttpServletRequest request,
			@RequestParam(value = "conversationId", required = false) String conversationId) throws Exception {

		StorageProvider storage = getStorageProvider(request);
		List<AttachmentDto> result = new ArrayList<>();
		String basePath = properties.getAttachment().getBasePath();

		if (!storage.exists(basePath)) {
			return ResponseEntity.ok(result);
		}

		if (StringUtils.hasText(conversationId)) {
			String convPath = basePath + "/" + conversationId.trim();
			if (storage.exists(convPath) && storage.isDirectory(convPath)) {
				traverseConversationDir(storage, conversationId.trim(), convPath, result);
			}
		} else {
			List<StorageProvider.Info> convDirs = storage.listDirectory(basePath);
			for (StorageProvider.Info convDir : convDirs) {
				if (convDir.isDirectory()) {
					String convId = convDir.path();
					if (convId.endsWith("/")) {
						convId = convId.substring(0, convId.length() - 1);
					}
					String convPath = basePath + "/" + convId;
					traverseConversationDir(storage, convId, convPath, result);
				}
			}
		}

		return ResponseEntity.ok(result);
	}

	/**
	 * 删除指定的附件（移动到垃圾箱 .trash/ 目录中）。
	 *
	 * @param request        HTTP 请求
	 * @param attachmentId   附件的 UUID
	 * @param conversationId 可选的会话标识
	 */
	@DeleteMapping("/{attachmentId}")
	public ResponseEntity<?> deleteAttachment(
			HttpServletRequest request,
			@PathVariable("attachmentId") String attachmentId,
			@RequestParam(value = "conversationId", required = false) String conversationId,
			@RequestParam(value = "trash", required = false, defaultValue = "true") boolean trash) throws Exception {

		StorageProvider storage = getStorageProvider(request);
		String basePath = properties.getAttachment().getBasePath();
		String convId = StringUtils.hasText(conversationId) ? conversationId.trim() : null;
		String targetPath = null;

		if (convId != null) {
			String path = basePath + "/" + convId + "/" + attachmentId;
			if (storage.exists(path)) {
				targetPath = path;
			}
		} else {
			// 未指定会话 ID 时，遍历所有会话子目录进行精确查找
			if (storage.exists(basePath)) {
				List<StorageProvider.Info> convDirs = storage.listDirectory(basePath);
				for (StorageProvider.Info convDir : convDirs) {
					if (convDir.isDirectory()) {
						String cId = convDir.path();
						if (cId.endsWith("/")) {
							cId = cId.substring(0, cId.length() - 1);
						}
						String path = basePath + "/" + cId + "/" + attachmentId;
						if (storage.exists(path)) {
							targetPath = path;
							break;
						}
					}
				}
			}
		}

		if (targetPath == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("error", "Attachment not found: " + attachmentId));
		}

		if (trash) {
			log.info("将附件目录移动至垃圾箱: {}", targetPath);
			storage.trash(targetPath);
			return ResponseEntity.ok(Map.of("message", "Attachment moved to trash successfully", "attachmentId", attachmentId));
		} else {
			log.info("直接彻底删除附件目录: {}", targetPath);
			storage.delete(targetPath);
			return ResponseEntity.ok(Map.of("message", "Attachment deleted successfully", "attachmentId", attachmentId));
		}
	}

	private void traverseConversationDir(StorageProvider storage, String conversationId, String convPath, List<AttachmentDto> result) throws IOException {
		List<StorageProvider.Info> uuidDirs = storage.listDirectory(convPath);
		for (StorageProvider.Info uuidDir : uuidDirs) {
			if (uuidDir.isDirectory()) {
				String uuid = uuidDir.path();
				if (uuid.endsWith("/")) {
					uuid = uuid.substring(0, uuid.length() - 1);
				}
				String uuidPath = convPath + "/" + uuid;
				List<StorageProvider.Info> files = storage.listDirectory(uuidPath);
				for (StorageProvider.Info file : files) {
					if (!file.isDirectory()) {
						String fileName = file.path();
						String filePath = uuidPath + "/" + fileName;
						result.add(new AttachmentDto(uuid, conversationId, fileName, filePath, file.size(), file.lastModified()));
					}
				}
			}
		}
	}
}
