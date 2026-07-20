package io.github.springai.harness.toolgateway.auth;

import java.util.Map;

/**
 * 网关认证接口。
 * 根据请求中的 Header 信息验证用户身份。
 * 认证失败时抛出 {@link GatewayAuthenticationException}。
 *
 * @author ichaobuster
 */
public interface GatewayAuthProvider {

	/**
	 * 对请求进行身份认证。
	 *
	 * @param headers 从请求中提取的转发 Header Map（key 均为小写）
	 * @throws GatewayAuthenticationException 当认证失败时抛出
	 */
	void authenticate(Map<String, String> headers) throws GatewayAuthenticationException;
}
