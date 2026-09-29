package com.ie.evalos.notification;

import java.util.Objects;

import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.PipelinePurpose;
import com.ie.evalos.repository.PipelineStageRepository;

import org.springframework.stereotype.Component;

/**
 * Tells the brand's ENMs when a hiring candidate arrives or changes stage <strong>in GHL</strong>
 * (Unit 63) — a GHL form, calendar booking or workflow moving a card. A move made on the EvalOS
 * board is the ENM's own and never reaches here as a change: the mirror row already holds it.
 */
@Component
public class HiringPipelineNotifier {

	private final NotificationService notifications;
	private final RecipientResolver recipients;
	private final PipelineStageRepository stages;

	HiringPipelineNotifier(NotificationService notifications, RecipientResolver recipients,
			PipelineStageRepository stages) {
		this.notifications = notifications;
		this.recipients = recipients;
		this.stages = stages;
	}

	/**
	 * @param stageBefore the mirror's stage before GHL's answer was absorbed
	 * @param arrived     whether the mirror had never held this deal
	 */
	public void absorbed(Pipeline pipeline, Opportunity deal, String stageBefore, boolean arrived) {
		if (pipeline.getPurpose() != PipelinePurpose.EXPERT_HIRING) {
			return;
		}
		// The first read of a pipeline is an import, not news: a whole pipeline's worth of alerts.
		if (arrived && pipeline.getOpportunitiesSyncedAt() == null) {
			return;
		}
		if (!arrived && Objects.equals(stageBefore, deal.getGhlStageId())) {
			return;
		}
		String stage = stages.findByBrandIdAndGhlId(deal.getBrandId(), deal.getGhlStageId())
				.map(s -> s.getName()).orElse("a new stage");
		notifications.create(deal.getBrandId(), recipients.enms(deal.getBrandId()),
				NotificationType.HIRING_PIPELINE_UPDATED, null,
				(arrived ? "New candidate " + deal.getName() + " in " : deal.getName() + " moved to ") + stage
						+ " (from GHL).");
	}
}
