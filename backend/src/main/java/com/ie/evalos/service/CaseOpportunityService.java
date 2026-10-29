package com.ie.evalos.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
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
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CaseOpportunityService.class);

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

	/**
	 * The case's deal, fetched from GHL whenever it exists there (2026-10-29): first by its own id, then
	 * through the case contact. A case that names no deal takes the contact's — the won one, else the
	 * newest. Empty only when GHL holds none either, or the id is a seeded one.
	 */
	public Optional<Opportunity> of(UUID caseId) {
		Case subject = lifecycle.load(caseId);
		if (!TenantContext.current().role().seesCaseContent()) {
			throw new ForbiddenException("This role does not read a case's client details");
		}
		String dealId = subject.getGhlOpportunityId();
		boolean named = dealId != null && !dealId.isBlank();
		if (named) {
			Optional<Opportunity> held = mirrored(subject, dealId);
			if (held.isPresent() || dealId.startsWith(SEEDED_ID_PREFIX)) {
				return held;
			}
			// By its own id: needs no contact, which a case opened outside Handoff A may not carry.
			absorb(() -> Optional.ofNullable(ghl.byId(dealId)).map(List::of).orElse(List.of()), dealId);
			held = mirrored(subject, dealId);
			if (held.isPresent()) {
				return held;
			}
		}
		Optional<String> ghlContactId = Optional.ofNullable(subject.getContactId())
				.flatMap(contacts::findById)
				.filter(contact -> subject.getBrandId().equals(contact.getBrandId()))
				.map(ContactSnapshot::getGhlContactId)
				.filter(id -> !id.isBlank() && !id.startsWith(SEEDED_ID_PREFIX));
		if (ghlContactId.isEmpty()) {
			return Optional.empty();
		}
		List<GhlPipelineClient.Opportunity> theirs = absorb(() -> ghl.forContact(ghlContactId.get()), ghlContactId.get());
		if (named) {
			return mirrored(subject, dealId);
		}
		return theirs.stream()
				.filter(deal -> deal.id() != null)
				.sorted(Comparator.comparing((GhlPipelineClient.Opportunity deal) -> !"won".equalsIgnoreCase(deal.status()))
						.thenComparing(GhlPipelineClient.Opportunity::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.map(deal -> mirrored(subject, deal.id()))
				.flatMap(Optional::stream)
				.findFirst();
	}

	/** Reads from GHL and writes what came back into the mirror. GHL unreachable or refusing reads as "none". */
	private List<GhlPipelineClient.Opportunity> absorb(Supplier<List<GhlPipelineClient.Opportunity>> read, String asked) {
		try {
			List<GhlPipelineClient.Opportunity> rows = read.get();
			opportunities.absorbForContact(rows);
			return rows;
		}
		catch (GhlUnavailableException unavailable) {
			log.info("GHL did not answer for {} on a case's deal: {}", asked, unavailable.getMessage());
			return List.of();
		}
	}

	private Optional<Opportunity> mirrored(Case subject, String dealId) {
		return opportunities.byGhlId(dealId).filter(deal -> subject.getBrandId().equals(deal.getBrandId()));
	}
}
