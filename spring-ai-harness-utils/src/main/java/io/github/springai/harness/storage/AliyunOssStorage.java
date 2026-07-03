package io.github.springai.harness.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Aliyun OSS implementation of the StorageProvider.
 * Uses the Aliyun OSS SDK to manage memory files.
 *
 * @author ichaobuster
 */
public class AliyunOssStorage implements StorageProvider {

	private final OSS ossClient;

	private final String bucketName;

	private final String prefix;

	private final AntPathMatcher pathMatcher = new AntPathMatcher();

	public AliyunOssStorage(OSS ossClient, String bucketName, String prefix) {
		Assert.notNull(ossClient, "ossClient must not be null");
		Assert.hasText(bucketName, "bucketName must not be empty");
		this.ossClient = ossClient;
		this.bucketName = bucketName;

		String ossPrefix = StringUtils.hasText(prefix) ? (prefix.endsWith("/") ? prefix : prefix + "/") : "";
		// TODO 是否要判断 "\" 及多个 "/" 的情况
		if (ossPrefix.startsWith("/")) {
			ossPrefix = ossPrefix.substring(1);
		}
		this.prefix = ossPrefix;
	}

	@Override
	public char getSeparator() {
		return '/';
	}

	@Override
	public StorageProvider subDirProvider(String subDir) {
		return new AliyunOssStorage(this.ossClient, this.bucketName, this.prefix + subDir);
	}

	@Override
	public boolean exists(String path) {
		return this.ossClient.doesObjectExist(this.bucketName, getFullKey(path));
	}

	@Override
	public boolean isDirectory(String path) {
		// In OSS, a path is a directory if there are objects with this prefix ending in '/'
		// or if we treat the prefix itself as a directory.
		String key = toPathPrefix(path);
		return this.ossClient.listObjects(new ListObjectsRequest(this.bucketName).withPrefix(key).withMaxKeys(1))
				.getObjectSummaries()
				.size() > 0;
	}

	@Override
	public List<Info> listDirectory(String path) throws IOException {
		List<Info> details = new ArrayList<>();

		String keyPrefix = getFullKey(path);
		if (StringUtils.hasText(keyPrefix) && !keyPrefix.endsWith("/")) {
			keyPrefix += "/";
		}

		ObjectListing listResult = this.ossClient.listObjects(new ListObjectsRequest(this.bucketName).withPrefix(keyPrefix).withDelimiter("/"));

		// Common prefixes are "directories"
		for (String commonPrefix : listResult.getCommonPrefixes()) {
			String name = commonPrefix.substring(keyPrefix.length());
			details.add(new Info(name, true, true, 0, 0));
		}

		// Objects are "files"
		for (OSSObjectSummary summary : listResult.getObjectSummaries()) {
			String name = summary.getKey().substring(keyPrefix.length());
			if (StringUtils.hasText(name)) {
				details.add(new Info(name, true, false, summary.getSize(), summary.getLastModified().getTime()));
			}
		}

		return details;
	}

	@Override
	public String readString(String path) throws IOException {
		try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, getFullKey(path));
			 InputStream is = ossObject.getObjectContent()) {
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Override
	public String readImage(String path) throws IOException {
		try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, getFullKey(path));
			 InputStream is = ossObject.getObjectContent()) {
			ObjectMetadata metadata = ossObject.getObjectMetadata();
			String contentType = metadata != null ? metadata.getContentType() : null;
			return ImageStorageUtil.toBase64ImageString(is.readAllBytes(), path, contentType);
		}
	}

	@Override
	public List<String> readAllLines(String path) throws IOException {
		try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, getFullKey(path));
			 InputStream is = ossObject.getObjectContent();
			 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			return reader.lines().collect(Collectors.toList());
		}
	}

	@Override
	public void writeString(String path, String content) throws IOException {
		byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
		try (InputStream is = new ByteArrayInputStream(bytes)) {
			this.ossClient.putObject(this.bucketName, getFullKey(path), is);
		}
	}

	@Override
	public void delete(String path) throws IOException {
		String key = getFullKey(path);
		if (isDirectory(path)) {
			// Recursive delete
			if (!key.endsWith("/")) {
				key += "/";
			}
			String nextMarker = null;
			do {
				ObjectListing listResult = this.ossClient.listObjects(new ListObjectsRequest(this.bucketName).withPrefix(key).withMarker(nextMarker));
				List<String> keysToDelete = listResult.getObjectSummaries().stream().map(OSSObjectSummary::getKey).collect(Collectors.toList());
				if (!keysToDelete.isEmpty()) {
					this.ossClient.deleteObjects(new DeleteObjectsRequest(this.bucketName).withKeys(keysToDelete));
				}
				nextMarker = listResult.getNextMarker();
			} while (nextMarker != null);
		} else {
			this.ossClient.deleteObject(this.bucketName, key);
		}
	}

	@Override
	public void rename(String oldPath, String newPath) throws IOException {
		String sourceKey = getFullKey(oldPath);
		String destKey = getFullKey(newPath);

		if (isDirectory(oldPath)) {
			// OSS doesn't have a direct rename for "directories". Must copy and delete all objects.
			if (!sourceKey.endsWith("/")) sourceKey += "/";
			if (!destKey.endsWith("/")) destKey += "/";

			String nextMarker = null;
			do {
				ObjectListing listResult = this.ossClient.listObjects(new ListObjectsRequest(this.bucketName).withPrefix(sourceKey).withMarker(nextMarker));
				for (OSSObjectSummary summary : listResult.getObjectSummaries()) {
					String relativeKey = summary.getKey().substring(sourceKey.length());
					this.ossClient.copyObject(this.bucketName, summary.getKey(), this.bucketName, destKey + relativeKey);
					this.ossClient.deleteObject(this.bucketName, summary.getKey());
				}
				nextMarker = listResult.getNextMarker();
			} while (nextMarker != null);
		} else {
			this.ossClient.copyObject(this.bucketName, sourceKey, this.bucketName, destKey);
			this.ossClient.deleteObject(this.bucketName, sourceKey);
		}
	}

	@Override
	public List<String> glob(String pattern, String path) throws IOException {
		// Build pathPrefix
		String pathPrefix = toPathPrefix(path);
		// Build PathMatcher pattern from glob pattern
		final String matcherPattern = globToMatcherPattern(pattern, pathPrefix);
		// Find all files in the path
		List<OSSObjectSummary> allObjects = getOssObjectSummaries(pathPrefix);

		List<OSSObjectSummary> matchedSummaries = allObjects.stream()
				.filter(obj -> !obj.getKey().endsWith("/"))
				.filter(obj -> pathMatcher.match(matcherPattern, obj.getKey()))
				.sorted((o1, o2) -> o2.getLastModified().compareTo(o1.getLastModified()))
				.collect(Collectors.toList());

		return matchedSummaries.stream()
				.limit(MAX_RESULT)
				.map(s -> s.getKey().substring(this.prefix.length()))
				.collect(Collectors.toList());
	}

	private List<OSSObjectSummary> getOssObjectSummaries(String pathPrefix) {
		List<OSSObjectSummary> allObjects = new ArrayList<>();
		String nextMarker = null;
		ObjectListing objectListing;
		int currentDepth = 0;

		do {
			ListObjectsRequest listObjectsRequest = new ListObjectsRequest(this.bucketName)
					.withPrefix(pathPrefix)
					.withMarker(nextMarker);
			objectListing = this.ossClient.listObjects(listObjectsRequest);
			allObjects.addAll(objectListing.getObjectSummaries());
			nextMarker = objectListing.getNextMarker();
			currentDepth++;
		} while (objectListing.isTruncated() || currentDepth >= MAX_DEPTH);

		return allObjects;
	}

	private String globToMatcherPattern(String pattern, String pathPrefix) {
		if (!StringUtils.hasText(pattern)) {
			return null;
		}
		String matchPattern = pattern.startsWith("**/") ? pattern : "**/" + pattern;
		return matchPattern.replaceAll("/{2,}", "/");
	}

	private String toPathPrefix(String path) {
		String pathPrefix = getFullKey(path);
		if (!pathPrefix.endsWith("/")) {
			pathPrefix += "/";
		}
		return pathPrefix;
	}

	@Override
	public List<String> grep(String pattern, String path, String glob, GrepOutputMode outputMode, Integer contextBefore, Integer contextAfter, Integer context, Boolean showLineNumbers, Boolean caseInsensitive, Integer headLimit, Integer offset, Boolean multiline) throws IOException {
		// Build pathPrefix
		String pathPrefix = toPathPrefix(path);
		// Build glob PathMatcher pattern from glob pattern
		String globPattern = globToMatcherPattern(glob, pathPrefix);

		// Compile regex pattern
		Pattern searchPattern = compileRegexPattern(pattern, caseInsensitive, multiline);

		// Determine output mode
		outputMode = outputMode != null ? outputMode : GrepOutputMode.files_with_matches;

		// Perform search based on mode
		List<String> result;
		switch (outputMode) {
			case count:
				result = this.searchCount(path, searchPattern, globPattern, headLimit, offset);
				break;
			case content:
				int beforeContext = context != null ? context : (contextBefore != null ? contextBefore : 0);
				int afterContext = context != null ? context : (contextAfter != null ? contextAfter : 0);
				boolean lineNumbers = showLineNumbers == null || showLineNumbers;
				result = this.searchContent(path, searchPattern, globPattern, beforeContext, afterContext,
						lineNumbers, headLimit, offset);
				break;
			default: // default as files_with_matches
				result = this.searchFilesWithMatches(path, searchPattern, globPattern, headLimit, offset);
		}

		return result;
	}

	/**
	 * Search for files containing matches (files_with_matches mode)
	 */
	private List<String> searchFilesWithMatches(String searchPath, Pattern pattern, String globPattern,
												Integer headLimit, Integer offset) throws IOException {

		List<String> matchingFiles = new ArrayList<>();
		AtomicInteger count = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : DEFAULT_HEAD_LIMIT;

		this.processFiles(searchPath, globPattern, fullKey -> {
			if (count.get() >= skip + limit) {
				return false; // Stop processing
			}

			if (this.fileContainsPattern(fullKey, pattern)) {
				if (count.getAndIncrement() >= skip) {
					matchingFiles.add(fullKey.substring(this.prefix.length()));
				}
			}
			return true; // Continue processing
		});

		return matchingFiles;
	}

	/**
	 * Search and count matches per file (count mode)
	 */
	private List<String> searchCount(String searchPath, Pattern pattern, String globPattern, Integer headLimit,
									 Integer offset) throws IOException {

		Map<String, Integer> fileCounts = new LinkedHashMap<>();
		AtomicInteger fileCount = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : Integer.MAX_VALUE;

		this.processFiles(searchPath, globPattern, fullKey -> {
			if (fileCount.get() >= skip + limit) {
				return false; // Stop processing
			}

			int matches = this.countMatchesInFile(fullKey, pattern);
			if (matches > 0) {
				if (fileCount.getAndIncrement() >= skip) {
					fileCounts.put(fullKey.substring(this.prefix.length()), matches);
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
	private List<String> searchContent(String searchPath, Pattern pattern, String globPattern, int beforeContext,
									   int afterContext, boolean lineNumbers, Integer headLimit, Integer offset) throws IOException {

		List<String> result = new ArrayList<>();
		AtomicInteger lineCount = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : Integer.MAX_VALUE;

		this.processFiles(searchPath, globPattern, fullKey -> {
			if (lineCount.get() >= skip + limit) {
				return false; // Stop processing
			}

			List<String> matches = this.findMatchesWithContext(fullKey, pattern, beforeContext, afterContext, lineNumbers);
			if (!matches.isEmpty()) {
				StringBuilder singleResultSb = new StringBuilder();
				// Add file header
				singleResultSb.append(fullKey.substring(this.prefix.length())).append("\n");

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
	private void processFiles(String path, String globPattern, OssProcessor processor) throws IOException {
		if (!isDirectory(path)) {
			// Single file
			if (globPattern == null || pathMatcher.match(globPattern, getFullKey(path))) {
				processor.process(path);
			}
		} else {
			// Directory traversal
			String pathPrefix = toPathPrefix(path);
			// Find all files in the path
			getOssObjectSummaries(pathPrefix).stream()
					.filter(obj -> !obj.getKey().endsWith("/"))
					.filter(obj -> globPattern == null || pathMatcher.match(globPattern, obj.getKey()))
					.filter(obj -> !this.isIgnoredPath(obj.toString()))
					.anyMatch(obj -> !processor.process(obj.getKey())); // Stop when processor
		}
	}

	/**
	 * Check if file contains the pattern
	 */
	private boolean fileContainsPattern(String fullKey, Pattern pattern) {
		try (OSSObject ossObject = ossClient.getObject(bucketName, fullKey);
			 BufferedReader reader = new BufferedReader(new InputStreamReader(ossObject.getObjectContent(), StandardCharsets.UTF_8))) {
			return fileContainsPattern(pattern, reader);
		} catch (Exception e) {
			// Skip files that can't be read
		}
		return false;
	}

	/**
	 * Count matches in a fullKey
	 */
	private int countMatchesInFile(String fullKey, Pattern pattern) {
		AtomicInteger count = new AtomicInteger(0);
		try (OSSObject ossObject = ossClient.getObject(bucketName, fullKey);
			 BufferedReader reader = new BufferedReader(new InputStreamReader(ossObject.getObjectContent(), StandardCharsets.UTF_8))) {
			this.countMatchesInFile(reader, pattern, count);
		} catch (IOException e) {
			// Skip files that can't be read
		}
		return count.get();
	}

	/**
	 * Find matches with context lines
	 */
	private List<String> findMatchesWithContext(String fullKey, Pattern pattern, int beforeContext, int afterContext,
												boolean lineNumbers) {
		try (OSSObject ossObject = ossClient.getObject(bucketName, fullKey);
			 BufferedReader reader = new BufferedReader(new InputStreamReader(ossObject.getObjectContent(), StandardCharsets.UTF_8))) {
			return findMatchesWithContext(reader, pattern, beforeContext, afterContext, lineNumbers);
		} catch (IOException e) {
			// Skip files that can't be read
		}

		return new ArrayList<>();
	}

	@FunctionalInterface
	private interface OssProcessor {

		/**
		 * Process a oss file
		 *
		 * @return true to continue processing, false to stop
		 */
		boolean process(String fullKey);

	}

	@Override
	public Info getInfo(String path) throws IOException {
		if (isDirectory(path)) {
			return new Info(path, true, true, 0, 0);
		}
		ObjectMetadata objectMetadata = this.ossClient.getObjectMetadata(this.bucketName, getFullKey(path));
		if (objectMetadata == null || !exists(path)) {
			return new Info(path, false, false, 0, 0);
		}
		return new Info(path, true, false, objectMetadata.getContentLength(), objectMetadata.getLastModified().getTime());
	}

	private String getFullKey(String path) {
		if (!StringUtils.hasText(path)) {
			return this.prefix;
		}

		if (path.startsWith("/")) {
			throw new SecurityException("Absolute paths are not allowed: '" + path + "'");
		}
		
		if (path.startsWith("./")) {
			return this.prefix + path.substring(2);
		}
		return this.prefix + path;
	}

}
