package io.github.springai.harness.storage;

import io.modelcontextprotocol.common.McpTransportContext;

/**
 * StorageProviderFactory to construct storage provider based on transport context.
 *
 * @author buyc
 */
public interface StorageProviderFactory {

	/**
	 * Resolves StorageProvider from transport context.
	 *
	 * @param context MCP transport context
	 * @return StorageProvider instance
	 */
	StorageProvider getStorageProvider(McpTransportContext context);
}
