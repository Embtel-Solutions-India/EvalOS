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
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceSubtype;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SourceChannel;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.domain.VisaCategory;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.NotificationRepository;
import com.ie.evalos.repository.TeamMemberRepository;

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

	/** What a confirmed payment says the case is. Transport-agnostic on purpose. */
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
			String campaignAttribution) {
	}

	private final CaseRepository cases;
	private final ContactSnapshotRepository contacts;
	private final DocumentChecklistItemRepository checklistItems;
	private final NotificationRepository notifications;
	private final TeamMemberRepository teamMembers;
	private final AuditService audit;
	private final SlaCalculator sla;
	private final ApplicationEventPublisher events;

	CaseIntakeService(CaseRepository cases, ContactSnapshotRepository contacts,
			DocumentChecklistItemRepository checklistItems, NotificationRepository notifications,
			TeamMemberRepository teamMembers, AuditService audit, SlaCalculator sla,
			ApplicationEventPublisher events) {
		this.cases = cases;
		this.contacts = contacts;
		this.checklistItems = checklistItems;
		this.notifications = notifications;
		this.teamMembers = teamMembers;
		this.audit = audit;
		this.sla = sla;
		this.events = events;
	}

	/**
	 * One transaction: sync the contact, create the case in the pool, open its
	 * checklist, notify the pool, write the audit row, publish the two events. Any
	 * failure rolls all of it back, so a redelivery starts from nothing.
	 */
	@Transactional
	public Case intake(Brand brand, NewCase request) {
		ContactSnapshot contact = syncContact(brand.getId(), request.contact());
		Case created = cases.save(newCase(brand, request, contact.getId()));

		seedChecklist(created, request.serviceType());
		notifyPool(brand, created);

		audit.recordSystemEvent(brand.getId(), OBJECT_TYPE, created.getId(), AuditAction.CREATED,
				null, CaseLifecycleService.CaseSnapshot.of(created));
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
		created.setSlaStatus(sla.statusOf(created));
		return created;
	}

	private void seedChecklist(Case created, ServiceType serviceType) {
		for (String label : ChecklistTemplates.forService(serviceType)) {
			checklistItems.save(new DocumentChecklistItem(
					created.getBrandId(), created.getId(), label, ChecklistItemStatus.REQUIRED));
		}
	}

	/** The pool is watched by the GM and by that brand's Brand Managers, nobody else. */
	private void notifyPool(Brand brand, Case created) {
		String body = "New paid case %s is in the %s pool and needs a project manager."
				.formatted(created.getCaseCode(), brand.getName());

		Stream.concat(
				teamMembers.findByActiveTrueAndRole(Role.GM).stream(),
				teamMembers.findByActiveTrueAndRoleAndBrandId(Role.BRAND_MANAGER, brand.getId()).stream())
				.map(TeamMember::getId)
				.forEach(recipient -> notifications.save(new Notification(
						brand.getId(), recipient, NotificationType.NEW_CASE_IN_POOL, created.getId(), body)));
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
