package com.ie.evalos.service;

import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.service.DraftReviewService.DraftStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two derivations the draft workspace rests on, both of which look like stored fields and are
 * not: a draft's status, and how far along the case is.
 *
 * <p>Tested as pure functions because that is what they are — no repository, no clock.
 */
class DraftReviewServiceTest {

	private static final UUID BRAND = UUID.randomUUID();

	private static Case drafting() {
		Case subject = new Case(BRAND, "IE-2026-0001", Stage.DRAFT_GENERATION);
		subject.setDraftVersionCount(1);
		return subject;
	}

	@Test
	void aDraftSittingWithThePmIsPendingReview() {
		Case subject = drafting();
		subject.setPmApprovalStatus(PmApprovalStatus.PENDING);

		assertThat(DraftReviewService.statusOf(subject)).isEqualTo(DraftStatus.PENDING_REVIEW);
	}

	@Test
	void aReturnedDraftIsTheCaseManagersAgain() {
		Case subject = drafting();
		subject.setPmApprovalStatus(PmApprovalStatus.RETURNED);

		assertThat(DraftReviewService.statusOf(subject)).isEqualTo(DraftStatus.REVISIONS_REQUESTED);
	}

	/**
	 * Approved means through the QC gate, and the evidence is the **stage** — not the approval
	 * flag, which says only that the PM signed off on the text. A case can be PM-approved and still
	 * be several steps from done.
	 */
	@Test
	void pmApprovalAloneIsNotApprovedUntilTheCaseIsPastQc() {
		Case awaitingQc = drafting();
		awaitingQc.setPmApprovalStatus(PmApprovalStatus.APPROVED);
		assertThat(DraftReviewService.statusOf(awaitingQc)).isEqualTo(DraftStatus.READY_FOR_QC);

		Case delivered = new Case(BRAND, "IE-2026-0002", Stage.FINAL_DELIVERY);
		delivered.setDraftVersionCount(1);
		delivered.setPmApprovalStatus(PmApprovalStatus.APPROVED);
		assertThat(DraftReviewService.statusOf(delivered)).isEqualTo(DraftStatus.APPROVED);
	}

	@Test
	void everyMilestoneIsReadFromTheCaseRatherThanStored() {
		Case subject = new Case(BRAND, "IE-2026-0003", Stage.EXPERT_SIGNING);
		subject.setDraftVersionCount(2);
		subject.setExpertId(UUID.randomUUID());
		subject.setPmApprovalStatus(PmApprovalStatus.APPROVED);
		subject.setClientApprovalStatus(ClientApprovalStatus.APPROVED);
		subject.setExpertSignStatus(ExpertSignStatus.SIGNED);

		var milestones = DraftReviewService.milestones(subject);

		assertThat(milestones).hasSize(8);
		// Everything up to and including the signature is done; QC is the one still open.
		assertThat(milestones.stream().filter(DraftReviewService.Milestone::done).count()).isEqualTo(7);
		assertThat(milestones.get(7).label()).isEqualTo("QC approved");
		assertThat(milestones.get(7).done()).isFalse();
	}

	/**
	 * The bar is a picture of now, not a high-water mark.
	 *
	 * <p>`submitDraft` nulls `client_approval_status` — "a new draft is not the draft the client
	 * already saw" — so a case sent back for revisions genuinely loses a completed step. A
	 * monotonic bar would keep claiming the client had approved something they have not seen.
	 */
	@Test
	void aResubmittedDraftLosesTheClientApprovalStepRatherThanKeepingIt() {
		Case resubmitted = drafting();
		resubmitted.setDraftVersionCount(2);
		resubmitted.setPmApprovalStatus(PmApprovalStatus.PENDING);
		resubmitted.setClientApprovalStatus(null);

		var milestones = DraftReviewService.milestones(resubmitted);

		assertThat(milestones.get(4).label()).isEqualTo("Sent to client");
		assertThat(milestones.get(4).done()).isFalse();
		assertThat(milestones.get(5).done()).isFalse();
	}
}
