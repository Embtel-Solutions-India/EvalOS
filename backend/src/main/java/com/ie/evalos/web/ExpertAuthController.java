package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.ExpertAccountService;
import com.ie.evalos.service.PortalAccessService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The expert portal's front door (Unit 59). permitAll, behind the portal chain's per-IP limiter,
 * named one by one in {@code PortalSecurityConfig}. Bodies are {@link ClientAuthController}'s.
 */
@RestController
@RequestMapping("/api/portal/auth/expert")
public class ExpertAuthController {

	private final ExpertAccountService accounts;

	ExpertAuthController(ExpertAccountService accounts) {
		this.accounts = accounts;
	}

	/** 204 whether or not the email is on the roster — the form must not answer "who is on the panel". */
	@PostMapping({ "/sign-up", "/forgot-password" })
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void sendLink(@Valid @RequestBody ClientAuthController.EmailRequest request) {
		accounts.sendLink(request.email());
	}

	@PostMapping("/sign-in")
	public ApiResponse<ClientAuthController.SessionView> signIn(
			@Valid @RequestBody ClientAuthController.SignInRequest request) {
		return ApiResponse.ok(session(accounts.signIn(request.email(), request.password())));
	}

	@PostMapping("/set-password")
	public ApiResponse<ClientAuthController.SessionView> setPassword(
			@Valid @RequestBody ClientAuthController.SetPasswordRequest request) {
		return ApiResponse.ok(session(accounts.setPassword(request.token(), request.password())));
	}

	private static ClientAuthController.SessionView session(PortalAccessService.MintedToken minted) {
		return new ClientAuthController.SessionView(minted.token(), minted.expiresAt());
	}
}
