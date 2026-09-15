package com.ie.evalos.integration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.AuditService;

import org.springframework.stereotype.Component;

/**
 * Every write EvalOS makes to GHL.
 *
 * <p><strong>Renamed from {@code GhlLeadClient} in Unit 40.</strong> It was named for the
 * marketing desk that first used it; the sales desk now closes deals and schedules follow-ups
 * through the same methods, so the old name described one of its two callers. A class named for
 * whoever happened to need it first is a name the next reader has to un-learn.
 *
 * <p><strong>It audits, and {@code GhlHttp} still does not.</strong> Unit 37 argued that the door
 * is transport and cannot write a meaningful audit row, because it does not know what a write
 * <em>means</em>. This class does: its methods are named for domain operations, not verbs, so
 * "a marketer upserted a lead" is a sentence it can actually record. {@code GhlHttpTest}'s
 * structural check — any class holding a {@code GhlHttp} and calling a write verb must reach
 * {@code AuditService} — is satisfied here rather than waived.
 *
 * <p><strong>Creates go through upsert, never through {@code POST /contacts/} or
 * {@code POST /opportunities/}.</strong> GHL marks its writes as needing idempotency but offers
 * no key to send — no header, no client token. What it offers instead is upsert, keyed on data it
 * already owns: a contact by the location's duplicate rule, an opportunity by
 * {@code contactId + pipelineId}. A marketer who double-submits gets one lead. Spec 39 §3a
 * carries the argument and the two limits.
 *
 * <p><strong>Nothing here retries</strong> (Unit 37 §7). Upsert makes a repeated *human* action
 * safe; it does not make an automatic retry safe, because a retry after an ambiguous failure can
 * still race a first attempt that succeeded.
 *
 * <p>Separate from {@link GhlOpportunityClient}, which reads boards. Reads and writes have
 * different failure modes and different review needs, and one class doing both is one class
 * nobody wants to change.
 */
@Component
public class GhlWriteClient {

	/**
	 * What GHL returns from an opportunity upsert.
	 *
	 * <p>{@code isNew} is the reason to prefer upsert over create: it says whether anything was
	 * actually created, so the caller can tell a marketer "opened" or "already open" rather than
	 * guessing.
	 */
	public record UpsertedOpportunity(String id, String contactId, String pipelineId, String stageId,
			String status, String name, BigDecimal monetaryValue, boolean isNew) {
	}

	/** What GHL returns from a contact upsert. The id is GHL's; EvalOS never mints one. */
	public record UpsertedContact(String id, String name, String email, String phone) {
	}

	private final GhlHttp http;
	private final AuditService audit;

	GhlWriteClient(GhlHttp http, AuditService audit) {
		this.http = http;
		this.audit = audit;
	}

	/**
	 * Creates the contact, or finds the one GHL already has.
	 *
	 * <p><strong>EvalOS does not decide which.</strong> GHL applies its location's
	 * <em>Allow Duplicate Contact</em> setting — email first, then phone — and returns the
	 * contact. That is invariant 7 working as intended: the identity authority stays over there,
	 * and EvalOS never mints a {@code ghl_contact_id}.
	 *
	 * @throws GhlUnavailableException if GHL is not configured here or refused the request
	 */
	public UpsertedContact upsertContact(String firstName, String lastName, String email, String phone) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("locationId", http.locationId());
		putIfPresent(body, "firstName", firstName);
		putIfPresent(body, "lastName", lastName);
		putIfPresent(body, "email", email);
		putIfPresent(body, "phone", phone);

		ContactEnvelope response = http.post(ContactEnvelope.class,
				(uri) -> uri.path("/contacts/upsert").build(), body);
		ContactRow contact = require(response == null ? null : response.contact(), "contact");

		// Audited on the id GHL returned, not on anything the caller supplied — a row naming an
		// id EvalOS invented would be a trail to nowhere. The email is deliberately not in the
		// payload: an audit row is not a place to accumulate contact PII.
		audit.recordEvent("GHL_CONTACT", auditKey("GHL_CONTACT", contact.id()), AuditAction.UPDATED, actor(), null,
				Map.of("ghlContactId", contact.id()));

		return new UpsertedContact(contact.id(), contact.contactName(), contact.email(), contact.phone());
	}

	/**
	 * Opens the opportunity, or returns the one already open for this contact in this pipeline.
	 *
	 * <p><strong>The pipeline is the caller's, checked before this is reached.</strong> This
	 * class takes it as an argument and does not verify it — {@code MarketingLeadService} is
	 * where the scope check lives, because that is where the caller's principal is.
	 */
	public UpsertedOpportunity upsertOpportunity(String pipelineId, String contactId, String name,
			BigDecimal monetaryValue) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("pipelineId", pipelineId);
		body.put("contactId", contactId);
		body.put("status", "open");
		putIfPresent(body, "name", name);
		if (monetaryValue != null) {
			body.put("monetaryValue", monetaryValue);
		}

		OpportunityEnvelope response = http.post(OpportunityEnvelope.class,
				(uri) -> uri.path("/opportunities/upsert").build(), body);
		OpportunityRow row = require(response == null ? null : response.opportunity(), "opportunity");

		audit.recordEvent("GHL_OPPORTUNITY", auditKey("GHL_OPPORTUNITY", row.id()),
				// CREATED and UPDATED are different facts and the trail should say which. GHL's
				// own `new` flag is the only thing that knows, which is a second reason to use
				// upsert over create.
				Boolean.TRUE.equals(response.isNew()) ? AuditAction.CREATED : AuditAction.UPDATED,
				actor(), null,
				Map.of("ghlOpportunityId", row.id(), "ghlPipelineId", pipelineId));

		return new UpsertedOpportunity(row.id(), row.contactId(), pipelineId, row.pipelineStageId(),
				row.status(), row.name(), row.monetaryValue(), Boolean.TRUE.equals(response.isNew()));
	}

	/**
	 * Opens a <strong>genuinely new</strong> opportunity, duplicate risk included.
	 *
	 * <p><strong>This is the escape hatch Unit 39 §3a named, and taking it is the decision that
	 * spec said would have to be taken knowingly.</strong> Its words: "Opportunity upsert means
	 * one open opportunity per contact per pipeline. That is correct for a *marketing* pipeline —
	 * a lead is a lead. It is **not** correct for a repeat client buying a second evaluation…
	 * Unit 40 must not reuse this path for a genuine second deal; the escape hatch is
	 * {@code POST /opportunities/}, and taking it **brings the duplicate risk back, knowingly**."
	 *
	 * <p>A salesperson opening a deal for a client who already bought is exactly that case, so
	 * {@link #upsertOpportunity} is the wrong verb here: it would silently overwrite the first
	 * deal's name and value rather than create the second. The duplicate risk is managed one layer
	 * up — {@code SalesDeskService} refuses a second open deal for the same contact unless the
	 * caller confirms — because that is where the caller's own pipeline cache can answer it
	 * without another GHL round trip.
	 *
	 * <p><strong>{@code status} is forced to {@code open} and is not a parameter.</strong> GHL
	 * accepts {@code won} on create, and a won opportunity fires the {@code opportunity.won}
	 * workflow — which is Handoff A, which mints a <em>paid</em> case. A form field that can
	 * conjure a paid case with no payment is not a field this method offers. Closing a deal is
	 * {@link #setStatus}, which is a separate, deliberate act.
	 */
	public UpsertedOpportunity createOpportunity(String pipelineId, String contactId, String name,
			BigDecimal monetaryValue, String stageId, String expectedCloseDate,
			Map<String, String> customFields) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("pipelineId", pipelineId);
		body.put("contactId", contactId);
		body.put("status", "open");
		body.put("name", name);
		if (monetaryValue != null) {
			body.put("monetaryValue", monetaryValue);
		}
		putIfPresent(body, "pipelineStageId", stageId);
		putIfPresent(body, "forecastExpectedCloseDate", expectedCloseDate);

		// GHL takes custom fields as a list of {id, fieldValue}, keyed by the LOCATION's own field
		// ids. Nothing here knows or hardcodes them — `GhlCustomFieldClient` reads the definitions
		// and the caller passes back ids it got from that read, so a location that renames or adds
		// a field needs no change here. Blank values are dropped rather than sent: GHL stores an
		// empty string as an answer, and "not asked" and "answered with nothing" are different.
		if (customFields != null && !customFields.isEmpty()) {
			List<Map<String, String>> fields = customFields.entrySet().stream()
					.filter((entry) -> entry.getValue() != null && !entry.getValue().isBlank())
					.map((entry) -> Map.of("id", entry.getKey(), "fieldValue", entry.getValue()))
					.toList();
			if (!fields.isEmpty()) {
				body.put("customFields", fields);
			}
		}

		OpportunityEnvelope response = http.post(OpportunityEnvelope.class,
				(uri) -> uri.path("/opportunities/").build(), body);
		OpportunityRow row = require(response == null ? null : response.opportunity(), "opportunity");

		// Always CREATED: unlike upsert there is no `new` flag to read, because a create that
		// returned an existing row would be an upsert.
		audit.recordEvent("GHL_OPPORTUNITY", auditKey("GHL_OPPORTUNITY", row.id()),
				AuditAction.CREATED, actor(), null,
				Map.of("ghlOpportunityId", row.id(), "ghlPipelineId", pipelineId));

		return new UpsertedOpportunity(row.id(), row.contactId(), pipelineId, row.pipelineStageId(),
				row.status(), row.name(), row.monetaryValue(), true);
	}

	/**
	 * Marks a follow-up done in GHL.
	 *
	 * <p>Its own endpoint rather than a field on the task update, which is GHL's shape and worth
	 * following: completing is the common act and it takes one call rather than a read-modify-write
	 * that could clobber a title somebody edited in GHL meanwhile.
	 */
	public void completeFollowUp(String contactId, String taskId, String opportunityId,
			String pipelineId) {
		http.put(Object.class,
				(uri) -> uri.path("/contacts/{contactId}/tasks/{taskId}/completed")
						.build(contactId, taskId),
				Map.of("completed", true));

		audit.recordEvent("GHL_OPPORTUNITY", auditKey("GHL_OPPORTUNITY", opportunityId),
				AuditAction.UPDATED, actor(), null,
				Map.of("ghlOpportunityId", opportunityId, "ghlPipelineId", pipelineId,
						"ghlTaskId", taskId, "followUp", "completed"));
	}

	/**
	 * Changes an opportunity's own fields — the valuation, the name, the stage.
	 *
	 * <p>{@code PUT}, not upsert: this names an opportunity that already exists, so there is
	 * nothing to deduplicate and the id is the key.
	 */
	public UpsertedOpportunity updateOpportunity(String opportunityId, String pipelineId, String name,
			BigDecimal monetaryValue, String stageId) {
		Map<String, Object> body = new LinkedHashMap<>();
		putIfPresent(body, "name", name);
		putIfPresent(body, "pipelineStageId", stageId);
		if (monetaryValue != null) {
			body.put("monetaryValue", monetaryValue);
		}

		OpportunityEnvelope response = http.put(OpportunityEnvelope.class,
				(uri) -> uri.path("/opportunities/{id}").build(opportunityId), body);
		OpportunityRow row = require(response == null ? null : response.opportunity(), "opportunity");

		audit.recordEvent("GHL_OPPORTUNITY", auditKey("GHL_OPPORTUNITY", opportunityId), AuditAction.UPDATED, actor(), null,
				Map.of("ghlOpportunityId", opportunityId, "ghlPipelineId", pipelineId));

		return new UpsertedOpportunity(row.id(), row.contactId(), pipelineId, row.pipelineStageId(),
				row.status(), row.name(), row.monetaryValue(), false);
	}

	/**
	 * Moves an opportunity to a stage, and optionally to another pipeline.
	 *
	 * <p><strong>A pipeline move is an update in place — the id does not change.</strong> That is
	 * what makes the note stream survive the marketing-to-sales handoff without a migration
	 * step, and it was Unit 40's gating check: {@code PUT /opportunities/{id}} accepts a
	 * {@code pipelineId}, so GHL treats the pipeline as a mutable field rather than as identity.
	 */
	public UpsertedOpportunity moveStage(String opportunityId, String pipelineId, String stageId) {
		return updateOpportunity(opportunityId, pipelineId, null, null, stageId);
	}

	/**
	 * Marks an opportunity won, lost or abandoned.
	 *
	 * <p><strong>Winning does NOT create a case, and must never be made to.</strong> EvalOS tells
	 * GHL the deal is won and waits for the {@code opportunity.won} webhook, which is Handoff A
	 * and the only door a case enters custody through (invariant 8). The delay between the two is
	 * real and the screen shows it as pending — a "helpful" local case creation here would be a
	 * second intake path racing the webhook, and {@code DomainInvariantsTest} refuses the shape.
	 */
	public UpsertedOpportunity setStatus(String opportunityId, String pipelineId, String status) {
		Map<String, Object> body = Map.of("status", status);

		OpportunityEnvelope response = http.put(OpportunityEnvelope.class,
				(uri) -> uri.path("/opportunities/{id}/status").build(opportunityId), body);
		OpportunityRow row = require(response == null ? null : response.opportunity(), "opportunity");

		audit.recordEvent("GHL_OPPORTUNITY", auditKey("GHL_OPPORTUNITY", opportunityId),
				AuditAction.STAGE_CHANGED, actor(), null,
				Map.of("ghlOpportunityId", opportunityId, "ghlPipelineId", pipelineId, "status", status));

		return new UpsertedOpportunity(row.id(), row.contactId(), pipelineId, row.pipelineStageId(),
				row.status(), row.name(), row.monetaryValue(), false);
	}

	/**
	 * Sets a follow-up as a <strong>GHL task</strong>, not an EvalOS reminder.
	 *
	 * <p><strong>Deliberately GHL's, and it needs no new grant.</strong> GHL has tasks, its
	 * automation can act on them, and they appear where the rest of the business already looks.
	 * An EvalOS-side reminder would need the {@code job} package, would duplicate something that
	 * exists, and would <em>reach nobody</em> — invariant 14: EvalOS has no outbound channel.
	 * {@code POST /contacts/{contactId}/tasks} needs only {@code contacts.write}, which is
	 * already granted, so this ships without waiting on the {@code calendars/*} scopes that hold
	 * up meetings.
	 *
	 * <p>Hung off the contact because that is where GHL puts tasks. The opportunity is named in
	 * the title so the task is legible over there, which is the same reasoning that keeps notes
	 * on the EvalOS side: GHL's task has nowhere to record which deal it belongs to.
	 */
	public String createFollowUp(String contactId, String opportunityId, String pipelineId, String title,
			String dueAt, String note, String assignedUserId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		body.put("dueDate", dueAt);
		body.put("completed", false);
		// GHL calls it `body`; the desk calls it a note. Optional, and absent rather than blank —
		// an empty string is an answer and "not written" is not.
		putIfPresent(body, "body", note);
		// Absent means GHL's own default assignment. A guess would put the reminder on the wrong
		// person's list, which is worse than leaving it where GHL puts it.
		putIfPresent(body, "assignedTo", assignedUserId);

		TaskEnvelope response = http.post(TaskEnvelope.class,
				(uri) -> uri.path("/contacts/{contactId}/tasks").build(contactId), body);
		TaskRow task = require(response == null ? null : response.task(), "task");

		audit.recordEvent("GHL_OPPORTUNITY", auditKey("GHL_OPPORTUNITY", opportunityId),
				AuditAction.CHASED, actor(), null,
				Map.of("ghlOpportunityId", opportunityId, "ghlPipelineId", pipelineId,
						"ghlTaskId", task.id(), "dueAt", dueAt));

		return task.id();
	}

	/** The staff member responsible, or null outside a request — the audit contract's own rule. */
	private static UUID actor() {
		return TenantContext.find().map(TenantContext::memberId).orElse(null);
	}

	/**
	 * A stable {@code audit_event.object_id} for an object that lives in GHL.
	 *
	 * <p><strong>{@code audit_event.object_id} is {@code uuid NOT NULL} and a GHL id is an opaque
	 * string, so one of them had to give.</strong> Widening the column was the alternative and
	 * was rejected: {@code audit_event} is append-only with a trigger that refuses UPDATE, so
	 * changing its shape is the most expensive migration in the schema, to serve a foreign id
	 * that is already recorded verbatim in the payload beside it.
	 *
	 * <p>So the id is <em>derived</em>, deterministically: every row about one GHL opportunity
	 * shares an {@code object_id}, which is what makes {@code idx_audit_object} still answer
	 * "show me this deal's history".
	 *
	 * <p><strong>Namespaced by object type, and that was a bug before a test caught it.</strong>
	 * The first version hashed {@code "ghl:" + id} for both contacts and opportunities — an
	 * identical prefix, so a contact and an opportunity sharing an id (which GHL does not
	 * forbid) hashed to the same {@code object_id} and their histories merged. The comment
	 * claimed the prefix prevented exactly that. It did not; the type does.
	 *
	 * <p><strong>This is an audit key, not an identity, and the distinction is invariant 7's.</strong>
	 * EvalOS still mints no {@code ghl_contact_id} and no {@code ghl_opportunity_id} — nothing
	 * outside this method ever sees this value, no column stores it, and no request carries it.
	 * The real id is in {@code after_snapshot}, which is what anyone reading the trail will use.
	 */
	private static UUID auditKey(String objectType, String ghlId) {
		return UUID.nameUUIDFromBytes((objectType + ':' + ghlId).getBytes(StandardCharsets.UTF_8));
	}

	private static void putIfPresent(Map<String, Object> body, String key, String value) {
		if (value != null && !value.isBlank()) {
			body.put(key, value);
		}
	}

	/**
	 * A write that came back without the thing it was supposed to create is a 502, not a null.
	 *
	 * <p>The alternative is returning a record full of nulls and letting the caller write a row
	 * naming no opportunity — which is the one outcome worse than the write having failed,
	 * because it looks like success.
	 */
	private static <T> T require(T value, String what) {
		if (value == null) {
			throw new GhlUnavailableException("GHL accepted the write but returned no " + what);
		}
		return value;
	}

	// --- wire shapes -----------------------------------------------------------------

	record ContactEnvelope(ContactRow contact) {
	}

	record ContactRow(String id, String contactName, String email, String phone) {
	}

	/**
	 * {@code new} is present on the upsert response and absent on the update one.
	 *
	 * <p><strong>The {@code @JsonProperty} is load-bearing.</strong> {@code new} is a Java
	 * keyword, so the component has to be called something else — and without the mapping
	 * Jackson looks for {@code isNew}, finds nothing, and leaves it null. Every upsert then
	 * reports "matched an existing deal" no matter what GHL said, which is a wrong audit action
	 * and a wrong message to the marketer, with nothing failing.
	 */
	record OpportunityEnvelope(OpportunityRow opportunity, @JsonProperty("new") Boolean isNew) {
	}

	record OpportunityRow(String id, String name, String contactId, String pipelineStageId, String status,
			BigDecimal monetaryValue) {
	}

	record TaskEnvelope(TaskRow task) {
	}

	record TaskRow(String id, String title, String dueDate) {
	}
}
