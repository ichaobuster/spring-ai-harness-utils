package io.github.springai.harness.storage;

import com.aliyun.oss.OSS;
import io.github.springai.harness.auth.AuthenticationProvider;
import io.github.springai.harness.auth.WorkspaceIdentity;
import io.github.springai.harness.autoconfig.HarnessMcpServerProperties;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Default implementation of StorageProviderFactory using AuthenticationProvider to extract identity
 * and construct AliyunOssStorage.
 *
 * @author ichaobuster
 */
public class DefaultStorageProviderFactory implements StorageProviderFactory {

	private final OSS ossClient;
	private final HarnessMcpServerProperties properties;
	private final AuthenticationProvider authenticationProvider;
	private final QuotaManager quotaManager;
	private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider) {
		this(ossClient, properties, authenticationProvider, null, null);
	}

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider, ObjectProvider<ObservationRegistry> observationRegistryProvider) {
		this(ossClient, properties, authenticationProvider, null, observationRegistryProvider);
	}

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider, QuotaManager quotaManager, ObjectProvider<ObservationRegistry> observationRegistryProvider) {
		this.ossClient = ossClient;
		this.properties = properties;
		this.authenticationProvider = authenticationProvider;
		this.quotaManager = quotaManager != null ? quotaManager : new QuotaManager(properties.getQuota());
		this.observationRegistryProvider = observationRegistryProvider;
	}

	@Override
	public StorageProvider getStorageProvider(McpTransportContext context) {
		ServerRequest serverRequest = (ServerRequest) context.get(McpTransportContext.KEY);
		WorkspaceIdentity identity = this.authenticationProvider.authenticate(serverRequest);
		String workspaceKey = identity.getWorkspacePath(this.properties.getOssPrefix());
		StorageProvider baseStorage = new AliyunOssStorage(this.ossClient, this.properties.getOssBucket(), workspaceKey);

		if (this.properties.getQuota().isEnabled()) {
			baseStorage = new QuotaEnforcedStorageProvider(baseStorage, this.quotaManager);
		}

		ObservationRegistry registry = observationRegistryProvider != null ? observationRegistryProvider.getIfAvailable() : null;
		if (registry != null && this.properties.getObservability().isEnabled()) {
			return new ObservedStorageProvider(baseStorage, registry);
		}
		return baseStorage;
	}
}
