package com.ie.evalos.web;

import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.service.PortalAccessService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff side of the client portal link: mint one, and see whether one is live.
 *
 * <p>On the <strong>normal</strong> chain, unlike {@code ClientPortalController} — minting is a
 * staff act, gated by role here and by the scoped case load in the service. Everyone who works a
 * case toward the client is on it: the GM and Brand Manager as oversight, the PM whose approval
 * precedes it, and the Case Manager who wrote the draft and is the one fielding "my link doesn't
 * work". The Coordinator is deliberately off it even though they run
 * {@code draft/send-to-client} — Unit 18 dispatches the link on that event once GHL can carry it,
 * so the manual mint is the stopgap and not their workflow. Widening this is one role in one list.
 *
 * <p><strong>The token exists exactly once, in the response to the POST.</strong> Nothing reads it
 * back: the GET answers whether a link is live, when it expires and when it was last opened, and
 * there is no route that returns the URL of an existing link. A staff member who loses it re-mints,
 * which revokes the old one.
 */
@RestController
@RequestMapping("/api/cases/{id}/portal-link")
public class PortalLinkController {

	private static final String MAY_MINT =
			"hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'CASE_MANAGER')";

	/**
	 * @param url       the whole link, fragment and all. Shown once and stored nowhere — this is
	 *                  the stopgap while open question (b) is open: staff copy it to the client
	 *                  through GHL by hand, and Unit 18 dispatches it on an event if GHL can
	 * @param expiresAt when it stops working, so whoever sends it knows what they promised
	 */
	public record MintedLinkView(String url, Instant expiresAt) {
	}

	/**
	 * @param live     whether the newest link still works
	 * @param openedAt when the client last opened it, or null if they never have. This is the
	 *                 answer the Case Manager actually wants before chasing
	 */
	public record LinkStatusView(boolean live, Instant expiresAt, Instant openedAt) {
	}

	private final PortalAccessService links;

	PortalLinkController(PortalAccessService links) {
		this.links = links;
	}

	/**
	 * Whether this case has a client link, and how it stands. Never the token.
	 *
	 * <p>Not in the spec's route table, which lists the mint alone — added because frontend
	 * deliverable 6 asks the case page to say whether a live link exists, when it expires and
	 * whether it has been opened, and a panel that can only mint would have to mint to find out.
	 */
	@GetMapping
	@PreAuthorize(MAY_MINT)
	public ApiResponse<LinkStatusView> status(@PathVariable UUID id) {
		PortalAccessService.LinkStatus status = links.status(id, PortalAudience.CLIENT);
		return ApiResponse.ok(new LinkStatusView(status.live(), status.expiresAt(), status.lastSeenAt()));
	}

	/** Mints, or re-mints — which revokes the previous link immediately. Audited. */
	@PostMapping
	@PreAuthorize(MAY_MINT)
	public ApiResponse<MintedLinkView> mint(@PathVariable UUID id) {
		PortalAccessService.MintedLink minted = links.mint(id, PortalAudience.CLIENT);
		return ApiResponse.ok(new MintedLinkView(minted.url(), minted.expiresAt()));
	}
}
