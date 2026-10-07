package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.service.PortalTermsService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first-sign-in policy acceptance (Unit 72, D71), on both portals. Each path demands its own
 * audience ({@link PortalPrincipal#current}), so a client token cannot answer for an expert.
 */
@RestController
public class PortalTermsController {

	private final PortalTermsService terms;

	PortalTermsController(PortalTermsService terms) {
		this.terms = terms;
	}

	@GetMapping("/api/portal/client/terms")
	public ApiResponse<PortalTermsService.TermsStatus> clientStatus() {
		return ApiResponse.ok(terms.status(PortalPrincipal.current(PortalAudience.CLIENT)));
	}

	/** The client's first name for the dashboard greeting. Lives here because this controller already resolves the account from the token. */
	@GetMapping("/api/portal/client/me")
	public ApiResponse<PortalTermsService.ClientMe> clientMe() {
		return ApiResponse.ok(terms.clientMe(PortalPrincipal.current(PortalAudience.CLIENT)));
	}

	@PostMapping("/api/portal/client/terms")
	public ApiResponse<PortalTermsService.TermsStatus> clientAccept() {
		return ApiResponse.ok(terms.accept(PortalPrincipal.current(PortalAudience.CLIENT)));
	}

	@GetMapping("/api/portal/expert/terms")
	public ApiResponse<PortalTermsService.TermsStatus> expertStatus() {
		return ApiResponse.ok(terms.status(PortalPrincipal.current(PortalAudience.EXPERT)));
	}

	@PostMapping("/api/portal/expert/terms")
	public ApiResponse<PortalTermsService.TermsStatus> expertAccept() {
		return ApiResponse.ok(terms.accept(PortalPrincipal.current(PortalAudience.EXPERT)));
	}
}
