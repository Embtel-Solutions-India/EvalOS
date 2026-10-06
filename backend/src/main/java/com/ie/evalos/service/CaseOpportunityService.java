package com.ie.evalos.service;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deal a case was won from, read for the staff who work the case (not only the desk that holds
 * the deal's pipeline). Authorised by the <em>case</em>: its scoped load, and a role that reads case
 * content — the deal's contact card is client identity, which the ENM does not see (D63). Read-only.
 */
@Service
public class CaseOpportunityService {

	private final CaseLifecycleService lifecycle;
	private final OpportunityMirrorService opportunities;

	CaseOpportunityService(CaseLifecycleService lifecycle, OpportunityMirrorService opportunities) {
		this.lifecycle = lifecycle;
		this.opportunities = opportunities;
	}

	/** Empty when the case has no deal (opened outside Handoff A) or the mirror has not absorbed it. */
	@Transactional(readOnly = true)
	public Optional<Opportunity> of(UUID caseId) {
		Case subject = lifecycle.load(caseId);
		if (!TenantContext.current().role().seesCaseContent()) {
			throw new ForbiddenException("This role does not read a case's client details");
		}
		return Optional.ofNullable(subject.getGhlOpportunityId())
				.flatMap(opportunities::byGhlId)
				.filter(deal -> subject.getBrandId().equals(deal.getBrandId()));
	}
}
