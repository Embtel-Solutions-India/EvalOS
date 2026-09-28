package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.CaseDrafts;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.UploadedFileType;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.integration.GhlInvoiceClient;
import com.ie.evalos.service.PortalCaseService;
import com.ie.evalos.service.PortalInvoiceService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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
 * The client's routes, behind the portal filter chain (Unit 14; case-first since Unit 58).
 *
 * <p><strong>Every case route names its case</strong>, and {@code PortalCaseService} matches it
 * against the credential before anything is read. The case-less routes (the case resolved from the
 * token, answering 409 {@code SAY_WHICH_CASE} on a party token with several) were removed in
 * Unit 58 phase 3, with the meetings read the portal no longer shows.
 *
 * <p>No {@code @PreAuthorize}: this chain has no roles in it. A staff JWT is never read on these
 * paths (see {@code SecurityConfig}), so there is no staff caller to gate.
 */
@RestController
@RequestMapping("/api/portal/client")
public class ClientPortalController {

	private final PortalCaseService portal;
	private final PortalInvoiceService portalInvoices;

	ClientPortalController(PortalCaseService portal, PortalInvoiceService portalInvoices) {
		this.portal = portal;
		this.portalInvoices = portalInvoices;
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
	public ApiResponse<List<GhlInvoiceClient.ClientInvoice>> invoices(@RequestParam(required = false) String status) {
		List<GhlInvoiceClient.ClientInvoice> mine = portalInvoices.forCaller(client());
		// Unit 58: the portal's Invoices page shows settled bills only — GHL's own `paid` status.
		return ApiResponse.ok(status == null ? mine
				: mine.stream().filter((invoice) -> status.equalsIgnoreCase(invoice.status())).toList());
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

	// --- Unit 58: per-case routes ---------------------------------------------
	//
	// Every one names its case, and PortalCaseService.authorized(principal, caseId) checks it against
	// the credential before anything is read — another client's case answers 403.

	@GetMapping("/cases/{caseId}/documents")
	public ApiResponse<PortalCaseService.ClientDocumentsView> caseDocuments(@PathVariable UUID caseId) {
		return ApiResponse.ok(portal.documents(client(), caseId));
	}

	/** @param url expires in five minutes. Never stored — a stored one is a stored credential. */
	public record ReadUrl(String url) {
	}

	/**
	 * What the client gets back. <strong>Not the object key</strong> — that is an internal address,
	 * and handing it out invites a client to think it is a URL they can keep.
	 */
	public record UploadedView(UUID id, String filename, int version) {
	}

	/**
	 * The client uploads one document against one of this case's checklist items (Unit 30).
	 *
	 * <p><strong>The upload trust boundary is enforced here</strong>: the size cap is Spring's
	 * multipart limit, the filename is never used as a path (the key is the document's own id), and
	 * <strong>the type is decided by sniffing the first bytes</strong> (Unit 35, gap G14) — a renamed
	 * executable, script or HTML page is refused. See {@code PortalCaseService.upload} for why the
	 * object is written before the row.
	 */
	@PostMapping("/cases/{caseId}/documents")
	public ApiResponse<UploadedView> uploadToCase(@PathVariable UUID caseId, @RequestParam UUID checklistItemId,
			@RequestParam("file") MultipartFile file) throws java.io.IOException {
		if (file.isEmpty()) {
			throw new IllegalTransitionException("an empty file is not a document");
		}
		UploadedFileType.require(file, UploadedFileType.CLIENT_DOCUMENT);
		CaseDocument saved = portal.upload(client(), caseId, checklistItemId, file.getOriginalFilename(),
				file.getContentType(), file.getSize(), file.getInputStream());
		return ApiResponse.ok(new UploadedView(saved.getId(), saved.getFilename(), saved.getVersion()));
	}

	@GetMapping("/cases/{caseId}/documents/{documentId}/url")
	public ApiResponse<ReadUrl> caseDocumentUrl(@PathVariable UUID caseId, @PathVariable UUID documentId) {
		return ApiResponse.ok(new ReadUrl(portal.documentUrl(client(), caseId, documentId)));
	}

	@GetMapping("/cases/{caseId}/drafts")
	public ApiResponse<List<CaseDrafts.ClientDraftVersion>> drafts(@PathVariable UUID caseId) {
		return ApiResponse.ok(portal.drafts(client(), caseId));
	}

	@GetMapping("/cases/{caseId}/drafts/{draftId}/files/{file}/url")
	public ApiResponse<ReadUrl> draftFileUrl(@PathVariable UUID caseId, @PathVariable UUID draftId,
			@PathVariable String file, @RequestParam(defaultValue = "false") boolean view) {
		if (!file.equals("docx") && !file.equals("pdf")) {
			throw new InvalidRequestException("file is docx or pdf");
		}
		// `view` opens the PDF in the browser (D51); a Word file can only be downloaded.
		if (view && !file.equals("pdf")) {
			throw new InvalidRequestException("only the PDF can be viewed");
		}
		return ApiResponse.ok(new ReadUrl(portal.draftFileUrl(client(), caseId, draftId, file.equals("pdf"), view)));
	}

	@GetMapping("/cases/{caseId}/drafts/{draftId}/comments")
	public ApiResponse<List<CaseDrafts.CommentView>> draftComments(@PathVariable UUID caseId,
			@PathVariable UUID draftId) {
		return ApiResponse.ok(portal.draftComments(client(), caseId, draftId));
	}

	public record CommentRequest(@NotBlank @Size(max = 2000) String body, @Positive Integer page) {
	}

	@PostMapping("/cases/{caseId}/drafts/{draftId}/comments")
	public ApiResponse<CaseDrafts.CommentView> addDraftComment(@PathVariable UUID caseId, @PathVariable UUID draftId,
			@Valid @RequestBody CommentRequest request) {
		return ApiResponse.ok(portal.addDraftComment(client(), caseId, draftId, request.body(), request.page()));
	}

	@PostMapping("/cases/{caseId}/drafts/{draftId}/approve")
	public ApiResponse<PortalCaseService.ClientDraftView> approveDraft(@PathVariable UUID caseId,
			@PathVariable UUID draftId) {
		return ApiResponse.ok(portal.approveDraft(client(), caseId, draftId));
	}

	/** The note is optional here (the comment thread carries the detail), unlike the legacy route. */
	public record ChangesRequest(@Size(max = 2000) String notes) {
	}

	@PostMapping("/cases/{caseId}/drafts/{draftId}/request-changes")
	public ApiResponse<PortalCaseService.ClientDraftView> requestChanges(@PathVariable UUID caseId,
			@PathVariable UUID draftId, @Valid @RequestBody(required = false) ChangesRequest request) {
		return ApiResponse.ok(portal.requestChanges(client(), caseId, draftId,
				request == null ? null : request.notes()));
	}

	@GetMapping("/cases/{caseId}/delivered")
	public ApiResponse<List<PortalCaseService.DeliveredFile>> delivered(@PathVariable UUID caseId) {
		return ApiResponse.ok(portal.delivered(client(), caseId));
	}

	@GetMapping("/cases/{caseId}/delivered/{documentId}/url")
	public ApiResponse<ReadUrl> deliveredUrl(@PathVariable UUID caseId, @PathVariable UUID documentId) {
		return ApiResponse.ok(new ReadUrl(portal.deliveredUrl(client(), caseId, documentId)));
	}
}
