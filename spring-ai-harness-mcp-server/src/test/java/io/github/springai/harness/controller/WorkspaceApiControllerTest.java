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

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
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
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalRestExceptionHandler())
				.build();
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
	@DisplayName("Should return 500 when list files throws exception")
	void shouldReturn500WhenListFilesFails() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenThrow(new RuntimeException("Storage error"));

		mockMvc.perform(get("/api/v1/workspace/files")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Storage error"));
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
	@DisplayName("Should return 404 when file does not exist")
	void shouldReturn404WhenGetFileContentNotFound() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("missing.txt")).thenReturn(false);

		mockMvc.perform(get("/api/v1/workspace/files/content")
						.param("path", "missing.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("File not found: missing.txt"));
	}

	@Test
	@DisplayName("Should return 400 when path is a directory")
	void shouldReturn400WhenGetFileContentIsDirectory() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("some-dir")).thenReturn(true);
		when(storageProvider.isDirectory("some-dir")).thenReturn(true);

		mockMvc.perform(get("/api/v1/workspace/files/content")
						.param("path", "some-dir")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Path is a directory: some-dir"));
	}

	@Test
	@DisplayName("Should return 500 when get file content throws exception")
	void shouldReturn500WhenGetFileContentFails() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("foo.txt")).thenThrow(new RuntimeException("Read error"));

		mockMvc.perform(get("/api/v1/workspace/files/content")
						.param("path", "foo.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Read error"));
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
	@DisplayName("Should return 500 when upload file throws exception")
	void shouldReturn500WhenUploadFileFails() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		doThrow(new RuntimeException("Write error")).when(storageProvider).writeString("foo.txt", "New Content");

		mockMvc.perform(post("/api/v1/workspace/files/upload")
						.param("path", "foo.txt")
						.content("New Content")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Write error"));
	}

	@Test
	@DisplayName("Should delete file via trash when trash=true")
	void shouldDeleteFileViaTrash() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("foo.txt")).thenReturn(true);

		mockMvc.perform(delete("/api/v1/workspace/files")
						.param("path", "foo.txt")
						.param("trash", "true")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Moved to trash successfully"));

		verify(snapshotProvider).createSnapshot(storageProvider, "foo.txt", "TRASH");
		verify(storageProvider).trash("foo.txt");
	}

	@Test
	@DisplayName("Should delete file permanently when trash=false")
	void shouldDeleteFilePermanentlyWhenTrashFalse() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("foo.txt")).thenReturn(true);

		mockMvc.perform(delete("/api/v1/workspace/files")
						.param("path", "foo.txt")
						.param("trash", "false")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Deleted file successfully"));

		verify(snapshotProvider).createSnapshot(storageProvider, "foo.txt", "TRASH");
		verify(storageProvider).delete("foo.txt");
	}

	@Test
	@DisplayName("Should return 404 when delete path does not exist")
	void shouldReturn404WhenDeletePathNotFound() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("missing.txt")).thenReturn(false);

		mockMvc.perform(delete("/api/v1/workspace/files")
						.param("path", "missing.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("Path not found: missing.txt"));
	}

	@Test
	@DisplayName("Should return 500 when delete throws exception")
	void shouldReturn500WhenDeleteFails() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("foo.txt")).thenReturn(true);
		doThrow(new RuntimeException("Trash error")).when(storageProvider).trash("foo.txt");

		mockMvc.perform(delete("/api/v1/workspace/files")
						.param("path", "foo.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Trash error"));
	}

	@Test
	@DisplayName("Should list snapshots successfully")
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
	@DisplayName("Should return 500 when list snapshots throws exception")
	void shouldReturn500WhenListSnapshotsFails() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(snapshotProvider.listSnapshots(any(), any())).thenThrow(new IOException("Snapshot read error"));

		mockMvc.perform(get("/api/v1/workspace/snapshots")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Snapshot read error"));
	}

	@Test
	@DisplayName("Should rewind snapshot successfully")
	void shouldRewindSnapshot() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(snapshotProvider.rewind(storageProvider, "snap1")).thenReturn("Successfully rewound file.");

		mockMvc.perform(post("/api/v1/workspace/rewind/snap1")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Successfully rewound file."));
	}

	@Test
	@DisplayName("Should return 500 when rewind returns error message")
	void shouldReturn500WhenRewindReturnsError() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(snapshotProvider.rewind(storageProvider, "invalid-snap")).thenThrow(new java.io.FileNotFoundException("Snapshot not found: invalid-snap"));

		mockMvc.perform(post("/api/v1/workspace/rewind/invalid-snap")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Snapshot not found: invalid-snap"));
	}

	@Test
	@DisplayName("Should return 500 when rewind throws exception")
	void shouldReturn500WhenRewindFails() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(snapshotProvider.rewind(any(), any())).thenThrow(new RuntimeException("Rewind error"));

		mockMvc.perform(post("/api/v1/workspace/rewind/snap1")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Rewind error"));
	}

	@Test
	@DisplayName("Should move file successfully")
	void shouldMoveFileSuccessfully() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("old.txt")).thenReturn(true);

		mockMvc.perform(post("/api/v1/workspace/files/move")
						.param("fromPath", "old.txt")
						.param("toPath", "new.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("File moved successfully"));

		verify(snapshotProvider).createSnapshot(storageProvider, "old.txt", "MOVE");
		verify(storageProvider).rename("old.txt", "new.txt");
	}

	@Test
	@DisplayName("Should return 404 when move file source path does not exist")
	void shouldReturn404WhenMoveFileSourceNotFound() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("missing.txt")).thenReturn(false);

		mockMvc.perform(post("/api/v1/workspace/files/move")
						.param("fromPath", "missing.txt")
						.param("toPath", "new.txt")
						.header("Authorization", "sys1-agent1-user1"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("Source path not found: missing.txt"));
	}
}
