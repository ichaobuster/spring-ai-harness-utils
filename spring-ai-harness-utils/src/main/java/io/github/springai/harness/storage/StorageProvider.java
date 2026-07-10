package io.github.springai.harness.storage;

import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

	List<String> IGNORED_PATH_PATTERN = List.of("/.git/", "/node_modules/", "/target/", "/build/", "/.idea/", "/.vscode/", "/dist/", "/__pycache__/");

	default char getSeparator() {
		return File.separatorChar;
	}

	default boolean isIgnoredPath(String path) {
		return IGNORED_PATH_PATTERN.stream().anyMatch(ignoredPathPattern -> path.contains(ignoredPathPattern));
	}

	/**
	 * Build a PathMatcher from the glob pattern
	 */
	default PathMatcher buildGlobMatcher(String pattern) {
		if (!StringUtils.hasText(pattern)) {
			return null;
		}
		// Handle both simple globs (*.java) and complex globs (**/*.java)
		String globPattern = pattern.startsWith("**/") ? pattern : "**/" + pattern;
		return FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
	}

	/**
	 * Compile regex pattern
	 */
	default Pattern compileRegexPattern(String pattern, Boolean caseInsensitive, Boolean multiline) {
		int flags = Pattern.MULTILINE;
		if (Boolean.TRUE.equals(caseInsensitive)) {
			flags |= Pattern.CASE_INSENSITIVE;
		}
		if (Boolean.TRUE.equals(multiline)) {
			flags |= Pattern.DOTALL;
		}

		Pattern searchPattern;
		try {
			searchPattern = Pattern.compile(pattern, flags);
		} catch (Exception e) {
			throw new IllegalArgumentException("Error: Invalid regex pattern: " + e.getMessage());
		}
		return searchPattern;
	}

	default boolean fileContainsPattern(Pattern pattern, BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.length() > MAX_LINE_LENGTH) {
				continue;
			}
			if (pattern.matcher(line).find()) {
				return true;
			}
		}
		return false;
	}

	default void countMatchesInFile(BufferedReader reader, Pattern pattern, AtomicInteger count) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.length() > MAX_LINE_LENGTH) {
				continue;
			}
			Matcher matcher = pattern.matcher(line);
			while (matcher.find()) {
				count.incrementAndGet();
			}
		}
	}

	default List<String> findMatchesWithContext(BufferedReader reader, Pattern pattern, int beforeContext, int afterContext, boolean lineNumbers) {
		List<String> results = new ArrayList<>();

		List<String> allLines = reader.lines().collect(Collectors.toList());
		List<Integer> matchingLineNumbers = new ArrayList<>();

		// Find all matching line numbers
		for (int i = 0; i < allLines.size(); i++) {
			String line = allLines.get(i);
			if (line.length() > MAX_LINE_LENGTH) {
				continue;
			}
			if (pattern.matcher(line).find()) {
				matchingLineNumbers.add(i);
			}
		}

		// Extract matches with context
		for (int matchLineNum : matchingLineNumbers) {
			int start = Math.max(0, matchLineNum - beforeContext);
			int end = Math.min(allLines.size() - 1, matchLineNum + afterContext);

			for (int i = start; i <= end; i++) {
				String prefix = "";
				if (lineNumbers) {
					prefix = (i + 1) + ":";
				}
				if (i == matchLineNum) {
					prefix += "  "; // Indicate matching line
				} else {
					prefix += "- "; // Indicate context line
				}
				results.add(prefix + allLines.get(i));
			}

			// Add separator between match groups
			if (!matchingLineNumbers.isEmpty()) {
				results.add("--");
			}
		}

		// Remove trailing separator
		if (!results.isEmpty() && results.get(results.size() - 1).equals("--")) {
			results.remove(results.size() - 1);
		}

		return results;
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
	 * Reads an image file as a Base64 image string.
	 *
	 * @param path the image file path relative to the storage.
	 * @return the image content in {@code data:image/{type};base64,{payload}} format.
	 * @throws IOException if an error occurs or the file cannot be decoded as an image.
	 */
	String readImage(String path) throws IOException;

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

	/**
	 * 创建目录。
	 *
	 * @param path 相对目录路径
	 * @throws IOException 如果操作失败
	 */
	void createDirectory(String path) throws IOException;
}
