package com.ie.evalos.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.ApplicationDocumentService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The documents behind a deal, read by the staff who act on it — Unit 53 §3 (D34).
 *
 * <p><strong>Its own route beside the application, and the same permission.</strong> Sales reaches
 * these by already being able to open that opportunity (D19b/D19c) — the documents ask no new
 * authorisation question, and there is no case access anywhere in this unit. The role list is
 * therefore copied from {@link ApplicationReviewController} rather than narrowed or widened: the
 * answers and the files the answers refer to are one act of reading, and a Project Manager who can
 * read a client's history can read the transcript it describes.
 *
 * <p><strong>Its own controller for the same reason the review route has one</strong> — not a route
 * on {@code SalesDeskController}, which is {@code hasRole('SALES')} throughout and mapped as the
 * desk. Production reads these too, and a route reached through a class called the sales desk is
 * one somebody tidies back to SALES-only.
 *
 * <p>Empty rather than 404 for a deal that never came from the portal, which is most of them. That
 * is an ordinary state, and matching the review route's "200 with nothing in it" keeps the panel
 * beside it from having to treat an error as normal.
 */
@RestController
@RequestMapping("/api/opportunities/{opportunityId}/documents")
public class ApplicationDocumentController {

	private static final String MAY_READ = "hasAnyRole('SALES', 'MARKETING', 'GM', 'BRAND_MANAGER', "
			+ "'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'CASE_MANAGER')";

	private final ApplicationDocumentService documents;

	ApplicationDocumentController(ApplicationDocumentService documents) {
		this.documents = documents;
	}

	@GetMapping
	@PreAuthorize(MAY_READ)
	public ApiResponse<List<ApplicationDocumentService.DocumentView>> list(
			@PathVariable String opportunityId) {
		return ApiResponse.ok(documents.forOpportunity(opportunityId));
	}

	/**
	 * A five-minute URL for one of them.
	 *
	 * <p><strong>Never the object key, and never a longer life than the click needs.</strong> The
	 * URL is minted per request and never stored; the document is matched against this deal in the
	 * service, so the id in the path proves nothing on its own.
	 */
	@GetMapping("/{documentId}/url")
	@PreAuthorize(MAY_READ)
	public ApiResponse<Map<String, String>> url(@PathVariable String opportunityId,
			@PathVariable UUID documentId) {
		return ApiResponse.ok(Map.of("url", documents.urlFor(opportunityId, documentId)));
	}
}
