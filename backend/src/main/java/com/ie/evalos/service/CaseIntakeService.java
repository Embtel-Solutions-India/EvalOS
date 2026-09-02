package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ClientType;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.ServiceSubtype;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SourceChannel;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.VisaCategory;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handoff A: the only thing in EvalOS that creates a case (invariant 8). It is
 * reachable from the inbound GHL webhook and from nowhere else — no REST endpoint
 * calls it, and adding one would break the invariant, not just the design.
 *
 * <p>The brand arrives as an argument because the caller resolved it from the
 * per-brand endpoint token. That is the only trustworthy source at this point:
 * there is no authenticated principal, and the payload's own idea of which brand it
 * belongs to is never consulted.
 */
@Service
public class CaseIntakeService {

	private static final String OBJECT_TYPE = "CASE";

	/** The GHL contact on the won opportunity, already mapped off the wire. */
	public record ContactDetails(
			String ghlContactId,
			String fullName,
			String email,
			String phone,
			String company,
			ClientType clientType,
			SourceChannel sourceChannel,
			String utmSource,
			String utmMedium,
			String utmCampaign) {
	}

	/**
	 * What a won GHL opportunity says the case is. Transport-agnostic on purpose.
	 *
	 * <p>There is no {@code paid} flag: every case created here is paid, because GHL
	 * invoices and collects before an opportunity is marked Won. {@code dealValue} is
	 * therefore the amount actually collected, and GHL is its source of truth — which is
	 * why {@link #refresh} overwrites it rather than only filling it.
	 */
	public record NewCase(
			ContactDetails contact,
			ServiceType serviceType,
			ServiceSubtype serviceSubtype,
			VisaCategory visaCategory,
			UUID selectedExpertId,
			String ghlOpportunityId,
			BigDecimal dealValue,
			Instant deadline,
			String invoiceRef,
			String campaignAttribution,
			/**
			 * Whatever sales wrote on the opportunity (Unit 23). Optional, and it is not stored
			 * on the case — it becomes the {@code note} on the {@code CREATED} audit row, so it
			 * is the first thing on the case's Notes &amp; timeline and reads as the handover it
			 * is. Null or blank simply leaves the row's note empty, which is the normal case; a
			 * required field here would fail Handoff A over a nicety.
			 */
			String notes) {
	}

	private final CaseRepository cases;
	private final ContactSnapshotRepository contacts;
	private final DocumentChecklistItemRepository checklistItems;
	private final AuditService audit;
	private final SlaCalculator sla;
	private final ApplicationEventPublisher events;

	CaseIntakeService(CaseRepository cases, ContactSnapshotRepository contacts,
			DocumentChecklistItemRepository checklistItems, AuditService audit, SlaCalculator sla,
			ApplicationEventPublisher events) {
		this.cases = cases;
		this.contacts = contacts;
		this.checklistItems = checklistItems;
		this.audit = audit;
		this.sla = sla;
		this.events = events;
	}

	/**
	 * One transaction, and create-or-update rather than create: sync the contact, then
	 * either refresh the case this contact already has open for this service or open a
	 * new one. Any failure rolls all of it back, so a redelivery starts from nothing.
	 *
	 * <p>One open case per contact per service. A contact buying a second service opens
	 * a second case; a contact coming back after the first one closed opens a new one.
	 */
	@Transactional
	public Case intake(Brand brand, NewCase request) {
		ContactSnapshot contact = syncContact(brand.getId(), request.contact());

		Optional<Case> open = cases
				.findFirstByBrandIdAndContactIdAndServiceTypeAndCurrentStageNotOrderByCreatedAtDesc(
						brand.getId(), contact.getId(), request.serviceType(), Stage.CLOSED);
		if (open.isPresent()) {
			return refresh(brand, open.get(), request);
		}
		return create(brand, request, contact.getId());
	}

	/**
	 * A repeat delivery for a case that is already open. The contact snapshot has just
	 * been re-synced; beyond that this only fills in what is still blank.
	 *
	 * <p>It deliberately cannot move the case. A GHL workflow re-firing must not reset a
	 * stage, drop an assignment, or un-pay a case — so stage, assignment and {@code paid}
	 * are never touched here, and no lifecycle event is published because nothing in the
	 * lifecycle happened.
	 *
	 * <p><strong>The won opportunity is the one exception, and it overwrites.</strong> With no
	 * manual payment path left, this is the only writer that could ever correct an amount
	 * after creation — fill-only would freeze the first figure forever with nothing able to
	 * fix it, and {@code deal_value} feeds revenue recognition. GHL is the source of truth
	 * for the amount, so the latest won-opportunity figure wins. Still one value and never
	 * a running total, so a correction cannot double-count.
	 *
	 * <p><strong>{@code dealValue} and {@code ghlOpportunityId} move together, always.</strong>
	 * They are two halves of one fact — this deal, for this money — and they arrive in the same
	 * delivery, so writing one without the other is what lets a case carry opp-B's amount under
	 * opp-A's id. Unit 18 closes the opportunity named by that column, so a stale id closes the
	 * wrong deal in GHL and leaves the paid one open. If the incoming id is already on another
	 * open case in this brand, {@code V24} refuses the write, which is correct: one opportunity
	 * is one case.
	 */
	private Case refresh(Brand brand, Case subject, NewCase request) {
		CaseLifecycleService.CaseSnapshot before = CaseLifecycleService.CaseSnapshot.of(subject);
		if (subject.getDeadline() == null) {
			subject.setDeadline(request.deadline());
		}
		if (subject.getVisaCategory() == null) {
			subject.setVisaCategory(request.visaCategory());
		}
		if (subject.getServiceSubtype() == null) {
			subject.setServiceSubtype(request.serviceSubtype());
		}
		if (subject.getCampaignAttribution() == null) {
			subject.setCampaignAttribution(request.campaignAttribution());
		}
		// Recorded before the write, because afterwards there is nothing left to compare: the
		// snapshot either side of this deliberately omits deal_value (it is role-restricted, and
		// CaseTimelineService surfaces the note to every role that may read the case), so an
		// amount correction would otherwise produce an UPDATED row whose before and after are
		// byte-identical — a money change that reads as a no-op edit. The note says *that* the
		// figure moved and never what it moved to; the figures themselves are in the
		// append-only webhook_event archive, which holds the raw payload of every delivery.
		//
		// Only when the delivery actually carries the deal, which GHL's Custom Webhook does
		// not unless the workflow author added it to customData. A delivery that says nothing
		// about the money must not erase what an earlier one said — that would blank the
		// amount *and* the opportunity id Unit 18 needs to close the deal.
		boolean amountCorrected = false;
		if (request.dealValue() != null || request.ghlOpportunityId() != null) {
			amountCorrected = request.dealValue() != null && subject.getDealValue() != null
					&& request.dealValue().compareTo(subject.getDealValue()) != 0;
			subject.setDealValue(request.dealValue());
			subject.setGhlOpportunityId(request.ghlOpportunityId());
		}

		Case saved = cases.save(subject);
		audit.recordSystemEvent(brand.getId(), OBJECT_TYPE, saved.getId(), AuditAction.UPDATED,
				before, CaseLifecycleService.CaseSnapshot.of(saved, amountCorrected
						? "refreshed from GHL won opportunity — deal value corrected"
						: "refreshed from GHL won opportunity"));
		return saved;
	}

	/** A whitespace-only note is no note; the timeline should not draw empty quotation marks. */
	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}

	private Case create(Brand brand, NewCase request, UUID contactId) {
		Case created = cases.save(newCase(brand, request, contactId));
		seedChecklist(created, request.serviceType());

		// The intake note rides on the CREATED row rather than into a column, because it is not a
		// fact about the case — it is the first thing somebody said about it, and the trail is
		// where things people said live (Unit 23).
		audit.recordSystemEvent(brand.getId(), OBJECT_TYPE, created.getId(), AuditAction.CREATED,
				null, CaseLifecycleService.CaseSnapshot.of(created, blankToNull(request.notes())));
		// Unit 06 listens for these: CASE_CREATED is the pool arrival ("assign a project
		// manager"), CHECKLIST_REQUESTED is GHL's to deliver. There is no separate paid
		// announcement any more, because a case can no longer exist before the money.
		// Nothing here decides who hears about it.
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CASE_CREATED, created));
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CHECKLIST_REQUESTED, created));
		return created;
	}

	/**
	 * Upsert by GHL contact id, falling back to email — a brand's second order from
	 * the same person must not create a second snapshot. Both lookups are
	 * brand-scoped by their signature.
	 */
	private ContactSnapshot syncContact(UUID brandId, ContactDetails details) {
		ContactSnapshot contact = existingContact(brandId, details)
				.orElseGet(() -> new ContactSnapshot(brandId, details.ghlContactId()));
		// Repairs a row that was matched by email because it had no GHL id. Without this
		// the id never lands and every later delivery re-matches by email — which works
		// until the email changes, and then it is a second contact again.
		contact.linkGhlContact(details.ghlContactId());
		contact.syncFromGhl(details.fullName(), details.email(), details.phone(), details.company(),
				details.clientType(), details.sourceChannel(), details.utmSource(), details.utmMedium(),
				details.utmCampaign());
		return contacts.save(contact);
	}

	/**
	 * Both lookups, in order of authority — <strong>not</strong> one or the other.
	 *
	 * <p>These used to be exclusive returns, which needed no race to duplicate a contact:
	 * the payload carries no {@code @NotBlank} on the GHL id, so a first delivery could
	 * store a snapshot with a null {@code ghl_contact_id}; a later delivery *with* the id
	 * then missed the id lookup, never reached the email one, and inserted a second
	 * snapshot — and therefore a second case for the same contact and service.
	 *
	 * <p>Falling through to email fixes the reading. {@code syncContact} then backfills
	 * the id onto the row it found, so the same delivery cannot keep re-matching by email
	 * forever.
	 *
	 * <p><strong>But email never outranks a GHL id</strong> (invariant 7), which is what
	 * {@link #contradicts} enforces. Two distinct GHL contacts can share an inbox — a firm's
	 * office address is the obvious case — and without the guard the second one's delivery
	 * matched the first one's row by email, could not backfill its own id over the id
	 * already there, and quietly attached a paid case to <em>the wrong client</em> while
	 * overwriting that client's name and phone. A wrong merge is worse than a duplicate:
	 * the duplicate is visible and fixable, the merge looks like a normal case.
	 */
	private Optional<ContactSnapshot> existingContact(UUID brandId, ContactDetails details) {
		return byGhlContactId(brandId, details.ghlContactId())
				.or(() -> byEmail(brandId, details.email())
						.filter(match -> !contradicts(match, details.ghlContactId())));
	}

	/**
	 * An email match that names a different client. Only a genuine conflict counts — both
	 * ids present and different — so the two cases the fall-through exists for still match:
	 * a row with no id yet (it gets backfilled), and a delivery with no id to assert.
	 *
	 * <p>Rejecting sends intake down the create path, and {@code V27} is what lets that
	 * insert land: the email uniqueness index now applies only to rows without a GHL id,
	 * because a row that has one does not need email to tell it apart.
	 */
	private static boolean contradicts(ContactSnapshot match, String incomingGhlContactId) {
		String held = match.getGhlContactId();
		return held != null && !held.isBlank()
				&& incomingGhlContactId != null && !incomingGhlContactId.isBlank()
				&& !held.equals(incomingGhlContactId);
	}

	private Optional<ContactSnapshot> byGhlContactId(UUID brandId, String ghlContactId) {
		return ghlContactId == null || ghlContactId.isBlank()
				? Optional.empty()
				: contacts.findByBrandIdAndGhlContactId(brandId, ghlContactId);
	}

	private Optional<ContactSnapshot> byEmail(UUID brandId, String email) {
		return email == null || email.isBlank()
				? Optional.empty()
				: contacts.findByBrandIdAndEmailIgnoreCase(brandId, email);
	}

	private Case newCase(Brand brand, NewCase request, UUID contactId) {
		Case created = new Case(brand.getId(), caseCode(brand), Stage.DOC_COLLECTION);
		created.setPoolStatus(PoolStatus.IN_POOL);
		created.setStageEnteredAt(Instant.now());
		created.setContactId(contactId);
		created.setServiceType(request.serviceType());
		created.setServiceSubtype(request.serviceSubtype());
		created.setVisaCategory(request.visaCategory());
		created.setClientType(request.contact().clientType());
		created.setDealValue(request.dealValue());
		created.setDeadline(request.deadline());
		created.setInvoiceRef(request.invoiceRef());
		created.setCampaignAttribution(request.campaignAttribution());
		created.setGhlOpportunityId(request.ghlOpportunityId());
		// Pre-selected during the sale; the PM still confirms availability at assignment.
		created.setExpertId(request.selectedExpertId());
		// Won is paid: GHL invoiced and collected before the opportunity was marked Won, so
		// the webhook is the proof and there is nothing for a human to record.
		created.setPaid(true);
		created.setPaidAt(Instant.now());
		created.setSlaStatus(sla.statusOf(created));
		return created;
	}

	private void seedChecklist(Case created, ServiceType serviceType) {
		for (String label : ChecklistTemplates.forService(serviceType)) {
			checklistItems.save(new DocumentChecklistItem(
					created.getBrandId(), created.getId(), label, ChecklistItemStatus.REQUIRED));
		}
	}

	/**
	 * Human-facing case id: brand initials, year, and six hex characters.
	 *
	 * <p>ponytail: random suffix rather than a per-brand sequence, which would need a
	 * counter table and a lock. A collision is caught by the unique constraint and
	 * surfaces as a retriable 5xx that the source redelivers; swap in a sequence if
	 * gapless numbering is ever asked for.
	 */
	private static String caseCode(Brand brand) {
		return "%s-%d-%s".formatted(
				brandPrefix(brand.getSlug()),
				Instant.now().atZone(ZoneOffset.UTC).getYear(),
				UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
	}

	/** {@code international-evaluations} → IE; {@code xpertsportal} → XP. */
	private static String brandPrefix(String slug) {
		List<String> words = Arrays.stream(slug.split("-")).filter(word -> !word.isBlank()).toList();
		if (words.size() > 1) {
			return words.stream().map(word -> word.substring(0, 1).toUpperCase()).collect(Collectors.joining());
		}
		return slug.substring(0, Math.min(2, slug.length())).toUpperCase();
	}
}
