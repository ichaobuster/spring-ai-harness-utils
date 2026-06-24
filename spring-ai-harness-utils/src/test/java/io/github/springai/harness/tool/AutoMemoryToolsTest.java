package io.github.springai.harness.tool;

import io.github.springai.harness.storage.LocalFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AutoMemoryTools}.
 *
 * @author ichaobuster
 */
@DisplayName("AutoMemoryTools Tests")
class AutoMemoryToolsTest {

	@TempDir
	Path tempDir;

	private AutoMemoryTools tools;

	@BeforeEach
	void setUp() {
		tools = AutoMemoryTools.builder().memoryStorage(new LocalFileStorage(tempDir)).build();
	}

	// -------------------------------------------------------------------------
	// MemoryView
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("MemoryView")
	class MemoryViewTests {

		@Test
		@DisplayName("Lists root directory")
		void listRoot() throws IOException {
			Files.writeString(tempDir.resolve("a.md"), "content");
			String result = tools.memoryView("", null);
			assertThat(result).contains("a.md");
		}
		
		@Test
		@DisplayName("Reads file with line numbers")
		void readFile() throws IOException {
			Files.writeString(tempDir.resolve("mem.md"), "line1\nline2\nline3");
			String result = tools.memoryView("mem.md", null);
			assertThat(result).contains("1\tline1").contains("2\tline2").contains("3\tline3");
		}

		@Test
		@DisplayName("Reads file with line range")
		void readFileWithRange() throws IOException {
			Files.writeString(tempDir.resolve("mem.md"), "A\nB\nC\nD\nE");
			String result = tools.memoryView("mem.md", "2,4");
			assertThat(result).contains("2\tB").contains("3\tC").contains("4\tD");
			assertThat(result).doesNotContain("1\tA").doesNotContain("5\tE");
		}

		@Test
		@DisplayName("Returns error for non-existent path")
		void errorOnMissing() {
			assertThat(tools.memoryView("ghost.md", null)).startsWith("Error:");
		}

		@Test
		@DisplayName("Returns error for invalid view_range format")
		void errorOnBadRange() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "x");
			assertThat(tools.memoryView("f.md", "bad")).startsWith("Error:");
			assertThat(tools.memoryView("f.md", "1,2,3")).startsWith("Error:");
			assertThat(tools.memoryView("f.md", "not,num")).startsWith("Error:");
		}

		@Test
		@DisplayName("readFile with path including slash")
		void readFileSlash() throws IOException {
			tools.memoryCreate("sub/test.md", "content");
			String result = tools.memoryView("sub/test.md", null);
			assertThat(result).contains("File: test.md");
		}

	}

	// -------------------------------------------------------------------------
	// MemoryCreate
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("MemoryCreate")
	class MemoryCreateTests {

		@Test
		@DisplayName("Creates new file with content")
		void createNewFile() throws IOException {
			String result = tools.memoryCreate("user.md", "---\nname: user\ntype: user\n---\nContent");
			assertThat(result).contains("Successfully created");
			assertThat(Files.readString(tempDir.resolve("user.md"))).contains("Content");
		}

		@Test
		@DisplayName("Creates parent directories automatically")
		void createWithParentDirs() {
			String result = tools.memoryCreate("sub/dir/note.md", "body");
			assertThat(result).contains("Successfully created");
			assertThat(tempDir.resolve("sub/dir/note.md")).exists();
		}

		@Test
		@DisplayName("Returns error when file already exists")
		void errorWhenExists() throws IOException {
			Files.writeString(tempDir.resolve("existing.md"), "old");
			assertThat(tools.memoryCreate("existing.md", "new")).startsWith("Error:");
		}

	}

	// -------------------------------------------------------------------------
	// MemoryStrReplace
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("MemoryStrReplace")
	class MemoryStrReplaceTests {

		@Test
		@DisplayName("Replaces unique occurrence")
		void replaceUnique() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "hello world");
			String result = tools.memoryStrReplace("f.md", "world", "earth");
			assertThat(result).contains("Successfully edited");
			assertThat(Files.readString(tempDir.resolve("f.md"))).isEqualTo("hello earth");
		}

		@Test
		@DisplayName("Deletes text when newStr is empty — no snippet returned")
		void deleteText() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "keep remove keep");
			String result = tools.memoryStrReplace("f.md", " remove", "");
			assertThat(result).contains("deleted").doesNotContain("snippet");
			assertThat(Files.readString(tempDir.resolve("f.md"))).isEqualTo("keep keep");
		}

		@Test
		@DisplayName("Returns error when oldStr not found")
		void errorWhenNotFound() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "content");
			assertThat(tools.memoryStrReplace("f.md", "missing", "x")).startsWith("Error:");
		}

		@Test
		@DisplayName("Returns error when oldStr is ambiguous")
		void errorWhenAmbiguous() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "dup\ndup");
			assertThat(tools.memoryStrReplace("f.md", "dup", "x")).startsWith("Error:");
		}

		@Test
		@DisplayName("Returns error for non-existent file")
		void errorOnMissing() {
			assertThat(tools.memoryStrReplace("ghost.md", "a", "b")).startsWith("Error:");
		}

		@Test
		@DisplayName("Replace with null newStr (same as empty)")
		void replaceNullNewStr() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "hello world");
			tools.memoryStrReplace("f.md", "world", null);
			assertThat(Files.readString(tempDir.resolve("f.md"))).isEqualTo("hello ");
		}

		@Test
		@DisplayName("Error when target is directory")
		void errorOnDirectory() throws IOException {
			Files.createDirectory(tempDir.resolve("mydir"));
			assertThat(tools.memoryStrReplace("mydir", "a", "b")).contains("directory");
		}

	}

	// -------------------------------------------------------------------------
	// MemoryInsert
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("MemoryInsert")
	class MemoryInsertTests {

		@Test
		@DisplayName("Inserts at line 0 (before first line)")
		void insertAtBeginning() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "B\nC");
			tools.memoryInsert("f.md", 0, "A");
			assertThat(Files.readString(tempDir.resolve("f.md"))).isEqualTo("A\nB\nC");
		}

		@Test
		@DisplayName("Inserts after given line")
		void insertAfterLine() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "A\nC");
			tools.memoryInsert("f.md", 1, "B");
			assertThat(Files.readString(tempDir.resolve("f.md"))).isEqualTo("A\nB\nC");
		}

		@Test
		@DisplayName("Appends to end of file")
		void appendToEnd() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "A\nB");
			tools.memoryInsert("f.md", 2, "C");
			assertThat(Files.readString(tempDir.resolve("f.md"))).isEqualTo("A\nB\nC");
		}

		@Test
		@DisplayName("Preserves trailing newline")
		void preservesTrailingNewline() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "A\nB\n");
			tools.memoryInsert("f.md", 2, "C");
			assertThat(Files.readString(tempDir.resolve("f.md"))).endsWith("\n");
		}

		@Test
		@DisplayName("Returns error when insert_line exceeds file length")
		void errorOnOutOfBounds() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "A");
			assertThat(tools.memoryInsert("f.md", 99, "X")).startsWith("Error:");
		}

		@Test
		@DisplayName("Inserts into file without trailing newline")
		void noTrailingNewline() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "A");
			tools.memoryInsert("f.md", 1, "B");
			assertThat(Files.readString(tempDir.resolve("f.md"))).isEqualTo("A\nB");
		}

		@Test
		@DisplayName("Error when target is directory")
		void errorOnDirectory() throws IOException {
			Files.createDirectory(tempDir.resolve("mydir"));
			assertThat(tools.memoryInsert("mydir", 1, "b")).contains("directory");
		}

	}

	// -------------------------------------------------------------------------
	// MemoryDelete
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("MemoryDelete")
	class MemoryDeleteTests {

		@Test
		@DisplayName("Deletes a file")
		void deleteFile() throws IOException {
			Path file = tempDir.resolve("del.md");
			Files.writeString(file, "x");
			tools.memoryDelete("del.md");
			assertThat(file).doesNotExist();
		}

		@Test
		@DisplayName("Deletes a directory recursively")
		void deleteDirectory() throws IOException {
			Path sub = tempDir.resolve("sub");
			Files.createDirectory(sub);
			Files.writeString(sub.resolve("f.md"), "x");
			String result = tools.memoryDelete("sub");
			assertThat(result).contains("Successfully deleted directory");
			assertThat(sub).doesNotExist();
		}

		@Test
		@DisplayName("Returns error for non-existent path")
		void errorOnMissing() {
			assertThat(tools.memoryDelete("ghost.md")).startsWith("Error:");
		}

		@Test
		@DisplayName("Cannot delete memories root")
		void cannotDeleteRoot() {
			assertThat(tools.memoryDelete("")).startsWith("Error:");
		}

	}

	// -------------------------------------------------------------------------
	// MemoryRename
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("MemoryRename")
	class MemoryRenameTests {

		@Test
		@DisplayName("Renames a file")
		void renameFile() throws IOException {
			Files.writeString(tempDir.resolve("old.md"), "data");
			tools.memoryRename("old.md", "new.md");
			assertThat(tempDir.resolve("old.md")).doesNotExist();
			assertThat(tempDir.resolve("new.md")).exists();
		}

		@Test
		@DisplayName("Creates destination parent directories")
		void createsParentDirs() throws IOException {
			Files.writeString(tempDir.resolve("f.md"), "x");
			tools.memoryRename("f.md", "sub/f.md");
			assertThat(tempDir.resolve("sub/f.md")).exists();
		}

		@Test
		@DisplayName("Returns error when source does not exist")
		void errorOnMissingSource() {
			assertThat(tools.memoryRename("ghost.md", "dest.md")).startsWith("Error:");
		}

		@Test
		@DisplayName("Returns error when destination already exists")
		void errorWhenDestExists() throws IOException {
			Files.writeString(tempDir.resolve("a.md"), "a");
			Files.writeString(tempDir.resolve("b.md"), "b");
			assertThat(tools.memoryRename("a.md", "b.md")).startsWith("Error:");
		}

	}

	// -------------------------------------------------------------------------
	// Security
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Security")
	class SecurityTests {

		@Test
		@DisplayName("Blocks path traversal via ..")
		void blocksPathTraversal() {
			assertThat(tools.memoryView("../../etc/passwd", null)).startsWith("Error:");
		}

		@Test
		@DisplayName("Blocks absolute paths")
		void blocksAbsolutePaths() {
			assertThat(tools.memoryCreate("/etc/evil.md", "x")).startsWith("Error:");
		}

	}

	// -------------------------------------------------------------------------
	// Builder
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("Builder")
	class BuilderTests {

		@Test
		@DisplayName("Auto-creates memories directory on build()")
		void autoCreatesDir(@TempDir Path base) {
			Path newDir = base.resolve("memories");
			AutoMemoryTools.builder().memoryStorage(new LocalFileStorage(newDir)).build();
			assertThat(newDir).isDirectory();
		}

		@Test
		@DisplayName("Normalizes memoriesDir")
		void normalizesDir(@TempDir Path base) throws IOException {
			Path sub = base.resolve("a");
			Files.createDirectory(sub);
			// Pass a non-normalized path using ".."
			Path nonNormalized = sub.resolve("../a");
			LocalFileStorage storage = new LocalFileStorage(nonNormalized);
			AutoMemoryTools t = AutoMemoryTools.builder().memoryStorage(storage).build();
			assertThat(storage.getBaseDir()).isEqualTo(sub.normalize());
		}

	}

}
