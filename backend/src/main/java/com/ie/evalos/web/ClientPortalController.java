package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.common.UploadedFileType;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.integration.GhlInvoiceClient;
import com.ie.evalos.service.PortalCaseService;
import com.ie.evalos.service.PortalInvoiceService;
import com.ie.evalos.service.PortalMeetingService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.IllegalTransitionException;
import java.util.List;
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
	private final PortalInvoiceService portalInvoices;
	private final PortalMeetingService portalMeetings;

	ClientPortalController(PortalCaseService portal, PortalInvoiceService portalInvoices,
			PortalMeetingService portalMeetings) {
		this.portal = portal;
		this.portalInvoices = portalInvoices;
		this.portalMeetings = portalMeetings;
	}

	private static PortalPrincipal client() {
		return PortalPrincipal.current(PortalAudience.CLIENT);
	}

	/**
	 * The client's invoices and what has been paid (Unit 41).
	 *
	 * <p><strong>Read straight from GHL, stored nowhere.</strong> Sales raises invoices in GHL,
	 * GHL's QuickBooks integration does the accounting, and EvalOS shows the outcome — invariant
	 * 2 kept that clause when it lost the others.
	 *
	 * <p><strong>No parameter names a contact.</strong> The id comes off the portal credential
	 * (Unit 35's party scope), which is the whole reason this route is safe.
	 *
	 * <p><strong>Do not read a paid invoice as revenue.</strong> Invariant 5 is unchanged:
	 * recognition is <em>paid AND delivered</em>, read only through
	 * {@code RefundService.isRevenueRecognized}. A settled invoice here is a fact about the
	 * client's bill, not about the case — and a later dashboard summing this screen would be
	 * exactly the mistake that invariant exists to prevent.
	 */
	@GetMapping("/invoices")
	public ApiResponse<List<GhlInvoiceClient.ClientInvoice>> invoices() {
		return ApiResponse.ok(portalInvoices.forCaller(client()));
	}

	/**
	 * The client's meetings, read straight from GHL.
	 *
	 * <p>Party-scoped only, like invoices: a meeting belongs to the client, not to one case, and
	 * a case-scoped link is told which link it is holding rather than given a wide answer.
	 *
	 * <p>The times in this payload are <strong>GHL's own strings and not ISO-8601</strong> —
	 * {@code "2026-09-13 12:30:00"}, no offset. They are not parsed on the way through, because
	 * with no zone in the payload any parse would invent one. See
	 * {@code GhlCalendarClient.forContact}.
	 */
	@GetMapping("/meetings")
	public ApiResponse<List<GhlCalendarClient.ClientMeeting>> meetings() {
		return ApiResponse.ok(portalMeetings.forCaller(client()));
	}

	/** The whitelisted view, and the first read stamps the receipt. */
	@GetMapping("/case")
	public ApiResponse<PortalCaseService.ClientDraftView> read() {
		return ApiResponse.ok(portal.clientView(client()));
	}

	/**
	 * Every case behind a party-scoped client link (Unit 35, D1) — the list no case token could
	 * answer, and what {@code /requests} in the portal is for.
	 *
	 * <p>A case-scoped token is refused here rather than given a one-element list: the two
	 * credentials are different things, and the narrow one does not get the wide one's reply.
	 */
	@GetMapping("/cases")
	public ApiResponse<List<PortalCaseService.ClientCaseSummary>> cases() {
		return ApiResponse.ok(portal.clientCases(client()));
	}

	/**
	 * One of them, named in the path.
	 *
	 * <p><strong>The id comes from the request, and that is safe here for one reason only:</strong>
	 * the service matches it against the credential before it reads anything — the client's own
	 * contact id. Same shape as Unit 34c's document-kind filter. A case that is not theirs answers
	 * 403, never a 404 that would confirm it exists.
	 */
	@GetMapping("/cases/{caseId}")
	public ApiResponse<PortalCaseService.ClientDraftView> readCase(@PathVariable UUID caseId) {
		return ApiResponse.ok(portal.clientView(client(), caseId));
	}

	/**
	 * Handoff B: this is the act that sends the letter to an expert to sign.
	 *
	 * <p>A case whose draft is not with the client answers 409 through Unit 04's existing guard —
	 * not a portal-specific check, so the state machine is not duplicated for this surface.
	 *
	 * <p>On a party token with several cases this answers <strong>409 {@code SAY_WHICH_CASE}</strong>
	 * rather than picking one. Approving is what sends a letter onward, and there is no undo that
	 * reaches the client.
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

	/**
	 * Approve one named case (34b).
	 *
	 * <p><strong>Added because Unit 35 gave party scoping to the reads and not to the writes.</strong>
	 * A client with two cases could open either draft through {@code GET /cases/{caseId}} and
	 * approve neither: both actions resolved the case from the token and answered 409
	 * {@code SAY_WHICH_CASE} with nowhere to say which. The screen 34b builds is unusable for
	 * that client without this.
	 *
	 * <p>The path variable is safe for the same reason the read's is: it is checked against the
	 * credential's party before anything is read or written, and a case that is not theirs
	 * answers 403 rather than 404.
	 */
	@PostMapping("/cases/{caseId}/approve")
	public ApiResponse<PortalCaseService.ClientDraftView> approveCase(@PathVariable UUID caseId) {
		return ApiResponse.ok(portal.approve(client(), caseId));
	}

	@PostMapping("/cases/{caseId}/request-revisions")
	public ApiResponse<PortalCaseService.ClientDraftView> requestRevisionsOnCase(
			@PathVariable UUID caseId, @Valid @RequestBody RevisionsRequest request) {
		return ApiResponse.ok(portal.requestRevisions(client(), caseId, request.notes()));
	}
}
