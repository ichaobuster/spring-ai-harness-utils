package io.github.springai.harness.controller;

import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.storage.StorageProvider;
import io.github.springai.harness.storage.StorageProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("AttachmentController Unit Tests")
@ExtendWith(MockitoExtension.class)
class AttachmentControllerTest {

	@Mock
	private StorageProviderFactory storageProviderFactory;

	@Mock
	private StorageProvider storageProvider;

	@Spy
	private HarnessMcpServerProperties properties = new HarnessMcpServerProperties();

	@InjectMocks
	private AttachmentController controller;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalRestExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("Should upload attachment successfully with conversationId")
	void shouldUploadAttachmentSuccessfully() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);

		MockMultipartFile mockFile = new MockMultipartFile(
				"file",
				"report..xlsx",
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
				new byte[]{1, 2, 3, 4}
		);

		mockMvc.perform(multipart("/api/v1/workspace/attachments")
						.file(mockFile)
						.param("conversationId", "conv-123"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attachmentId").exists())
				.andExpect(jsonPath("$.conversationId").value("conv-123"))
				.andExpect(jsonPath("$.fileName").value("report_xlsx"))
				.andExpect(jsonPath("$.size").value(4))
				.andExpect(jsonPath("$.path").exists());

		verify(storageProvider).writeFile(anyString(), any(InputStream.class), eq(4L));
	}

	@Test
	@DisplayName("Should upload attachment successfully with default conversationId when omitted")
	void shouldUploadAttachmentWithDefaultConversationId() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);

		MockMultipartFile mockFile = new MockMultipartFile(
				"file",
				"photo.png",
				"image/png",
				new byte[]{1, 2, 3}
		);

		mockMvc.perform(multipart("/api/v1/workspace/attachments")
						.file(mockFile))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.attachmentId").exists())
				.andExpect(jsonPath("$.conversationId").value("default"))
				.andExpect(jsonPath("$.fileName").value("photo.png"))
				.andExpect(jsonPath("$.size").value(3));

		verify(storageProvider).writeFile(anyString(), any(InputStream.class), eq(3L));
	}

	@Test
	@DisplayName("Should reject empty upload file")
	void shouldRejectEmptyUploadFile() throws Exception {
		MockMultipartFile mockFile = new MockMultipartFile(
				"file",
				"",
				"application/octet-stream",
				new byte[0]
		);

		mockMvc.perform(multipart("/api/v1/workspace/attachments")
						.file(mockFile))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Uploaded file is empty"));
	}

	@Test
	@DisplayName("Should list attachments for specified conversationId")
	void shouldListAttachmentsForConversation() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("attachments")).thenReturn(true);
		when(storageProvider.exists("attachments/conv-123")).thenReturn(true);
		when(storageProvider.isDirectory("attachments/conv-123")).thenReturn(true);
		when(storageProvider.listDirectory("attachments/conv-123")).thenReturn(List.of(
				new StorageProvider.Info("uuid-1/", true, true, 0, 0)
		));
		when(storageProvider.listDirectory("attachments/conv-123/uuid-1")).thenReturn(List.of(
				new StorageProvider.Info("report.xlsx", true, false, 1024L, 1000L)
		));

		mockMvc.perform(get("/api/v1/workspace/attachments")
						.param("conversationId", "conv-123"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].attachmentId").value("uuid-1"))
				.andExpect(jsonPath("$[0].conversationId").value("conv-123"))
				.andExpect(jsonPath("$[0].fileName").value("report.xlsx"))
				.andExpect(jsonPath("$[0].size").value(1024))
				.andExpect(jsonPath("$[0].path").value("attachments/conv-123/uuid-1/report.xlsx"));
	}

	@Test
	@DisplayName("Should list all attachments when conversationId is not provided")
	void shouldListAllAttachments() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("attachments")).thenReturn(true);
		when(storageProvider.listDirectory("attachments")).thenReturn(List.of(
				new StorageProvider.Info("conv-1/", true, true, 0, 0),
				new StorageProvider.Info("conv-2/", true, true, 0, 0)
		));
		when(storageProvider.listDirectory("attachments/conv-1")).thenReturn(List.of(
				new StorageProvider.Info("uuid-1/", true, true, 0, 0)
		));
		when(storageProvider.listDirectory("attachments/conv-1/uuid-1")).thenReturn(List.of(
				new StorageProvider.Info("a.txt", true, false, 10L, 1000L)
		));
		when(storageProvider.listDirectory("attachments/conv-2")).thenReturn(List.of());

		mockMvc.perform(get("/api/v1/workspace/attachments"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].attachmentId").value("uuid-1"))
				.andExpect(jsonPath("$[0].conversationId").value("conv-1"))
				.andExpect(jsonPath("$[0].fileName").value("a.txt"))
				.andExpect(jsonPath("$[0].size").value(10));
	}

	@Test
	@DisplayName("Should delete attachment by ID and conversationId")
	void shouldDeleteAttachmentWithConversationId() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("attachments/conv-123/uuid-999")).thenReturn(true);

		mockMvc.perform(delete("/api/v1/workspace/attachments/uuid-999")
						.param("conversationId", "conv-123"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Attachment moved to trash successfully"))
				.andExpect(jsonPath("$.attachmentId").value("uuid-999"));

		verify(storageProvider).trash("attachments/conv-123/uuid-999");
	}

	@Test
	@DisplayName("Should search and delete attachment by ID when conversationId is omitted")
	void shouldSearchAndDeleteAttachment() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("attachments")).thenReturn(true);
		when(storageProvider.listDirectory("attachments")).thenReturn(List.of(
				new StorageProvider.Info("conv-1/", true, true, 0, 0),
				new StorageProvider.Info("conv-2/", true, true, 0, 0)
		));
		when(storageProvider.exists("attachments/conv-1/uuid-999")).thenReturn(false);
		when(storageProvider.exists("attachments/conv-2/uuid-999")).thenReturn(true);

		mockMvc.perform(delete("/api/v1/workspace/attachments/uuid-999"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Attachment moved to trash successfully"))
				.andExpect(jsonPath("$.attachmentId").value("uuid-999"));

		verify(storageProvider).trash("attachments/conv-2/uuid-999");
	}

	@Test
	@DisplayName("Should delete attachment permanently when trash is false")
	void shouldDeleteAttachmentPermanently() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("attachments/conv-123/uuid-999")).thenReturn(true);

		mockMvc.perform(delete("/api/v1/workspace/attachments/uuid-999")
						.param("conversationId", "conv-123")
						.param("trash", "false"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Attachment deleted successfully"))
				.andExpect(jsonPath("$.attachmentId").value("uuid-999"));

		verify(storageProvider).delete("attachments/conv-123/uuid-999");
		verify(storageProvider, never()).trash(anyString());
	}

	@Test
	@DisplayName("Should return 404 when deleting non-existent attachment")
	void shouldReturn404WhenDeletingNonExistent() throws Exception {
		when(storageProviderFactory.getStorageProvider(any())).thenReturn(storageProvider);
		when(storageProvider.exists("attachments")).thenReturn(false);

		mockMvc.perform(delete("/api/v1/workspace/attachments/uuid-999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("Attachment not found: uuid-999"));
	}
}
