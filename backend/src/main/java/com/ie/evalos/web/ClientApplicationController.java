package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.service.ClientApplicationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client's own requests (Unit 43).
 *
 * <p><strong>Its own controller rather than more routes on {@code ClientPortalController}</strong>,
 * which is mapped at {@code /api/portal/client} and is entirely about a <em>case</em> — its
 * documents, its draft, its approval. An application exists before any case does and may never
 * become one, so hanging it off that mapping would put the two halves of a client's life behind
 * one class and one set of assumptions.
 *
 * <p><strong>Every route resolves the client from the token and nothing else.</strong> No route
 * here takes a client id, an account id or an email, so there is nothing to enumerate: the
 * application id in the path is checked against the token's own account in the service.
 */
@RestController
@RequestMapping("/api/portal/applications")
public class ClientApplicationController {

	/**
	 * @param serviceId   the catalog's id, stored as given — the catalog is frontend data
	 * @param serviceName the display name <em>as the client saw it</em>, which is what the GHL
	 *                    opportunity is called and what Sales reads later
	 * @param purpose     why they want it, or absent for a service that implies its own
	 */
	public record StartRequest(@NotBlank @Size(max = 100) String serviceId,
			@NotBlank @Size(max = 200) String serviceName, @Size(max = 100) String purpose) {
	}

	/**
	 * @param answers the whole questionnaire as a JSON object, bounded by the service rather than
	 *                here — a body limit belongs where the reason for it lives
	 */
	public record SaveRequest(@NotBlank String answers, @Size(max = 100) String purpose) {
	}

	private final ClientApplicationService applications;

	ClientApplicationController(ClientApplicationService applications) {
		this.applications = applications;
	}

	/** Everything this client has asked for, newest first. Drives the dashboard's Continue card. */
	@GetMapping
	public ApiResponse<List<ClientApplicationService.ApplicationView>> mine() {
		return ApiResponse.ok(applications.mine(PortalPrincipal.current(PortalAudience.CLIENT)));
	}

	/**
	 * Start a request. <strong>Also opens the GHL opportunity</strong> on the intake pipeline — see
	 * the service for why that happens here and not at submit. No stage and no assignee are sent:
	 * GHL's automation decides both.
	 */
	@PostMapping
	public ApiResponse<ClientApplicationService.ApplicationView> start(
			@Valid @RequestBody StartRequest request) {
		return ApiResponse.ok(applications.start(PortalPrincipal.current(PortalAudience.CLIENT),
				request.serviceId(), request.serviceName(), request.purpose()));
	}

	/** Autosave. Called on every step, so it does the least it can and never moves a stage. */
	@PutMapping("/{applicationId}")
	public ApiResponse<ClientApplicationService.ApplicationView> save(@PathVariable UUID applicationId,
			@Valid @RequestBody SaveRequest request) {
		return ApiResponse.ok(applications.save(PortalPrincipal.current(PortalAudience.CLIENT),
				applicationId, request.answers(), request.purpose()));
	}

	/** Hand it to Sales. Refuses if GHL never took the opportunity — see the service. */
	@PostMapping("/{applicationId}/submit")
	public ApiResponse<ClientApplicationService.ApplicationView> submit(@PathVariable UUID applicationId) {
		return ApiResponse.ok(applications.submit(PortalPrincipal.current(PortalAudience.CLIENT),
				applicationId));
	}
}
