package io.github.springai.harness.snapshot;

import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultSnapshotProvider}.
 */
@DisplayName("DefaultSnapshotProvider Unit Tests")
@ExtendWith(MockitoExtension.class)
class DefaultSnapshotProviderTest {

	@Mock
	private StorageProvider storage;

	private DefaultSnapshotProvider snapshotProvider;

	@BeforeEach
	void setUp() {
		snapshotProvider = new DefaultSnapshotProvider();
	}

	@Test
	@DisplayName("Should return null when file does not exist")
	void shouldReturnNullWhenFileDoesNotExist() throws IOException {
		when(storage.exists("missing.txt")).thenReturn(false);

		String snapshotId = snapshotProvider.createSnapshot(storage, "missing.txt", "WRITE");

		assertThat(snapshotId).isNull();
	}

	@Test
	@DisplayName("Should create snapshot for existing file")
	void shouldCreateSnapshotForExistingFile() throws IOException {
		when(storage.exists("foo.txt")).thenReturn(true);
		when(storage.isDirectory("foo.txt")).thenReturn(false);
		when(storage.readString("foo.txt")).thenReturn("Original Content");

		String snapshotId = snapshotProvider.createSnapshot(storage, "foo.txt", "EDIT");

		assertThat(snapshotId).isNotNull();
		verify(storage).writeString(eq(".snapshots/" + snapshotId + "/foo.txt"), eq("Original Content"));
		verify(storage).writeString(eq(".snapshots/" + snapshotId + "/meta.txt"), anyString());
	}

	@Test
	@DisplayName("Should list snapshots")
	void shouldListSnapshots() throws IOException {
		when(storage.exists(".snapshots")).thenReturn(true);
		when(storage.listDirectory(".snapshots")).thenReturn(List.of(
				new StorageProvider.Info("snap1", true, true, 0, 0)
		));
		when(storage.exists(".snapshots/snap1/meta.txt")).thenReturn(true);
		when(storage.readString(".snapshots/snap1/meta.txt")).thenReturn("""
				filePath=foo.txt
				action=EDIT
				timestamp=100000
				""");

		List<SnapshotInfo> snapshots = snapshotProvider.listSnapshots(storage, null);

		assertThat(snapshots).hasSize(1);
		SnapshotInfo info = snapshots.get(0);
		assertThat(info.snapshotId()).isEqualTo("snap1");
		assertThat(info.filePath()).isEqualTo("foo.txt");
		assertThat(info.action()).isEqualTo("EDIT");
	}

	@Test
	@DisplayName("Should rewind snapshot to original file")
	void shouldRewindSnapshot() throws IOException {
		when(storage.exists(".snapshots/snap1/meta.txt")).thenReturn(true);
		when(storage.readString(".snapshots/snap1/meta.txt")).thenReturn("""
				filePath=foo.txt
				action=EDIT
				timestamp=100000
				""");
		when(storage.exists(".snapshots/snap1/foo.txt")).thenReturn(true);
		when(storage.readString(".snapshots/snap1/foo.txt")).thenReturn("Original Content");

		// When creating rewind snapshot for current file
		when(storage.exists("foo.txt")).thenReturn(true);
		when(storage.isDirectory("foo.txt")).thenReturn(false);
		when(storage.readString("foo.txt")).thenReturn("Modified Content");

		String result = snapshotProvider.rewind(storage, "snap1");

		assertThat(result).contains("Successfully rewound file 'foo.txt' to snapshot state [snap1]");
		verify(storage).writeString("foo.txt", "Original Content");
	}

	@Test
	@DisplayName("Should return null or throw when parameters are invalid in createSnapshot")
	void shouldReturnNullWhenParametersInvalid() throws IOException {
		assertThat(snapshotProvider.createSnapshot(null, "foo.txt", "WRITE")).isNull();
		assertThat(snapshotProvider.createSnapshot(storage, "", "WRITE")).isNull();
	}

	@Test
	@DisplayName("Should return null when storage isDirectory is true")
	void shouldReturnNullWhenPathIsDirectory() throws IOException {
		when(storage.exists("dir")).thenReturn(true);
		when(storage.isDirectory("dir")).thenReturn(true);

		String result = snapshotProvider.createSnapshot(storage, "dir", "WRITE");
		assertThat(result).isNull();
	}

	@Test
	@DisplayName("Should throw IOException when storage operation fails in createSnapshot")
	void shouldThrowExceptionWhenStorageFailsInCreate() throws IOException {
		when(storage.exists("foo.txt")).thenReturn(true);
		when(storage.isDirectory("foo.txt")).thenReturn(false);
		when(storage.readString("foo.txt")).thenThrow(new IOException("Disk read error"));

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> snapshotProvider.createSnapshot(storage, "foo.txt", "WRITE")
		);
	}

	@Test
	@DisplayName("Should return empty list or throw when storage is null or missing in listSnapshots")
	void shouldReturnEmptyListWhenStorageNullOrMissing() throws IOException {
		assertThat(snapshotProvider.listSnapshots(null, "foo.txt")).isEmpty();
		
		when(storage.exists(".snapshots")).thenReturn(false);
		assertThat(snapshotProvider.listSnapshots(storage, "foo.txt")).isEmpty();
	}

	@Test
	@DisplayName("Should throw IllegalArgumentException when snapshotId is empty in rewind")
	void shouldThrowExceptionWhenSnapshotIdIsEmptyInRewind() {
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> snapshotProvider.rewind(storage, "")
		);
		org.junit.jupiter.api.Assertions.assertThrows(
				IllegalArgumentException.class,
				() -> snapshotProvider.rewind(null, "snap")
		);
	}

	@Test
	@DisplayName("Should throw FileNotFoundException when meta.txt does not exist in rewind")
	void shouldThrowFileNotFoundWhenMetaMissingInRewind() throws IOException {
		when(storage.exists(".snapshots/missing-snap/meta.txt")).thenReturn(false);

		org.junit.jupiter.api.Assertions.assertThrows(
				java.io.FileNotFoundException.class,
				() -> snapshotProvider.rewind(storage, "missing-snap")
		);
	}

	@Test
	@DisplayName("Should throw IOException when metadata is corrupted in rewind")
	void shouldThrowExceptionWhenMetadataCorrupted() throws IOException {
		when(storage.exists(".snapshots/corrupt-snap/meta.txt")).thenReturn(true);
		when(storage.readString(".snapshots/corrupt-snap/meta.txt")).thenReturn("bad-format");

		org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> snapshotProvider.rewind(storage, "corrupt-snap")
		);
	}

	@Test
	@DisplayName("Should throw FileNotFoundException when snapshot content file is missing in rewind")
	void shouldThrowFileNotFoundWhenContentMissingInRewind() throws IOException {
		when(storage.exists(".snapshots/nocontent-snap/meta.txt")).thenReturn(true);
		when(storage.readString(".snapshots/nocontent-snap/meta.txt")).thenReturn("""
				filePath=foo.txt
				action=EDIT
				timestamp=100000
				""");
		when(storage.exists(".snapshots/nocontent-snap/foo.txt")).thenReturn(false);

		org.junit.jupiter.api.Assertions.assertThrows(
				java.io.FileNotFoundException.class,
				() -> snapshotProvider.rewind(storage, "nocontent-snap")
		);
	}

	@Test
	@DisplayName("Should auto clean expired snapshots when enabled")
	void shouldAutoCleanExpiredSnapshotsWhenEnabled() throws IOException {
		HarnessMcpServerProperties.SnapshotProperties props = new HarnessMcpServerProperties.SnapshotProperties();
		props.setAutoCleanEnabled(true);
		props.setCleanTtl(java.time.Duration.ofDays(7));

		DefaultSnapshotProvider cleanProvider = new DefaultSnapshotProvider(props);

		when(storage.exists("foo.txt")).thenReturn(true);
		when(storage.isDirectory("foo.txt")).thenReturn(false);
		when(storage.readString("foo.txt")).thenReturn("content");

		when(storage.exists(".snapshots")).thenReturn(true);

		long now = System.currentTimeMillis();
		long expiredTs = now - java.time.Duration.ofDays(8).toMillis();
		long recentTs = now - java.time.Duration.ofDays(2).toMillis();

		when(storage.listDirectory(".snapshots")).thenReturn(List.of(
				new StorageProvider.Info(expiredTs + "_1", true, true, 0, 0),
				new StorageProvider.Info(recentTs + "_2", true, true, 0, 0),
				new StorageProvider.Info("invalid-name", true, true, 0, 0)
		));

		String snapshotId = cleanProvider.createSnapshot(storage, "foo.txt", "WRITE");

		assertThat(snapshotId).isNotNull();
		verify(storage).delete(".snapshots/" + expiredTs + "_1");
		verify(storage, org.mockito.Mockito.never()).delete(".snapshots/" + recentTs + "_2");
		verify(storage, org.mockito.Mockito.never()).delete(".snapshots/invalid-name");
	}

	@Test
	@DisplayName("Should not auto clean snapshots when disabled")
	void shouldNotAutoCleanSnapshotsWhenDisabled() throws IOException {
		HarnessMcpServerProperties.SnapshotProperties props = new HarnessMcpServerProperties.SnapshotProperties();
		props.setAutoCleanEnabled(false);

		DefaultSnapshotProvider noCleanProvider = new DefaultSnapshotProvider(props);

		when(storage.exists("foo.txt")).thenReturn(true);
		when(storage.isDirectory("foo.txt")).thenReturn(false);
		when(storage.readString("foo.txt")).thenReturn("content");

		String snapshotId = noCleanProvider.createSnapshot(storage, "foo.txt", "WRITE");

		assertThat(snapshotId).isNotNull();
		verify(storage, org.mockito.Mockito.never()).listDirectory(".snapshots");
	}
}
