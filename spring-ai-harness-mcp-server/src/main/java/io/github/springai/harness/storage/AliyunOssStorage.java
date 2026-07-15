package io.github.springai.harness.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
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
		return isDirectory(path) || this.ossClient.doesObjectExist(this.bucketName, getFullKey(path));
	}

	@Override
	public boolean isDirectory(String path) {
		// In OSS, a path is a directory if there are objects with this prefix ending in '/'
		// or if we treat the prefix itself as a directory.
		String key = toPathPrefix(path);
		return !this.ossClient.listObjects(new ListObjectsRequest(this.bucketName).withPrefix(key).withMaxKeys(1))
				.getObjectSummaries().isEmpty();
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
				String etag = summary.getETag() != null ? summary.getETag().replace("\"", "") : null;
				details.add(new Info(name, true, false, summary.getSize(), summary.getLastModified().getTime(), etag));
			}
		}

		return details;
	}

	@Override
	public String readString(String path) throws IOException {
		try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, getFullKey(path));
			 InputStream is = ossObject.getObjectContent()) {
			return FileContentProcessor.streamToString(is);
		}
	}

	@Override
	public List<String> readAllLines(String path) throws IOException {
		try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, getFullKey(path));
			 InputStream is = ossObject.getObjectContent()) {
			return FileContentProcessor.streamToLines(is);
		}
	}

	@Override
	public InputStream readStream(String path) throws IOException {
		OSSObject ossObject = this.ossClient.getObject(this.bucketName, getFullKey(path));
		return ossObject.getObjectContent();
	}

	@Override
	public void writeString(String path, String content) throws IOException {
		byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
		try (InputStream is = new ByteArrayInputStream(bytes)) {
			this.ossClient.putObject(this.bucketName, getFullKey(path), is);
		}
	}

	@Override
	public void writeFile(String path, InputStream inputStream, long contentLength) throws IOException {
		ObjectMetadata metadata = new ObjectMetadata();
		if (contentLength >= 0) {
			metadata.setContentLength(contentLength);
		}
		this.ossClient.putObject(this.bucketName, getFullKey(path), inputStream, metadata);
	}

	@Override
	public void trash(String path) throws IOException {
		if (!exists(path)) {
			throw new IOException("File or directory does not exist: " + path);
		}
		long timestamp = System.currentTimeMillis();
		String cleanPath = (path != null && path.startsWith("./")) ? path.substring(2) : path;
		if (cleanPath != null && cleanPath.startsWith("/")) {
			cleanPath = cleanPath.substring(1);
		}
		String trashPath = StorageConstants.TRASH_DIR + "/" + timestamp + "/" + cleanPath;
		rename(path, trashPath);
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
		Assert.hasText(pattern, "pattern should not be empty");

		// Build pathPrefix
		String pathPrefix = toPathPrefix(path);
		// Build PathMatcher pattern from glob pattern
		final String matcherPattern = globToMatcherPattern(pattern);
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

	private String globToMatcherPattern(String pattern) {
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
		String globPattern = globToMatcherPattern(glob);

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
			return new Info(path, true, true, 0, 0, null);
		}
		ObjectMetadata objectMetadata = this.ossClient.getObjectMetadata(this.bucketName, getFullKey(path));
		if (objectMetadata == null || !exists(path)) {
			return new Info(path, false, false, 0, 0, null);
		}
		String etag = objectMetadata.getETag() != null ? objectMetadata.getETag().replace("\"", "") : null;
		return new Info(path, true, false, objectMetadata.getContentLength(), objectMetadata.getLastModified().getTime(), etag);
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

	/**
	 * Build a PathMatcher from the glob pattern
	 */
	protected PathMatcher buildGlobMatcher(String pattern) {
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
	protected Pattern compileRegexPattern(String pattern, Boolean caseInsensitive, Boolean multiline) {
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

	protected boolean fileContainsPattern(Pattern pattern, BufferedReader reader) throws IOException {
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

	protected void countMatchesInFile(BufferedReader reader, Pattern pattern, AtomicInteger count) throws IOException {
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

	protected List<String> findMatchesWithContext(BufferedReader reader, Pattern pattern, int beforeContext, int afterContext, boolean lineNumbers) {
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

	@Override
	public String readImage(String path) throws IOException {
		Assert.hasText(path, "path must not be empty");
		String lower = path.toLowerCase(Locale.ENGLISH);
		if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
			throw new IllegalArgumentException("Unsupported image format. Only PNG and JPG/JPEG are supported.");
		}
		if (!exists(path) || isDirectory(path)) {
			throw new FileNotFoundException("File not found or is a directory: " + path);
		}

		String fullKey = getFullKey(path);
		try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, fullKey);
			 InputStream is = ossObject.getObjectContent()) {
			return FileContentProcessor.processImageStream(is, path);
		}
	}

	@Override
	public String readPdf(String path, Integer startPage, Integer endPage) throws IOException {
		Assert.hasText(path, "path must not be empty");
		String lower = path.toLowerCase(Locale.ENGLISH);
		if (!lower.endsWith(".pdf")) {
			throw new IllegalArgumentException("Unsupported format. Expected a PDF file.");
		}
		if (!exists(path) || isDirectory(path)) {
			throw new FileNotFoundException("File not found or is a directory: " + path);
		}

		String etag = getETag(path);
		String shadowKey = getShadowKey(path, etag);

		String cachedText = readShadowCache(shadowKey);
		if (cachedText != null) {
			return applyPageRange(cachedText, startPage, endPage);
		}

		String fullKey = getFullKey(path);
		String fullText;
		try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, fullKey);
			 InputStream is = ossObject.getObjectContent()) {
			fullText = FileContentProcessor.processPdfStream(is, null, null);
		}

		writeShadowCache(shadowKey, fullText);

		return applyPageRange(fullText, startPage, endPage);
	}

	@Override
	public String readDocument(String path) throws IOException {
		Assert.hasText(path, "path must not be empty");
		String lower = path.toLowerCase(Locale.ENGLISH);
		if (!lower.endsWith(".docx") && !lower.endsWith(".xlsx") && !lower.endsWith(".pptx")) {
			throw new IllegalArgumentException("Unsupported Office document format. Only .docx, .xlsx, and .pptx are supported.");
		}
		if (!exists(path) || isDirectory(path)) {
			throw new FileNotFoundException("File not found or is a directory: " + path);
		}

		String etag = getETag(path);
		String shadowKey = getShadowKey(path, etag);

		String cachedText = readShadowCache(shadowKey);
		if (cachedText != null) {
			return cachedText;
		}

		String fullKey = getFullKey(path);
		String fullText;
		try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, fullKey);
			 InputStream is = ossObject.getObjectContent()) {
			fullText = FileContentProcessor.processDocumentStream(is);
		}

		writeShadowCache(shadowKey, fullText);

		return fullText;
	}

	private String getETag(String path) {
		try {
			SimplifiedObjectMeta meta = this.ossClient.getSimplifiedObjectMeta(this.bucketName, getFullKey(path));
			String etag = meta.getETag();
			return etag != null ? etag.replace("\"", "") : null;
		} catch (Exception e) {
			throw new RuntimeException("获取文件ETag失败: " + path, e);
		}
	}

	private String getShadowKey(String path, String etag) {
		if (path.startsWith("/")) {
			throw new SecurityException("Absolute paths are not allowed: '" + path + "'");
		}
		String cleanPath = path;
		if (cleanPath.startsWith("./")) {
			cleanPath = cleanPath.substring(2);
		}
		return this.prefix + ".shadow/" + cleanPath + "." + etag + ".txt";
	}

	private String readShadowCache(String shadowKey) {
		try {
			if (this.ossClient.doesObjectExist(this.bucketName, shadowKey)) {
				try (OSSObject ossObject = this.ossClient.getObject(this.bucketName, shadowKey);
					 InputStream is = ossObject.getObjectContent()) {
					return FileContentProcessor.streamToString(is);
				}
			}
		} catch (Exception e) {
			// Ignore read cache failure and fallback to direct parsing
		}
		return null;
	}

	private void writeShadowCache(String shadowKey, String content) {
		try {
			byte[] bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
			try (InputStream is = new ByteArrayInputStream(bytes)) {
				this.ossClient.putObject(this.bucketName, shadowKey, is);
			}
		} catch (Exception e) {
			// Ignore write cache failure
		}
	}

	private String applyPageRange(String text, Integer startPage, Integer endPage) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		String[] pages = text.split("\f", -1);
		int totalPages = pages.length;
		int start = (startPage != null) ? Math.max(1, startPage) : 1;
		int end = (endPage != null) ? Math.min(totalPages, endPage) : totalPages;

		if (start > totalPages || start > end) {
			return "";
		}

		StringBuilder sb = new StringBuilder();
		for (int i = start - 1; i < end && i < totalPages; i++) {
			sb.append(pages[i]);
			if (i < end - 1) {
				sb.append("\n");
			}
		}
		return sb.toString().trim();
	}

	@Override
	public long calculateTotalSize(List<String> excludePrefixes) throws IOException {
		long totalSize = 0;
		String nextMarker = null;
		ObjectListing listResult;
		do {
			ListObjectsRequest listObjectsRequest = new ListObjectsRequest(this.bucketName)
					.withPrefix(this.prefix)
					.withMarker(nextMarker);
			listResult = this.ossClient.listObjects(listObjectsRequest);
			for (OSSObjectSummary summary : listResult.getObjectSummaries()) {
				String key = summary.getKey();
				if (key.endsWith("/")) {
					continue;
				}
				String relativePath = key.substring(this.prefix.length());
				if (!StringUtils.hasText(relativePath)) {
					continue;
				}

				boolean excluded = false;
				if (excludePrefixes != null) {
					for (String prefixStr : excludePrefixes) {
						String pathToCheck = relativePath.endsWith("/") ? relativePath : relativePath + "/";
						if (pathToCheck.startsWith(prefixStr)) {
							excluded = true;
							break;
						}
					}
				}
				if (excluded) {
					continue;
				}
				totalSize += summary.getSize();
			}
			nextMarker = listResult.getNextMarker();
		} while (listResult.isTruncated());

		return totalSize;
	}

	@Override
	public void createDirectory(String path) throws IOException {
		// 规范化目录路径，确保以 '/' 结尾且不以 '/' 开头
		String key = getFullKey(path);
		if (StringUtils.hasText(key) && !key.endsWith("/")) {
			key += "/";
		}
		try (InputStream is = new ByteArrayInputStream(new byte[0])) {
			this.ossClient.putObject(this.bucketName, key, is);
		}
	}

	@Override
	public DownloadLink createDownloadLink(String path, Duration ttl) throws IOException {
		// getFullKey(path) 会校验是否以 "/" 开头及包含 "../"
		String key = getFullKey(path);

		// 校验是否是内部路径
		validateNotInternalPath(key);

		// 校验文件存在且非目录
		if (isDirectory(path)) {
			throw new IOException("Cannot create download link for a directory: " + path);
		}
		if (!exists(path)) {
			throw new FileNotFoundException("File not found: " + path);
		}

		Info info = getInfo(path);

		// 设置过期时间
		Date expiration = Date.from(Instant.now().plus(ttl));

		// 创建预签名 URL 请求
		GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(this.bucketName, key);
		request.setMethod(HttpMethod.GET);
		request.setExpiration(expiration);

		// 通过 ResponseHeaderOverrides 强制以附件形式下载，并净化文件名，避免浏览器渲染 HTML/PDF
		ResponseHeaderOverrides overrides = new ResponseHeaderOverrides();
		String fileName = sanitizeFileName(extractFileName(path));
		overrides.setContentDisposition("attachment; filename=\"" + fileName + "\"");
		request.setResponseHeaders(overrides);

		URL url = this.ossClient.generatePresignedUrl(request);

		try {
			return new DownloadLink(url.toURI(), expiration, fileName, info.size());
		} catch (Exception e) {
			throw new IOException("Failed to convert presigned URL to URI", e);
		}
	}

	private void validateNotInternalPath(String path) {
		if (path == null) {
			return;
		}

		for (String pattern : INTERNAL_PATH_PATTERN) {
			if (path.contains(pattern)) {
				throw new SecurityException("Access to internal path is denied: " + path);
			}
		}
	}

	private String extractFileName(String path) {
		if (path == null || path.isBlank()) {
			return "file";
		}
		int lastSlash = path.lastIndexOf('/');
		if (lastSlash == -1) {
			return path;
		}
		return path.substring(lastSlash + 1);
	}

	private String sanitizeFileName(String fileName) {
		if (fileName == null) {
			return "file";
		}
		// 移除非法字符，以下划线代替
		String sanitized = fileName.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
		return sanitized.isBlank() ? "file" : sanitized;
	}

	@Override
	public void emptyTrash() throws IOException {
		if (exists(StorageConstants.TRASH_DIR)) {
			delete(StorageConstants.TRASH_DIR);
		}
	}
}
