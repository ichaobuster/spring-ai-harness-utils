package io.github.springai.harness.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Local file system implementation of the StorageProvider.
 * Uses java.nio.file.Files and Path APIs to manage memory files.
 *
 * @author ichaobuster
 */
@Slf4j
public class LocalFileStorage implements StorageProvider {

	private final Path baseDir;

	public LocalFileStorage(Path baseDir) {
		Assert.notNull(baseDir, "baseDir must not be null");
		this.baseDir = baseDir.normalize();
		try {
			Files.createDirectories(this.baseDir);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to create base directory: " + this.baseDir, e);
		}
	}

	public Path getBaseDir() {
		return this.baseDir;
	}

	@Override
	public StorageProvider subDirProvider(String subDir) {
		return new LocalFileStorage(this.baseDir.resolve(subDir));
	}

	@Override
	public boolean exists(String path) {
		return Files.exists(resolveSafePath(path));
	}

	@Override
	public boolean isDirectory(String path) {
		return Files.isDirectory(resolveSafePath(path));
	}

	@Override
	public List<Info> listDirectory(String path) throws IOException {
		List<Info> details = new ArrayList<>();
		Path dir = resolveSafePath(path);
		try (Stream<Path> pathStream = Files.list(dir)) {
			List<Path> entries = pathStream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (Files.isDirectory(entry)) {
					details.add(new Info(name, true, true, 0, Files.getLastModifiedTime(entry).toMillis()));
				} else {
					details.add(new Info(name, true, false, Files.size(entry), Files.getLastModifiedTime(entry).toMillis()));
				}
			}
		}
		return details;
	}

	@Override
	public String readString(String path) throws IOException {
		return Files.readString(resolveSafePath(path), StandardCharsets.UTF_8);
	}

	@Override
	public List<String> readAllLines(String path) throws IOException {
		return Files.readAllLines(resolveSafePath(path), StandardCharsets.UTF_8);
	}

	@Override
	public void writeString(String path, String content) throws IOException {
		Path target = resolveSafePath(path);
		Path parent = target.getParent();
		if (parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		Files.writeString(target, content != null ? content : "", StandardCharsets.UTF_8);
	}

	@Override
	public void delete(String path) throws IOException {
		Path target = resolveSafePath(path);
		if (target.equals(this.baseDir)) {
			throw new SecurityException("Cannot delete the root directory.");
		}

		if (Files.isDirectory(target)) {
			try (Stream<Path> walk = Files.walk(target)) {
				walk.sorted(Comparator.reverseOrder()).forEach(p -> {
					try {
						Files.delete(p);
					} catch (IOException e) {
						throw new RuntimeException("Failed to delete: " + p, e);
					}
				});
			}
		} else {
			Files.deleteIfExists(target);
		}
	}

	@Override
	public void rename(String oldPath, String newPath) throws IOException {
		Path source = resolveSafePath(oldPath);
		Path destination = resolveSafePath(newPath);

		Path destParent = destination.getParent();
		if (destParent != null && !Files.exists(destParent)) {
			Files.createDirectories(destParent);
		}

		Files.move(source, destination);
	}

	@Override
	public Info getInfo(String path) throws IOException {
		Path safePath = resolveSafePath(path);
		if (!exists(path)) {
			return new Info(path, false, false, 0, 0);
		}
		if (isDirectory(path)) {
			return new Info(path, true, true, 0, Files.getLastModifiedTime(safePath).toMillis());
		}
		return new Info(path, true, false, Files.size(safePath), Files.getLastModifiedTime(safePath).toMillis());
	}

	@Override
	public List<String> glob(String pattern, String path) throws IOException {
		Path searchPath = resolveSafePath(path);

		if (!Files.exists(searchPath)) {
			throw new IOException("Error: Path does not exist: " + searchPath.toString());
		}
		if (!Files.isDirectory(searchPath)) {
			throw new IOException("Error: Path is not a directory: " + searchPath.toString());
		}

		// Build glob matcher
		PathMatcher matcher = this.buildGlobMatcher(pattern);

		// Find matching files
		List<Path> matchedPaths = new ArrayList<>();

		try (Stream<Path> paths = Files.walk(searchPath, MAX_DEPTH, FileVisitOption.FOLLOW_LINKS)) {
			matchedPaths = paths.filter(Files::isRegularFile)
					.filter(p -> !this.isIgnoredPath(p.toString()))
					.filter(p -> matcher.matches(p))
					.limit(MAX_RESULT)
					.sorted((p1, p2) -> {
						try {
							return Long.compare(Files.getLastModifiedTime(p2).toMillis(), Files.getLastModifiedTime(p1).toMillis());
						} catch (IOException e) {
							return 0;
						}
					}).collect(Collectors.toList());
		}

		return matchedPaths.stream()
				.map(p -> this.baseDir.relativize(p).toString())
				.collect(Collectors.toList());
	}

	@Override
	public List<String> grep(String pattern, String path, String glob, GrepOutputMode outputMode, Integer contextBefore, Integer contextAfter, Integer context, Boolean showLineNumbers, Boolean caseInsensitive, Integer headLimit, Integer offset, Boolean multiline) throws IOException {
		// Determine search path - use configured workingDirectory if path not specified
		Path searchPath = resolveSafePath(path);

		if (!Files.exists(searchPath)) {
			throw new IOException("Error: Path does not exist: " + searchPath.toString());
		}

		// Compile regex pattern
		Pattern searchPattern = compileRegexPattern(pattern, caseInsensitive, multiline);

		// Determine output mode
		outputMode = outputMode != null ? outputMode : GrepOutputMode.files_with_matches;

		// Build glob matchers
		PathMatcher globMatcher = this.buildGlobMatcher(glob);

		// Perform search based on mode
		List<String> result;
		switch (outputMode) {
			case count:
				result = this.searchCount(searchPath, searchPattern, globMatcher, headLimit, offset);
				break;
			case content:
				int beforeContext = context != null ? context : (contextBefore != null ? contextBefore : 0);
				int afterContext = context != null ? context : (contextAfter != null ? contextAfter : 0);
				boolean lineNumbers = showLineNumbers == null || showLineNumbers;
				result = this.searchContent(searchPath, searchPattern, globMatcher, beforeContext, afterContext,
						lineNumbers, headLimit, offset);
				break;
			default: // default as files_with_matches
				result = this.searchFilesWithMatches(searchPath, searchPattern, globMatcher, headLimit, offset);
		}
		return result;
	}

	/**
	 * Resolves a user-supplied relative path against the memories directory,
	 * guarding against path traversal attacks and absolute path injection.
	 */
	private Path resolveSafePath(String relativePath) {
		if (!StringUtils.hasText(relativePath) || relativePath.equals("/")) {
			return this.baseDir;
		}
		Path userPath = Paths.get(relativePath);
		if (userPath.isAbsolute()) {
			throw new SecurityException("Absolute paths are not allowed: '" + relativePath + "'");
		}
		Path resolved = this.baseDir.resolve(userPath).normalize();
		if (!resolved.startsWith(this.baseDir)) {
			throw new SecurityException(
					"Path traversal attempt detected: '" + relativePath + "' escapes the memories directory");
		}
		return resolved;
	}

	/**
	 * Search for files containing matches (files_with_matches mode)
	 */
	private List<String> searchFilesWithMatches(Path searchPath, Pattern pattern, PathMatcher matcher,
												Integer headLimit, Integer offset) throws IOException {

		List<String> matchingFiles = new ArrayList<>();
		AtomicInteger count = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : DEFAULT_HEAD_LIMIT;

		this.processFiles(searchPath, matcher, file -> {
			if (count.get() >= skip + limit) {
				return false; // Stop processing
			}

			if (this.fileContainsPattern(file, pattern)) {
				if (count.getAndIncrement() >= skip) {
					matchingFiles.add(this.baseDir.relativize(file).toString());
				}
			}
			return true; // Continue processing
		});

		return matchingFiles;
	}

	/**
	 * Search and count matches per file (count mode)
	 */
	private List<String> searchCount(Path searchPath, Pattern pattern, PathMatcher matcher, Integer headLimit,
									 Integer offset) throws IOException {

		Map<String, Integer> fileCounts = new LinkedHashMap<>();
		AtomicInteger fileCount = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : Integer.MAX_VALUE;

		this.processFiles(searchPath, matcher, file -> {
			if (fileCount.get() >= skip + limit) {
				return false; // Stop processing
			}

			int matches = this.countMatchesInFile(file, pattern);
			if (matches > 0) {
				if (fileCount.getAndIncrement() >= skip) {
					fileCounts.put(this.baseDir.relativize(file).toString(), matches);
				}
			}
			return true; // Continue processing
		});
		return fileCounts.entrySet().stream()
				.map(entry -> entry.getKey() + ": " + entry.getValue())
				.collect(Collectors.toList());
	}

	/**
	 * Search and show matching content with context (content mode)
	 */
	private List<String> searchContent(Path searchPath, Pattern pattern, PathMatcher matcher, int beforeContext,
									   int afterContext, boolean lineNumbers, Integer headLimit, Integer offset) throws IOException {

		List<String> result = new ArrayList<>();
		AtomicInteger lineCount = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : Integer.MAX_VALUE;

		this.processFiles(searchPath, matcher, file -> {
			if (lineCount.get() >= skip + limit) {
				return false; // Stop processing
			}

			List<String> matches = this.findMatchesWithContext(file, pattern, beforeContext, afterContext, lineNumbers);
			if (!matches.isEmpty()) {
				StringBuilder singleResultSb = new StringBuilder();
				// Add file header
				singleResultSb.append(this.baseDir.relativize(file).toString()).append("\n");

				// Add matches with offset and limit
				for (String match : matches) {
					if (lineCount.get() >= skip + limit) {
						break;
					}
					if (lineCount.getAndIncrement() >= skip) {
						singleResultSb.append(match).append("\n");
					}
				}
				result.add(singleResultSb.toString());
			}
			return lineCount.get() < skip + limit; // Continue if under limit
		});

		return result;
	}

	/**
	 * Process files in the search path
	 */
	private void processFiles(Path searchPath, PathMatcher matcher, FileProcessor processor) throws IOException {
		if (Files.isRegularFile(searchPath)) {
			// Single file
			if (matcher == null || matcher.matches(searchPath)) {
				processor.process(searchPath);
			}
		} else if (Files.isDirectory(searchPath)) {
			// Directory traversal
			try (Stream<Path> paths = Files.walk(searchPath, MAX_DEPTH, FileVisitOption.FOLLOW_LINKS)) {
				paths.filter(Files::isRegularFile)
						.filter(p -> matcher == null || matcher.matches(p))
						.filter(p -> !this.isIgnoredPath(p.toString()))
						.anyMatch(file -> !processor.process(file)); // Stop when processor
				// returns false
			}
		}
	}

	/**
	 * Check if file contains the pattern
	 */
	private boolean fileContainsPattern(Path file, Pattern pattern) {
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return fileContainsPattern(pattern, reader);
		} catch (IOException e) {
			// Skip files that can't be read
		}
		return false;
	}

	/**
	 * Count matches in a file
	 */
	private int countMatchesInFile(Path file, Pattern pattern) {
		AtomicInteger count = new AtomicInteger(0);
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			this.countMatchesInFile(reader, pattern, count);
		} catch (IOException e) {
			// Skip files that can't be read
		}
		return count.get();
	}

	/**
	 * Find matches with context lines
	 */
	private List<String> findMatchesWithContext(Path file, Pattern pattern, int beforeContext, int afterContext,
												boolean lineNumbers) {
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return findMatchesWithContext(reader, pattern, beforeContext, afterContext, lineNumbers);
		} catch (IOException e) {
			// Skip files that can't be read
		}

		return new ArrayList<>();
	}

	/**
	 * Functional interface for file processing
	 */
	@FunctionalInterface
	private interface FileProcessor {

		/**
		 * Process a file
		 *
		 * @return true to continue processing, false to stop
		 */
		boolean process(Path file);

	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private Path baseDir = Paths.get("/tmp");

		private Builder() {
		}

		/**
		 * Set the root directory where all available files are stored.
		 * Defaults to {@code /tmp}.
		 *
		 * @param baseDir the root directory
		 * @return this builder
		 */
		public Builder baseDir(Path baseDir) {
			this.baseDir = baseDir;
			return this;
		}

		/**
		 * Set the root directory where all available files are stored using a string path.
		 *
		 * @param baseDir the root directory as string
		 * @return this builder
		 */
		public Builder baseDir(String baseDir) {
			this.baseDir = baseDir != null ? Paths.get(baseDir) : Paths.get("/tmp");
			return this;
		}

		public LocalFileStorage build() {
			Assert.notNull(this.baseDir, "baseDir must not be null");
			return new LocalFileStorage(baseDir);
		}

	}

}