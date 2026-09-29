package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.Test;

/** Unit 65 rule 8: one offer's money history, merged from the offer, its payout and its payment. */
class OfferLogTest {

	private final AuditEventRepository events = mock(AuditEventRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final OfferLog log = new OfferLog(events, members);

	@Test
	void theLogMergesTheOfferThePayoutAndThePaymentOldestFirstWithNames() {
		UUID offerId = UUID.randomUUID(), payoutId = UUID.randomUUID(), paymentId = UUID.randomUUID();
		UUID pm = UUID.randomUUID();
		ExpertCaseOffer offer = mock(ExpertCaseOffer.class);
		given(offer.getId()).willReturn(offerId);
		PayoutLedger payout = mock(PayoutLedger.class);
		given(payout.getId()).willReturn(payoutId);
		given(payout.getPaymentId()).willReturn(paymentId);
		AuditEvent created = event(AuditAction.CREATED, pm, ActorType.STAFF, null, "{\"fee\":350.00}",
				"2026-09-01T10:00:00Z");
		AuditEvent accepted = event(AuditAction.UPDATED, null, ActorType.EXPERT, "{\"outcome\":\"OFFERED\"}",
				"{\"outcome\":\"ACCEPTED\",\"fee\":350.00}", "2026-09-02T10:00:00Z");
		AuditEvent opened = event(AuditAction.CREATED, null, ActorType.SYSTEM, null, "{\"status\":\"PENDING\"}",
				"2026-09-10T10:00:00Z");
		AuditEvent settled = event(AuditAction.PAYOUT_SETTLED, pm, ActorType.STAFF, null, "{\"amount\":350.00}",
				"2026-09-12T10:00:00Z");
		AuditEvent confirmed = event(AuditAction.UPDATED, null, ActorType.EXPERT, "{\"confirmed\":false}",
				"{\"confirmed\":true,\"draftCount\":1}", "2026-09-13T10:00:00Z");
		given(events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("OFFER", offerId))
				.willReturn(List.of(created, accepted));
		given(events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("PAYOUT", payoutId)).willReturn(List.of(opened));
		given(events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("PAYOUT_PAYMENT", paymentId))
				.willReturn(List.of(confirmed, settled)); // out of order on purpose: the log sorts
		TeamMember member = mock(TeamMember.class);
		given(member.getId()).willReturn(pm);
		given(member.getDisplayName()).willReturn("Priya PM");
		given(members.findAllById(anyIterable())).willReturn(List.of(member));

		List<OfferLog.Entry> entries = log.forOffer(offer, payout);

		assertThat(entries).extracting(OfferLog.Entry::what).containsExactly("Fee set", "Offer accepted",
				"Payout opened", "Transfer recorded", "Expert confirmed receipt");
		assertThat(entries).extracting(OfferLog.Entry::who)
				.containsExactly("Priya PM", "Expert", "EvalOS", "Priya PM", "Expert");
	}

	@Test
	void anOfferWithNoPayoutLogsOnlyItself() {
		UUID offerId = UUID.randomUUID();
		ExpertCaseOffer offer = mock(ExpertCaseOffer.class);
		given(offer.getId()).willReturn(offerId);
		AuditEvent edited = event(AuditAction.UPDATED, null, ActorType.SYSTEM, "{\"fee\":350.00}",
				"{\"fee\":400.00}", "2026-09-01T10:00:00Z");
		given(events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("OFFER", offerId)).willReturn(List.of(edited));

		assertThat(log.forOffer(offer, null)).extracting(OfferLog.Entry::what).containsExactly("Fee changed");
	}

	private static AuditEvent event(AuditAction action, UUID actor, ActorType type, String before, String after,
			String at) {
		AuditEvent e = mock(AuditEvent.class);
		given(e.getAction()).willReturn(action);
		given(e.getActorId()).willReturn(actor);
		given(e.getActorType()).willReturn(type);
		given(e.getBeforeSnapshot()).willReturn(before);
		given(e.getAfterSnapshot()).willReturn(after);
		given(e.getCreatedAt()).willReturn(Instant.parse(at));
		return e;
	}
}
