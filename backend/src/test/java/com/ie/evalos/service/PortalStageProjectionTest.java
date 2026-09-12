package com.ie.evalos.service;

import java.util.Arrays;

import com.ie.evalos.domain.Stage;
import com.ie.evalos.service.PortalStageProjection.PortalStep;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D5: the twelve stages, and the words the two portals are given for them (Unit 35).
 *
 * <p>The whole point of this unit is that the SPA holds no lifecycle enum, which makes this table
 * the only copy of the mapping. So it is asserted stage by stage rather than spot-checked: a
 * {@code switch} over an enum is exhaustive at compile time, but nothing except a test says the
 * arms carry the <em>right</em> words, and the acceptance criteria are written in those words.
 */
class PortalStageProjectionTest {

	@Test
	void theClientSeesAWordForEveryStage() {
		// A client always has something to be told. If a stage ever answered null here the portal
		// would render a blank row for a case the client can see the existence of, which reads as
		// a bug in the case rather than a gap in a table.
		for (Stage stage : Stage.values()) {
			assertThat(PortalStageProjection.forClient(stage))
					.as("client step for %s", stage)
					.isNotNull();
		}
	}

	@Test
	void theClientsTwoActionsAreTheOnesTheyCanActOn() {
		assertThat(PortalStageProjection.forClient(Stage.DOC_COLLECTION))
				.isEqualTo(new PortalStep("Upload", true));
		assertThat(PortalStageProjection.forClient(Stage.CLIENT_REVIEW))
				.isEqualTo(new PortalStep("Review", true));

		// Exactly two, and CLIENT_APPROVAL is deliberately not a third: by then they have approved
		// and the case is EvalOS's again. A client chased for an action they already took is the
		// specific failure this asserts against.
		assertThat(Arrays.stream(Stage.values())
				.filter(stage -> PortalStageProjection.forClient(stage).actionRequired())
				.toList())
				.containsExactlyInAnyOrder(Stage.DOC_COLLECTION, Stage.CLIENT_REVIEW);
	}

	@Test
	void theClientSeesTheEndStatesAsDifferentThings() {
		// Delivered and Closed are one thing to EvalOS's board and two to the client: one has a
		// letter to fetch, the other is finished business.
		assertThat(PortalStageProjection.forClient(Stage.DELIVERED).label()).isEqualTo("Ready to download");
		assertThat(PortalStageProjection.forClient(Stage.CLOSED).label()).isEqualTo("Complete");
	}

	@Test
	void theExpertSeesNothingBeforeTheCaseReachesThem() {
		// Null, not a placeholder. An expert on a panel should not learn the stage of a case that
		// has not come to them — and the payload carrying null is what lets the SPA render nothing
		// rather than inventing a word of its own.
		for (Stage stage : new Stage[] { Stage.DOC_COLLECTION, Stage.PM_REVIEW, Stage.DRAFT_IN_PROGRESS,
				Stage.DRAFT_REVIEW, Stage.READY_TO_SEND, Stage.CLIENT_REVIEW, Stage.CLIENT_APPROVAL }) {
			assertThat(PortalStageProjection.forExpert(stage)).as("expert step for %s", stage).isNull();
		}
	}

	@Test
	void theExpertHasExactlyOneActionAndItIsTheOneTheSlaChases() {
		assertThat(PortalStageProjection.forExpert(Stage.EXPERT_SIGNING))
				.isEqualTo(new PortalStep("Sign", true));

		assertThat(Arrays.stream(Stage.values())
				.filter(stage -> PortalStageProjection.forExpert(stage) != null)
				.filter(stage -> PortalStageProjection.forExpert(stage).actionRequired())
				.toList())
				.containsExactly(Stage.EXPERT_SIGNING);
	}

	@Test
	void everythingAfterSigningReadsAsSubmittedToTheExpert() {
		// Four stages, one word: what happens after they sign is EvalOS's business, and telling an
		// expert the case is in Final QC invites them to ask about work that is not theirs.
		for (Stage stage : new Stage[] { Stage.FINAL_QC, Stage.READY_TO_DELIVER, Stage.DELIVERED, Stage.CLOSED }) {
			assertThat(PortalStageProjection.forExpert(stage))
					.as("expert step for %s", stage)
					.isEqualTo(new PortalStep("Submitted", false));
		}
	}
}
