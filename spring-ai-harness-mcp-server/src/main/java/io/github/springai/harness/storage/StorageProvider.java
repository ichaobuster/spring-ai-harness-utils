package io.github.springai.harness.storage;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Storage provider.
 * Encapsulates all persistent storage operations (read, write, delete, list).
 * All paths are relative to the storage.
 *
 * @author buyc
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

	List<String> IGNORED_PATH_PATTERN = List.of("/.git/", "/node_modules/", "/target/", "/build/", "/.idea/", "/.vscode/", "/dist/", "/__pycache__/");

	default char getSeparator() {
		return File.separatorChar;
	}

	default boolean isIgnoredPath(String path) {
		return IGNORED_PATH_PATTERN.stream().anyMatch(ignoredPathPattern -> path.contains(ignoredPathPattern));
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
	record Info(String path, boolean exists, boolean isDirectory, long size, long lastModified) {
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
}
