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

	@Test
	void aDeclineCanBeRetakenOnlyWhileTheCaseWaitsForARematchAndTheExpertIsFree() {
		Case waiting = aCase(Stage.EXPERT_SIGNING, ExpertSignStatus.PENDING, ExceptionState.EXPERT_DECLINED_REMATCHING);
		ExpertCaseOffer declined = anOffer(OfferOutcome.DECLINED);

		assertThat(ExpertCaseHistoryService.retakeEligible(declined, waiting, anExpert(Availability.AVAILABLE)))
				.isTrue();
		assertThat(ExpertCaseHistoryService.retakeEligible(declined, waiting, anExpert(Availability.ON_LEAVE)))
				.isFalse();
		assertThat(ExpertCaseHistoryService.retakeEligible(anOffer(OfferOutcome.ACCEPTED), waiting,
				anExpert(Availability.AVAILABLE))).isFalse();
		assertThat(ExpertCaseHistoryService.retakeEligible(declined,
				aCase(Stage.EXPERT_SIGNING, ExpertSignStatus.REASSIGNED, ExceptionState.NONE),
				anExpert(Availability.AVAILABLE))).isFalse();
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
