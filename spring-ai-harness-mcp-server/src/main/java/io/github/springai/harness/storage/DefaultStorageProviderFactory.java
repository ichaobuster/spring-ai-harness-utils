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

	public DefaultStorageProviderFactory(OSS ossClient, HarnessMcpServerProperties properties, AuthenticationProvider authenticationProvider) {
		this.ossClient = ossClient;
		this.properties = properties;
		this.authenticationProvider = authenticationProvider;
	}

	@Override
	public StorageProvider getStorageProvider(McpTransportContext context) {
		ServerRequest serverRequest = (ServerRequest) context.get(McpTransportContext.KEY);
		WorkspaceIdentity identity = this.authenticationProvider.authenticate(serverRequest);
		String workspaceKey = identity.getWorkspacePath(this.properties.getOssPrefix());
		return new AliyunOssStorage(this.ossClient, this.properties.getOssBucket(), workspaceKey);
	}
}
