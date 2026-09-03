package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.common.UploadedFileType;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.service.PortalCaseService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.IllegalTransitionException;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
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

	/**
	 * What the client must send, and what they have sent (Unit 34c).
	 *
	 * <p>Without this the upload below is <strong>uncallable</strong>: it takes a
	 * {@code checklistItemId} and no portal route revealed one. Like every route here it takes no
	 * case id — the token names the case.
	 */
	@GetMapping("/documents")
	public ApiResponse<PortalCaseService.ClientDocumentsView> documents() {
		return ApiResponse.ok(portal.documents(client()));
	}

	/**
	 * A five-minute URL for one document the client uploaded (Unit 34c).
	 *
	 * <p>The document id is a path variable and that is safe for the same reason the checklist item
	 * id is safe on the upload: it is <em>matched against the token's case</em> in the service, and
	 * against {@code CLIENT_UPLOAD}, before any URL exists. A caller naming somebody else's
	 * document — or their own draft — gets a 403, not a link.
	 */
	@GetMapping("/documents/{documentId}/url")
	public ApiResponse<ReadUrl> documentUrl(@PathVariable UUID documentId) {
		return ApiResponse.ok(new ReadUrl(portal.documentUrl(client(), documentId)));
	}

	/** @param url expires in five minutes. Never stored — a stored one is a stored credential. */
	public record ReadUrl(String url) {
	}

	/**
	 * The client uploads one document against one checklist item (Unit 30).
	 *
	 * <p><strong>The case comes off the token, never off the request.</strong> There is no case id
	 * in this signature and there must not be: the portal token <em>is</em> the scope, and a
	 * caller-supplied id is how one client writes into another's case.
	 *
	 * <p>Multipart, streamed. The part's {@code InputStream} and its known size go straight to S3 —
	 * see {@code PortalCaseService.upload} for why the object is written before the row.
	 *
	 * <p><strong>The upload trust boundary is enforced here</strong>, because this is EvalOS's
	 * endpoint: the size cap is Spring's multipart limit, the filename is never used as a path (the
	 * key is the document's own id), the stored content type comes from the part rather than from
	 * anything the client can rename, and <strong>the type is decided by sniffing the first bytes
	 * </strong> (Unit 35, gap G14 — Unit 30's owed item, closed).
	 */
	@PostMapping("/documents")
	public ApiResponse<UploadedView> upload(@RequestParam UUID checklistItemId,
			@RequestParam("file") MultipartFile file) throws java.io.IOException {

		if (file.isEmpty()) {
			throw new IllegalTransitionException("an empty file is not a document");
		}
		// **Sniffed, not trusted (Unit 35, gap G14).** Unit 30 shipped this endpoint recording the
		// declared content type and noted in its own spec that a declared type is not evidence;
		// this is that item closed. A renamed executable, script or HTML page is refused here, and
		// the other half of the posture is that nothing is ever served inline — see
		// `DocumentStore.presignedUrl`.
		UploadedFileType.require(file, UploadedFileType.CLIENT_DOCUMENT);
		CaseDocument saved = portal.upload(client(), checklistItemId, file.getOriginalFilename(),
				file.getContentType(), file.getSize(), file.getInputStream());
		return ApiResponse.ok(new UploadedView(saved.getId(), saved.getFilename(), saved.getVersion()));
	}

	/**
	 * What the client gets back. <strong>Not the object key</strong> — that is an internal address,
	 * and handing it out invites a client to think it is a URL they can keep.
	 */
	public record UploadedView(UUID id, String filename, int version) {
	}

	@PostMapping("/request-revisions")
	public ApiResponse<PortalCaseService.ClientDraftView> requestRevisions(
			@Valid @RequestBody RevisionsRequest request) {
		return ApiResponse.ok(portal.requestRevisions(client(), request.notes()));
	}
}
