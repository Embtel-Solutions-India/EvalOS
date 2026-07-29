package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ClientType;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.NotificationType;
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

	/** The GHL contact, already mapped off the wire. */
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
	 * What an inbound GHL contact says the case is. Transport-agnostic on purpose.
	 *
	 * <p>{@code dealValue} is a quote, not a payment — it may be null, and it is
	 * {@code markPaid} that records the amount actually taken. {@code paid} is only
	 * true when GHL already knows the contact paid.
	 */
	public record NewCase(
			ContactDetails contact,
			ServiceType serviceType,
			ServiceSubtype serviceSubtype,
			VisaCategory visaCategory,
			UUID selectedExpertId,
			BigDecimal dealValue,
			Instant deadline,
			String driveLink,
			String invoiceRef,
			String campaignAttribution,
			boolean paid) {
	}

	private final CaseRepository cases;
	private final ContactSnapshotRepository contacts;
	private final DocumentChecklistItemRepository checklistItems;
	private final AuditService audit;
	private final SlaCalculator sla;
	private final PoolNotifier pool;
	private final ApplicationEventPublisher events;

	CaseIntakeService(CaseRepository cases, ContactSnapshotRepository contacts,
			DocumentChecklistItemRepository checklistItems, AuditService audit, SlaCalculator sla,
			PoolNotifier pool, ApplicationEventPublisher events) {
		this.cases = cases;
		this.contacts = contacts;
		this.checklistItems = checklistItems;
		this.audit = audit;
		this.sla = sla;
		this.pool = pool;
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
	 * stage, drop an assignment, or un-pay a case that somebody has since paid — so
	 * stage, assignment and {@code paid} are never touched here, and no lifecycle event
	 * is published because nothing in the lifecycle happened.
	 */
	private Case refresh(Brand brand, Case subject, NewCase request) {
		CaseLifecycleService.CaseSnapshot before = CaseLifecycleService.CaseSnapshot.of(subject);
		if (subject.getDeadline() == null) {
			subject.setDeadline(request.deadline());
		}
		if (subject.getDriveLink() == null) {
			subject.setDriveLink(request.driveLink());
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
		if (subject.getDealValue() == null) {
			subject.setDealValue(request.dealValue());
		}
		Case saved = cases.save(subject);
		audit.recordSystemEvent(brand.getId(), OBJECT_TYPE, saved.getId(), AuditAction.UPDATED,
				before, CaseLifecycleService.CaseSnapshot.of(saved, "refreshed from GHL contact"));
		return saved;
	}

	private Case create(Brand brand, NewCase request, UUID contactId) {
		Case created = cases.save(newCase(brand, request, contactId));
		seedChecklist(created, request.serviceType());

		audit.recordSystemEvent(brand.getId(), OBJECT_TYPE, created.getId(), AuditAction.CREATED,
				null, CaseLifecycleService.CaseSnapshot.of(created));
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CASE_CREATED, created));
		events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CHECKLIST_REQUESTED, created));

		// A lead is not a pool arrival. The GM and Brand Manager hear about it, but the
		// alert that says "assign a PM" is raised by markPaid, when there is money.
		pool.alert(brand.getId(), created.getId(), NotificationType.NEW_LEAD,
				"New %s lead %s from %s.".formatted(brand.getName(), created.getCaseCode(),
						request.contact().fullName()));

		if (request.paid()) {
			// GHL already knew this contact had paid, so the case skips the lead state.
			events.publishEvent(CaseEvents.CaseEvent.of(CaseEvents.Type.CASE_PAID, created));
			pool.alert(brand.getId(), created.getId(), NotificationType.NEW_CASE_IN_POOL,
					"Case %s is paid and needs a project manager.".formatted(created.getCaseCode()));
		}
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
		contact.syncFromGhl(details.fullName(), details.email(), details.phone(), details.company(),
				details.clientType(), details.sourceChannel(), details.utmSource(), details.utmMedium(),
				details.utmCampaign());
		return contacts.save(contact);
	}

	private Optional<ContactSnapshot> existingContact(UUID brandId, ContactDetails details) {
		if (details.ghlContactId() != null && !details.ghlContactId().isBlank()) {
			return contacts.findByBrandIdAndGhlContactId(brandId, details.ghlContactId());
		}
		if (details.email() != null && !details.email().isBlank()) {
			return contacts.findByBrandIdAndEmailIgnoreCase(brandId, details.email());
		}
		return Optional.empty();
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
		created.setDriveLink(request.driveLink());
		created.setInvoiceRef(request.invoiceRef());
		created.setCampaignAttribution(request.campaignAttribution());
		// Pre-selected during the sale; the PM still confirms availability at assignment.
		created.setExpertId(request.selectedExpertId());
		created.setPaid(request.paid());
		if (request.paid()) {
			created.setPaidAt(Instant.now());
		}
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
