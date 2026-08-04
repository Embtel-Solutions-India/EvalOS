package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.RedactedProfileService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The expert profile a client is shown: anonymous on demand, identified once paid, and
 * filed into the case's Drive folder when the PM says so (Unit 13).
 *
 * <p><strong>The Case Manager reads and does not publish.</strong> They are on both GETs
 * because they draft the letter and need to know who is signing it; they are deliberately
 * off the Drive write, because putting an artefact in front of the client is the Project
 * Manager's call. The Coordinator and the Expert Network Manager are on none of the three —
 * the Coordinator chases documents and the ENM owns the roster, and neither is part of
 * getting the client to approve an expert.
 *
 * <p>All three routes read the case through {@code CaseLifecycleService.read} and the expert
 * through {@code ExpertRepository.findScoped}, so another brand's case — and another Case
 * Manager's — is refused inside the service. Nothing here re-derives scope, and there is no
 * new scoped query.
 *
 * <p><strong>The HTML travels inside the envelope, not as the response body.</strong> The
 * spec calls these routes "HTML"; {@code common/ApiResponse} is returned by every endpoint
 * in EvalOS without exception, and a route answering {@code text/html} directly would also
 * have no way to carry the {@code reference} label or to report a refusal in the shape every
 * other route uses. The panel renders {@code html} into a sandboxed iframe via
 * {@code srcdoc}, which is printable and is what the spec asks the client to do with it.
 */
@RestController
@RequestMapping("/api/cases/{id}/expert-profile")
public class ExpertProfileController {

	/**
	 * @param html      the whole document, ready for an iframe's {@code srcdoc}. Generated
	 *                  on demand and stored nowhere (invariant 14)
	 * @param reference the anonymous label used for this expert on this case, e.g.
	 *                  {@code Expert AK}. Sent on the full profile too, so the PM can tell
	 *                  the client which earlier document this identifies
	 */
	public record ProfileView(String html, String reference) {

		static ProfileView of(RedactedProfileService.Profile profile) {
			return new ProfileView(profile.html(), profile.reference());
		}
	}

	/**
	 * @param link the Drive {@code webViewLink} for the created Doc — Drive's own URL, not
	 *             one EvalOS assembled
	 */
	public record DriveWriteView(String fileId, String link, String reference) {
	}

	private final RedactedProfileService profiles;

	ExpertProfileController(RedactedProfileService profiles) {
		this.profiles = profiles;
	}

	/** Available whenever an expert is assigned. No payment gate: this is the pre-approval document. */
	@GetMapping("/redacted")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'CASE_MANAGER')")
	public ApiResponse<ProfileView> redacted(@PathVariable UUID id) {
		return ApiResponse.ok(ProfileView.of(profiles.redacted(id)));
	}

	/**
	 * <strong>409 unless the case is paid</strong>, and never includes {@code payment_detail}
	 * (invariant 4) — "full" means the expert's identity and credentials, never how they are
	 * paid.
	 */
	@GetMapping("/full")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'CASE_MANAGER')")
	public ApiResponse<ProfileView> full(@PathVariable UUID id) {
		return ApiResponse.ok(ProfileView.of(profiles.full(id)));
	}

	/**
	 * Writes the redacted profile into the case's own Drive folder.
	 *
	 * <p>A POST because it has an outward effect — a document appears in a folder the client
	 * can be pointed at — and it is audited for the same reason. A case whose
	 * {@code drive_link} names no folder is a 409 that quotes the link, and
	 * <strong>nothing is written</strong>: writing to a default folder would file the
	 * document somewhere nobody looks, or somewhere another brand can see it.
	 */
	@PostMapping("/redacted/to-drive")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<DriveWriteView> toDrive(@PathVariable UUID id) {
		RedactedProfileService.DriveWrite written = profiles.writeRedactedToDrive(id);
		return ApiResponse.ok(new DriveWriteView(written.fileId(), written.link(), written.reference()));
	}
}
