package io.github.springai.harness.service;

import io.github.springai.harness.storage.StorageConstants;
import io.github.springai.harness.storage.StorageProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 工作空间同步服务。
 * 负责路径安全校验、目录递归展开、skills 智能过滤和 zip 流式打包。
 *
 * @author ichaobuster
 */
@Service
@Slf4j
public class WorkspaceSyncService {

    /**
     * 禁止打包的根路径
     */
    private static final Set<String> FORBIDDEN_ROOT_PATHS = Set.of(
            "", ".", "/", "./"
    );

    /**
     * 同步文件数量上限
     */
    private static final int MAX_FILE_COUNT = 500;

    /**
     * SKILL.md 文件名
     */
    private static final String SKILL_MD = "SKILL.md";

    /**
     * 校验并展开请求路径列表，返回最终需要打包的文件相对路径集合。
     *
     * @param storage          存储提供者
     * @param paths            请求的文件/目录路径列表
     * @param skillFullContent 打包 skills/ 时是否包含全部文件
     * @return 展开后的文件路径列表
     * @throws IOException              如果存储操作失败
     * @throws IllegalArgumentException 如果路径安全校验失败或文件数量超限
     */
    public List<String> resolveFilePaths(StorageProvider storage, List<String> paths, boolean skillFullContent) throws IOException {
        List<String> resolvedFiles = new ArrayList<>();

        for (String path : paths) {
            String normalized = normalizePath(path);

            // 安全校验
            validatePath(normalized);

            if (!storage.exists(normalized)) {
                log.warn("同步跳过不存在的路径: {}", normalized);
                continue;
            }

            if (storage.isDirectory(normalized)) {
                // 目录递归展开
                boolean isSkillsDir = isSkillsPath(normalized);
                collectFilesRecursive(storage, normalized, resolvedFiles, isSkillsDir && !skillFullContent);
            } else {
                // 单文件直接加入
                resolvedFiles.add(normalized);
            }
        }

        if (resolvedFiles.size() > MAX_FILE_COUNT) {
            throw new IllegalArgumentException(
                    String.format("Requested file count (%d) exceeds limit: %d", resolvedFiles.size(), MAX_FILE_COUNT));
        }

        return resolvedFiles;
    }

    /**
     * 将文件列表打包成 zip 流式写入 OutputStream。
     *
     * @param storage      存储提供者
     * @param filePaths    待打包的文件相对路径列表
     * @param outputStream 输出流
     * @throws IOException 如果打包过程中发生错误
     */
    public void writeZip(StorageProvider storage, List<String> filePaths, OutputStream outputStream) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            for (String filePath : filePaths) {
                zos.putNextEntry(new ZipEntry(filePath));
                try (InputStream is = storage.readStream(filePath)) {
                    is.transferTo(zos);
                }
                zos.closeEntry();
            }
        }
    }

    /**
     * 规范化路径：去除多余斜杠、前导 "./"，以及尾部 "/"（用于统一比较）。
     */
    String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replaceAll("/{2,}", "/");
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        // 去除尾部斜杠，用于统一判断
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 安全校验路径。
     * 拒绝根目录、内部目录、路径逃逸和绝对路径。
     */
    void validatePath(String path) {
        // 拒绝根目录
        if (FORBIDDEN_ROOT_PATHS.contains(path)) {
            throw new IllegalArgumentException("Syncing root directory is not allowed");
        }

        // 拒绝绝对路径
        if (path.startsWith("/")) {
            throw new SecurityException("Absolute paths are not allowed: '" + path + "'");
        }

        // 拒绝路径逃逸
        if (path.contains("..")) {
            throw new SecurityException("Path traversal is not allowed: '" + path + "'");
        }

        // 拒绝内部目录精确匹配
        if (StorageConstants.FORBIDDEN_EXACT_PATHS.contains(path)) {
            throw new SecurityException("Access to internal path is denied: '" + path + "'");
        }

        // 拒绝内部目录前缀匹配
        for (String prefix : StorageConstants.FORBIDDEN_PREFIXES) {
            if (path.startsWith(prefix) || (path + "/").startsWith(prefix)) {
                throw new SecurityException("Access to internal path is denied: '" + path + "'");
            }
        }
    }

    /**
     * 判断路径是否为 skills 目录或其子目录。
     */
    boolean isSkillsPath(String path) {
        String check = path.endsWith("/") ? path : path + "/";
        return check.startsWith("skills/");
    }

    /**
     * 递归收集目录下的所有文件路径。
     *
     * @param storage          存储提供者
     * @param dirPath          当前目录路径
     * @param result           收集结果列表
     * @param skillsFilterOnly 是否仅收集 SKILL.md（skills 智能过滤模式）
     */
    private void collectFilesRecursive(StorageProvider storage, String dirPath, List<String> result, boolean skillsFilterOnly) throws IOException {
        List<StorageProvider.Info> items = storage.listDirectory(dirPath);
        for (StorageProvider.Info item : items) {
            // listDirectory 返回的 path 是相对于 dirPath 的名称，需要拼接完整相对路径
            String itemName = item.path();
            if (itemName.endsWith("/")) {
                itemName = itemName.substring(0, itemName.length() - 1);
            }
            String fullRelativePath = dirPath.isEmpty() ? itemName : dirPath + "/" + itemName;

            // 跳过被忽略的路径（node_modules、.git 等）
            if (storage.isIgnoredPath("/" + fullRelativePath + "/")) {
                log.debug("同步跳过被忽略的路径: {}", fullRelativePath);
                continue;
            }

            if (item.isDirectory()) {
                // 递归进入子目录
                collectFilesRecursive(storage, fullRelativePath, result, skillsFilterOnly);
            } else {
                // skills 过滤模式：只收集 SKILL.md
                if (skillsFilterOnly && !itemName.equals(SKILL_MD)) {
                    continue;
                }
                result.add(fullRelativePath);
            }
        }
    }

}
