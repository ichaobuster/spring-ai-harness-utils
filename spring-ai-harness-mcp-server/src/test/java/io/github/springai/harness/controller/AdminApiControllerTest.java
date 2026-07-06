package io.github.springai.harness.controller;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectListing;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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

	@InjectMocks
	private AdminApiController controller;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
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
}
