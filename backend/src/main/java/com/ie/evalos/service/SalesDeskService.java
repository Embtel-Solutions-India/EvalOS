package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.Set;

import com.ie.evalos.common.DuplicateDealException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.security.TenantContext;

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
	/**
	 * Opens a new deal on the caller's own pipeline.
	 *
	 * <p><strong>The contact comes first, through upsert.</strong> GHL matches an existing contact
	 * on email then phone, so a client the business already knows is reused rather than duplicated
	 * — the same call {@code MarketingLeadService} makes, and the reason an email or a phone is
	 * required rather than merely nice to have.
	 *
	 * <p><strong>The opportunity is then a true create, not an upsert</strong>, which is the whole
	 * reason this method exists. Upsert keys on (contact, pipeline): using it here would silently
	 * overwrite a repeat client's first deal instead of opening their second. See
	 * {@link GhlWriteClient#createOpportunity}, and Unit 39 §3a for the trade being taken.
	 *
	 * <p><strong>The duplicate that upsert used to prevent is now handled by asking.</strong> If
	 * the contact already has an open deal on this pipeline the call is refused with
	 * {@link DuplicateDealException} until {@code confirmSecondDeal} is set, so the salesperson
	 * sees the existing deal before creating a second. Read from the board cache rather than from
	 * GHL: it is the same data the desk is already looking at, and a second round trip on every
	 * create would spend the rate budget to answer a question the screen can already see.
	 *
	 * <p><strong>The pipeline is never a parameter</strong> — it is the caller's own, exactly as
	 * on every other route here. A create that let the caller name a pipeline would make the whole
	 * access model advisory.
	 *
	 * <p><strong>{@code customFields} is keyed by GHL's own field ids and is not validated
	 * here.</strong> The definitions are the location's, read by {@code GhlCustomFieldClient} and
	 * handed to the form, so an id arriving back is one GHL itself supplied. Validating the value
	 * against a picklist would mean holding a second copy of the options and going stale the day
	 * somebody edits them in GHL — GHL rejects a bad value, and its refusal is the authority.
	 */
	public Deal createDeal(String firstName, String lastName, String email, String phone,
			String name, BigDecimal monetaryValue, String stageId, String expectedCloseDate,
			java.util.Map<String, String> customFields, boolean confirmSecondDeal) {
		String pipelineId = scope.mine();
		if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
			throw new InvalidRequestException(
					"A deal needs the client's email or phone number: GHL matches an existing "
							+ "contact on those, and without either every save creates a new one.");
		}
		if (name == null || name.isBlank()) {
			throw new InvalidRequestException("A deal needs a name");
		}

		GhlWriteClient.UpsertedContact contact = ghl.upsertContact(firstName, lastName, email, phone);

		if (!confirmSecondDeal) {
			cache.forPipelines(java.util.List.of(pipelineId)).stream()
					.filter((row) -> contact.id().equals(row.getGhlContactId()))
					.filter((row) -> "open".equalsIgnoreCase(row.getStatus()))
					.findFirst()
					.ifPresent((row) -> {
						throw new DuplicateDealException(row.getGhlOpportunityId());
					});
		}

		GhlWriteClient.UpsertedOpportunity created = ghl.createOpportunity(pipelineId, contact.id(),
				name, monetaryValue, stageId, expectedCloseDate, customFields);
		return new Deal(created.id(), created.contactId(), created.name(), created.stageId(),
				created.status(), created.monetaryValue());
	}

	public record Deal(String opportunityId, String contactId, String name, String stageId,
			String status, BigDecimal monetaryValue) {
	}

	private final GhlWriteClient ghl;
	private final PipelineScope scope;
	private final OpportunityCache cache;
	private final com.ie.evalos.repository.FollowUpRepository followUps;

	SalesDeskService(GhlWriteClient ghl, PipelineScope scope, OpportunityCache cache,
			com.ie.evalos.repository.FollowUpRepository followUps) {
		this.cache = cache;
		this.followUps = followUps;
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
	public String followUp(String opportunityId, String contactId, String title, String dueAt,
			String note, String assignedUserId) {
		String pipelineId = scope.requireMine(opportunityId);
		if (title == null || title.isBlank()) {
			throw new InvalidRequestException("A follow-up needs a title");
		}
		if (dueAt == null || dueAt.isBlank()) {
			// A follow-up with no date is a note, and notes have their own route. Refusing is
			// kinder than creating a task nothing will ever surface.
			throw new InvalidRequestException("A follow-up needs a due date");
		}
		java.time.Instant due;
		try {
			due = java.time.Instant.parse(dueAt);
		}
		catch (java.time.format.DateTimeParseException badDate) {
			throw new InvalidRequestException("That due date is not a valid instant");
		}

		String taskId = ghl.createFollowUp(contactId, opportunityId, pipelineId, title, dueAt, note,
				assignedUserId);

		// Mirrored after GHL accepts, from the id GHL returned. Written here rather than discarded
		// is the whole difference between a desk that can show its own reminders and one that
		// cannot — see V48__follow_up.sql.
		TenantContext caller = TenantContext.current();
		followUps.save(new com.ie.evalos.domain.FollowUp(caller.brandId(), taskId, opportunityId,
				contactId, pipelineId, title, note, due, caller.memberId()));
		return taskId;
	}

	/**
	 * Marks a follow-up done — in GHL first, then in the mirror.
	 *
	 * <p><strong>GHL first, deliberately.</strong> If GHL refuses, nothing local changes and the
	 * salesperson sees the refusal. The reverse order would show a completed reminder that is still
	 * open in the system the automations read.
	 */
	@org.springframework.transaction.annotation.Transactional
	public void completeFollowUp(String opportunityId, String taskId) {
		String pipelineId = scope.requireMine(opportunityId);
		TenantContext caller = TenantContext.current();

		com.ie.evalos.domain.FollowUp row = followUps
				.findByBrandIdAndGhlTaskId(caller.brandId(), taskId)
				.orElseThrow(() -> new InvalidRequestException("No such follow-up on this desk"));

		ghl.completeFollowUp(row.getGhlContactId(), taskId, opportunityId, pipelineId);
		row.complete();
		followUps.save(row);
	}

	/**
	 * The desk's open follow-ups due before a cutoff, soonest first.
	 *
	 * <p>Read from the mirror rather than GHL, because GHL only lists tasks per contact — a
	 * desk-wide view would be one call per contact, which the rate budget refuses.
	 */
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public java.util.List<com.ie.evalos.domain.FollowUp> openFollowUps(java.time.Instant before) {
		TenantContext caller = TenantContext.current();
		return followUps
				.findByBrandIdAndGhlPipelineIdAndCompletedFalseAndDueAtBeforeOrderByDueAtAsc(
						caller.brandId(), scope.mine(), before);
	}

	private static Deal asDeal(GhlWriteClient.UpsertedOpportunity from) {
		return new Deal(from.id(), from.contactId(), from.name(), from.stageId(), from.status(),
				from.monetaryValue());
	}
}
