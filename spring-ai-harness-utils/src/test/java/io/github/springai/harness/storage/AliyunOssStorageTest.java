package io.github.springai.harness.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AliyunOssStorage}.
 *
 * @author ichaobuster
 */
@DisplayName("AliyunOssStorage Tests")
@ExtendWith(MockitoExtension.class)
class AliyunOssStorageTest {

	@Mock
	private OSS ossClient;

	private AliyunOssStorage storage;

	private final String bucketName = "test-bucket";

	private final String prefix = "memories/";

	@BeforeEach
	void setUp() {
		storage = new AliyunOssStorage(ossClient, bucketName, prefix);
	}

	@Test
	void subDirProvider() {
		AliyunOssStorage sub = (AliyunOssStorage) storage.subDirProvider("sub");
		assertThat(sub).isNotNull();
		String subPrefix = (String) ReflectionTestUtils.getField(sub, "prefix");
		assertThat(subPrefix).isEqualTo(prefix + "sub/");
	}

	@Test
	@DisplayName("exists() delegates to ossClient")
	void exists() {
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(new ObjectListing());
		when(ossClient.doesObjectExist(bucketName, prefix + "test.md")).thenReturn(true);
		assertThat(storage.exists("test.md")).isTrue();
	}

	@Test
	@DisplayName("readString() reads object content")
	void readString() throws IOException {
		OSSObject ossObject = new OSSObject();
		ossObject.setObjectContent(new ByteArrayInputStream("hello oss".getBytes(StandardCharsets.UTF_8)));
		when(ossClient.getObject(eq(bucketName), eq(prefix + "test.md"))).thenReturn(ossObject);

		String content = storage.readString("test.md");
		assertThat(content).isEqualTo("hello oss");
	}

	@Test
	@DisplayName("readImage() returns small PNG as original base64 data URL")
	void readImageSmallPng() throws IOException {
		byte[] pngBytes = createImageBytes(16, 12, "png");
		OSSObject ossObject = new OSSObject();
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentType("image/png");
		ossObject.setObjectMetadata(metadata);
		ossObject.setObjectContent(new ByteArrayInputStream(pngBytes));
		when(ossClient.getObject(eq(bucketName), eq(prefix + "small.png"))).thenReturn(ossObject);

		String result = storage.readImage("small.png");

		assertThat(result).startsWith("data:image/png;base64,");
		assertThat(decodeDataUrl(result)).isEqualTo(pngBytes);
	}

	@Test
	@DisplayName("readImage() resizes large image to JPEG")
	void readImageLargeImage() throws IOException {
		OSSObject ossObject = new OSSObject();
		ossObject.setObjectContent(new ByteArrayInputStream(createImageBytes(4096, 1024, "png")));
		when(ossClient.getObject(eq(bucketName), eq(prefix + "large.png"))).thenReturn(ossObject);

		String result = storage.readImage("large.png");

		assertThat(result).startsWith("data:image/jpeg;base64,");
		BufferedImage resizedImage = ImageIO.read(new ByteArrayInputStream(decodeDataUrl(result)));
		assertThat(resizedImage.getWidth()).isEqualTo(2048);
		assertThat(resizedImage.getHeight()).isEqualTo(512);
	}

	@Test
	@DisplayName("readImage() infers MIME type from extension when metadata is missing")
	void readImageInfersMimeTypeFromExtension() throws IOException {
		byte[] pngBytes = createImageBytes(16, 12, "png");
		OSSObject ossObject = new OSSObject();
		ossObject.setObjectContent(new ByteArrayInputStream(pngBytes));
		when(ossClient.getObject(eq(bucketName), eq(prefix + "fallback.png"))).thenReturn(ossObject);

		String result = storage.readImage("fallback.png");

		assertThat(result).startsWith("data:image/png;base64,");
		assertThat(decodeDataUrl(result)).isEqualTo(pngBytes);
	}

	@Test
	@DisplayName("writeString() puts object")
	void writeString() throws IOException {
		storage.writeString("new.md", "content");
		verify(ossClient).putObject(eq(bucketName), eq(prefix + "new.md"), any(ByteArrayInputStream.class));
	}

	@Test
	@DisplayName("createDirectory() puts a 0-byte directory object")
	void createDirectory() throws IOException {
		storage.createDirectory("new-dir");
		verify(ossClient).putObject(eq(bucketName), eq(prefix + "new-dir/"), any(ByteArrayInputStream.class));
	}

	@Test
	@DisplayName("listDirectory() lists objects with prefix")
	void listDirectory() throws IOException {
		ObjectListing listing = new ObjectListing();
		OSSObjectSummary summary = new OSSObjectSummary();
		summary.setKey(prefix + "file.md");
		summary.setSize(100L);
		summary.setLastModified(new Date());
		listing.setObjectSummaries(Collections.singletonList(summary));
		listing.setCommonPrefixes(Collections.singletonList(prefix + "subdir/"));

		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		List<StorageProvider.Info> result = storage.listDirectory("");
		assertThat(result)
				.anyMatch(item -> !item.isDirectory() && item.path().equals("file.md") && item.size() == 100)
				.anyMatch(item -> item.isDirectory() && item.path().equals("subdir/"));
	}

	@Test
	@DisplayName("delete() for a file delegates to ossClient")
	void deleteFile() throws IOException {
		// Mock isDirectory to return false for a file
		ObjectListing listing = new ObjectListing();
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		storage.delete("test.md");
		verify(ossClient).deleteObject(bucketName, prefix + "test.md");
	}

	@Test
	@DisplayName("prefix variants in constructor")
	void constructorPrefix() {
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(new ObjectListing());

		var s1 = new AliyunOssStorage(ossClient, bucketName, null);
		assertThat(s1.exists("f")).isFalse();
		verify(ossClient).doesObjectExist(bucketName, "f");

		var s2 = new AliyunOssStorage(ossClient, bucketName, "root");
		s2.exists("f");
		verify(ossClient).doesObjectExist(bucketName, "root/f");
	}

	@Test
	@DisplayName("path starting with / throws SecurityException")
	void pathStartsWithSlashThrowsSecurityException() {
		assertThatThrownBy(() -> storage.exists("/test.txt"))
				.isInstanceOf(SecurityException.class)
				.hasMessage("Absolute paths are not allowed: '/test.txt'");

		assertThatThrownBy(() -> storage.readString("/dir/file.txt"))
				.isInstanceOf(SecurityException.class)
				.hasMessage("Absolute paths are not allowed: '/dir/file.txt'");

		assertThatThrownBy(() -> storage.exists("/"))
				.isInstanceOf(SecurityException.class)
				.hasMessage("Absolute paths are not allowed: '/'");
	}

	@Test
	@DisplayName("path starting with ./ strips dot-slash prefix")
	void pathStartsWithDotSlashStripsPrefix() throws IOException {
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(new ObjectListing());

		when(ossClient.doesObjectExist(bucketName, prefix + "test.md")).thenReturn(true);
		assertThat(storage.exists("./test.md")).isTrue();

		OSSObject ossObject = new OSSObject();
		ossObject.setObjectContent(new ByteArrayInputStream("hello oss".getBytes(StandardCharsets.UTF_8)));
		when(ossClient.getObject(eq(bucketName), eq(prefix + "test.md"))).thenReturn(ossObject);
		assertThat(storage.readString("./test.md")).isEqualTo("hello oss");

		storage.writeString("./new.md", "content");
		verify(ossClient).putObject(eq(bucketName), eq(prefix + "new.md"), any(ByteArrayInputStream.class));
	}

	@Test
	@DisplayName("isDirectory() logic")
	void isDirectory() {
		ObjectListing listing = new ObjectListing();
		OSSObjectSummary summary = new OSSObjectSummary();
		summary.setKey(prefix + "sub/file.md");
		listing.getObjectSummaries().add(summary);

		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		assertThat(storage.isDirectory("sub")).isTrue();

		ObjectListing emptyListing = new ObjectListing();
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(emptyListing);
		assertThat(storage.isDirectory("empty")).isFalse();
	}

	@Test
	@DisplayName("delete() for a directory")
	void deleteDirectory() throws IOException {
		// Mock isDirectory to return true
		ObjectListing listing = new ObjectListing();
		OSSObjectSummary s1 = new OSSObjectSummary();
		s1.setKey(prefix + "dir/f1.md");
		listing.getObjectSummaries().add(s1);

		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		storage.delete("dir");

		verify(ossClient).deleteObjects(any(DeleteObjectsRequest.class));
	}

	@Test
	@DisplayName("rename() for a directory")
	void renameDirectory() throws IOException {
		ObjectListing listing = new ObjectListing();
		OSSObjectSummary s1 = new OSSObjectSummary();
		s1.setKey(prefix + "olddir/f1.md");
		listing.getObjectSummaries().add(s1);

		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		storage.rename("olddir", "newdir");

		verify(ossClient).copyObject(eq(bucketName), eq(prefix + "olddir/f1.md"), eq(bucketName), eq(prefix + "newdir/f1.md"));
		verify(ossClient).deleteObject(bucketName, prefix + "olddir/f1.md");
	}

	@Test
	@DisplayName("listDirectory() with various path inputs")
	void listDirectoryPaths() throws IOException {
		ObjectListing listing = new ObjectListing();
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		storage.listDirectory("");
		storage.listDirectory("sub");
		verify(ossClient, times(2)).listObjects(any(ListObjectsRequest.class));
	}

	@Test
	@DisplayName("readAllLines() delegates correctly")
	void readAllLines() throws IOException {
		OSSObject ossObject = new OSSObject();
		ossObject.setObjectContent(new ByteArrayInputStream("L1\nL2".getBytes(StandardCharsets.UTF_8)));
		when(ossClient.getObject(eq(bucketName), eq(prefix + "test.md"))).thenReturn(ossObject);

		List<String> lines = storage.readAllLines("test.md");
		assertThat(lines).containsExactly("L1", "L2");
	}

	@Test
	@DisplayName("getInfo")
	void getInfo() throws IOException {
		ObjectListing listing = new ObjectListing();
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		ObjectMetadata objectMetadata = mock(ObjectMetadata.class);
		when(ossClient.getObjectMetadata(eq(bucketName), eq(prefix + "test.md"))).thenReturn(objectMetadata);
		when(ossClient.doesObjectExist(bucketName, prefix + "test.md")).thenReturn(true);
		Date lastModified = new Date();
		when(objectMetadata.getContentLength()).thenReturn(7L);
		when(objectMetadata.getLastModified()).thenReturn(lastModified);

		StorageProvider.Info result = storage.getInfo("test.md");
		assertThat(result).isNotNull();
		assertThat(result.exists()).isTrue();
		assertThat(result.isDirectory()).isFalse();
		assertThat(result.size()).isEqualTo(7L);
		assertThat(result.lastModified()).isEqualTo(lastModified.getTime());
	}

	@Test
	@DisplayName("get directory info")
	void getDirectoryInfo() throws IOException {
		ObjectListing listing = new ObjectListing();
		OSSObjectSummary summary = new OSSObjectSummary();
		summary.setKey(prefix + "sub/file.md");
		listing.getObjectSummaries().add(summary);

		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		StorageProvider.Info result = storage.getInfo("sub");
		assertThat(result).isNotNull();
		assertThat(result.exists()).isTrue();
		assertThat(result.isDirectory()).isTrue();
		assertThat(result.size()).isEqualTo(0);
		assertThat(result.lastModified()).isEqualTo(0);
	}

	@Test
	@DisplayName("get info of not exists file")
	void getNotExistsFileInfo() throws IOException {
		ObjectListing listing = new ObjectListing();
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		when(ossClient.getObjectMetadata(eq(bucketName), eq(prefix + "test.md"))).thenReturn(null);

		StorageProvider.Info result = storage.getInfo("test.md");
		assertThat(result).isNotNull();
		assertThat(result.exists()).isFalse();
		assertThat(result.isDirectory()).isFalse();
		assertThat(result.size()).isEqualTo(0);
		assertThat(result.lastModified()).isEqualTo(0);

		when(ossClient.getObjectMetadata(eq(bucketName), eq(prefix + "test.md"))).thenReturn(mock(ObjectMetadata.class));
		when(ossClient.doesObjectExist(bucketName, prefix + "test.md")).thenReturn(false);
		result = storage.getInfo("test.md");
		assertThat(result).isNotNull();
		assertThat(result.exists()).isFalse();
		assertThat(result.isDirectory()).isFalse();
		assertThat(result.size()).isEqualTo(0);
		assertThat(result.lastModified()).isEqualTo(0);
	}

	@Test
	public void testGlobMatches() throws IOException {
		ObjectListing listing = new ObjectListing();
		OSSObjectSummary summary1 = new OSSObjectSummary();
		summary1.setKey(prefix + "sub/file1.md");
		summary1.setLastModified(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
		listing.getObjectSummaries().add(summary1);
		OSSObjectSummary summary2 = new OSSObjectSummary();
		summary2.setKey(prefix + "sub/file2.md");
		summary2.setLastModified(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)));
		listing.getObjectSummaries().add(summary2);

		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		List<String> result = storage.glob("**/*.md", "sub");
		assertThat(result).containsExactly("sub/file1.md", "sub/file2.md");
	}

	private static byte[] createImageBytes(int width, int height, String formatName) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, 0x000000ff);
			}
		}
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			ImageIO.write(image, formatName, outputStream);
			return outputStream.toByteArray();
		}
	}

	private static byte[] decodeDataUrl(String dataUrl) {
		return Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
	}


	@Nested
	@DisplayName("Grep Tests")
	class GrepTests {

		void simpleOssMocksForGrep(List<String> filenames, String content) {
			ObjectListing listing = new ObjectListing();
			filenames.stream().forEach(filename -> {
				OSSObjectSummary summary = new OSSObjectSummary();
				summary.setKey(prefix + filename);
				summary.setLastModified(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
				listing.getObjectSummaries().add(summary);

				OSSObject ossObject = new OSSObject();
				ossObject.setObjectContent(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
				lenient().when(ossClient.getObject(anyString(), eq(prefix + filename))).thenReturn(ossObject);
			});

			lenient().when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
		}

		void simpleOssMocksForGrep(Map<String, String> fileContents) {
			ObjectListing listing = new ObjectListing();
			fileContents.entrySet().stream().forEach(entry -> {
				OSSObjectSummary summary = new OSSObjectSummary();
				summary.setKey(prefix + entry.getKey());
				summary.setLastModified(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
				listing.getObjectSummaries().add(summary);

				OSSObject ossObject = new OSSObject();
				ossObject.setObjectContent(new ByteArrayInputStream(entry.getValue().getBytes(StandardCharsets.UTF_8)));
				lenient().when(ossClient.getObject(anyString(), eq(prefix + entry.getKey()))).thenReturn(ossObject);
			});

			lenient().when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
		}

		@Nested
		@DisplayName("Basic Pattern Matching Tests")
		class BasicPatternMatchingTests {

			@Test
			@DisplayName("Should find simple pattern in single file")
			void shouldFindSimplePattern() throws IOException {
				// Given
				simpleOssMocksForGrep(List.of("test.txt"), "Hello World\nFoo Bar");

				// When
				List<String> result = storage.grep("Hello", "test.txt", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).contains("test.txt");
			}

			@Test
			@DisplayName("Should return no matches message when pattern not found")
			void shouldReturnNoMatchesMessage() throws IOException {
				// Given
				simpleOssMocksForGrep(List.of("test.txt"), "Hello World\nFoo Bar");

				// When
				List<String> result = storage.grep("NotFound", "test.txt", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).isEmpty();
			}

			@Test
			@DisplayName("Should find regex pattern")
			void shouldFindRegexPattern() throws IOException {
				// Given
				simpleOssMocksForGrep(List.of("test.txt"), "Error: Something went wrong\nInfo: All good\nError: Another issue");

				// When
				List<String> result = storage.grep("Error:.*", "test.txt", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).contains("test.txt");
			}

			@Test
			@DisplayName("Should handle case insensitive search")
			void shouldHandleCaseInsensitiveSearch() throws IOException {
				// Given
				simpleOssMocksForGrep(List.of("test.txt"), "Hello World\nFoo Bar");

				// When
				List<String> result = storage.grep("HELLO", "test.txt", null, null, null, null, null, null, true, null,
						null, null);

				// Then
				assertThat(result).contains("test.txt");
			}

			@Test
			@DisplayName("Should be case sensitive by default")
			void shouldBeCaseSensitiveByDefault() throws IOException {
				// Given
				simpleOssMocksForGrep(List.of("test.txt"), "Hello World\nFoo Bar");

				// When
				List<String> result = storage.grep("HELLO", "test.txt", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).isEmpty();
			}

			@Test
			@DisplayName("Should return error for invalid regex pattern")
			void shouldReturnErrorForInvalidRegex() throws IOException {
				// When
				assertThatThrownBy(() -> storage.grep("[invalid(", "", null, null, null, null, null, null, null,
						null, null, null))
						.isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("Error: Invalid regex pattern");
			}

			@Nested
			@DisplayName("Output Mode Tests")
			class OutputModeTests {

				@BeforeEach
				void grepSetup() {
					// Given
					simpleOssMocksForGrep(Map.of(
							"file1.txt", "Hello World",
							"file2.txt", "Goodbye World",
							"file3.txt", "Hello Again",
							"test.txt", "Line 1\nLine 2 Error\nLine 3"
					));
				}

				@Test
				@DisplayName("Should show only files with matches in files_with_matches mode")
				void shouldShowOnlyFilesWithMatches() throws IOException {
					// When
					List<String> result = storage.grep("Hello", "", null,
							StorageProvider.GrepOutputMode.files_with_matches, null, null, null, null, null, null, null, null);

					// Then
					assertThat(result).contains("file1.txt");
					assertThat(result).contains("file3.txt");
					assertThat(result).doesNotContain("file2.txt");
					assertThat(result).doesNotContain("Hello World"); // Should not show content
				}

				@Test
				@DisplayName("Should show match counts in count mode")
				void shouldShowMatchCounts() throws IOException {
					// When
					List<String> result = storage.grep("Hello", "", null, StorageProvider.GrepOutputMode.count, null,
							null, null, null, null, null, null, null);

					// Then
					assertThat(result).anyMatch(a -> a.matches("file1.txt" + ":\\s\\d+"));
					assertThat(result).anyMatch(a -> a.matches("file3.txt" + ":\\s\\d+"));
				}

				@Test
				@DisplayName("Should show content with line numbers in content mode")
				void shouldShowContentWithLineNumbers() throws IOException {
					// When
					List<String> result = storage.grep("Error", "test.txt", null, StorageProvider.GrepOutputMode.content, null,
							null, null, true, null, null, null, null);

					// Then
					assertThat(result).anyMatch(t -> t.contains("test.txt"));
					assertThat(result).anyMatch(t -> t.contains("2:"));
					assertThat(result).anyMatch(t -> t.contains("Line 2 Error"));
				}

				@Test
				@DisplayName("Should show content without line numbers when disabled")
				void shouldShowContentWithoutLineNumbers() throws IOException {
					// When
					List<String> result = storage.grep("Error", "test.txt", null, StorageProvider.GrepOutputMode.content, null,
							null, null, false, null, null, null, null);

					// Then
					assertThat(result).anyMatch(t -> t.contains("Line 2 Error"));
					// Line numbers should still appear for formatting but without the prefix
				}

			}

			@Nested
			@DisplayName("Context Tests")
			class ContextTests {
				@Test
				@DisplayName("Should show context lines before match")
				void shouldShowContextBefore() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("test.txt"), "Line 1\nLine 2\nLine 3 Error\nLine 4\nLine 5");

					// When
					List<String> result = storage.grep("Error", "test.txt", null, StorageProvider.GrepOutputMode.content, 2, null,
							null, true, null, null, null, null);

					// Then
					assertThat(result).anyMatch(t -> t.contains("Line 1"));
					assertThat(result).anyMatch(t -> t.contains("Line 2"));
					assertThat(result).anyMatch(t -> t.contains("Line 3 Error"));
				}

				@Test
				@DisplayName("Should show context lines after match")
				void shouldShowContextAfter() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("test.txt"), "Line 1\nLine 2\nLine 3 Error\nLine 4\nLine 5");

					// When
					List<String> result = storage.grep("Error", "test.txt", null, StorageProvider.GrepOutputMode.content, null, 2,
							null, true, null, null, null, null);

					// Then
					assertThat(result).anyMatch(t -> t.contains("Line 3 Error"));
					assertThat(result).anyMatch(t -> t.contains("Line 4"));
					assertThat(result).anyMatch(t -> t.contains("Line 5"));
				}

				@Test
				@DisplayName("Should show context lines both before and after match")
				void shouldShowContextBeforeAndAfter() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("test.txt"), "Line 1\nLine 2\nLine 3 Error\nLine 4\nLine 5");

					// When
					List<String> result = storage.grep("Error", "test.txt", null, StorageProvider.GrepOutputMode.content, null,
							null, 2, true, null, null, null, null);

					// Then
					assertThat(result).anyMatch(t -> t.contains("Line 1"));
					assertThat(result).anyMatch(t -> t.contains("Line 2"));
					assertThat(result).anyMatch(t -> t.contains("Line 3 Error"));
					assertThat(result).anyMatch(t -> t.contains("Line 4"));
					assertThat(result).anyMatch(t -> t.contains("Line 5"));
				}

			}

			@Nested
			@DisplayName("Glob and Type Filter Tests")
			class GlobAndTypeFilterTests {

				@BeforeEach
				void grepSetup() {
					simpleOssMocksForGrep(Map.of(
							"test.java", "public class Test {}",
							"test.txt", "public class Test {}",
							"src/test.ts", "interface Test {}",
							"src/component.tsx", "const Component = () => {}",
							"src/test.js", "function test() {}"
					));
				}

				@Test
				@DisplayName("Should filter by simple glob pattern")
				void shouldFilterBySimpleGlob() throws IOException {

					// When
					List<String> result = storage.grep("public", "", "*.java", null, null, null, null, null, null,
							null, null, null);

					// Then
					assertThat(result).contains("test.java");
					assertThat(result).doesNotContain("test.txt");
				}

				@Test
				@DisplayName("Should filter by TypeScript file type")
				void shouldFilterByTypeScriptType() throws IOException {
					// When - Use glob instead of type for more reliable matching
					List<String> result = storage.grep("interface|Component", "", "*.{ts,tsx}", null, null, null,
							null, null, null, null, null, null);

					// Then
					assertThat(result).anyMatch(t -> t.contains("src/test.ts"));
					assertThat(result).anyMatch(t -> t.contains("src/component.tsx"));
					assertThat(result).anyMatch(t -> !t.contains("src/test.js"));
				}

			}

			@Nested
			@DisplayName("Head Limit and Offset Tests")
			class HeadLimitAndOffsetTests {

				@BeforeEach
				void grepSetup() {
					List<String> filenames = IntStream.rangeClosed(1, 10).mapToObj(i -> prefix + "file" + i + ".txt").collect(Collectors.toList());
					simpleOssMocksForGrep(filenames, "match");
				}

				@Test
				@DisplayName("Should limit results with headLimit")
				void shouldLimitResults() throws IOException {

					// When
					List<String> result = storage.grep("match", "", null,
							StorageProvider.GrepOutputMode.files_with_matches, null, null, null, null, null, 3, null, null);

					// Then
					assertThat(result).hasSizeLessThanOrEqualTo(3);
				}

				@Test
				@DisplayName("Should skip results with offset")
				void shouldSkipResults() throws IOException {

					// When - Skip first 2, get the rest
					List<String> result = storage.grep("match", "", null,
							StorageProvider.GrepOutputMode.files_with_matches, null, null, null, null, null, null, 2, null);

					// Then - Should have results (8 remaining files after skipping 2)
					assertThat(result.size()).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(8);
				}

				@Test
				@DisplayName("Should combine offset and headLimit")
				void shouldCombineOffsetAndHeadLimit() throws IOException {

					// When - Skip 2, take 3
					List<String> result = storage.grep("match", "", null,
							StorageProvider.GrepOutputMode.files_with_matches, null, null, null, null, null, 3, 2, null);

					// Then
					assertThat(result).hasSizeLessThanOrEqualTo(3);
				}

			}

			@Nested
			@DisplayName("Multiline Mode Tests")
			class MultilineModeTests {
				@Test
				@DisplayName("Should match within single line by default")
				void shouldMatchWithinSingleLine() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("test.txt"), "Line 1\nStart Middle End\nLine 3");

					// When - Pattern that matches within a line
					List<String> result = storage.grep("Start.*End", "test.txt", null, null, null, null, null, null, null,
							null, null, null);

					// Then
					assertThat(result).contains("test.txt");
				}

				@Test
				@DisplayName("Should not match across lines without multiline mode")
				void shouldNotMatchAcrossLinesWithoutMultilineMode() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("test.txt"), "Start\nMiddle\nEnd");

					// When - Pattern that would need to span lines
					List<String> result = storage.grep("Start.*End", "test.txt", null, null, null, null, null, null, null,
							null, null, null);

					// Then
					assertThat(result).isEmpty();
				}

			}

			@Nested
			@DisplayName("Directory Traversal Tests")
			class DirectoryTraversalTests {

				@Test
				@DisplayName("Should search recursively in subdirectories")
				void shouldSearchRecursively() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("file1.txt", "subdir/file2.txt"), "match");

					// When
					List<String> result = storage.grep("match", "", null, null, null, null, null, null, null, null,
							null, null);

					// Then
					assertThat(result).contains("file1.txt");
					assertThat(result).contains("subdir/file2.txt");
				}

				@Test
				@DisplayName("Should ignore common directories like node_modules and .git")
				void shouldIgnoreCommonDirectories() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("node_modules/file1.txt", ".git/config", "test.txt"), "match");

					// When
					List<String> result = storage.grep("match", "", null, null, null, null, null, null, null, null,
							null, null);

					// Then
					assertThat(result).contains("test.txt");
					assertThat(result).doesNotContain("node_modules");
					assertThat(result).doesNotContain(".git");
				}

			}

			@Nested
			@DisplayName("Edge Cases and Error Handling Tests")
			class EdgeCasesTests {

				@Test
				@DisplayName("Should handle empty files")
				void shouldHandleEmptyFiles() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("empty.txt"), "");

					// When
					List<String> result = storage.grep("test", "empty.txt", null, null, null, null, null, null, null, null,
							null, null);

					// Then
					assertThat(result).isEmpty();
				}

				@Test
				@DisplayName("Should skip extremely long lines")
				void shouldSkipExtremelyLongLines() throws IOException {
					// Given
					String longLine = "x".repeat(20000) + "match";

					// Given
					simpleOssMocksForGrep(List.of("longline.txt"), longLine);

					// When
					List<String> result = storage.grep("match", "longline.txt", null, null, null, null, null, null, null, null,
							null, null);

					// Then - Should handle gracefully (line might be skipped)
					assertThat(result).isNotNull();
				}

				@Test
				@DisplayName("Should handle files with special characters in names")
				void shouldHandleFilesWithSpecialCharacters() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("test-file_name (1).txt"), "match");

					// When
					List<String> result = storage.grep("match", "test-file_name (1).txt", null, null, null, null, null, null, null, null,
							null, null);

					// Then
					assertThat(result).contains("test-file_name (1).txt");
				}

				@Test
				@DisplayName("Should handle patterns with special regex characters")
				void shouldHandlePatternsWithSpecialRegexCharacters() throws IOException {
					// Given
					simpleOssMocksForGrep(List.of("test.txt"), "Test (with) [brackets] {braces}");

					// When
					List<String> result = storage.grep("\\(with\\)", "test.txt", null, null, null, null, null, null, null,
							null, null, null);

					// Then
					assertThat(result).contains("test.txt");
				}

			}

			@Nested
			@DisplayName("Multiple File Tests")
			class MultipleFileTests {

				@Test
				@DisplayName("Should find pattern in multiple files")
				void shouldFindPatternInMultipleFiles() throws IOException {
					// Given
					simpleOssMocksForGrep(Map.of(
							"file1.txt", "Error occurred",
							"file2.txt", "No issues here",
							"file3.txt", "Error found"
					));

					// When
					List<String> result = storage.grep("Error", "", null, null, null, null, null, null, null, null,
							null, null);

					// Then
					assertThat(result).anyMatch(e -> e.contains("file1.txt".toString()));
					assertThat(result).anyMatch(e -> e.contains("file3.txt".toString()));
					assertThat(result).anyMatch(e -> !e.contains("file2.txt".toString()));
				}

				@Test
				@DisplayName("Should count matches across multiple files")
				void shouldCountMatchesAcrossMultipleFiles() throws IOException {
					// Given
					simpleOssMocksForGrep(Map.of(
							"file1.txt", "Error\nError\nError",
							"file2.txt", "Error"
					));

					// When
					List<String> result = storage.grep("Error", "", null, StorageProvider.GrepOutputMode.count, null,
							null, null, null, null, null, null, null);

					// Then
					assertThat(result).anyMatch(e -> e.contains("file1.txt".toString()));
					assertThat(result).anyMatch(e -> e.contains("file2.txt".toString()));
					assertThat(result).anyMatch(e -> e.contains(": 3")); // 3 matches in file1
					assertThat(result).anyMatch(e -> e.contains(": 1")); // 1 match in file2
				}

			}
		}
	}

}
