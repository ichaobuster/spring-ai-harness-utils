package io.github.springai.harness.snapshot;

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
}
