package com.ie.evalos.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.GhlCustomFieldRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A case's service type, read from its deal's <em>Service Requested</em> field (2026-10-30, D77's follow-up).
 *
 * <p><strong>Why EvalOS reads it.</strong> GHL's won webhook sends no service — its workflow cannot carry the
 * opportunity's dropdown — so every case opened as Credential evaluation with that checklist. The deal itself
 * holds the answer, and EvalOS already reads the deal: at intake (the win is confirmed against GHL), and in the
 * mirror every few minutes.
 *
 * <p><strong>Changing a type keeps what has happened.</strong> A checklist item that was sent, uploaded or
 * reviewed stays; an untouched one the new type does not need goes; the new type's missing ones are added.
 */
@Service
public class CaseServiceTypeService {

	/**
	 * GHL's "Service Requested" options → service type. ponytail: a static map, like
	 * {@link ChecklistTemplates}; it moves into the database when the business edits options without a deploy.
	 * Not mapped, so a case keeps its type: EB-1B Expert Opinion Letter, Cover Letter &amp; Support Letter,
	 * Immigration Business Plans, Pre-Filing Documentation Review (no matching type yet).
	 */
	private static final Map<String, ServiceType> BY_LABEL = Map.ofEntries(
			Map.entry("academic credential evaluation", ServiceType.ACADEMIC_EQUIVALENCY),
			Map.entry("course-by-course evaluation", ServiceType.CREDENTIAL_EVALUATION),
			Map.entry("work experience evaluation", ServiceType.EXPERIENCE_BASED_EQUIVALENCY),
			Map.entry("h-1b expert opinion letter", ServiceType.EXPERT_OPINION_LETTER),
			Map.entry("o-1a / o-1b expert opinion letter", ServiceType.O1A_VISA),
			Map.entry("eb-1a expert opinion letter", ServiceType.EB1A_SUPPORT_LETTER),
			Map.entry("eb-2 niw expert opinion letter", ServiceType.EB2_NIW_LETTER),
			Map.entry("l-1a / l-1b expert opinion letter", ServiceType.L1A_VISA),
			Map.entry("tn visa expert opinion letter", ServiceType.TN_VISA),
			Map.entry("h-1b wage level letter", ServiceType.WAGE_LEVEL_LETTER),
			Map.entry("certified translation services", ServiceType.TRANSLATION),
			Map.entry("rfe response", ServiceType.RFE_RESPONSE),
			Map.entry("perm recruitment & advertising", ServiceType.PERM));

	private static final String OBJECT_TYPE = "CASE";

	/** What intake opens a case as when nothing names its service — {@code GhlOpportunityHandler.DEFAULT_SERVICE}. */
	private static final ServiceType INTAKE_FALLBACK = ServiceType.CREDENTIAL_EVALUATION;

	private final CaseRepository cases;
	private final CaseLifecycleService lifecycle;
	private final DocumentChecklistItemRepository checklistItems;
	private final GhlCustomFieldRepository customFields;
	private final OpportunityMirrorService opportunities;
	private final AuditService audit;

	CaseServiceTypeService(CaseRepository cases, CaseLifecycleService lifecycle,
			DocumentChecklistItemRepository checklistItems, GhlCustomFieldRepository customFields,
			OpportunityMirrorService opportunities, AuditService audit) {
		this.cases = cases;
		this.lifecycle = lifecycle;
		this.checklistItems = checklistItems;
		this.customFields = customFields;
		this.opportunities = opportunities;
		this.audit = audit;
	}

	public record Change(ServiceType from, ServiceType to) {
	}

	static Optional<ServiceType> fromLabel(String label) {
		return label == null ? Optional.empty()
				: Optional.ofNullable(BY_LABEL.get(label.trim().toLowerCase(Locale.ROOT)));
	}

	/** What a deal's Service Requested says, given its custom field values by GHL field id. Brand-scoped. */
	public Optional<ServiceType> requestedOn(UUID brandId, Map<String, String> valuesByFieldId) {
		return customFields.findByBrandIdAndModelOrderByNameAsc(brandId, ReferenceMirrorService.OPPORTUNITY_MODEL)
				.stream()
				.filter((field) -> OpportunityBoardService.SERVICE_FIELD.equals(field.getFieldKey()))
				.map((field) -> valuesByFieldId.get(field.getGhlId()))
				.map(CaseServiceTypeService::fromLabel)
				.flatMap(Optional::stream)
				.findFirst();
	}

	/**
	 * The sweep's half: a case still on intake's fallback type, still collecting documents, whose checklist
	 * nobody has touched, follows its deal. Only the fallback, so a type a PM chose is never overridden; and
	 * anything further along is the PM's call ({@link #change}), so a type never moves under work.
	 */
	@Transactional
	public boolean reconcile(Case subject) {
		if (subject.getServiceType() != INTAKE_FALLBACK || subject.getCurrentStage() != Stage.DOC_COLLECTION
				|| subject.getGhlOpportunityId() == null) {
			return false;
		}
		Optional<ServiceType> requested = opportunities.byGhlId(subject.getGhlOpportunityId())
				.filter((deal) -> subject.getBrandId().equals(deal.getBrandId()))
				.flatMap((deal) -> requestedOn(subject.getBrandId(), deal.getCustomFields()));
		if (requested.isEmpty() || requested.get() == subject.getServiceType()) {
			return false;
		}
		List<DocumentChecklistItem> items = checklistItems.findByCaseId(subject.getId());
		if (!items.stream().allMatch(CaseServiceTypeService::untouched)) {
			return false;
		}
		apply(subject, requested.get(), items, null);
		return true;
	}

	/** The PM's half: any open case, any type. Returns what changed. */
	@Transactional
	public Change change(UUID caseId, ServiceType target) {
		if (target == null) {
			throw new InvalidRequestException("Choose a service type");
		}
		Case subject = lifecycle.load(caseId);
		if (subject.getCurrentStage() == Stage.CLOSED) {
			throw new InvalidRequestException("A closed case's service type cannot be changed");
		}
		if (subject.getServiceType() == target) {
			throw new InvalidRequestException("The case is already that service type");
		}
		ServiceType from = subject.getServiceType();
		apply(subject, target, checklistItems.findByCaseId(subject.getId()), TenantContext.current().memberId());
		return new Change(from, target);
	}

	private void apply(Case subject, ServiceType target, List<DocumentChecklistItem> items, UUID actor) {
		List<String> template = ChecklistTemplates.forService(target);
		Set<String> kept = new HashSet<>();
		for (DocumentChecklistItem item : items) {
			if (untouched(item) && !template.contains(item.getLabel())) {
				checklistItems.delete(item);
			}
			else {
				kept.add(item.getLabel());
			}
		}
		for (String label : template) {
			if (!kept.contains(label)) {
				checklistItems.save(new DocumentChecklistItem(subject.getBrandId(), subject.getId(), label,
						ChecklistItemStatus.REQUIRED));
			}
		}
		ServiceType from = subject.getServiceType();
		subject.setServiceType(target);
		cases.save(subject);
		audit.recordEvent(OBJECT_TYPE, subject.getId(), AuditAction.UPDATED, actor,
				Map.of("serviceType", from), Map.of("serviceType", target));
	}

	/** Never sent to the client and still only required — nothing has happened on it. */
	private static boolean untouched(DocumentChecklistItem item) {
		return item.getStatus() == ChecklistItemStatus.REQUIRED && !item.isSent();
	}
}
