package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.domain.DocumentStatus;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What stands between a Case Manager's open cases and delivery: checklist items that are missing or
 * wrong, expert offers that are open or have come back, and where each draft sits.
 *
 * <p>The case set is {@link CaseManagerMetricsService#myCases()} — one scope read, no second predicate.
 * Items and offers are then loaded in batch for exactly those cases and their brands, so a case that is
 * not the caller's cannot appear. Read-only; nothing is stored.
 */
@Service
public class CaseManagerWorkService {

	/** Rows returned per list; the screen shows the worst first and the cap drops the rest. */
	static final int ROW_CAP = 25;

	private final CaseManagerMetricsService cm;
	private final DocumentChecklistItemRepository items;
	private final ExpertCaseOfferRepository offers;
	private final ExpertRepository experts;
	private final BrandRepository brands;
	private final BusinessCalendar calendar;

	private final com.ie.evalos.repository.CaseDocumentRepository documents;
	private final com.ie.evalos.repository.AuditEventRepository auditEvents;

	CaseManagerWorkService(CaseManagerMetricsService cm, DocumentChecklistItemRepository items,
			ExpertCaseOfferRepository offers, ExpertRepository experts, BrandRepository brands,
			BusinessCalendar calendar, com.ie.evalos.repository.CaseDocumentRepository documents,
			com.ie.evalos.repository.AuditEventRepository auditEvents) {
		this.documents = documents;
		this.auditEvents = auditEvents;
		this.cm = cm;
		this.items = items;
		this.offers = offers;
		this.experts = experts;
		this.brands = brands;
		this.calendar = calendar;
	}

	public record ChecklistCase(UUID caseId, String caseCode, int total, int approved, int uploaded,
			int required, int missing, int incorrect) {
	}

	/** {@code blockerItems} counts checklist items; {@code blockerCases} counts cases. Never added together. */
	public record Checklist(int blockerItems, int blockerCases, List<ChecklistCase> cases) {
	}

	/** {@code fee} is null when unpriced and {@code currency} null when the brand has none — never 0. */
	public record OfferRow(UUID caseId, String caseCode, String expertName, OfferOutcome outcome, BigDecimal fee,
			String currency, Instant offeredAt, long ageBusinessHours, String declineReason) {
	}

	/** {@code open} counts offers; {@code rematch} counts cases. */
	public record Offers(int open, int rematch, List<OfferRow> rows) {
	}

	/** Open cases, each in exactly one bucket, so the six sum to the open-case count. */
	public record Drafts(int beforeDraft, int drafting, int returned, int withPm, int withClient, int approved) {
	}

	/** A draft the PM sent back, with the PM's reason from the version they returned (2026-10-28). */
	public record ReturnedDraft(UUID caseId, String caseCode, String reason, Instant returnedAt) {
	}

	/** A case the expert put on hold because something is missing before they will sign (2026-10-28). */
	public record EvidenceRequest(UUID caseId, String caseCode, String missing, Instant askedAt) {
	}

	public record CaseManagerWork(Checklist checklist, Offers offers, Drafts drafts, List<ReturnedDraft> returned,
			List<EvidenceRequest> evidence) {
	}

	@Transactional(readOnly = true)
	public CaseManagerWork forCaller() {
		List<Case> open = cm.myCases().stream()
				.filter(c -> c.getCurrentStage() != Stage.DELIVERED && c.getCurrentStage() != Stage.CLOSED)
				.toList();
		if (open.isEmpty()) {
			return new CaseManagerWork(new Checklist(0, 0, List.of()), new Offers(0, 0, List.of()),
					new Drafts(0, 0, 0, 0, 0, 0), List.of(), List.of());
		}
		Set<UUID> brandIds = open.stream().map(Case::getBrandId).collect(Collectors.toSet());
		Set<UUID> caseIds = open.stream().map(Case::getId).collect(Collectors.toSet());
		Map<UUID, Case> byId = open.stream().collect(Collectors.toMap(Case::getId, c -> c));
		return new CaseManagerWork(checklist(brandIds, caseIds, byId), offers(open, brandIds, caseIds, byId),
				drafts(open), returned(open), evidence(open, brandIds));
	}

	/**
	 * Drafts the PM returned, longest-waiting first. The reason is the {@code review_comment} the return
	 * stamped on that version; the time is when the case re-entered drafting, which the return set.
	 */
	private List<ReturnedDraft> returned(List<Case> open) {
		List<Case> back = open.stream()
				.filter(c -> c.getCurrentStage() == Stage.DRAFT_IN_PROGRESS
						&& c.getPmApprovalStatus() == PmApprovalStatus.RETURNED)
				.sorted(Comparator.comparing(Case::getStageEnteredAt, Comparator.nullsLast(Comparator.naturalOrder())))
				.limit(ROW_CAP)
				.toList();
		Map<UUID, String> reasons = new HashMap<>();
		for (Map.Entry<UUID, List<Case>> byBrand : back.stream().collect(Collectors.groupingBy(Case::getBrandId)).entrySet()) {
			Set<UUID> ids = byBrand.getValue().stream().map(Case::getId).collect(Collectors.toSet());
			documents.findByBrandIdAndCaseIdInAndKind(byBrand.getKey(), ids, DocumentKind.DRAFT).stream()
					.filter(d -> d.getStatus() == DocumentStatus.RETURNED)
					.sorted(Comparator.comparingInt(CaseDocument::getVersion).reversed())
					.forEach(d -> reasons.putIfAbsent(d.getCaseId(), d.getReviewComment()));
		}
		return back.stream()
				.map(c -> new ReturnedDraft(c.getId(), c.getCaseCode(), reasons.get(c.getId()), c.getStageEnteredAt()))
				.toList();
	}

	/**
	 * Cases on hold because the expert asked for more evidence. Found the way the case screen finds it: the
	 * expert's own audit row that put the case on {@code ON_HOLD_AWAITING_CLIENT}, its note being what is
	 * missing. A hold someone else raised is not an evidence request and is not listed.
	 */
	private List<EvidenceRequest> evidence(List<Case> open, Set<UUID> brandIds) {
		Map<UUID, Case> held = open.stream()
				.filter(c -> c.getExceptionState() == ExceptionState.ON_HOLD_AWAITING_CLIENT)
				.collect(Collectors.toMap(Case::getId, c -> c));
		if (held.isEmpty()) {
			return List.of();
		}
		Map<UUID, EvidenceRequest> latest = new HashMap<>();
		for (AuditEvent row : auditEvents.findCaseActionScoped("CASE", AuditAction.UPDATED, held.keySet(), brandIds)) {
			if (row.getActorType() != ActorType.EXPERT) {
				continue;
			}
			com.fasterxml.jackson.databind.JsonNode after = snapshot(row.getAfterSnapshot());
			if (after == null || !"ON_HOLD_AWAITING_CLIENT".equals(after.path("exceptionState").asText())) {
				continue;
			}
			Case subject = held.get(row.getObjectId());
			EvidenceRequest candidate = new EvidenceRequest(subject.getId(), subject.getCaseCode(),
					after.path("note").isTextual() ? after.path("note").asText() : null, row.getCreatedAt());
			latest.merge(subject.getId(), candidate, (a, b) -> a.askedAt().isAfter(b.askedAt()) ? a : b);
		}
		return latest.values().stream()
				.sorted(Comparator.comparing(EvidenceRequest::askedAt))
				.limit(ROW_CAP)
				.toList();
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

	/** An audit snapshot as a tree, or null when there is none or it does not parse. */
	private static com.fasterxml.jackson.databind.JsonNode snapshot(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return JSON.readTree(json);
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
			return null;
		}
	}

	private Checklist checklist(Set<UUID> brandIds, Set<UUID> caseIds, Map<UUID, Case> byId) {
		Map<UUID, List<DocumentChecklistItem>> grouped = items.findByBrandIdInAndCaseIdIn(brandIds, caseIds).stream()
				.collect(Collectors.groupingBy(DocumentChecklistItem::getCaseId));
		List<ChecklistCase> rows = grouped.entrySet().stream()
				.map(entry -> {
					List<DocumentChecklistItem> list = entry.getValue();
					return new ChecklistCase(entry.getKey(), byId.get(entry.getKey()).getCaseCode(), list.size(),
							count(list, ChecklistItemStatus.APPROVED), count(list, ChecklistItemStatus.UPLOADED),
							count(list, ChecklistItemStatus.REQUIRED), count(list, ChecklistItemStatus.MISSING),
							count(list, ChecklistItemStatus.INCORRECT));
				})
				.toList();
		int blockerItems = rows.stream().mapToInt(r -> r.missing() + r.incorrect()).sum();
		int blockerCases = (int) rows.stream().filter(r -> r.missing() + r.incorrect() > 0).count();
		// Worst first, so the cap drops the cases that need nothing.
		List<ChecklistCase> shown = rows.stream()
				.sorted(Comparator.comparingInt((ChecklistCase r) -> r.missing() + r.incorrect()).reversed()
						.thenComparing(ChecklistCase::caseCode))
				.limit(ROW_CAP)
				.toList();
		return new Checklist(blockerItems, blockerCases, shown);
	}

	private static int count(List<DocumentChecklistItem> list, ChecklistItemStatus status) {
		return (int) list.stream().filter(i -> i.getStatus() == status).count();
	}

	private Offers offers(List<Case> open, Set<UUID> brandIds, Set<UUID> caseIds, Map<UUID, Case> byId) {
		Instant now = Instant.now();
		// The latest offer that is not SUPERSEDED, per case: superseded rows are history, not state.
		Map<UUID, ExpertCaseOffer> latest = offers.findByBrandIdInAndCaseIdIn(brandIds, caseIds).stream()
				.filter(o -> o.getOutcome() != OfferOutcome.SUPERSEDED)
				.collect(Collectors.toMap(ExpertCaseOffer::getCaseId, o -> o,
						(a, b) -> a.getOfferedAt().isAfter(b.getOfferedAt()) ? a : b));
		Map<UUID, String> expertNames = expertNames(latest.values());
		Map<UUID, String> currencies = currencies(brandIds);

		List<OfferRow> rows = latest.values().stream()
				.map(o -> {
					Case subject = byId.get(o.getCaseId());
					return new OfferRow(o.getCaseId(), subject.getCaseCode(), expertNames.get(o.getExpertId()),
							o.getOutcome(), o.getFee(), currencies.get(subject.getBrandId()), o.getOfferedAt(),
							calendar.elapsedBusinessTime(o.getOfferedAt(), now).toHours(), o.getDeclineReason());
				})
				.toList();
		int openOffers = (int) rows.stream().filter(r -> r.outcome() == OfferOutcome.OFFERED).count();
		int rematch = (int) open.stream()
				.filter(c -> c.getExceptionState() == ExceptionState.EXPERT_DECLINED_REMATCHING).count();
		List<OfferRow> shown = rows.stream()
				.sorted(Comparator.comparingInt((OfferRow r) -> rank(r.outcome()))
						.thenComparing(Comparator.comparingLong(OfferRow::ageBusinessHours).reversed()))
				.limit(ROW_CAP)
				.toList();
		return new Offers(openOffers, rematch, shown);
	}

	/** Waiting offers first, then ones that came back, then accepted. */
	private static int rank(OfferOutcome outcome) {
		return switch (outcome) {
			case OFFERED -> 0;
			case DECLINED, TIMED_OUT -> 1;
			default -> 2;
		};
	}

	private Map<UUID, String> expertNames(Collection<ExpertCaseOffer> rows) {
		List<UUID> ids = rows.stream().map(ExpertCaseOffer::getExpertId).filter(Objects::nonNull).distinct().toList();
		Map<UUID, String> names = new HashMap<>();
		if (!ids.isEmpty()) {
			// Brand-scoped, from the brands of the offers the ids came from.
			Set<UUID> brandIds = rows.stream().map(ExpertCaseOffer::getBrandId).collect(Collectors.toSet());
			for (Expert expert : experts.findByBrandIdInAndIdIn(brandIds, ids)) {
				names.put(expert.getId(), expert.getFullName());
			}
		}
		return names;
	}

	private Map<UUID, String> currencies(Set<UUID> brandIds) {
		Map<UUID, String> out = new HashMap<>();
		for (Brand brand : brands.findAllById(brandIds)) {
			out.put(brand.getId(), brand.getCurrency());
		}
		return out;
	}

	private Drafts drafts(List<Case> open) {
		int before = 0;
		int drafting = 0;
		int returned = 0;
		int withPm = 0;
		int withClient = 0;
		int approved = 0;
		for (Case subject : open) {
			switch (subject.getCurrentStage()) {
				case DOC_COLLECTION, PM_REVIEW -> before++;
				case DRAFT_IN_PROGRESS -> {
					if (subject.getPmApprovalStatus() == PmApprovalStatus.RETURNED) {
						returned++;
					}
					else {
						drafting++;
					}
				}
				case DRAFT_REVIEW -> withPm++;
				case READY_TO_SEND, CLIENT_REVIEW -> withClient++;
				default -> approved++;
			}
		}
		return new Drafts(before, drafting, returned, withPm, withClient, approved);
	}
}
