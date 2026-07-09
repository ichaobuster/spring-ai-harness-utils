package io.github.springai.harness.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for default methods of {@link StorageProvider}.
 */
@DisplayName("StorageProvider Default Methods Tests")
class StorageProviderDefaultMethodTest {

	@Test
	@DisplayName("Should recursively calculate total size using listDirectory and apply exclusions")
	void shouldCalculateTotalSizeRecursively() throws IOException {
		StorageProvider mockProvider = mock(StorageProvider.class, Mockito.CALLS_REAL_METHODS);

		// Mock root directory listing: has file1.txt (100b), dir1 (directory), and excluded .snapshots (directory)
		StorageProvider.Info file1 = new StorageProvider.Info("file1.txt", true, false, 100L, 1000L);
		StorageProvider.Info dir1 = new StorageProvider.Info("dir1", true, true, 0L, 1000L);
		StorageProvider.Info snapDir = new StorageProvider.Info(".snapshots", true, true, 0L, 1000L);
		when(mockProvider.listDirectory("")).thenReturn(List.of(file1, dir1, snapDir));

		// Mock dir1 listing: has file2.txt (200b)
		StorageProvider.Info file2 = new StorageProvider.Info("file2.txt", true, false, 200L, 1000L);
		when(mockProvider.listDirectory("dir1")).thenReturn(List.of(file2));

		// Call calculateTotalSize with exclusion for ".snapshots/"
		long totalSize = mockProvider.calculateTotalSize(List.of(".snapshots/"));

		// file1 (100) + file2 (200) = 300
		assertThat(totalSize).isEqualTo(300L);
		verify(mockProvider).listDirectory("");
		verify(mockProvider).listDirectory("dir1");
		// .snapshots should be excluded, so we never list it
		verify(mockProvider, never()).listDirectory(".snapshots");
	}
}
