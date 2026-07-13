package io.github.springai.harness.storage;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Storage provider.
 * Encapsulates all persistent storage operations (read, write, delete, list).
 * All paths are relative to the storage.
 *
 * @author ichaobuster
 */
public interface StorageProvider {

	Integer MAX_RESULT = 100;

	Integer MAX_DEPTH = 50;

	/**
	 * 如有变动，需同步修改 prompt
	 */
	Integer MAX_LINES = 2000;

	/**
	 * 如有变动，需同步修改 prompt
	 */
	Integer MAX_LINE_LENGTH = 2000;

	/**
	 * 如有变动，需同步修改 prompt
	 */
	Integer DEFAULT_HEAD_LIMIT = 250;

	/**
	 * 图片单边最大尺寸限制，超出将等比例缩放
	 */
	Integer MAX_IMAGE_EDGE = 2048;

	List<String> IGNORED_PATH_PATTERN = List.of("/.git/", "/node_modules/", "/target/", "/build/", "/.idea/", "/.vscode/", "/dist/", "/__pycache__/", "/.trash/", "/.snapshots/", "/.storage", "/.shadow/");
	List<String> INTERNAL_FILE_PREFIXES = List.of(".snapshots/", ".trash/", ".shadow/", ".storage");

	default char getSeparator() {
		return File.separatorChar;
	}

	default boolean isIgnoredPath(String path) {
		return IGNORED_PATH_PATTERN.stream().anyMatch(ignoredPathPattern -> path.contains(ignoredPathPattern));
	}

	/**
	 * 计算当前存储空间下所有文件的总大小（字节）。
	 *
	 * @param excludePrefixes 需要排除的路径前缀列表（如 ".snapshots/", ".trash/"）
	 * @return 总字节数
	 * @throws IOException 如果存储操作失败
	 */
	default long calculateTotalSize(List<String> excludePrefixes) throws IOException {
		return calculateTotalSizeRecursive("", excludePrefixes);
	}

	private long calculateTotalSizeRecursive(String currentDir, List<String> excludePrefixes) throws IOException {
		long total = 0;
		List<Info> items = listDirectory(currentDir);
		for (Info item : items) {
			String relativePath = currentDir.isEmpty() ? item.path() : currentDir + "/" + item.path();

			// 规范化路径分隔符
			String cleanPath = relativePath.replaceAll("/{2,}", "/");
			if (cleanPath.startsWith("/")) {
				cleanPath = cleanPath.substring(1);
			}

			boolean excluded = false;
			if (excludePrefixes != null) {
				for (String prefix : excludePrefixes) {
					String pathToCheck = cleanPath.endsWith("/") ? cleanPath : cleanPath + "/";
					if (pathToCheck.startsWith(prefix)) {
						excluded = true;
						break;
					}
				}
			}
			if (excluded) {
				continue;
			}

			if (item.isDirectory()) {
				total += calculateTotalSizeRecursive(cleanPath, excludePrefixes);
			} else {
				total += item.size();
			}
		}
		return total;
	}

	/**
	 * create the copy of storageProvider for sub directory.
	 *
	 * @param subDir sub directory of the new storageProvider
	 * @return copy of storage provider
	 */
	StorageProvider subDirProvider(String subDir);

	/**
	 * Checks if the given path exists in the memory store.
	 *
	 * @param path the path relative to the storage.
	 * @return true if the path exists.
	 */
	boolean exists(String path);

	/**
	 * Checks if the given path is a directory.
	 *
	 * @param path the path relative to the storage.
	 * @return true if it is a directory.
	 */
	boolean isDirectory(String path);

	/**
	 * Lists the detail contents of a directory in the store.
	 *
	 * @param path the directory path relative to the storage.
	 * @return item list of the contents.
	 * @throws IOException if an error occurs.
	 */
	List<Info> listDirectory(String path) throws IOException;

	/**
	 * Reads the entire content of a file as a string.
	 *
	 * @param path the file path relative to the storage.
	 * @return the file content.
	 * @throws IOException if an error occurs.
	 */
	String readString(String path) throws IOException;

	/**
	 * Reads all lines of a file.
	 *
	 * @param path the file path relative to the storage.
	 * @return the list of lines.
	 * @throws IOException if an error occurs.
	 */
	List<String> readAllLines(String path) throws IOException;

	/**
	 * Writes a string to a file. Overwrites if it exists.
	 *
	 * @param path    the file path relative to the storage.
	 * @param content the content to write.
	 * @throws IOException if an error occurs.
	 */
	void writeString(String path, String content) throws IOException;

	/**
	 * Moves a file or directory to the workspace trash (.trash/).
	 *
	 * @param path the path relative to the storage.
	 * @throws IOException if an error occurs.
	 */
	void trash(String path) throws IOException;

	/**
	 * Deletes a file or directory (recursively) from the memory store.
	 *
	 * @param path the path relative to the storage.
	 * @throws IOException if an error occurs.
	 */
	void delete(String path) throws IOException;

	/**
	 * Renames or moves a file or directory.
	 *
	 * @param oldPath current path.
	 * @param newPath new path.
	 * @throws IOException if an error occurs.
	 */
	void rename(String oldPath, String newPath) throws IOException;


	/**
	 * Find files by name patterns
	 *
	 * @param pattern glob pattern
	 * @param path    path to find
	 * @return list of files
	 */
	List<String> glob(String pattern, String path) throws IOException;

	/**
	 * A powerful search tool with grep parameters
	 *
	 * @return grep results
	 */
	List<String> grep(String pattern, String path, String glob, GrepOutputMode outputMode, Integer contextBefore, Integer contextAfter, Integer context, Boolean showLineNumbers, Boolean caseInsensitive, Integer headLimit, Integer offset, Boolean multiline) throws IOException;

	/**
	 * Resource information
	 */
	record Info(String path, boolean exists, boolean isDirectory, long size, long lastModified, String etag) {
		public Info(String path, boolean exists, boolean isDirectory, long size, long lastModified) {
			this(path, exists, isDirectory, size, lastModified, null);
		}
	}

	/**
	 * Get the information of path
	 *
	 * @param path the path relative to the storage.
	 * @return resource info
	 * @throws IOException
	 */
	Info getInfo(String path) throws IOException;

	/**
	 * Get the information of paths
	 *
	 * @param paths the paths relative to the storage.
	 * @return resource info list
	 */
	default List<Info> getInfo(List<String> paths) {
		return paths.stream().map(path -> {
			try {
				return getInfo(path);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}).toList();
	}

	/**
	 * Output modes for grep
	 */
	enum GrepOutputMode {// @formatter:off
		files_with_matches,
		count,
		content

	}

	/**
	 * Reads an image file and returns its base64-encoded string content. Supported formats: jpg, jpeg, png.
	 */
	String readImage(String path) throws IOException;

	/**
	 * Reads a PDF file and extracts its text content. Supports page ranges (1-based indices).
	 */
	String readPdf(String path, Integer startPage, Integer endPage) throws IOException;

	/**
	 * Reads an Office document (.docx, .xlsx, .pptx) and extracts its text.
	 */
	String readDocument(String path) throws IOException;

	/**
	 * 创建目录。
	 *
	 * @param path 相对目录路径
	 * @throws IOException 如果操作失败
	 */
	void createDirectory(String path) throws IOException;

	/**
	 * Creates a temporary download link (presigned URL) for a file.
	 *
	 * @param path relative path of the file
	 * @param ttl duration of link validity
	 * @return temporary download link information
	 * @throws IOException if storage operation fails
	 */
	default DownloadLink createDownloadLink(String path, Duration ttl) throws IOException {
		throw new UnsupportedOperationException("This storage implementation does not support generating download links");
	}
}
