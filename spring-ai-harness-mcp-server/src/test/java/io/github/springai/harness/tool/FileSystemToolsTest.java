package io.github.springai.harness.tool;

import com.aliyun.oss.OSS;
import io.github.springai.harness.auth.HeaderAuthenticationProvider;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.storage.AliyunOssStorage;
import io.github.springai.harness.storage.DefaultStorageProviderFactory;
import io.github.springai.harness.storage.StorageProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.function.ServerRequest;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FileSystemTools}.
 */
@DisplayName("FileSystemTools Tests")
@ExtendWith(MockitoExtension.class)
class FileSystemToolsTest {

	@Mock
	private OSS ossClient;

	@Mock
	private StorageProvider storageProvider;

	@Mock
	private ServerRequest serverRequest;

	@Mock
	private ServerRequest.Headers headers;

	@Mock
	private io.github.springai.harness.skill.SkillProvider skillProvider;

	private HarnessMcpServerProperties properties;

	private FileSystemTools fileSystemTools;

	private FileSystemTools spyFileSystemTools;

	private McpTransportContext context;

	@BeforeEach
	void setUp() {
		properties = new HarnessMcpServerProperties();
		properties.setOssBucket("test-bucket");
		properties.setOssPrefix("harness/");

		HeaderAuthenticationProvider authProvider = new HeaderAuthenticationProvider();
		DefaultStorageProviderFactory factory = new DefaultStorageProviderFactory(ossClient, properties, authProvider);

		fileSystemTools = new FileSystemTools();
		ReflectionTestUtils.setField(fileSystemTools, "storageProviderFactory", factory);
		ReflectionTestUtils.setField(fileSystemTools, "skillProvider", skillProvider);

		spyFileSystemTools = spy(fileSystemTools);

		context = McpTransportContext.create(Map.of());
	}

	private McpTransportContext createValidContext(String authHeader) {
		ServerRequest req = mock(ServerRequest.class);
		ServerRequest.Headers hdrs = mock(ServerRequest.Headers.class);
		when(req.headers()).thenReturn(hdrs);
		when(hdrs.header("Authorization")).thenReturn(List.of(authHeader));
		when(hdrs.firstHeader("Authorization")).thenReturn(authHeader);
		return McpTransportContext.create(Map.of(McpTransportContext.KEY, req));
	}

	@Nested
	@DisplayName("getStorageProvider Tests")
	class GetStorageProviderTests {

		@Test
		@DisplayName("Should throw AuthenticationException when ServerRequest is missing in context")
		void shouldThrowWhenServerRequestMissing() {
			assertThatThrownBy(() -> fileSystemTools.getStorageProvider(context))
					.isInstanceOf(io.github.springai.harness.auth.AuthenticationException.class)
					.hasMessage("Missing Authorization header");
		}

		@Test
		@DisplayName("Should throw AuthenticationException when headers are null")
		void shouldThrowWhenHeadersNull() {
			ServerRequest req = mock(ServerRequest.class);
			when(req.headers()).thenReturn(null);
			McpTransportContext ctx = McpTransportContext.create(Map.of(McpTransportContext.KEY, req));

			assertThatThrownBy(() -> fileSystemTools.getStorageProvider(ctx))
					.isInstanceOf(io.github.springai.harness.auth.AuthenticationException.class)
					.hasMessage("Missing Authorization header");
		}

		@Test
		@DisplayName("Should throw AuthenticationException when Authorization header is missing")
		void shouldThrowWhenAuthHeaderMissing() {
			ServerRequest req = mock(ServerRequest.class);
			ServerRequest.Headers hdrs = mock(ServerRequest.Headers.class);
			when(req.headers()).thenReturn(hdrs);
			when(hdrs.header("Authorization")).thenReturn(Collections.emptyList());
			McpTransportContext ctx = McpTransportContext.create(Map.of(McpTransportContext.KEY, req));

			assertThatThrownBy(() -> fileSystemTools.getStorageProvider(ctx))
					.isInstanceOf(io.github.springai.harness.auth.AuthenticationException.class)
					.hasMessage("Missing Authorization header");
		}

		@Test
		@DisplayName("Should throw AuthenticationException when Authorization header format is invalid")
		void shouldThrowWhenAuthHeaderFormatInvalid() {
			McpTransportContext ctx = createValidContext("invalid-auth");

			assertThatThrownBy(() -> fileSystemTools.getStorageProvider(ctx))
					.isInstanceOf(io.github.springai.harness.auth.AuthenticationException.class)
					.hasMessage("Authorization header format error");
		}

		@Test
		@DisplayName("Should create AliyunOssStorage when Authorization header format is valid")
		void shouldCreateStorageProviderWhenValid() {
			McpTransportContext ctx = createValidContext("sys1-agent2-user3");

			StorageProvider provider = fileSystemTools.getStorageProvider(ctx);

			assertThat(provider).isInstanceOf(AliyunOssStorage.class);
			String prefix = (String) ReflectionTestUtils.getField(provider, "prefix");
			assertThat(prefix).isEqualTo("harness/sys1-agent2-user3/");
		}
	}

	@Nested
	@DisplayName("read Tool Tests")
	class ReadToolTests {

		@BeforeEach
		void setupSpy() {
			doReturn(storageProvider).when(spyFileSystemTools).getStorageProvider(any());
		}

		@Test
		@DisplayName("Should return error when file does not exist")
		void shouldReturnErrorWhenFileDoesNotExist() throws IOException {
			when(storageProvider.exists("test.txt")).thenReturn(false);

			String result = spyFileSystemTools.read(context, "test.txt", null, null);

			assertThat(result).isEqualTo("Error: File does not exist: test.txt");
		}

		@Test
		@DisplayName("Should return error when path is a directory")
		void shouldReturnErrorWhenPathIsDirectory() throws IOException {
			when(storageProvider.exists("docs")).thenReturn(true);
			when(storageProvider.isDirectory("docs")).thenReturn(true);

			String result = spyFileSystemTools.read(context, "docs", null, null);

			assertThat(result).isEqualTo("Error: Path is a directory, not a file: docs");
		}

		@Test
		@DisplayName("Should return empty message when file content is empty")
		void shouldReturnEmptyMessageWhenFileIsEmpty() throws IOException {
			when(storageProvider.exists("empty.txt")).thenReturn(true);
			when(storageProvider.isDirectory("empty.txt")).thenReturn(false);
			when(storageProvider.readAllLines("empty.txt")).thenReturn(Collections.emptyList());

			String result = spyFileSystemTools.read(context, "empty.txt", null, null);

			assertThat(result).isEqualTo("File is empty: empty.txt");
		}

		@Test
		@DisplayName("Should return offset error when offset exceeds total lines")
		void shouldReturnErrorWhenOffsetExceedsTotalLines() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readAllLines("file.txt")).thenReturn(List.of("line1", "line2"));

			String result = spyFileSystemTools.read(context, "file.txt", 5, 10);

			assertThat(result).isEqualTo("No lines to read. File has 2 lines, but offset was 5");
		}

		@Test
		@DisplayName("Should read file content successfully with default limit and offset")
		void shouldReadFileContentSuccessfully() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readAllLines("file.txt")).thenReturn(List.of("first line", "second line"));

			String result = spyFileSystemTools.read(context, "file.txt", null, null);

			assertThat(result).contains("File: file.txt");
			assertThat(result).contains("Showing lines 1-2 of 2");
			assertThat(result).contains("     1\tfirst line");
			assertThat(result).contains("     2\tsecond line");
		}

		@Test
		@DisplayName("Should read file slice with custom offset and limit")
		void shouldReadFileSliceWithCustomOffsetAndLimit() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readAllLines("file.txt")).thenReturn(List.of("line 1", "line 2", "line 3", "line 4"));

			String result = spyFileSystemTools.read(context, "file.txt", 2, 2);

			assertThat(result).contains("Showing lines 2-3 of 4");
			assertThat(result).contains("     2\tline 2");
			assertThat(result).contains("     3\tline 3");
			assertThat(result).doesNotContain("line 1");
			assertThat(result).doesNotContain("line 4");
		}

		@Test
		@DisplayName("Should truncate lines exceeding max line length")
		void shouldTruncateLongLines() throws IOException {
			String longLine = "a".repeat(2005);
			when(storageProvider.exists("long.txt")).thenReturn(true);
			when(storageProvider.isDirectory("long.txt")).thenReturn(false);
			when(storageProvider.readAllLines("long.txt")).thenReturn(List.of(longLine));

			String result = spyFileSystemTools.read(context, "long.txt", null, null);

			assertThat(result).contains("... (line truncated)");
			assertThat(result).contains("a".repeat(2000));
		}

		@Test
		@DisplayName("Should return error message when IOException occurs")
		void shouldReturnErrorMessageOnIOException() throws IOException {
			when(storageProvider.exists("err.txt")).thenReturn(true);
			when(storageProvider.isDirectory("err.txt")).thenReturn(false);
			when(storageProvider.readAllLines("err.txt")).thenThrow(new IOException("Read failure"));

			String result = spyFileSystemTools.read(context, "err.txt", null, null);

			assertThat(result).isEqualTo("Error reading file: Read failure");
		}
	}

	@Nested
	@DisplayName("write Tool Tests")
	class WriteToolTests {

		@BeforeEach
		void setupSpy() {
			doReturn(storageProvider).when(spyFileSystemTools).getStorageProvider(any());
		}

		@Test
		@DisplayName("Should return creation success message when file does not exist")
		void shouldCreateNewFile() throws IOException {
			when(storageProvider.exists("new.txt")).thenReturn(false);

			String result = spyFileSystemTools.write(context, "new.txt", "Hello World");

			verify(storageProvider).writeString("new.txt", "Hello World");
			assertThat(result).isEqualTo("Successfully created file: new.txt (11 bytes)");
		}

		@Test
		@DisplayName("Should return overwrite success message when file already exists")
		void shouldOverwriteExistingFile() throws IOException {
			when(storageProvider.exists("existing.txt")).thenReturn(true);

			String result = spyFileSystemTools.write(context, "existing.txt", "Updated Content");

			verify(storageProvider).writeString("existing.txt", "Updated Content");
			assertThat(result).isEqualTo("Successfully overwrote file: existing.txt (15 bytes)");
		}

		@Test
		@DisplayName("Should handle null content by converting to empty string")
		void shouldHandleNullContent() throws IOException {
			when(storageProvider.exists("empty.txt")).thenReturn(false);

			String result = spyFileSystemTools.write(context, "empty.txt", null);

			verify(storageProvider).writeString("empty.txt", "");
			assertThat(result).isEqualTo("Successfully created file: empty.txt (0 bytes)");
		}

		@Test
		@DisplayName("Should return error message on IOException")
		void shouldReturnErrorOnIOException() throws IOException {
			when(storageProvider.exists("err.txt")).thenReturn(false);
			doThrow(new IOException("Disk full")).when(storageProvider).writeString(any(), any());

			String result = spyFileSystemTools.write(context, "err.txt", "content");

			assertThat(result).isEqualTo("Error writing file: Disk full");
		}

		@Test
		@DisplayName("Should return error message on general Exception")
		void shouldReturnErrorOnGeneralException() throws IOException {
			when(storageProvider.exists("err.txt")).thenThrow(new RuntimeException("Unexpected error"));

			String result = spyFileSystemTools.write(context, "err.txt", "content");

			assertThat(result).isEqualTo("Error: Unexpected error");
		}
	}

	@Nested
	@DisplayName("edit Tool Tests")
	class EditToolTests {

		@BeforeEach
		void setupSpy() {
			doReturn(storageProvider).when(spyFileSystemTools).getStorageProvider(any());
		}

		@Test
		@DisplayName("Should return error when file does not exist")
		void shouldReturnErrorWhenFileDoesNotExist() throws IOException {
			when(storageProvider.exists("missing.txt")).thenReturn(false);

			String result = spyFileSystemTools.edit(context, "missing.txt", "old", "new", false);

			assertThat(result).isEqualTo("Error: File does not exist: missing.txt");
		}

		@Test
		@DisplayName("Should return error when path is a directory")
		void shouldReturnErrorWhenPathIsDirectory() throws IOException {
			when(storageProvider.exists("dir")).thenReturn(true);
			when(storageProvider.isDirectory("dir")).thenReturn(true);

			String result = spyFileSystemTools.edit(context, "dir", "old", "new", false);

			assertThat(result).isEqualTo("Error: Path is a directory, not a file: dir");
		}

		@Test
		@DisplayName("Should return error when oldString and newString are equal")
		void shouldReturnErrorWhenStringsAreEqual() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);

			String result = spyFileSystemTools.edit(context, "file.txt", "same", "same", false);

			assertThat(result).isEqualTo("Error: oldString and newString must be different");
		}

		@Test
		@DisplayName("Should return error when oldString is not found in file")
		void shouldReturnErrorWhenOldStringNotFound() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readString("file.txt")).thenReturn("Hello World");

			String result = spyFileSystemTools.edit(context, "file.txt", "foo", "bar", false);

			assertThat(result).isEqualTo("Error: oldString not found in file: file.txt");
		}

		@Test
		@DisplayName("Should return error when oldString occurs multiple times and replaceAll is false")
		void shouldReturnErrorWhenMultipleOccurrencesWithoutReplaceAll() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readString("file.txt")).thenReturn("foo foo foo");

			String result = spyFileSystemTools.edit(context, "file.txt", "foo", "bar", false);

			assertThat(result).contains("Error: oldString appears 3 times in the file.");
		}

		@Test
		@DisplayName("Should edit single occurrence successfully")
		void shouldEditSingleOccurrenceSuccessfully() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readString("file.txt")).thenReturn("line1\nline2 target\nline3");

			String result = spyFileSystemTools.edit(context, "file.txt", "target", "replacement", false);

			verify(storageProvider).writeString("file.txt", "line1\nline2 replacement\nline3");
			assertThat(result).contains("The file file.txt has been updated.");
			assertThat(result).contains("2→line2 replacement");
		}

		@Test
		@DisplayName("Should replace all occurrences when replaceAll is true")
		void shouldReplaceAllOccurrences() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readString("file.txt")).thenReturn("foo bar foo baz foo");

			String result = spyFileSystemTools.edit(context, "file.txt", "foo", "qux", true);

			verify(storageProvider).writeString("file.txt", "qux bar qux baz qux");
			assertThat(result).contains("The file file.txt has been updated.");
		}

		@Test
		@DisplayName("Should handle multi-line string replacement and snippet generation")
		void shouldHandleMultiLineEditSnippet() throws IOException {
			String original = "line1\nline2\nold line A\nold line B\nline5";
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readString("file.txt")).thenReturn(original);

			String result = spyFileSystemTools.edit(context, "file.txt", "old line A\nold line B", "new line A\nnew line B", false);

			verify(storageProvider).writeString("file.txt", "line1\nline2\nnew line A\nnew line B\nline5");
			assertThat(result).contains("3→new line A");
			assertThat(result).contains("4→new line B");
		}

		@Test
		@DisplayName("Should return error on IOException during edit")
		void shouldReturnErrorOnIOException() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);
			when(storageProvider.readString("file.txt")).thenThrow(new IOException("Read error"));

			String result = spyFileSystemTools.edit(context, "file.txt", "foo", "bar", false);

			assertThat(result).isEqualTo("Error editing file: Read error");
		}
	}

	@Nested
	@DisplayName("glob Tool Tests")
	class GlobToolTests {

		@BeforeEach
		void setupSpy() {
			doReturn(storageProvider).when(spyFileSystemTools).getStorageProvider(any());
		}

		@Test
		@DisplayName("Should throw IllegalArgumentException when pattern is empty")
		void shouldThrowWhenPatternIsEmpty() {
			assertThatThrownBy(() -> spyFileSystemTools.glob(context, "", "path"))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("Should return error when path does not exist")
		void shouldReturnErrorWhenPathDoesNotExist() throws IOException {
			when(storageProvider.exists("invalid")).thenReturn(false);

			String result = spyFileSystemTools.glob(context, "*.java", "invalid");

			assertThat(result).isEqualTo("Error: Path does not exist: invalid");
		}

		@Test
		@DisplayName("Should return error when path is not a directory")
		void shouldReturnErrorWhenPathIsNotDirectory() throws IOException {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);

			String result = spyFileSystemTools.glob(context, "*.java", "file.txt");

			assertThat(result).isEqualTo("Error: Path is not a directory: file.txt");
		}

		@Test
		@DisplayName("Should return joined matching paths on successful glob")
		void shouldReturnMatchingPaths() throws IOException {
			when(storageProvider.exists("src")).thenReturn(true);
			when(storageProvider.isDirectory("src")).thenReturn(true);
			when(storageProvider.glob("*.java", "src")).thenReturn(List.of("src/A.java", "src/B.java"));

			String result = spyFileSystemTools.glob(context, "*.java", "src");

			assertThat(result).isEqualTo("src/A.java\nsrc/B.java");
		}

		@Test
		@DisplayName("Should return error message on Exception during glob")
		void shouldReturnErrorOnException() throws IOException {
			when(storageProvider.exists("src")).thenReturn(true);
			when(storageProvider.isDirectory("src")).thenReturn(true);
			when(storageProvider.glob("*.java", "src")).thenThrow(new RuntimeException("Glob error"));

			String result = spyFileSystemTools.glob(context, "*.java", "src");

			assertThat(result).isEqualTo("Error executing glob: Glob error");
		}
	}

	@Nested
	@DisplayName("grep Tool Tests")
	class GrepToolTests {

		@BeforeEach
		void setupSpy() {
			doReturn(storageProvider).when(spyFileSystemTools).getStorageProvider(any());
		}

		@Test
		@DisplayName("Should return no matches message when grep result is empty")
		void shouldReturnNoMatchesMessage() throws IOException {
			when(storageProvider.grep(eq("pattern"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
					.thenReturn(Collections.emptyList());

			String result = spyFileSystemTools.grep(context, "pattern", null, null, null, null, null, null, null, null, null, null, null);

			assertThat(result).isEqualTo("No matches found for pattern: pattern");
		}

		@Test
		@DisplayName("Should return joined results when matches are found")
		void shouldReturnJoinedResults() throws IOException {
			when(storageProvider.grep(eq("test"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
					.thenReturn(List.of("file1.txt: test line 1", "file2.txt: test line 2"));

			String result = spyFileSystemTools.grep(context, "test", null, null, null, null, null, null, null, null, null, null, null);

			assertThat(result).isEqualTo("file1.txt: test line 1\nfile2.txt: test line 2");
		}

		@Test
		@DisplayName("Should truncate results if output exceeds GREP_MAX_OUTPUT_LENGTH")
		void shouldTruncateLongResults() throws IOException {
			String longMatch = "x".repeat(60_000);
			when(storageProvider.grep(eq("long"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
					.thenReturn(List.of(longMatch));

			String result = spyFileSystemTools.grep(context, "long", null, null, null, null, null, null, null, null, null, null, null);

			assertThat(result).contains("... (output truncated, 10000 characters omitted)");
			assertThat(result.substring(0, 50_000)).isEqualTo("x".repeat(50_000));
		}

		@Test
		@DisplayName("Should return failure message on IOException during grep")
		void shouldReturnFailureMessageOnIOException() throws IOException {
			when(storageProvider.grep(eq("err"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
					.thenThrow(new IOException("Grep execution failed"));

			String result = spyFileSystemTools.grep(context, "err", null, null, null, null, null, null, null, null, null, null, null);

			assertThat(result).isEqualTo("Failed to grep for pattern \"err\": Grep execution failed");
		}
	}

	@Nested
	@DisplayName("ListDirectory Tool Tests")
	class ListDirectoryToolTests {

		@BeforeEach
		void setupSpy() {
			doReturn(storageProvider).when(spyFileSystemTools).getStorageProvider(any());
		}

		@Test
		@DisplayName("Should return error when path does not exist")
		void shouldReturnErrorWhenPathDoesNotExist() {
			when(storageProvider.exists("nonexistent")).thenReturn(false);

			String result = spyFileSystemTools.listDirectory(context, "nonexistent");

			assertThat(result).isEqualTo("Error: Path does not exist: nonexistent");
		}

		@Test
		@DisplayName("Should return error when path is a file")
		void shouldReturnErrorWhenPathIsFile() {
			when(storageProvider.exists("file.txt")).thenReturn(true);
			when(storageProvider.isDirectory("file.txt")).thenReturn(false);

			String result = spyFileSystemTools.listDirectory(context, "file.txt");

			assertThat(result).isEqualTo("Error: Path is a file, not a directory: file.txt");
		}

		@Test
		@DisplayName("Should return formatted directory listing")
		void shouldReturnFormattedListing() throws IOException {
			when(storageProvider.exists("")).thenReturn(true);
			when(storageProvider.isDirectory("")).thenReturn(true);
			when(storageProvider.listDirectory("")).thenReturn(List.of(
					new StorageProvider.Info("src", true, true, 0, 0),
					new StorageProvider.Info("README.md", true, false, 100, 1700000000000L)
			));

			String result = spyFileSystemTools.listDirectory(context, null);

			assertThat(result)
					.contains("Directory listing for: .")
					.contains("<DIR>")
					.contains("src")
					.contains("<FILE>")
					.contains("README.md");
		}
	}

	@Nested
	@DisplayName("Trash Tool Tests")
	class TrashToolTests {

		@BeforeEach
		void setupSpy() {
			doReturn(storageProvider).when(spyFileSystemTools).getStorageProvider(any());
		}

		@Test
		@DisplayName("Should return error when file does not exist")
		void shouldReturnErrorWhenFileDoesNotExist() {
			when(storageProvider.exists("missing.txt")).thenReturn(false);

			String result = spyFileSystemTools.trash(context, "missing.txt");

			assertThat(result).isEqualTo("Error: File or directory does not exist: missing.txt");
		}

		@Test
		@DisplayName("Should move file to trash and return success message")
		void shouldMoveFileToTrash() throws IOException {
			when(storageProvider.exists("foo.txt")).thenReturn(true);

			String result = spyFileSystemTools.trash(context, "foo.txt");

			verify(storageProvider).trash("foo.txt");
			assertThat(result).isEqualTo("Successfully moved to trash: foo.txt");
		}
	}

	@Nested
	@DisplayName("ListSkills Tool Tests")
	class ListSkillsToolTests {

		@Test
		@DisplayName("Should return no skills message when empty")
		void shouldReturnNoSkillsMessage() throws IOException {
			when(skillProvider.listSkills(any())).thenReturn(Collections.emptyList());

			String result = fileSystemTools.listSkills(context);

			assertThat(result).isEqualTo("No skills found.");
		}

		@Test
		@DisplayName("Should return formatted skills list")
		void shouldReturnFormattedSkillsList() throws IOException {
			when(skillProvider.listSkills(any())).thenReturn(List.of(
					new io.github.springai.harness.skill.SkillInfo("skills/code-review", Map.of("name", "code-review", "description", "Reviews code"), "# Content")
			));

			String result = fileSystemTools.listSkills(context);

			assertThat(result)
					.contains("Available Skills:")
					.contains("code-review")
					.contains("Reviews code");
		}
	}

	@Nested
	@DisplayName("ReadSkill Tool Tests")
	class ReadSkillToolTests {

		@Test
		@DisplayName("Should return error when skillName is blank")
		void shouldReturnErrorWhenSkillNameIsBlank() {
			String result = fileSystemTools.readSkill(context, "   ");

			assertThat(result).isEqualTo("Error: skillName must not be empty.");
		}

		@Test
		@DisplayName("Should return skill content when skillName is valid")
		void shouldReturnSkillContent() throws IOException {
			when(skillProvider.readSkill(any(), eq("code-review"))).thenReturn("# Code Review Skill Instructions");

			String result = fileSystemTools.readSkill(context, "code-review");

			assertThat(result).isEqualTo("# Code Review Skill Instructions");
		}
	}
}
