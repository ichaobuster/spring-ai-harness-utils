package io.github.springai.harness.toolgateway.auth;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 默认认证实现：允许所有请求通过（不校验）。
 * 作为预留扩展点，后续可替换为实际的 Token / JWT / API Key 校验逻辑。
 *
 * @author ichaobuster
 */
@Slf4j
public class AllowAllGatewayAuthProvider implements GatewayAuthProvider {

	@Override
	public void authenticate(Map<String, String> headers) throws GatewayAuthenticationException {
		log.debug("AllowAllGatewayAuthProvider: authentication bypassed, headers keys: {}", headers.keySet());
		// 默认放行，不做任何校验
	}
}
