package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.service.PortalCaseService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client's three routes, behind the portal filter chain (Unit 14).
 *
 * <p><strong>No route takes a case id.</strong> The token names the case, so there is nothing to
 * enumerate and no ownership check to write here — {@code PortalPrincipal.current(CLIENT)} is the
 * whole authorization, and it is the same call Unit 15's expert routes will make with the other
 * audience.
 *
 * <p>No {@code @PreAuthorize}: this chain has no roles in it. A staff JWT is never read on these
 * paths (see {@code SecurityConfig}), so there is no staff caller to gate.
 */
@RestController
@RequestMapping("/api/portal/client")
public class ClientPortalController {

	/** Revisions with no reason are useless to the Case Manager, so the reason is required. */
	public record RevisionsRequest(@NotBlank String notes) {
	}

	private final PortalCaseService portal;

	ClientPortalController(PortalCaseService portal) {
		this.portal = portal;
	}

	private static PortalPrincipal client() {
		return PortalPrincipal.current(PortalAudience.CLIENT);
	}

	/** The whitelisted view, and the first read stamps the receipt. */
	@GetMapping("/case")
	public ApiResponse<PortalCaseService.ClientDraftView> read() {
		return ApiResponse.ok(portal.clientView(client()));
	}

	/**
	 * Handoff B: this is the act that sends the letter to an expert to sign.
	 *
	 * <p>A case whose draft is not with the client answers 409 through Unit 04's existing guard —
	 * not a portal-specific check, so the state machine is not duplicated for this surface.
	 */
	@PostMapping("/approve")
	public ApiResponse<PortalCaseService.ClientDraftView> approve() {
		return ApiResponse.ok(portal.approve(client()));
	}

	@PostMapping("/request-revisions")
	public ApiResponse<PortalCaseService.ClientDraftView> requestRevisions(
			@Valid @RequestBody RevisionsRequest request) {
		return ApiResponse.ok(portal.requestRevisions(client(), request.notes()));
	}
}
