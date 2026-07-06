package io.github.springai.harness.controller;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.github.springai.harness.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link AdminApiController}.
 */
@DisplayName("AdminApiController Unit Tests")
@ExtendWith(MockitoExtension.class)
class AdminApiControllerTest {

	@Mock
	private OSS ossClient;

	@Mock
	private HarnessMcpServerProperties properties;

	@Mock
	private StorageProvider adminStorageProvider;

	@Spy
	private AdminApiController controller;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(controller, "ossClient", ossClient);
		ReflectionTestUtils.setField(controller, "properties", properties);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	@DisplayName("Should return 401 Unauthorized when admin token is missing or invalid")
	void shouldReturn401WhenAdminTokenInvalid() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");

		mockMvc.perform(get("/api/v1/admin/workspaces")
						.header("X-Admin-Token", "wrong-token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Should list workspaces when admin token is valid")
	void shouldListWorkspacesWhenAdminTokenValid() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");
		when(properties.getOssPrefix()).thenReturn("mcp/workspaces/");
		when(properties.getOssBucket()).thenReturn("test-bucket");

		ObjectListing listing = new ObjectListing();
		listing.getCommonPrefixes().add("mcp/workspaces/sys1-agent1-user1/");
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

		mockMvc.perform(get("/api/v1/admin/workspaces")
						.header("X-Admin-Token", "admin-secret"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].workspaceId").value("sys1-agent1-user1"))
				.andExpect(jsonPath("$[0].system").value("sys1"))
				.andExpect(jsonPath("$[0].agent").value("agent1"))
				.andExpect(jsonPath("$[0].user").value("user1"));
	}

	@Test
	@DisplayName("Should return 400 when list workspaces throws exception")
	void shouldReturn400WhenListWorkspacesFails() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");
		when(properties.getOssPrefix()).thenReturn("mcp/workspaces/");
		when(properties.getOssBucket()).thenReturn("test-bucket");
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenThrow(new RuntimeException("OSS connection error"));

		mockMvc.perform(get("/api/v1/admin/workspaces")
						.header("X-Admin-Token", "admin-secret"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("OSS connection error"));
	}

	@Test
	@DisplayName("Should list workspace files for admin")
	void shouldListWorkspaceFilesWhenAdminTokenValid() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");
		doReturn(adminStorageProvider).when(controller).createStorageProvider("sys1-agent1-user1");
		when(adminStorageProvider.listDirectory("")).thenReturn(List.of(
				new StorageProvider.Info("src/main.py", true, false, 250, 200000L)
		));

		mockMvc.perform(get("/api/v1/admin/workspaces/sys1-agent1-user1/files")
						.header("X-Admin-Token", "admin-secret"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].path").value("src/main.py"))
				.andExpect(jsonPath("$[0].isDirectory").value(false))
				.andExpect(jsonPath("$[0].size").value(250));
	}

	@Test
	@DisplayName("Should return 401 when list workspace files with invalid admin token")
	void shouldReturn401WhenListWorkspaceFilesTokenInvalid() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");

		mockMvc.perform(get("/api/v1/admin/workspaces/sys1-agent1-user1/files")
						.header("X-Admin-Token", "bad-token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Should return 400 when list workspace files throws exception")
	void shouldReturn400WhenListWorkspaceFilesFails() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");
		doReturn(adminStorageProvider).when(controller).createStorageProvider("sys1-agent1-user1");
		when(adminStorageProvider.listDirectory("")).thenThrow(new IOException("Access denied"));

		mockMvc.perform(get("/api/v1/admin/workspaces/sys1-agent1-user1/files")
						.header("X-Admin-Token", "admin-secret"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Access denied"));
	}

	@Test
	@DisplayName("Should delete workspace file for admin when file exists")
	void shouldDeleteWorkspaceFileWhenAdminTokenValid() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");
		doReturn(adminStorageProvider).when(controller).createStorageProvider("sys1-agent1-user1");
		when(adminStorageProvider.exists("temp.log")).thenReturn(true);

		mockMvc.perform(delete("/api/v1/admin/workspaces/sys1-agent1-user1/files")
						.param("path", "temp.log")
						.header("X-Admin-Token", "admin-secret"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("File deleted successfully"))
				.andExpect(jsonPath("$.workspaceKey").value("sys1-agent1-user1"))
				.andExpect(jsonPath("$.path").value("temp.log"));

		verify(adminStorageProvider).delete("temp.log");
	}

	@Test
	@DisplayName("Should return 404 when admin delete file path does not exist")
	void shouldReturn404WhenDeleteWorkspaceFileNotFound() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");
		doReturn(adminStorageProvider).when(controller).createStorageProvider("sys1-agent1-user1");
		when(adminStorageProvider.exists("missing.log")).thenReturn(false);

		mockMvc.perform(delete("/api/v1/admin/workspaces/sys1-agent1-user1/files")
						.param("path", "missing.log")
						.header("X-Admin-Token", "admin-secret"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("Path not found: missing.log"));
	}

	@Test
	@DisplayName("Should return 401 when admin delete workspace file token invalid")
	void shouldReturn401WhenDeleteWorkspaceFileTokenInvalid() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");

		mockMvc.perform(delete("/api/v1/admin/workspaces/sys1-agent1-user1/files")
						.param("path", "temp.log")
						.header("X-Admin-Token", "invalid"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Should return 400 when admin delete workspace file throws exception")
	void shouldReturn400WhenDeleteWorkspaceFileFails() throws Exception {
		when(properties.getAdminToken()).thenReturn("admin-secret");
		doReturn(adminStorageProvider).when(controller).createStorageProvider("sys1-agent1-user1");
		when(adminStorageProvider.exists("temp.log")).thenReturn(true);
		doThrow(new RuntimeException("Delete permission error")).when(adminStorageProvider).delete("temp.log");

		mockMvc.perform(delete("/api/v1/admin/workspaces/sys1-agent1-user1/files")
						.param("path", "temp.log")
						.header("X-Admin-Token", "admin-secret"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Delete permission error"));
	}
}
