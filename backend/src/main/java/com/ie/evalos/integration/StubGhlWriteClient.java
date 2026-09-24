package com.ie.evalos.integration;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Every GHL <strong>write</strong>, answered locally instead of sent — development only.
 *
 * <p><strong>Why this exists.</strong> A laptop runs against the real GHL location
 * ({@code WY6bW2xUCI8Tz8gw7aLJ}) because that is the only place the pipelines, stages and custom
 * fields actually are — so reads have to be live for anything to work. But the writes land in the
 * business's real CRM: opening a request from the portal creates a real opportunity a salesperson
 * then finds. Exercising the intake flow end to end therefore meant polluting production data, and
 * the alternative — not exercising it — is how Unit 53 went a day without anyone seeing it work.
 *
 * <p><strong>Reads stay live; only writes stop.</strong> That is the whole posture and it is a
 * coherent one: the mirror still fills from the real location, boards still draw real deals, and
 * nothing this class touches leaves the machine. A stub that faked reads too would be a second
 * fixture to maintain and would stop catching the shape mismatches that live reads catch (Unit
 * 47b's {@code notes} envelope was exactly that kind of bug).
 *
 * <p><strong>The ids are obviously fake, on purpose.</strong> {@code stub-…} rather than a
 * plausible 24-character GHL id, so a mirror row carrying one is recognisable at a glance and
 * nobody spends an afternoon looking for it in GHL. They are still unique, so the correlation key,
 * the outbox and the mirror all behave exactly as they would against real ids.
 *
 * <p><strong>It refuses to start outside the {@code local} profile</strong>, and that guard is the
 * price of admission. A deployment running this would accept every desk edit, every portal request
 * and every close, report success, and send none of it — the CRM and EvalOS would diverge silently
 * and permanently, with no failed request anywhere to notice. That is a worse failure than any
 * outage this system is designed to survive.
 */
@Component
@Primary
@ConditionalOnProperty(name = "evalos.ghl.write-mode", havingValue = "stub")
public class StubGhlWriteClient extends GhlWriteClient {

	private static final Logger log = LoggerFactory.getLogger(StubGhlWriteClient.class);

	StubGhlWriteClient(GhlHttp http, com.ie.evalos.service.AuditService audit, Environment environment) {
		super(http, audit);
		if (!environment.acceptsProfiles(Profiles.of("local"))) {
			throw new IllegalStateException(
					"evalos.ghl.write-mode is 'stub' outside the 'local' profile. Every CRM write "
							+ "would be accepted, reported as successful and silently discarded. Set it "
							+ "to 'live'.");
		}
		log.warn("GHL WRITES ARE STUBBED. Reads still reach the live location; nothing is written to "
				+ "it. Set EVALOS_GHL_WRITE_MODE=live to write for real.");
	}

	/** A fake id that cannot be mistaken for GHL's, and cannot collide with another fake one. */
	private static String stubId(String what) {
		return "stub-" + what + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Override
	public UpsertedContact upsertContact(String firstName, String lastName, String email, String phone,
			String source) {
		String id = stubId("contact");
		log.info("STUB upsertContact -> {} ({} {}, {})", id, firstName, lastName, email);
		String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName))
				.strip();
		return new UpsertedContact(id, name.isEmpty() ? email : name, email, phone);
	}

	@Override
	public UpsertedOpportunity upsertOpportunity(String pipelineId, String contactId, String name,
			BigDecimal monetaryValue) {
		String id = stubId("opp");
		log.info("STUB upsertOpportunity -> {} on pipeline {} for contact {}", id, pipelineId, contactId);
		// `isNew` true: an upsert that matched an existing deal is a real outcome the marketing desk
		// reports, but a stub has no history to match against and claiming a match would be a lie
		// with a visible consequence on screen.
		return new UpsertedOpportunity(id, contactId, pipelineId, null, "open", name, monetaryValue,
				true);
	}

	@Override
	public UpsertedOpportunity createOpportunity(String pipelineId, String contactId, String name,
			BigDecimal monetaryValue, String stageId, String expectedCloseDate,
			Map<String, String> customFields) {
		String id = stubId("opp");
		log.info("STUB createOpportunity -> {} on pipeline {} for contact {} ({} custom field(s))", id,
				pipelineId, contactId, customFields == null ? 0 : customFields.size());
		return new UpsertedOpportunity(id, contactId, pipelineId, stageId, "open", name, monetaryValue,
				true);
	}

	@Override
	public UpsertedOpportunity updateOpportunity(String opportunityId, String pipelineId, String name,
			BigDecimal monetaryValue, String stageId) {
		log.info("STUB updateOpportunity {} (name={}, amount={}, stage={})", opportunityId, name,
				monetaryValue, stageId);
		// `moveStage` delegates here, so it is stubbed by this override rather than by its own.
		return new UpsertedOpportunity(opportunityId, null, pipelineId, stageId, "open", name,
				monetaryValue, false);
	}

	@Override
	public UpsertedOpportunity setStatus(String opportunityId, String pipelineId, String status) {
		log.info("STUB setStatus {} -> {}", opportunityId, status);
		return new UpsertedOpportunity(opportunityId, null, pipelineId, null, status, null, null, false);
	}

	@Override
	public void setOpportunityFields(String opportunityId, Map<String, String> customFields) {
		log.info("STUB setOpportunityFields {} ({} field(s))", opportunityId,
				customFields == null ? 0 : customFields.size());
	}

	@Override
	public String createFollowUp(String contactId, String opportunityId, String pipelineId, String title,
			String dueAt, String note, String assignedUserId) {
		String id = stubId("task");
		log.info("STUB createFollowUp -> {} for contact {} due {}", id, contactId, dueAt);
		return id;
	}

	/**
	 * <strong>Null, not a stub id: "not sent".</strong> The other stubs can hand back a fake id
	 * because what they write is mutable. A note's GHL id goes into
	 * {@code opportunity_note_ghl_link}, which is append-only, so a fake one would permanently mark
	 * the note delivered — the deal page would claim it is in GHL, and switching to live would never
	 * send it. The outbox links nothing on a null. (First seen 2026-09-24, which left one
	 * {@code stub-note-…} link behind; {@link #isStubId} keeps that one from counting.)
	 */
	@Override
	public String addContactNote(String contactId, String ghlOpportunityId, String title, String body) {
		log.info("STUB addContactNote on contact {} — not sent, so not linked", contactId);
		return null;
	}

	@Override
	public void updateContactNote(String contactId, String ghlNoteId, String title, String body) {
		log.info("STUB updateContactNote {} on contact {} — not sent", ghlNoteId, contactId);
	}

	@Override
	public void deleteContactNote(String contactId, String ghlNoteId) {
		log.info("STUB deleteContactNote {} on contact {} — not sent", ghlNoteId, contactId);
	}

	/** Whether a recorded GHL id was minted by this stub rather than by GHL. */
	public static boolean isStubId(String ghlId) {
		return ghlId != null && ghlId.startsWith("stub-");
	}

	@Override
	public void completeFollowUp(String contactId, String taskId, String opportunityId,
			String pipelineId) {
		log.info("STUB completeFollowUp {} on contact {}", taskId, contactId);
	}
}
