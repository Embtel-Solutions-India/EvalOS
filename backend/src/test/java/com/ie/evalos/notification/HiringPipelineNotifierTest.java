package com.ie.evalos.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.PipelinePurpose;
import com.ie.evalos.repository.PipelineStageRepository;

import org.junit.jupiter.api.Test;

/** Unit 63: which mirror absorbs are news to the ENM. */
class HiringPipelineNotifierTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID ENM = UUID.randomUUID();

	private final NotificationService notifications = mock(NotificationService.class);
	private final RecipientResolver recipients = mock(RecipientResolver.class);
	private final HiringPipelineNotifier notifier = new HiringPipelineNotifier(notifications, recipients,
			mock(PipelineStageRepository.class, invocation -> Optional.empty()));

	@Test
	void aStageChangeFromGhlOnAHiringPipelineTellsTheEnms() {
		given(recipients.enms(BRAND)).willReturn(List.of(ENM));

		notifier.absorbed(hiring(true), deal("stage-2"), "stage-1", false);

		verify(notifications).create(eq(BRAND), eq(List.of(ENM)), eq(NotificationType.HIRING_PIPELINE_UPDATED),
				any(), contains("Dr Grace Hopper moved to"));
	}

	@Test
	void noStageChangeAFirstImportOrAnotherPipelineIsNotNews() {
		notifier.absorbed(hiring(true), deal("stage-1"), "stage-1", false);
		notifier.absorbed(hiring(false), deal("stage-1"), null, true);
		Pipeline sales = hiring(true);
		sales.setPurpose(PipelinePurpose.SALES);
		notifier.absorbed(sales, deal("stage-2"), "stage-1", false);

		verify(notifications, never()).create(any(), anyCollection(), any(), any(), any());
	}

	private static Pipeline hiring(boolean syncedBefore) {
		Pipeline pipeline = new Pipeline(BRAND, "p-hire", "Expert Hiring", 0);
		pipeline.setPurpose(PipelinePurpose.EXPERT_HIRING);
		if (syncedBefore) {
			pipeline.opportunitiesSynced();
		}
		return pipeline;
	}

	private static Opportunity deal(String stage) {
		Opportunity deal = mock(Opportunity.class);
		given(deal.getBrandId()).willReturn(BRAND);
		given(deal.getGhlStageId()).willReturn(stage);
		given(deal.getName()).willReturn("Dr Grace Hopper");
		return deal;
	}
}
