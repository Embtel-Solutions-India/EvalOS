package com.ie.evalos.service;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;

/**
 * The deal a case was won from, read for the staff who work the case (not only the desk that holds
 * the deal's pipeline). Authorised by the <em>case</em>: its scoped load, and a role that reads case
 * content — the deal's contact card is client identity, which the ENM does not see (D63).
 *
 * <p><strong>A deal the mirror has not absorbed is fetched from GHL</strong> (2026-10-28), through the
 * same read-and-absorb the opportunity webhook uses: the case contact's deals are read and written into
 * the mirror, then the mirror is asked again. Seeded demo ids ({@code ghl-…}) are never sent to GHL.
 * Not {@code @Transactional}: the GHL read must not hold a connection, and the absorb has its own.
 */
@Service
public class CaseOpportunityService {

	/** Seed data's fake GHL ids (`ghl-opp-tp-ie-0001`). Real GHL ids are bare alphanumerics. */
	private static final String SEEDED_ID_PREFIX = "ghl-";

	private final CaseLifecycleService lifecycle;
	private final OpportunityMirrorService opportunities;
	private final GhlPipelineClient ghl;
	private final ContactSnapshotRepository contacts;

	CaseOpportunityService(CaseLifecycleService lifecycle, OpportunityMirrorService opportunities,
			GhlPipelineClient ghl, ContactSnapshotRepository contacts) {
		this.lifecycle = lifecycle;
		this.opportunities = opportunities;
		this.ghl = ghl;
		this.contacts = contacts;
	}

	/** Empty when the case has no deal (opened outside Handoff A), it is a seeded one, or GHL has none either. */
	public Optional<Opportunity> of(UUID caseId) {
		Case subject = lifecycle.load(caseId);
		if (!TenantContext.current().role().seesCaseContent()) {
			throw new ForbiddenException("This role does not read a case's client details");
		}
		String dealId = subject.getGhlOpportunityId();
		if (dealId == null || dealId.isBlank()) {
			return Optional.empty();
		}
		Optional<Opportunity> held = mirrored(subject, dealId);
		if (held.isPresent() || dealId.startsWith(SEEDED_ID_PREFIX)) {
			return held;
		}
		Optional<String> ghlContactId = Optional.ofNullable(subject.getContactId())
				.flatMap(contacts::findById)
				.filter(contact -> subject.getBrandId().equals(contact.getBrandId()))
				.map(ContactSnapshot::getGhlContactId)
				.filter(id -> !id.isBlank() && !id.startsWith(SEEDED_ID_PREFIX));
		if (ghlContactId.isEmpty()) {
			return held;
		}
		opportunities.absorbForContact(ghl.forContact(ghlContactId.get()));
		return mirrored(subject, dealId);
	}

	private Optional<Opportunity> mirrored(Case subject, String dealId) {
		return opportunities.byGhlId(dealId).filter(deal -> subject.getBrandId().equals(deal.getBrandId()));
	}
}
