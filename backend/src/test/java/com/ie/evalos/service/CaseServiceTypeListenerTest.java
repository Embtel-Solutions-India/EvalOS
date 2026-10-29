package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.CaseRepository;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 2026-10-30: a new case's service type is checked as it opens, its deal fetched from GHL if not yet mirrored. */
class CaseServiceTypeListenerTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final CaseServiceTypeService serviceTypes = mock(CaseServiceTypeService.class);
	private final OpportunityMirrorService mirror = mock(OpportunityMirrorService.class);
	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private final CaseServiceTypeListener listener =
			new CaseServiceTypeListener(cases, serviceTypes, mirror, ghl, transactions);

	private Case opened(String dealId) {
		given(transactions.getTransaction(any())).willReturn(mock(TransactionStatus.class));
		Case subject = new Case(BRAND, "IE-1", Stage.DOC_COLLECTION);
		subject.setGhlOpportunityId(dealId);
		given(cases.findById(any())).willReturn(Optional.of(subject));
		return subject;
	}

	private static CaseEvents.CaseEvent event(CaseEvents.Type type) {
		return new CaseEvents.CaseEvent(type, BRAND, UUID.randomUUID(), null, null, Stage.DOC_COLLECTION);
	}

	@Test
	void aDealTheMirrorLacksIsFetchedThenTheTypeIsReconciled() {
		Case subject = opened("oppReal");
		given(mirror.byGhlId("oppReal")).willReturn(Optional.empty());
		GhlPipelineClient.Opportunity deal = new GhlPipelineClient.Opportunity("oppReal", "Deal", "c", "p", "s", "won",
				null, null, null, null, null, null, null);
		given(ghl.byId("oppReal")).willReturn(deal);

		listener.on(event(CaseEvents.Type.CASE_CREATED));

		verify(mirror).absorbForContact(List.of(deal));
		verify(serviceTypes).reconcile(subject);
	}

	@Test
	void aMirroredDealIsNotFetchedAgain() {
		Case subject = opened("oppReal");
		given(mirror.byGhlId("oppReal")).willReturn(Optional.of(mock(com.ie.evalos.domain.Opportunity.class)));

		listener.on(event(CaseEvents.Type.CASE_CREATED));

		verify(ghl, never()).byId(any());
		verify(serviceTypes).reconcile(subject);
	}

	@Test
	void otherEventsAreIgnored() {
		listener.on(event(CaseEvents.Type.CASE_CLOSED));

		verifyNoInteractions(cases, ghl, serviceTypes);
	}
}
