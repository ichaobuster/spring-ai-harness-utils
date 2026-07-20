package io.github.springai.harness.toolgateway.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * HTTP端点配置信息。
 * 用于定义如何将工具请求转发到外部的HTTP API。
 *
 * @author ichaobuster
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HttpEndpointConfig(
		/** 目标 HTTP API URL，支持 {param} 占位符替换 */
		String url,
		/** HTTP 方法（GET/POST/PUT/DELETE），默认 POST */
		String method,
		/** 静态请求头，合并到每次请求中 */
		Map<String, String> headers,
		/** 请求 Content-Type，默认 application/json */
		String contentType,
		/** 超时时间（秒），默认 30 */
		Integer timeoutSeconds,
		/** 是否透传原始请求的 Authorization header 给下游，默认 true */
		Boolean forwardAuthHeader
) {
	/**
	 * 获取 HTTP 方法，默认 POST
	 */
	public String methodOrDefault() {
		return method != null && !method.isBlank() ? method.toUpperCase() : "POST";
	}

	/**
	 * 获取 Content-Type，默认 application/json
	 */
	public String contentTypeOrDefault() {
		return contentType != null && !contentType.isBlank() ? contentType : "application/json";
	}

	/**
	 * 获取超时时间，默认 30 秒
	 */
	public int timeoutSecondsOrDefault() {
		return timeoutSeconds != null && timeoutSeconds > 0 ? timeoutSeconds : 30;
	}

	/**
	 * 是否透传 Authorization header，默认 true
	 */
	public boolean shouldForwardAuth() {
		return forwardAuthHeader == null || forwardAuthHeader;
	}
}
