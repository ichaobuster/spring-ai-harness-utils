package io.github.springai.harness.toolgateway.auth;

/**
 * 网关认证异常。
 * 当请求身份认证失败时抛出，Controller 层捕获后返回 HTTP 401。
 *
 * @author ichaobuster
 */
public class GatewayAuthenticationException extends RuntimeException {

	public GatewayAuthenticationException(String message) {
		super(message);
	}

	public GatewayAuthenticationException(String message, Throwable cause) {
		super(message, cause);
	}
}
