package io.github.springai.harness.controller;

import io.github.springai.harness.snapshot.SnapshotInfo;
import io.github.springai.harness.snapshot.SnapshotProvider;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link WorkspaceApiController}.
 */
@DisplayName("WorkspaceApiController Unit Tests")
@ExtendWith(MockitoExtension.class)
class WorkspaceApiControllerTest {

	@Mock
	private StorageProviderFactory storageProviderFactory;

	@Mock
	private SnapshotProvider snapshotProvider;

	@Mock
	private StorageProvider storageProvider;

	@InjectMocks
	private WorkspaceApiController controller;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	@DisplayName("Should list files successfully")
	void shouldListFiles() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.listDirectory("")).thenReturn(List.of(
				new StorageProvider.Info("foo.txt", true, false, 100, 1000L)
		));

		mockMvc.perform(get("/api/v1/workspace/files")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].path").value("foo.txt"))
				.andExpect(jsonPath("$[0].isDirectory").value(false))
				.andExpect(jsonPath("$[0].size").value(100));
	}

	@Test
	@DisplayName("Should get file content successfully")
	void shouldGetFileContent() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("foo.txt")).thenReturn(true);
		when(storageProvider.isDirectory("foo.txt")).thenReturn(false);
		when(storageProvider.readString("foo.txt")).thenReturn("Hello World");

		mockMvc.perform(get("/api/v1/workspace/files/content")
						.param("path", "foo.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(content().string("Hello World"));
	}

	@Test
	@DisplayName("Should upload file and create snapshot")
	void shouldUploadFile() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);

		mockMvc.perform(post("/api/v1/workspace/files/upload")
						.param("path", "foo.txt")
						.content("New Content")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("File uploaded successfully"));

		verify(snapshotProvider).createSnapshot(storageProvider, "foo.txt", "WRITE");
		verify(storageProvider).writeString("foo.txt", "New Content");
	}

	@Test
	@DisplayName("Should delete file via trash")
	void shouldDeleteFileViaTrash() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("foo.txt")).thenReturn(true);

		mockMvc.perform(delete("/api/v1/workspace/files")
						.param("path", "foo.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Moved to trash successfully"));

		verify(snapshotProvider).createSnapshot(storageProvider, "foo.txt", "TRASH");
		verify(storageProvider).trash("foo.txt");
	}

	@Test
	@DisplayName("Should list snapshots")
	void shouldListSnapshots() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(snapshotProvider.listSnapshots(storageProvider, "foo.txt")).thenReturn(List.of(
				new SnapshotInfo("snap1", "foo.txt", "EDIT", ".snapshots/snap1/foo.txt", 1000L)
		));

		mockMvc.perform(get("/api/v1/workspace/snapshots")
						.param("path", "foo.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].snapshotId").value("snap1"))
				.andExpect(jsonPath("$[0].action").value("EDIT"));
	}

	@Test
	@DisplayName("Should rewind snapshot")
	void shouldRewindSnapshot() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(snapshotProvider.rewind(storageProvider, "snap1")).thenReturn("Successfully rewound file.");

		mockMvc.perform(post("/api/v1/workspace/rewind/snap1")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Successfully rewound file."));
	}
}
