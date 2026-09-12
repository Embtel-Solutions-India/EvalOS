package com.ie.evalos.web;

import java.time.Instant;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.ClientAccountService;
import com.ie.evalos.service.PortalAccessService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client's front door (Unit 42).
 *
 * <p><strong>The only unauthenticated routes on the portal chain.</strong> Everything else under
 * {@code /api/portal/**} requires a token; these four are how a client obtains one. They are
 * {@code permitAll} in {@code PortalSecurityConfig} and are still covered by that chain's per-IP
 * limiter, which is what throttles both password guessing and the enumeration {@code identify}
 * deliberately allows.
 *
 * <p><strong>The token is returned in a body, not a fragment.</strong> The fragment convention
 * protects a link that travels through a mailbox; this is a response to a POST the client's own
 * browser made, over TLS, and it goes straight into memory.
 */
@RestController
@RequestMapping("/api/portal/auth")
public class ClientAuthController {

	public record EmailRequest(@NotBlank @Email String email) {
	}

	public record SignInRequest(@NotBlank @Email String email, @NotBlank String password) {
	}

	/**
	 * @param token the credential from the emailed link's fragment
	 * @param password rules mirror {@code schemas/intake.ts} — the frontend states them, and the
	 *                 length floor is restated here because a client is not the only caller
	 */
	public record SetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 8) String password) {
	}

	/**
	 * @param state one of {@code PASSWORD_SET}, {@code NO_PASSWORD}, {@code MAIL_UNAVAILABLE},
	 *              {@code UNKNOWN} — {@code ClientAccountService.IdentifyState}, spelled by the
	 *              enum itself rather than mapped here, so a fifth value cannot be silently
	 *              flattened into a fourth
	 */
	public record IdentifyView(String state) {
	}

	public record SessionView(String token, Instant expiresAt) {
	}

	private final ClientAccountService accounts;

	ClientAuthController(ClientAccountService accounts) {
		this.accounts = accounts;
	}

	@PostMapping("/identify")
	public ApiResponse<IdentifyView> identify(@Valid @RequestBody EmailRequest request) {
		return ApiResponse.ok(new IdentifyView(accounts.identify(request.email()).name()));
	}

	@PostMapping("/sign-in")
	public ApiResponse<SessionView> signIn(@Valid @RequestBody SignInRequest request) {
		return ApiResponse.ok(session(accounts.signIn(request.email(), request.password())));
	}

	/**
	 * <strong>204 always, known email or not.</strong> The service does not differentiate and
	 * neither does this — a status code that varied would undo it.
	 */
	@PostMapping("/forgot-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void forgotPassword(@Valid @RequestBody EmailRequest request) {
		accounts.forgotPassword(request.email());
	}

	@PostMapping("/set-password")
	public ApiResponse<SessionView> setPassword(@Valid @RequestBody SetPasswordRequest request) {
		return ApiResponse.ok(session(accounts.setPassword(request.token(), request.password())));
	}

	/**
	 * The minted token, as it is.
	 *
	 * <p><strong>This used to parse a URL apart.</strong> {@code PortalAccessService} handed back a
	 * whole link built from a configured base, and this method found the {@code #} and threw the
	 * rest away — two halves of one ceremony for a caller that needs neither half. The service now
	 * returns a {@link PortalAccessService.MintedToken} for the account path, because a client who
	 * has just signed in at the client portal is already where a link would have sent them. The
	 * expert path still returns a URL, because a staff member really does copy that somewhere.
	 */
	private static SessionView session(PortalAccessService.MintedToken minted) {
		return new SessionView(minted.token(), minted.expiresAt());
	}
}
