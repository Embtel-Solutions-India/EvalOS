package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Case;
import com.ie.evalos.webhook.GhlOpportunityHandler;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The PM's <strong>New case</strong> (2026-10-30, D9 edited): one GHL id in, the webhook's intake out. Either id
 * is enough — the opportunity's, or the contact's (their most recent won deal). Everything else is read from GHL.
 */
@RestController
@RequestMapping("/api/cases/from-ghl")
public class CaseFromGhlController {

	public record FromGhlRequest(String opportunityId, String contactId) {
	}

	public record OpenedCase(UUID id, String caseCode) {
	}

	private final GhlOpportunityHandler handler;

	CaseFromGhlController(GhlOpportunityHandler handler) {
		this.handler = handler;
	}

	@PostMapping
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<OpenedCase> open(@RequestBody FromGhlRequest request) {
		Case opened = handler.openFromGhl(request.opportunityId(), request.contactId());
		return ApiResponse.ok(new OpenedCase(opened.getId(), opened.getCaseCode()));
	}
}
