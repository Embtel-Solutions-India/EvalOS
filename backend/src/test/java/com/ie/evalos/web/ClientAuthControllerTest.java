package com.ie.evalos.web;

import java.time.Instant;

import com.ie.evalos.common.ApiExceptionHandler;
import com.ie.evalos.service.ClientAccountService;
import com.ie.evalos.service.PortalAccessService;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The auth routes' contract. The interesting one is {@link #forgotPasswordIsAlwaysNoContent()}:
 * the response must not differ between a known and an unknown email, or the non-differentiating
 * service method is undone by the controller.
 *
 * <p>{@link #signInWithAMissingPasswordAnswers400NotAService500()} and
 * {@link #setPasswordWithNoBodyFieldsAnswers400NotAService500()} are Task 5's Minor, routed here:
 * a null password reaching {@code ClientAccountService} would surface as an unmapped
 * {@code IllegalArgumentException} or {@code NullPointerException} — a 500, per
 * {@link ApiExceptionHandler}'s own javadoc — so the requests' {@code @NotBlank}/{@code @Email}/
 * {@code @Size} constraints have to catch it first. Standalone {@link MockMvc}, not
 * {@code @WebMvcTest}: this is proving {@code @Valid} runs before the service is ever called, not
 * exercising the security chain.
 */
class ClientAuthControllerTest {

	private final ClientAccountService service = mock(ClientAccountService.class);

	private final ClientAuthController controller = new ClientAuthController(service);

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new ApiExceptionHandler())
			.build();

	@Test
	void identifyReturnsTheStateTheServiceDecided() {
		given(service.identify("ana@example.com"))
				.willReturn(ClientAccountService.IdentifyState.NO_PASSWORD);

		assertThat(controller.identify(new ClientAuthController.EmailRequest("ana@example.com"))
				.data().state()).isEqualTo("NO_PASSWORD");
	}

	@Test
	void signInReturnsTheToken() {
		given(service.signIn("ana@example.com", "Correct!1")).willReturn(
				new PortalAccessService.MintedLink("https://portal.example.com/#abc",
						Instant.parse("2026-09-19T10:00:00Z")));

		var body = controller.signIn(
				new ClientAuthController.SignInRequest("ana@example.com", "Correct!1")).data();

		assertThat(body.token()).isEqualTo("abc");
		assertThat(body.expiresAt()).isEqualTo(Instant.parse("2026-09-19T10:00:00Z"));
	}

	@Test
	void forgotPasswordIsAlwaysNoContent() {
		controller.forgotPassword(new ClientAuthController.EmailRequest("nobody@example.com"));

		verify(service).forgotPassword("nobody@example.com");
	}

	// --- validation is this layer's job, not the service's (Task 5's Minor) -----------------

	@Test
	void signInWithAMissingPasswordAnswers400NotAService500() throws Exception {
		mockMvc.perform(post("/api/portal/auth/sign-in")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"ana@example.com\"}"))
				.andExpect(status().isBadRequest());

		// Never reached the service: BCryptPasswordEncoder.matches(raw, null) throws
		// IllegalArgumentException on a null raw password, which ApiExceptionHandler does not map
		// to 400 — @Valid has to refuse this before signIn(..) is ever called.
		verifyNoInteractions(service);
	}

	@Test
	void signInWithABlankEmailAnswers400() throws Exception {
		mockMvc.perform(post("/api/portal/auth/sign-in")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"\",\"password\":\"Correct!1\"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	void setPasswordWithNoBodyFieldsAnswers400NotAService500() throws Exception {
		mockMvc.perform(post("/api/portal/auth/set-password")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest());

		// Never reached the service: a null token NPEs inside hash(), and a null password NPEs
		// inside encoder.encode(..) — both unmapped by ApiExceptionHandler, so this has to be
		// caught here.
		verifyNoInteractions(service);
	}

	@Test
	void setPasswordWithATooShortPasswordAnswers400() throws Exception {
		mockMvc.perform(post("/api/portal/auth/set-password")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"abc\",\"password\":\"short\"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}
}
