package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.AuthService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Staff identity: exchange a password for a token, and read back who you are. */
@RestController
public class AuthController {

	public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
	}

	public record LoginResponse(String token, Role role, UUID brandId) {
	}

	public record StaffIdentity(UUID id, String displayName, Role role, UUID brandId, UUID teamId) {
	}

	private final AuthService authService;

	AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/api/auth/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		AuthService.Session session = authService.login(request.email(), request.password());
		return ApiResponse.ok(new LoginResponse(
				session.token(), session.principal().role(), session.principal().brandId()));
	}

	/** Reads the authenticated principal only — nothing here touches the request. */
	@GetMapping("/api/me")
	public ApiResponse<StaffIdentity> me(@AuthenticationPrincipal StaffPrincipal principal) {
		return ApiResponse.ok(new StaffIdentity(
				principal.memberId(),
				principal.displayName(),
				principal.role(),
				principal.brandId(),
				principal.teamId()));
	}
}
