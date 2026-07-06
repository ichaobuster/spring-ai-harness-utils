package io.github.springai.harness.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;

/**
 * HarnessMcpServerAutoConfiguration
 *
 * @author buyc
 */
@Configuration
@EnableConfigurationProperties({HarnessMcpServerProperties.class})
public class HarnessMcpServerAutoConfiguration {

	@Bean
	@Primary
	public WebMvcStatelessServerTransport jumpWebMvcStatelessServerTransport(@Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper, McpServerStreamableHttpProperties serverProperties) {
		return WebMvcStatelessServerTransport.builder()
//				.jsonMapper(new JacksonMcpJsonMapper(objectMapper))
				.messageEndpoint(serverProperties.getMcpEndpoint())
				// TODO 自定义contextExtractor，Spring AI 有默认实现后考虑删除
				.contextExtractor(serverRequest -> McpTransportContext.create(Map.of(McpTransportContext.KEY, serverRequest)))
				.build();
	}

}
