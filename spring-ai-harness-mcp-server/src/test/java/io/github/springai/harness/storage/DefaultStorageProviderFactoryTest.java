package io.github.springai.harness.storage;

import com.aliyun.oss.OSS;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
}
