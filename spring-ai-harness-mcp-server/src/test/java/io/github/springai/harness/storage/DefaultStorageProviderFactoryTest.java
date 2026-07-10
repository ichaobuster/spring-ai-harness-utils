package io.github.springai.harness.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import io.github.springai.harness.auth.AuthenticationProvider;
import io.github.springai.harness.auth.WorkspaceIdentity;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.function.ServerRequest;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DefaultStorageProviderFactory Unit Tests")
@ExtendWith(MockitoExtension.class)
class DefaultStorageProviderFactoryTest {

	@Mock
	private OSS ossClient;

	@Mock
	private AuthenticationProvider authenticationProvider;

	@Mock
	private ObjectProvider<ObservationRegistry> observationRegistryProvider;

	@Mock
	private ObservationRegistry observationRegistry;

	@Mock
	private ServerRequest serverRequest;

	private HarnessMcpServerProperties properties;
	private DefaultStorageProviderFactory factory;
	private McpTransportContext transportContext;

	@BeforeEach
	void setUp() {
		properties = new HarnessMcpServerProperties();
		properties.setOssBucket("test-bucket");
		properties.setOssPrefix("mcp/workspaces/");
		properties.getQuota().setEnabled(false);

		ObjectListing mockListing = mock(ObjectListing.class);
		lenient().when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(mockListing);
		lenient().when(mockListing.getObjectSummaries()).thenReturn(Collections.emptyList());

		factory = new DefaultStorageProviderFactory(ossClient, properties, authenticationProvider, observationRegistryProvider);

		transportContext = McpTransportContext.create(Map.of(McpTransportContext.KEY, serverRequest));
	}

	@Test
	@DisplayName("Should return QuotaEnforcedStorageProvider when quota is enabled")
	void shouldReturnQuotaEnforcedStorageWhenQuotaEnabled() {
		properties.getQuota().setEnabled(true);
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "agent", "user");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);

		StorageProvider provider = factory.getStorageProvider(transportContext);

		assertThat(provider).isInstanceOf(QuotaEnforcedStorageProvider.class);
	}

	@Test
	@DisplayName("Should construct with three arguments successfully")
	void shouldConstructWithThreeArguments() {
		DefaultStorageProviderFactory threeArgFactory = new DefaultStorageProviderFactory(ossClient, properties, authenticationProvider);
		assertThat(threeArgFactory).isNotNull();
	}

	@Test
	@DisplayName("Should return raw AliyunOssStorage when observability is disabled")
	void shouldReturnRawStorageWhenObservabilityDisabled() {
		properties.getObservability().setEnabled(false);
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "agent", "user");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);

		StorageProvider provider = factory.getStorageProvider(transportContext);

		assertThat(provider).isInstanceOf(AliyunOssStorage.class);
		assertThat(provider).isNotInstanceOf(ObservedStorageProvider.class);
	}

	@Test
	@DisplayName("Should return raw AliyunOssStorage when registry is null")
	void shouldReturnRawStorageWhenRegistryIsNull() {
		properties.getObservability().setEnabled(true);
		when(observationRegistryProvider.getIfAvailable()).thenReturn(null);
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "agent", "user");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);

		StorageProvider provider = factory.getStorageProvider(transportContext);

		assertThat(provider).isInstanceOf(AliyunOssStorage.class);
		assertThat(provider).isNotInstanceOf(ObservedStorageProvider.class);
	}

	@Test
	@DisplayName("Should return ObservedStorageProvider when registry is present and observability is enabled")
	void shouldReturnObservedStorageWhenRegistryPresentAndObservabilityEnabled() {
		properties.getObservability().setEnabled(true);
		when(observationRegistryProvider.getIfAvailable()).thenReturn(observationRegistry);
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "agent", "user");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);

		StorageProvider provider = factory.getStorageProvider(transportContext);

		assertThat(provider).isInstanceOf(ObservedStorageProvider.class);
	}

	@Test
	@DisplayName("Should create directory when root directory does not exist")
	void shouldCreateDirectoryWhenRootDoesNotExist() {
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "agent", "user");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);

		ObjectListing mockListing = mock(ObjectListing.class);
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(mockListing);
		when(mockListing.getObjectSummaries()).thenReturn(Collections.emptyList());

		StorageProvider provider = factory.getStorageProvider(transportContext);

		assertThat(provider).isNotNull();
		verify(ossClient).putObject(eq("test-bucket"), eq("mcp/workspaces/sys-agent-user/"), any(InputStream.class));
	}

	@Test
	@DisplayName("Should not create directory when root directory already exists")
	void shouldNotCreateDirectoryWhenRootExists() {
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "agent", "user");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);

		ObjectListing mockListing = mock(ObjectListing.class);
		OSSObjectSummary mockSummary = mock(OSSObjectSummary.class);
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(mockListing);
		when(mockListing.getObjectSummaries()).thenReturn(List.of(mockSummary));

		StorageProvider provider = factory.getStorageProvider(transportContext);

		assertThat(provider).isNotNull();
		verify(ossClient, never()).putObject(any(), any(), any(InputStream.class));
	}

	@Test
	@DisplayName("Should swallow exception when directory creation fails")
	void shouldSwallowExceptionWhenDirectoryCreationFails() {
		WorkspaceIdentity identity = new WorkspaceIdentity("sys", "agent", "user");
		when(authenticationProvider.authenticate(serverRequest)).thenReturn(identity);

		ObjectListing mockListing = mock(ObjectListing.class);
		when(ossClient.listObjects(any(ListObjectsRequest.class))).thenReturn(mockListing);
		when(mockListing.getObjectSummaries()).thenReturn(Collections.emptyList());

		when(ossClient.putObject(any(), any(), any(InputStream.class)))
				.thenThrow(new OSSException("OSS error"));

		StorageProvider provider = factory.getStorageProvider(transportContext);

		assertThat(provider).isNotNull();
		verify(ossClient).putObject(eq("test-bucket"), eq("mcp/workspaces/sys-agent-user/"), any(InputStream.class));
	}
}
