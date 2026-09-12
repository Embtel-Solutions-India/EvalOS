package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.Set;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.integration.GhlWriteClient;

import org.springframework.stereotype.Service;

/**
 * The sales desk: work a deal to a close, from EvalOS.
 *
 * <p><strong>The one thing this class must never do is create a case.</strong> Marking an
 * opportunity won tells GHL, and GHL's {@code opportunity.won} webhook creates the case — that
 * is Handoff A, and invariant 8 says it is the only door. The gap between the two is real and
 * visible: {@link #close} returns immediately and the case appears a moment later. A "helpful"
 * local creation here would be a second intake path racing the webhook, and
 * {@code DomainInvariantsTest} refuses the shape by forbidding anything but
 * {@code GhlOpportunityHandler} to depend on {@code CaseIntakeService}.
 *
 * <p><strong>Notes are not here.</strong> They are {@link OpportunityNoteService}'s, shared with
 * Marketing, because the conversation belongs to the deal rather than to whichever desk holds it.
 *
 * <p><strong>Meetings are not here either, and are the one thing this unit could not build.</strong>
 * {@code POST /calendars/events/appointments} needs {@code calendars/events.write} and
 * {@code calendars.readonly}, neither of which is granted. Follow-ups <em>are</em> here, because
 * a GHL task needs only {@code contacts.write}.
 */
@Service
public class SalesDeskService {

	/**
	 * The outcomes a salesperson may set, and deliberately not all four GHL accepts.
	 *
	 * <p>{@code open} is absent: re-opening a closed deal is not a sales action, it is an
	 * admission that the close was wrong, and it would silently un-fire nothing — the case
	 * created by {@code opportunity.won} does not disappear when the opportunity reverts. If the
	 * business needs it, it is a decision with a case-side answer, not a fourth enum value.
	 */
	private static final Set<String> CLOSABLE = Set.of("won", "lost", "abandoned");

	/** What the desk shows after an action: GHL's answer, never the request echoed back. */
	public record Deal(String opportunityId, String contactId, String name, String stageId,
			String status, BigDecimal monetaryValue) {
	}

	private final GhlWriteClient ghl;
	private final PipelineScope scope;

	SalesDeskService(GhlWriteClient ghl, PipelineScope scope) {
		this.ghl = ghl;
		this.scope = scope;
	}

	/** Renames a deal or re-prices it. Both are GHL's fields; EvalOS holds no second copy. */
	public Deal update(String opportunityId, String name, BigDecimal monetaryValue) {
		String pipelineId = scope.requireMine(opportunityId);
		if ((name == null || name.isBlank()) && monetaryValue == null) {
			throw new InvalidRequestException("Nothing to change: send a name, a value, or both");
		}
		return asDeal(ghl.updateOpportunity(opportunityId, pipelineId, name, monetaryValue, null));
	}

	/**
	 * Moves a deal to another stage of the pipeline it is already in.
	 *
	 * <p><strong>Within the caller's own pipeline only.</strong> There is no parameter for a
	 * target pipeline: moving a deal between pipelines is the marketing-to-sales promotion, and
	 * that is <strong>GHL's workflow to run</strong>, not a button here. A second promotion path
	 * racing the automation is exactly what the design avoids.
	 */
	public Deal moveToStage(String opportunityId, String stageId) {
		String pipelineId = scope.requireMine(opportunityId);
		if (stageId == null || stageId.isBlank()) {
			throw new InvalidRequestException("A stage is required");
		}
		return asDeal(ghl.moveStage(opportunityId, pipelineId, stageId));
	}

	/**
	 * Closes a deal won, lost or abandoned.
	 *
	 * <p><strong>Won does not create a case here.</strong> See the class comment: EvalOS tells
	 * GHL and waits for the webhook. The caller should show a pending state rather than looking
	 * for a case that has not arrived yet.
	 */
	public Deal close(String opportunityId, String status) {
		String pipelineId = scope.requireMine(opportunityId);
		if (status == null || !CLOSABLE.contains(status)) {
			// The message names what is allowed rather than what was sent: a caller who typed
			// "closed" needs the vocabulary, not their own word repeated back.
			throw new InvalidRequestException("Status must be one of: won, lost, abandoned");
		}
		return asDeal(ghl.setStatus(opportunityId, pipelineId, status));
	}

	/**
	 * Sets a follow-up on the deal's contact, as a GHL task.
	 *
	 * <p>A GHL task rather than an EvalOS reminder: GHL has tasks, its automation can act on
	 * them, and they surface where the business already looks. An EvalOS reminder would need the
	 * {@code job} package, duplicate something that exists, and reach nobody — EvalOS has no
	 * outbound channel (invariant 14).
	 *
	 * @param contactId the deal's contact, which is where GHL hangs tasks
	 * @param dueAt     ISO-8601; GHL validates the format and refuses what it cannot read
	 */
	public String followUp(String opportunityId, String contactId, String title, String dueAt) {
		String pipelineId = scope.requireMine(opportunityId);
		if (title == null || title.isBlank()) {
			throw new InvalidRequestException("A follow-up needs a title");
		}
		if (dueAt == null || dueAt.isBlank()) {
			// A follow-up with no date is a note, and notes have their own route. Refusing is
			// kinder than creating a task nothing will ever surface.
			throw new InvalidRequestException("A follow-up needs a due date");
		}
		return ghl.createFollowUp(contactId, opportunityId, pipelineId, title, dueAt);
	}

	private static Deal asDeal(GhlWriteClient.UpsertedOpportunity from) {
		return new Deal(from.id(), from.contactId(), from.name(), from.stageId(), from.status(),
				from.monetaryValue());
	}
}
