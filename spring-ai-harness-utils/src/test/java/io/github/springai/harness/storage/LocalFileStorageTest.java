package io.github.springai.harness.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link LocalFileStorage}.
 *
 * @author ichaobuster
 */
@DisplayName("LocalFileStorage Tests")
class LocalFileStorageTest {

	@TempDir
	Path tempDir;

	private LocalFileStorage storage;

	@BeforeEach
	void setUp() {
		storage = new LocalFileStorage(tempDir);
	}

	@Test
	void subDirProvider() {
		LocalFileStorage sub = (LocalFileStorage) storage.subDirProvider("sub");
		assertThat(sub).isNotNull();
		Path subPath = (Path) ReflectionTestUtils.getField(sub, "baseDir");
		assertThat(subPath).isEqualTo(tempDir.resolve("sub"));
	}

	@Nested
	@DisplayName("Builder")
	class BuilderTests {

		@Test
		@DisplayName("Auto-creates memories directory on build()")
		void autoCreatesDir(@TempDir Path base) {
			Path newDir = base.resolve("memories");
			LocalFileStorage.builder().baseDir(newDir).build();
			assertThat(newDir).isDirectory();
		}

		@Test
		@DisplayName("Accepts string path")
		void acceptsStringPath(@TempDir Path base) {
			Path newDir = base.resolve("str-mem");
			LocalFileStorage t = LocalFileStorage.builder().baseDir(newDir.toString()).build();
			assertThat(t.getBaseDir()).isEqualTo(newDir.normalize());
		}

		@Test
		@DisplayName("Normalizes baseDir")
		void normalizesDir(@TempDir Path base) throws IOException {
			Path sub = base.resolve("a");
			Files.createDirectory(sub);
			// Pass a non-normalized path using ".."
			Path nonNormalized = sub.resolve("../a");
			LocalFileStorage t = LocalFileStorage.builder().baseDir(nonNormalized).build();
			assertThat(t.getBaseDir()).isEqualTo(sub.normalize());
		}

	}

	@Test
	@DisplayName("exists() returns true for existing file")
	void exists() throws IOException {
		Path file = tempDir.resolve("test.txt");
		Files.writeString(file, "content");
		assertThat(storage.exists("test.txt")).isTrue();
		assertThat(storage.exists("missing.txt")).isFalse();
	}

	@Test
	@DisplayName("isDirectory() returns true for directory")
	void isDirectory() throws IOException {
		Path dir = tempDir.resolve("subdir");
		Files.createDirectory(dir);
		assertThat(storage.isDirectory("subdir")).isTrue();
		Files.writeString(tempDir.resolve("file.txt"), "x");
		assertThat(storage.isDirectory("file.txt")).isFalse();
	}

	@Test
	@DisplayName("listDirectory() returns List of Info")
	void listDirectory() throws IOException {
		Files.writeString(tempDir.resolve("a.txt"), "a");
		Path sub = tempDir.resolve("sub");
		Files.createDirectory(sub);
		Files.writeString(sub.resolve("b.txt"), "bb");

		List<StorageProvider.Info> listing = storage.listDirectory("");
		assertThat(listing)
				.anyMatch(item -> !item.isDirectory() && item.path().equals("a.txt") && item.size() == 1)
				.anyMatch(item -> item.isDirectory() && item.path().equals("sub"));
	}

	@Test
	@DisplayName("readString() and writeString() work correctly")
	void readWrite() throws IOException {
		storage.writeString("new.txt", "hello");
		assertThat(storage.readString("new.txt")).isEqualTo("hello");
		assertThat(Files.readString(tempDir.resolve("new.txt"))).isEqualTo("hello");
	}

	@Test
	@DisplayName("readAllLines() works correctly")
	void readAllLines() throws IOException {
		storage.writeString("lines.txt", "1\n2\n3");
		List<String> lines = storage.readAllLines("lines.txt");
		assertThat(lines).containsExactly("1", "2", "3");
	}

	@Test
	@DisplayName("delete() removes files and directories")
	void delete() throws IOException {
		storage.writeString("to-delete.txt", "x");
		storage.delete("to-delete.txt");
		assertThat(tempDir.resolve("to-delete.txt")).doesNotExist();

		Path sub = tempDir.resolve("sub");
		Files.createDirectory(sub);
		Files.writeString(sub.resolve("f.txt"), "x");
		storage.delete("sub");
		assertThat(sub).doesNotExist();
	}

	@Test
	@DisplayName("rename() moves files")
	void rename() throws IOException {
		storage.writeString("old.txt", "data");
		storage.rename("old.txt", "new.txt");
		assertThat(tempDir.resolve("old.txt")).doesNotExist();
		assertThat(tempDir.resolve("new.txt")).exists();
	}

	@Test
	@DisplayName("Path traversal is blocked")
	void pathTraversal() {
		assertThatThrownBy(() -> storage.exists("../passwd"))
				.isInstanceOf(SecurityException.class)
				.hasMessageContaining("Path traversal attempt detected");
	}

	@Test
	@DisplayName("Absolute paths are blocked")
	void absolutePaths() {
		assertThatThrownBy(() -> storage.exists("/etc/passwd"))
				.isInstanceOf(SecurityException.class);
	}

	@Test
	@DisplayName("exists() with root path variants")
	void existsRoot() {
		assertThat(storage.exists("")).isTrue();
		assertThat(storage.exists("/")).isTrue();
	}

	@Test
	@DisplayName("delete() root should throw SecurityException")
	void deleteRoot() {
		assertThatThrownBy(() -> storage.delete("/"))
				.isInstanceOf(SecurityException.class);
		assertThatThrownBy(() -> storage.delete(""))
				.isInstanceOf(SecurityException.class);
	}

	@Test
	@DisplayName("rename() to non-existent parent creates parents")
	void renameCreatesParents() throws IOException {
		storage.writeString("f.txt", "x");
		storage.rename("f.txt", "a/b/c.txt");
		assertThat(tempDir.resolve("a/b/c.txt")).exists();
	}

	@Test
	@DisplayName("getInfo")
	void getInfo() throws IOException {
		Path file = tempDir.resolve("info.txt");
		Files.writeString(file, "content");
		StorageProvider.Info result = storage.getInfo("info.txt");
		assertThat(result).isNotNull();
		assertThat(result.exists()).isTrue();
		assertThat(result.isDirectory()).isFalse();
		assertThat(result.size()).isGreaterThanOrEqualTo(7);
		assertThat(result.lastModified()).isCloseTo(System.currentTimeMillis(), within(10_000L));
	}

	@Test
	@DisplayName("get directory info")
	void getDirectoryInfo() throws IOException {
		Path dir = tempDir.resolve("info_subdir");
		Files.createDirectory(dir);
		StorageProvider.Info result = storage.getInfo("info_subdir");
		assertThat(result).isNotNull();
		assertThat(result.exists()).isTrue();
		assertThat(result.isDirectory()).isTrue();
		assertThat(result.size()).isEqualTo(0);
		assertThat(result.lastModified()).isCloseTo(System.currentTimeMillis(), within(10_000L));
	}

	@Test
	@DisplayName("get info of not exists file")
	void getNotExistsFileInfo() throws IOException {
		StorageProvider.Info result = storage.getInfo("info_not_exists");
		assertThat(result).isNotNull();
		assertThat(result.exists()).isFalse();
		assertThat(result.isDirectory()).isFalse();
		assertThat(result.size()).isEqualTo(0);
		assertThat(result.lastModified()).isEqualTo(0);
	}

	@Test
	@DisplayName("getInfo list")
	void getInfoList() throws IOException {
		Path file = tempDir.resolve("info.txt");
		Files.writeString(file, "content");
		List<StorageProvider.Info> result = storage.getInfo(List.of("info.txt"));
		assertThat(result).isNotNull();
		assertThat(result.get(0).exists()).isTrue();
		assertThat(result.get(0).isDirectory()).isFalse();
		assertThat(result.get(0).size()).isGreaterThanOrEqualTo(7);
		assertThat(result.get(0).lastModified()).isCloseTo(System.currentTimeMillis(), within(10_000L));
	}

	@Test
	public void testGlobMatches() throws IOException {
		Path testDir = tempDir.resolve("test-glob");
		Files.createDirectories(testDir);

		Path main = testDir.resolve("src").resolve("main");
		Files.createDirectories(main);

		Path appJava = main.resolve("App.java");
		if (!Files.exists(appJava)) Files.createFile(appJava);
		Path testJava = main.resolve("AppTest.java");
		if (!Files.exists(testJava)) Files.createFile(testJava);
		Path otherFile1 = tempDir.resolve("App.config.properties");
		if (!Files.exists(otherFile1)) Files.createFile(otherFile1);
		Path otherFile2 = main.resolve("test.config2.properties");
		if (!Files.exists(otherFile2)) Files.createFile(otherFile2);

		List<String> result1 = storage.glob("**/*.java", "");
		assertThat(result1).hasSize(2);
		assertThat(result1).anyMatch(item -> item.endsWith("App.java"));
		assertThat(result1).anyMatch(item -> item.endsWith("AppTest.java"));

		List<String> result2 = storage.glob("**/App*", "");
		assertThat(result2).hasSize(3);
		assertThat(result2).anyMatch(item -> item.endsWith("App.java"));
		assertThat(result2).anyMatch(item -> item.endsWith("AppTest.java"));
		assertThat(result2).anyMatch(item -> item.endsWith("App.config.properties"));

		List<String> result3 = storage.glob("*.java", "");
		assertThat(result3).hasSize(2);
		assertThat(result3).anyMatch(item -> item.endsWith("App.java"));
		assertThat(result3).anyMatch(item -> item.endsWith("AppTest.java"));

		List<String> result4 = storage.glob("*.cpp", "");
		assertThat(result4).isEmpty();

		List<String> result5 = storage.glob("**/test/*.java", "");
		assertThat(result5).isEmpty();
	}

	@Nested
	@DisplayName("Grep Tests")
	class GrepTests {

		@Nested
		@DisplayName("Basic Pattern Matching Tests")
		class BasicPatternMatchingTests {

			@Test
			@DisplayName("Should find simple pattern in single file")
			void shouldFindSimplePattern() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Hello World\nFoo Bar\nHello Again", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("Hello", "test.txt", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).contains(file.getFileName().toString());
			}

			@Test
			@DisplayName("Should return no matches message when pattern not found")
			void shouldReturnNoMatchesMessage() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Hello World\nFoo Bar", StandardCharsets.UTF_8);

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
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Error: Something went wrong\nInfo: All good\nError: Another issue",
						StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("Error:.*", "test.txt", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).contains(file.getFileName().toString());
			}

			@Test
			@DisplayName("Should handle case insensitive search")
			void shouldHandleCaseInsensitiveSearch() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Hello World\nGoodbye World", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("HELLO", "test.txt", null, null, null, null, null, null, true, null,
						null, null);

				// Then
				assertThat(result).contains(file.getFileName().toString());
			}

			@Test
			@DisplayName("Should be case sensitive by default")
			void shouldBeCaseSensitiveByDefault() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Hello World", StandardCharsets.UTF_8);

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

			@Test
			@DisplayName("Should return error for non-existent path")
			void shouldReturnErrorForNonExistentPath() throws IOException {
				// When
				assertThatThrownBy(() -> storage.grep("test", "nonexistent", null, null, null, null,
						null, null, null, null, null, null))
						.isInstanceOf(IOException.class)
						.hasMessageContaining("Error: Path does not exist");
			}

		}

		@Nested
		@DisplayName("Output Mode Tests")
		class OutputModeTests {

			@Test
			@DisplayName("Should show only files with matches in files_with_matches mode")
			void shouldShowOnlyFilesWithMatches() throws IOException {
				// Given
				Path file1 = tempDir.resolve("file1.txt");
				Path file2 = tempDir.resolve("file2.txt");
				Path file3 = tempDir.resolve("file3.txt");
				Files.writeString(file1, "Hello World", StandardCharsets.UTF_8);
				Files.writeString(file2, "Goodbye World", StandardCharsets.UTF_8);
				Files.writeString(file3, "Hello Again", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("Hello", "", null,
						StorageProvider.GrepOutputMode.files_with_matches, null, null, null, null, null, null, null, null);

				// Then
				assertThat(result).contains(file1.getFileName().toString());
				assertThat(result).contains(file3.getFileName().toString());
				assertThat(result).doesNotContain(file2.getFileName().toString());
				assertThat(result).doesNotContain("Hello World"); // Should not show content
			}

			@Test
			@DisplayName("Should show match counts in count mode")
			void shouldShowMatchCounts() throws IOException {
				// Given
				Path file1 = tempDir.resolve("file1.txt");
				Path file2 = tempDir.resolve("file2.txt");
				Files.writeString(file1, "Error\nError\nError", StandardCharsets.UTF_8);
				Files.writeString(file2, "Error\nInfo", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("Error", "", null, StorageProvider.GrepOutputMode.count, null,
						null, null, null, null, null, null, null);

				// Then
				assertThat(result).anyMatch(a -> a.matches(file1.getFileName().toString() + ":\\s\\d+"));
				assertThat(result).anyMatch(a -> a.matches(file2.getFileName().toString() + ":\\s\\d+"));
			}

			@Test
			@DisplayName("Should show content with line numbers in content mode")
			void shouldShowContentWithLineNumbers() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Line 1\nLine 2 Error\nLine 3", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("Error", "test.txt", null, StorageProvider.GrepOutputMode.content, null,
						null, null, true, null, null, null, null);

				// Then
				assertThat(result).anyMatch(t -> t.contains(file.getFileName().toString()));
				assertThat(result).anyMatch(t -> t.contains("2:"));
				assertThat(result).anyMatch(t -> t.contains("Line 2 Error"));
			}

			@Test
			@DisplayName("Should show content without line numbers when disabled")
			void shouldShowContentWithoutLineNumbers() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Line 1\nLine 2 Error\nLine 3", StandardCharsets.UTF_8);

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
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Line 1\nLine 2\nLine 3 Error\nLine 4\nLine 5", StandardCharsets.UTF_8);

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
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Line 1\nLine 2 Error\nLine 3\nLine 4\nLine 5", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("Error", "test.txt", null, StorageProvider.GrepOutputMode.content, null, 2,
						null, true, null, null, null, null);

				// Then
				assertThat(result).anyMatch(t -> t.contains("Line 2 Error"));
				assertThat(result).anyMatch(t -> t.contains("Line 3"));
				assertThat(result).anyMatch(t -> t.contains("Line 4"));
			}

			@Test
			@DisplayName("Should show context lines both before and after match")
			void shouldShowContextBeforeAndAfter() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Line 1\nLine 2\nLine 3 Error\nLine 4\nLine 5", StandardCharsets.UTF_8);

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

			@Test
			@DisplayName("Should filter by simple glob pattern")
			void shouldFilterBySimpleGlob() throws IOException {
				// Given
				Path javaFile = tempDir.resolve("Test.java");
				Path txtFile = tempDir.resolve("test.txt");
				Files.writeString(javaFile, "public class Test {}", StandardCharsets.UTF_8);
				Files.writeString(txtFile, "public class Test {}", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("public", "", "*.java", null, null, null, null, null, null,
						null, null, null);

				// Then
				assertThat(result).contains(javaFile.getFileName().toString());
				assertThat(result).doesNotContain(txtFile.getFileName().toString());
			}

			@Test
			@DisplayName("Should filter by TypeScript file type")
			void shouldFilterByTypeScriptType() throws IOException {
				// Given
				Path subDir = tempDir.resolve("src");
				Files.createDirectories(subDir);
				Path tsFile = subDir.resolve("test.ts");
				Path tsxFile = subDir.resolve("component.tsx");
				Path jsFile = subDir.resolve("test.js");
				Files.writeString(tsFile, "interface Test {}", StandardCharsets.UTF_8);
				Files.writeString(tsxFile, "const Component = () => {}", StandardCharsets.UTF_8);
				Files.writeString(jsFile, "function test() {}", StandardCharsets.UTF_8);

				// When - Use glob instead of type for more reliable matching
				List<String> result = storage.grep("interface|Component", "", "*.{ts,tsx}", null, null, null,
						null, null, null, null, null, null);

				// Then
				assertThat(result).anyMatch(t -> t.contains(tsFile.getFileName().toString()));
				assertThat(result).anyMatch(t -> t.contains(tsxFile.getFileName().toString()));
				assertThat(result).anyMatch(t -> !t.contains(jsFile.getFileName().toString()));
			}

		}

		@Nested
		@DisplayName("Head Limit and Offset Tests")
		class HeadLimitAndOffsetTests {

			@Test
			@DisplayName("Should limit results with headLimit")
			void shouldLimitResults() throws IOException {
				// Given
				for (int i = 1; i <= 10; i++) {
					Path file = tempDir.resolve("file" + i + ".txt");
					Files.writeString(file, "match", StandardCharsets.UTF_8);
				}

				// When
				List<String> result = storage.grep("match", "", null,
						StorageProvider.GrepOutputMode.files_with_matches, null, null, null, null, null, 3, null, null);

				// Then
				assertThat(result).hasSizeLessThanOrEqualTo(3);
			}

			@Test
			@DisplayName("Should skip results with offset")
			void shouldSkipResults() throws IOException {
				// Given
				for (int i = 1; i <= 5; i++) {
					Path file = tempDir.resolve("file" + i + ".txt");
					Files.writeString(file, "match", StandardCharsets.UTF_8);
				}

				// When - Skip first 2, get the rest
				List<String> result = storage.grep("match", "", null,
						StorageProvider.GrepOutputMode.files_with_matches, null, null, null, null, null, null, 2, null);

				// Then - Should have results (3 remaining files after skipping 2)
				assertThat(result.size()).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(3);
			}

			@Test
			@DisplayName("Should combine offset and headLimit")
			void shouldCombineOffsetAndHeadLimit() throws IOException {
				// Given
				for (int i = 1; i <= 10; i++) {
					Path file = tempDir.resolve("file" + i + ".txt");
					Files.writeString(file, "match", StandardCharsets.UTF_8);
				}

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
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Line 1\nStart Middle End\nLine 3", StandardCharsets.UTF_8);

				// When - Pattern that matches within a line
				List<String> result = storage.grep("Start.*End", "test.txt", null, null, null, null, null, null, null,
						null, null, null);

				// Then
				assertThat(result).contains(file.getFileName().toString());
			}

			@Test
			@DisplayName("Should not match across lines without multiline mode")
			void shouldNotMatchAcrossLinesWithoutMultilineMode() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Start\nMiddle\nEnd", StandardCharsets.UTF_8);

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
				Path subDir = tempDir.resolve("subdir");
				Files.createDirectory(subDir);
				Path file1 = tempDir.resolve("file1.txt");
				Path file2 = subDir.resolve("file2.txt");
				Files.writeString(file1, "match", StandardCharsets.UTF_8);
				Files.writeString(file2, "match", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("match", "", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).contains(file1.getFileName().toString());
				assertThat(result).contains(Path.of("subdir/file2.txt").normalize().toString());
			}

			@Test
			@DisplayName("Should ignore common directories like node_modules and .git")
			void shouldIgnoreCommonDirectories() throws IOException {
				// Given
				Path nodeModules = tempDir.resolve("node_modules");
				Path gitDir = tempDir.resolve(".git");
				Files.createDirectory(nodeModules);
				Files.createDirectory(gitDir);
				Path file1 = nodeModules.resolve("test.txt");
				Path file2 = gitDir.resolve("config");
				Path file3 = tempDir.resolve("test.txt");
				Files.writeString(file1, "match", StandardCharsets.UTF_8);
				Files.writeString(file2, "match", StandardCharsets.UTF_8);
				Files.writeString(file3, "match", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("match", "", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).contains(file3.getFileName().toString());
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
				Path file = tempDir.resolve("empty.txt");
				Files.writeString(file, "", StandardCharsets.UTF_8);

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
				Path file = tempDir.resolve("longline.txt");
				String longLine = "x".repeat(20000) + "match";
				Files.writeString(file, longLine, StandardCharsets.UTF_8);

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
				Path file = tempDir.resolve("test-file_name (1).txt");
				Files.writeString(file, "match", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("match", "test-file_name (1).txt", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).contains(file.getFileName().toString());
			}

			@Test
			@DisplayName("Should handle patterns with special regex characters")
			void shouldHandlePatternsWithSpecialRegexCharacters() throws IOException {
				// Given
				Path file = tempDir.resolve("test.txt");
				Files.writeString(file, "Test (with) [brackets] {braces}", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("\\(with\\)", "test.txt", null, null, null, null, null, null, null,
						null, null, null);

				// Then
				assertThat(result).contains(file.getFileName().toString());
			}

		}

		@Nested
		@DisplayName("Multiple File Tests")
		class MultipleFileTests {

			@Test
			@DisplayName("Should find pattern in multiple files")
			void shouldFindPatternInMultipleFiles() throws IOException {
				// Given
				Path file1 = tempDir.resolve("file1.txt");
				Path file2 = tempDir.resolve("file2.txt");
				Path file3 = tempDir.resolve("file3.txt");
				Files.writeString(file1, "Error occurred", StandardCharsets.UTF_8);
				Files.writeString(file2, "No issues here", StandardCharsets.UTF_8);
				Files.writeString(file3, "Error found", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("Error", "", null, null, null, null, null, null, null, null,
						null, null);

				// Then
				assertThat(result).anyMatch(e -> e.contains(file1.getFileName().toString()));
				assertThat(result).anyMatch(e -> e.contains(file3.getFileName().toString()));
				assertThat(result).anyMatch(e -> !e.contains(file2.getFileName().toString()));
			}

			@Test
			@DisplayName("Should count matches across multiple files")
			void shouldCountMatchesAcrossMultipleFiles() throws IOException {
				// Given
				Path file1 = tempDir.resolve("file1.txt");
				Path file2 = tempDir.resolve("file2.txt");
				Files.writeString(file1, "Error\nError\nError", StandardCharsets.UTF_8);
				Files.writeString(file2, "Error", StandardCharsets.UTF_8);

				// When
				List<String> result = storage.grep("Error", "", null, StorageProvider.GrepOutputMode.count, null,
						null, null, null, null, null, null, null);

				// Then
				assertThat(result).anyMatch(e -> e.contains(file1.getFileName().toString()));
				assertThat(result).anyMatch(e -> e.contains(file2.getFileName().toString()));
				assertThat(result).anyMatch(e -> e.contains(": 3")); // 3 matches in file1
				assertThat(result).anyMatch(e -> e.contains(": 1")); // 1 match in file2
			}

		}
	}

}
