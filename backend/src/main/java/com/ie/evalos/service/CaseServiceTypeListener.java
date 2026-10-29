package com.ie.evalos.service;

import java.util.List;

import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.CaseRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A new case's service type is checked the moment it opens (2026-10-30), not on the next sweep: if the mirror
 * does not hold its deal yet, the deal is read from GHL and absorbed, then {@link CaseServiceTypeService#reconcile}
 * runs. A failure only logs — the 5-minute SERVICE_TYPE_RECONCILE sweep is the floor under this.
 */
@Component
public class CaseServiceTypeListener {

	private static final Logger log = LoggerFactory.getLogger(CaseServiceTypeListener.class);

	/** Seed data's fake GHL ids; never sent to GHL. */
	private static final String SEEDED_ID_PREFIX = "ghl-";

	private final CaseRepository cases;
	private final CaseServiceTypeService serviceTypes;
	private final OpportunityMirrorService opportunities;
	private final GhlPipelineClient ghl;
	private final TransactionTemplate newTransaction;

	CaseServiceTypeListener(CaseRepository cases, CaseServiceTypeService serviceTypes,
			OpportunityMirrorService opportunities, GhlPipelineClient ghl, PlatformTransactionManager transactions) {
		this.cases = cases;
		this.serviceTypes = serviceTypes;
		this.opportunities = opportunities;
		this.ghl = ghl;
		this.newTransaction = new TransactionTemplate(transactions);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(CaseEvents.CaseEvent event) {
		if (event.type() != CaseEvents.Type.CASE_CREATED) {
			return;
		}
		try {
			cases.findById(event.caseId()).map((subject) -> subject.getGhlOpportunityId())
					.filter((dealId) -> !dealId.isBlank() && !dealId.startsWith(SEEDED_ID_PREFIX))
					.filter((dealId) -> opportunities.byGhlId(dealId).isEmpty())
					.ifPresent((dealId) -> {
						// Outside any transaction: the GHL read must not hold a connection; the absorb has its own.
						GhlPipelineClient.Opportunity deal = ghl.byId(dealId);
						if (deal != null) {
							opportunities.absorbForContact(List.of(deal));
						}
					});
			newTransaction.executeWithoutResult(
					(status) -> cases.findById(event.caseId()).ifPresent(serviceTypes::reconcile));
		}
		catch (RuntimeException failed) {
			log.warn("Service type not checked on new case {}; SERVICE_TYPE_RECONCILE will", event.caseId(), failed);
		}
	}
}
