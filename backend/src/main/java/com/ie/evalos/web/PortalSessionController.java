package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.security.PortalTokenFilter;
import com.ie.evalos.service.PortalAccessService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign out of either portal (Unit 75, D72). Authenticated like every other portal route — it is not
 * in the chain's {@code permitAll} list — so only a live token can end itself.
 */
@RestController
public class PortalSessionController {

	private final PortalAccessService access;

	PortalSessionController(PortalAccessService access) {
		this.access = access;
	}

	@PostMapping("/api/portal/sign-out")
	public ApiResponse<Void> signOut(@RequestHeader(PortalTokenFilter.HEADER) String token) {
		access.revoke(token);
		return ApiResponse.ok(null);
	}
}
