package io.github.springai.harness.autoconfig;

import io.github.springai.harness.autoconfig.HarnessMcpServerProperties.ObservabilityProperties;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for OpenTelemetry and Micrometer Tracing.
 * Configured with conditional property checks to remain completely pluggable.
 *
 * @author ichaobuster
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.harness.mcp.server.observability", name = "enabled", havingValue = "true")
@Slf4j
public class ObservabilityAutoConfiguration {

	@Bean
	public Sampler otelSampler(HarnessMcpServerProperties properties) {
		ObservabilityProperties obs = properties.getObservability();
		log.info("Configuring OTel sampler with probability: {}", obs.getProbability());
		return Sampler.traceIdRatioBased(obs.getProbability());
	}

	@Bean
	public Resource otelResource() {
		return Resource.getDefault().merge(
				Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "spring-ai-harness-mcp-server"))
		);
	}

	@Bean
	public SpanExporter otelSpanExporter(HarnessMcpServerProperties properties) {
		ObservabilityProperties obs = properties.getObservability();
		String type = obs.getExportType();
		log.info("Configuring OTel SpanExporter type: {}", type);

		if ("otlp".equalsIgnoreCase(type)) {
			return OtlpGrpcSpanExporter.builder().build();
		} else {
			return SpanExporter.composite();
		}
	}
}
