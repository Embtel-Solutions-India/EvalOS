package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.service.ExpertCaseHistoryService.WorkStatus;

import org.junit.jupiter.api.Test;

/** Unit 63: the derived work status and D62's retake eligibility. */
class ExpertCaseHistoryServiceTest {

	private static final UUID EXPERT = UUID.randomUUID();

	@Test
	void theWorkStatusFollowsTheOfferThenTheCase() {
		assertThat(ExpertCaseHistoryService.status(OfferOutcome.OFFERED, null)).isEqualTo(WorkStatus.OFFERED);
		assertThat(ExpertCaseHistoryService.status(OfferOutcome.DECLINED, null)).isEqualTo(WorkStatus.REJECTED);
		assertThat(ExpertCaseHistoryService.status(OfferOutcome.TIMED_OUT, null)).isEqualTo(WorkStatus.REJECTED);
		assertThat(ExpertCaseHistoryService.status(OfferOutcome.SUPERSEDED, null)).isEqualTo(WorkStatus.REASSIGNED);
		assertThat(ExpertCaseHistoryService.status(OfferOutcome.ACCEPTED,
				aCase(Stage.EXPERT_SIGNING, ExpertSignStatus.PENDING, ExceptionState.NONE)))
				.isEqualTo(WorkStatus.ACCEPTED);
		assertThat(ExpertCaseHistoryService.status(OfferOutcome.ACCEPTED,
				aCase(Stage.FINAL_QC, ExpertSignStatus.SIGNED, ExceptionState.NONE)))
				.isEqualTo(WorkStatus.SUBMITTED);
		assertThat(ExpertCaseHistoryService.status(OfferOutcome.ACCEPTED,
				aCase(Stage.DELIVERED, ExpertSignStatus.SIGNED, ExceptionState.NONE)))
				.isEqualTo(WorkStatus.DELIVERED);
	}

	/**
	 * Found in a local run-through (2026-09-29): an expert who ACCEPTED and then declined keeps an
	 * ACCEPTED offer, so the case's own state is what says they walked away.
	 */
	@Test
	void theCaseStateSaysTheExpertWalkedAwayWhateverTheOfferSays() {
		assertThat(ExpertCaseHistoryService.waitingForRematch(
				aCase(Stage.EXPERT_SIGNING, ExpertSignStatus.OVERDUE, ExceptionState.EXPERT_DECLINED_REMATCHING),
				anExpert(Availability.AVAILABLE))).isTrue();
		assertThat(ExpertCaseHistoryService.waitingForRematch(
				aCase(Stage.EXPERT_SIGNING, ExpertSignStatus.REASSIGNED, ExceptionState.NONE),
				anExpert(Availability.AVAILABLE))).isFalse();
		assertThat(ExpertCaseHistoryService.waitingForRematch(null, anExpert(Availability.AVAILABLE))).isFalse();
	}

	@Test
	void anAcceptedThenDeclinedCaseIsRejectedAndRetakeableInTheHistory() {
		java.util.UUID caseId = java.util.UUID.randomUUID();
		Expert expert = anExpert(Availability.AVAILABLE);
		given(expert.getBrandId()).willReturn(java.util.UUID.randomUUID());
		Case waiting = aCase(Stage.EXPERT_SIGNING, ExpertSignStatus.OVERDUE, ExceptionState.EXPERT_DECLINED_REMATCHING);
		given(waiting.getId()).willReturn(caseId);
		ExpertCaseOffer accepted = anOffer(OfferOutcome.ACCEPTED);
		given(accepted.getCaseId()).willReturn(caseId);

		com.ie.evalos.repository.ExpertRepository experts = mock(com.ie.evalos.repository.ExpertRepository.class);
		com.ie.evalos.repository.ExpertCaseOfferRepository offers = mock(com.ie.evalos.repository.ExpertCaseOfferRepository.class);
		com.ie.evalos.repository.CaseRepository cases = mock(com.ie.evalos.repository.CaseRepository.class);
		given(experts.findScoped(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(EXPERT)))
				.willReturn(java.util.Optional.of(expert));
		given(offers.findByBrandIdAndExpertIdOrderByOfferedAtDesc(expert.getBrandId(), EXPERT))
				.willReturn(java.util.List.of(accepted));
		given(cases.findByBrandIdAndIdIn(org.mockito.ArgumentMatchers.eq(expert.getBrandId()), org.mockito.ArgumentMatchers.any()))
				.willReturn(java.util.List.of(waiting));

		try (var tenant = org.mockito.Mockito.mockStatic(com.ie.evalos.security.TenantContext.class)) {
			tenant.when(com.ie.evalos.security.TenantContext::current).thenReturn(null);
			ExpertCaseHistoryService.Row row = new ExpertCaseHistoryService(experts, offers, cases).of(EXPERT).getFirst();
			assertThat(row.status()).isEqualTo(WorkStatus.REJECTED);
			assertThat(row.retakeEligible()).isTrue();
		}
	}

	private static Case aCase(Stage stage, ExpertSignStatus sign, ExceptionState exception) {
		Case subject = mock(Case.class);
		given(subject.getCurrentStage()).willReturn(stage);
		given(subject.getExpertSignStatus()).willReturn(sign);
		given(subject.getExceptionState()).willReturn(exception);
		given(subject.getExpertId()).willReturn(EXPERT);
		return subject;
	}

	private static ExpertCaseOffer anOffer(OfferOutcome outcome) {
		ExpertCaseOffer offer = mock(ExpertCaseOffer.class);
		given(offer.getOutcome()).willReturn(outcome);
		return offer;
	}

	private static Expert anExpert(Availability availability) {
		Expert expert = mock(Expert.class);
		given(expert.getId()).willReturn(EXPERT);
		given(expert.getAvailability()).willReturn(availability);
		return expert;
	}
}
