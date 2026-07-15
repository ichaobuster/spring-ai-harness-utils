package io.github.springai.harness.service;

import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WorkspaceSyncService}.
 */
@DisplayName("WorkspaceSyncService Tests")
@ExtendWith(MockitoExtension.class)
class WorkspaceSyncServiceTest {

	@Mock
	private StorageProvider storageProvider;

	private WorkspaceSyncService syncService;

	@BeforeEach
	void setUp() {
		syncService = new WorkspaceSyncService();
	}

	@Test
	@DisplayName("Should normalize path correctly")
	void shouldNormalizePath() {
		assertThat(syncService.normalizePath(null)).isEmpty();
		assertThat(syncService.normalizePath("")).isEmpty();
		assertThat(syncService.normalizePath("foo/bar")).isEqualTo("foo/bar");
		assertThat(syncService.normalizePath("./foo/bar/")).isEqualTo("foo/bar");
		assertThat(syncService.normalizePath("foo//bar")).isEqualTo("foo/bar");
		assertThat(syncService.normalizePath("foo/")).isEqualTo("foo");
		assertThat(syncService.normalizePath("/")).isEqualTo("/");
	}

	@Test
	@DisplayName("Should validate path security guidelines")
	void shouldValidatePathGuidelines() {
		// Valid paths
		syncService.validatePath("SOUL.md");
		syncService.validatePath("skills/code-review");
		syncService.validatePath("skills/code-review/SKILL.md");
		syncService.validatePath(".agent/config");

		// Traversal paths
		assertThrows(SecurityException.class, () -> syncService.validatePath("foo/../bar"));
		assertThrows(SecurityException.class, () -> syncService.validatePath("../bar"));

		// Absolute paths
		assertThrows(SecurityException.class, () -> syncService.validatePath("/foo/bar"));

		// Forbidden exact paths
		assertThrows(SecurityException.class, () -> syncService.validatePath(".trash"));
		assertThrows(SecurityException.class, () -> syncService.validatePath(".snapshots"));
		assertThrows(SecurityException.class, () -> syncService.validatePath(".shadow"));
		assertThrows(SecurityException.class, () -> syncService.validatePath(".storage"));

		// Forbidden prefix paths
		assertThrows(SecurityException.class, () -> syncService.validatePath(".trash/foo"));
		assertThrows(SecurityException.class, () -> syncService.validatePath(".snapshots/foo"));
		assertThrows(SecurityException.class, () -> syncService.validatePath(".shadow/foo"));

		// Forbidden root paths
		assertThrows(IllegalArgumentException.class, () -> syncService.validatePath(""));
		assertThrows(IllegalArgumentException.class, () -> syncService.validatePath("."));
		assertThrows(IllegalArgumentException.class, () -> syncService.validatePath("/"));
	}

	@Test
	@DisplayName("Should identify skills path")
	void shouldIdentifySkillsPath() {
		assertThat(syncService.isSkillsPath("skills")).isTrue();
		assertThat(syncService.isSkillsPath("skills/")).isTrue();
		assertThat(syncService.isSkillsPath("skills/code-review")).isTrue();
		assertThat(syncService.isSkillsPath("SOUL.md")).isFalse();
		assertThat(syncService.isSkillsPath("other-dir/skills")).isFalse();
	}

	@Test
	@DisplayName("Should resolve single file paths successfully")
	void shouldResolveSingleFilePaths() throws IOException {
		when(storageProvider.exists("SOUL.md")).thenReturn(true);
		when(storageProvider.isDirectory("SOUL.md")).thenReturn(false);

		when(storageProvider.exists("PROFILE.md")).thenReturn(false);

		List<String> resolved = syncService.resolveFilePaths(
				storageProvider, List.of("SOUL.md", "PROFILE.md"), false);

		assertThat(resolved).containsExactly("SOUL.md");
	}

	@Test
	@DisplayName("Should recursively resolve directories skipping ignored paths")
	void shouldResolveDirectoriesAndSkipIgnored() throws IOException {
		when(storageProvider.exists("scripts")).thenReturn(true);
		when(storageProvider.isDirectory("scripts")).thenReturn(true);

		// mock listDirectory
		when(storageProvider.listDirectory("scripts")).thenReturn(List.of(
				new StorageProvider.Info("run.sh", true, false, 100, 1000L),
				new StorageProvider.Info("node_modules", true, true, 0, 1000L),
				new StorageProvider.Info("sub", true, true, 0, 1000L)
		));
		when(storageProvider.listDirectory("scripts/sub")).thenReturn(List.of(
				new StorageProvider.Info("test.py", true, false, 50, 1000L)
		));

		// mock ignores
		when(storageProvider.isIgnoredPath("/scripts/run.sh/")).thenReturn(false);
		when(storageProvider.isIgnoredPath("/scripts/node_modules/")).thenReturn(true);
		when(storageProvider.isIgnoredPath("/scripts/sub/")).thenReturn(false);
		when(storageProvider.isIgnoredPath("/scripts/sub/test.py/")).thenReturn(false);

		List<String> resolved = syncService.resolveFilePaths(
				storageProvider, List.of("scripts"), false);

		assertThat(resolved).containsExactlyInAnyOrder(
				"scripts/run.sh",
				"scripts/sub/test.py"
		);
	}

	@Test
	@DisplayName("Should filter skills directory in smart mode (only SKILL.md)")
	void shouldFilterSkillsDirectoryInSmartMode() throws IOException {
		when(storageProvider.exists("skills")).thenReturn(true);
		when(storageProvider.isDirectory("skills")).thenReturn(true);

		when(storageProvider.listDirectory("skills")).thenReturn(List.of(
				new StorageProvider.Info("code-review", true, true, 0, 1000L),
				new StorageProvider.Info("some-config.json", true, false, 200, 1000L)
		));

		when(storageProvider.listDirectory("skills/code-review")).thenReturn(List.of(
				new StorageProvider.Info("SKILL.md", true, false, 500, 1000L),
				new StorageProvider.Info("scripts", true, true, 0, 1000L)
		));
		when(storageProvider.listDirectory("skills/code-review/scripts")).thenReturn(List.of(
				new StorageProvider.Info("lint.py", true, false, 300, 1000L)
		));

		// ignore paths mocks
		when(storageProvider.isIgnoredPath(anyString())).thenReturn(false);

		List<String> resolved = syncService.resolveFilePaths(
				storageProvider, List.of("skills"), false);

		// Should only contain SKILL.md
		assertThat(resolved).containsExactly("skills/code-review/SKILL.md");
	}

	@Test
	@DisplayName("Should keep all files in skills directory in full content mode")
	void shouldKeepAllFilesUnderSkillsInFullMode() throws IOException {
		when(storageProvider.exists("skills")).thenReturn(true);
		when(storageProvider.isDirectory("skills")).thenReturn(true);

		when(storageProvider.listDirectory("skills")).thenReturn(List.of(
				new StorageProvider.Info("code-review", true, true, 0, 1000L)
		));

		when(storageProvider.listDirectory("skills/code-review")).thenReturn(List.of(
				new StorageProvider.Info("SKILL.md", true, false, 500, 1000L),
				new StorageProvider.Info("scripts", true, true, 0, 1000L)
		));
		when(storageProvider.listDirectory("skills/code-review/scripts")).thenReturn(List.of(
				new StorageProvider.Info("lint.py", true, false, 300, 1000L)
		));

		// ignore paths mocks
		when(storageProvider.isIgnoredPath(anyString())).thenReturn(false);

		List<String> resolved = syncService.resolveFilePaths(
				storageProvider, List.of("skills"), true); // skillFullContent = true

		assertThat(resolved).containsExactlyInAnyOrder(
				"skills/code-review/SKILL.md",
				"skills/code-review/scripts/lint.py"
		);
	}

	@Test
	@DisplayName("Should package ZIP stream correctly")
	void shouldPackageZipCorrectly() throws IOException {
		when(storageProvider.readStream("foo.txt"))
				.thenReturn(new ByteArrayInputStream("foo content".getBytes(StandardCharsets.UTF_8)));
		when(storageProvider.readStream("bar/baz.txt"))
				.thenReturn(new ByteArrayInputStream("baz content".getBytes(StandardCharsets.UTF_8)));

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		syncService.writeZip(storageProvider, List.of("foo.txt", "bar/baz.txt"), baos);

		byte[] zipBytes = baos.toByteArray();
		assertThat(zipBytes).isNotEmpty();

		// Verify ZIP entries and contents
		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry entry1 = zis.getNextEntry();
			assertThat(entry1).isNotNull();
			assertThat(entry1.getName()).isEqualTo("foo.txt");
			String content1 = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(content1).isEqualTo("foo content");
			zis.closeEntry();

			ZipEntry entry2 = zis.getNextEntry();
			assertThat(entry2).isNotNull();
			assertThat(entry2.getName()).isEqualTo("bar/baz.txt");
			String content2 = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
			assertThat(content2).isEqualTo("baz content");
			zis.closeEntry();

			assertThat(zis.getNextEntry()).isNull();
		}
	}
}
