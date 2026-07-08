package io.github.springai.harness.controller;

import io.github.springai.harness.auth.AuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GlobalRestExceptionHandler Unit Tests")
class GlobalRestExceptionHandlerTest {

	private MockMvc mockMvc;

	@RestController
	static class TestController {
		@GetMapping("/test/auth")
		public ResponseEntity<?> triggerAuth() {
			throw new AuthenticationException("Auth failed");
		}

		@GetMapping("/test/security")
		public ResponseEntity<?> triggerSecurity() {
			throw new SecurityException("Security failed");
		}

		@GetMapping("/test/illegal-argument")
		public ResponseEntity<?> triggerIllegalArgument() {
			throw new IllegalArgumentException("Illegal argument");
		}

		@GetMapping("/test/generic")
		public ResponseEntity<?> triggerGeneric() throws Exception {
			throw new Exception("Internal error");
		}
	}

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
				.setControllerAdvice(new GlobalRestExceptionHandler())
				.build();
	}

	@Test
	@DisplayName("Should handle AuthenticationException and return 401")
	void shouldHandleAuthenticationException() throws Exception {
		mockMvc.perform(get("/test/auth"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Auth failed"));
	}

	@Test
	@DisplayName("Should handle SecurityException and return 400")
	void shouldHandleSecurityException() throws Exception {
		mockMvc.perform(get("/test/security"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Security failed"));
	}

	@Test
	@DisplayName("Should handle IllegalArgumentException and return 400")
	void shouldHandleIllegalArgumentException() throws Exception {
		mockMvc.perform(get("/test/illegal-argument"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Illegal argument"));
	}

	@Test
	@DisplayName("Should handle generic Exception and return 500")
	void shouldHandleGenericException() throws Exception {
		mockMvc.perform(get("/test/generic"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error").value("Internal error"));
	}
}
