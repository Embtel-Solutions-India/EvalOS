package com.ie.evalos.web;

import java.math.BigDecimal;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.MarketingLeadService;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The marketing desk's routes: open a lead, value it, and talk about it.
 *
 * <p><strong>No route here names a pipeline, and none may.</strong> The caller's own pipeline
 * comes from their principal in {@link MarketingLeadService}; an opportunity id in a path is
 * checked against it before anything is read or written. That check is the reason a path
 * variable is safe here at all, and it is the same rule Unit 34c's document filter and Unit 35's
 * case routes follow.
 *
 * <p><strong>`MARKETING` only, and Unit 40 kept it that way.</strong> The sales desk works the
 * same opportunities and shares the note stream, but *opening* a lead is a marketing act. Notes
 * moved to {@code OpportunityNoteController} when Sales needed them, which is the honest way to
 * share a thing — widening this controller instead would have made "marketing leads" the home
 * of every desk's conversation.
 */
@RestController
@RequestMapping("/api/marketing/leads")
public class MarketingLeadController {

	/**
	 * A new lead.
	 *
	 * <p>Neither email nor phone is individually required, but the service refuses both being
	 * absent: GHL matches an existing contact on email then phone, so a lead with neither makes
	 * every save create another contact. That is a rule about GHL's behaviour, so it lives in
	 * the service with the explanation rather than as a bare annotation here.
	 */
	public record OpenLeadRequest(String firstName, String lastName, String email, String phone,
			String name, BigDecimal monetaryValue) {
	}

	/** A valuation, and optionally a better name for the deal. */
	public record ValueLeadRequest(String name, BigDecimal monetaryValue) {
	}

	private final MarketingLeadService leads;

	MarketingLeadController(MarketingLeadService leads) {
		this.leads = leads;
	}

	@PostMapping
	@PreAuthorize("hasRole('MARKETING')")
	public ApiResponse<MarketingLeadService.Lead> open(@RequestBody @Valid OpenLeadRequest request) {
		return ApiResponse.ok(leads.openLead(request.firstName(), request.lastName(), request.email(),
				request.phone(), request.name(), request.monetaryValue()));
	}

	@PutMapping("/{opportunityId}")
	@PreAuthorize("hasRole('MARKETING')")
	public ApiResponse<MarketingLeadService.Lead> value(@PathVariable String opportunityId,
			@RequestBody @Valid ValueLeadRequest request) {
		return ApiResponse.ok(leads.value(opportunityId, request.name(), request.monetaryValue()));
	}
}
