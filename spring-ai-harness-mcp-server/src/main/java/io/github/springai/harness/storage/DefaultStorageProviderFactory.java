package io.github.springai.harness.storage;

import com.aliyun.oss.OSS;
import io.github.springai.harness.auth.AuthenticationProvider;
import io.github.springai.harness.auth.WorkspaceIdentity;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Default implementation of StorageProviderFactory using AuthenticationProvider to extract identity
 * and construct AliyunOssStorage.
 *
 * @author buyc
 */
public class DefaultStorageProviderFactory implements StorageProviderFactory {

	private final OSS ossClient;
	private final HarnessMcpServerProperties properties;
	private final AuthenticationProvider authenticationProvider;
	private final org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistryProvider;

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider) {
		this(ossClient, properties, authenticationProvider, null);
	}

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider, org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observationRegistryProvider) {
		this.ossClient = ossClient;
		this.properties = properties;
		this.authenticationProvider = authenticationProvider;
		this.observationRegistryProvider = observationRegistryProvider;
	}

	@Override
	public StorageProvider getStorageProvider(McpTransportContext context) {
		ServerRequest serverRequest = (ServerRequest) context.get(McpTransportContext.KEY);
		WorkspaceIdentity identity = this.authenticationProvider.authenticate(serverRequest);
		String workspaceKey = identity.getWorkspacePath(this.properties.getOssPrefix());
		StorageProvider baseStorage = new AliyunOssStorage(this.ossClient, this.properties.getOssBucket(), workspaceKey);

		io.micrometer.observation.ObservationRegistry registry = observationRegistryProvider != null ? observationRegistryProvider.getIfAvailable() : null;
		if (registry != null && this.properties.getObservability().isEnabled()) {
			return new ObservedStorageProvider(baseStorage, registry);
		}
		return baseStorage;
	}
}
