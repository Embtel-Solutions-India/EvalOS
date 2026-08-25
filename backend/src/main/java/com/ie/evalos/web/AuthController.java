package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.BrandRepository;
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

	/**
	 * @param brandName the caller's own brand, resolved here because they cannot look it up
	 *                  themselves: {@code GET /api/brands} is GM-only, so a Brand Manager holding
	 *                  a {@code brandId} had no way to turn it into a name. Null for the GM, who
	 *                  is cross-brand and whose scope is chosen with the brand switcher instead.
	 */
	public record StaffIdentity(UUID id, String displayName, Role role, UUID brandId, String brandName,
			UUID teamId) {
	}

	private final AuthService authService;
	private final BrandRepository brands;

	AuthController(AuthService authService, BrandRepository brands) {
		this.authService = authService;
		this.brands = brands;
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
		// findById rather than a scoped read: the id comes from the caller's own authenticated
		// principal, so there is nothing here they did not already hold. Not a pattern to copy
		// anywhere the id arrives from a request.
		String brandName = principal.brandId() == null
				? null
				: brands.findById(principal.brandId()).map(Brand::getName).orElse(null);

		return ApiResponse.ok(new StaffIdentity(
				principal.memberId(),
				principal.displayName(),
				principal.role(),
				principal.brandId(),
				brandName,
				principal.teamId()));
	}
}
