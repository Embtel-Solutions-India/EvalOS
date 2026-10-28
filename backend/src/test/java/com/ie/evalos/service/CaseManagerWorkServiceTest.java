package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
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
import com.ie.evalos.service.CaseManagerWorkService.CaseManagerWork;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** What blocks a Case Manager's cases, at the places a plausible number would be the wrong one. */
class CaseManagerWorkServiceTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final CaseManagerMetricsService cm = mock(CaseManagerMetricsService.class);
	private final DocumentChecklistItemRepository items = mock(DocumentChecklistItemRepository.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final com.ie.evalos.repository.CaseDocumentRepository documents =
			mock(com.ie.evalos.repository.CaseDocumentRepository.class);
	private final com.ie.evalos.repository.AuditEventRepository auditEvents =
			mock(com.ie.evalos.repository.AuditEventRepository.class);
	private final CaseManagerWorkService service = new CaseManagerWorkService(cm, items, offers, experts, brands,
			new BusinessCalendar(), documents, auditEvents);

	/** A fresh Case has no id until persisted, and everything here is keyed by it. */
	private static Case caseAt(Stage stage) {
		Case subject = new Case(BRAND, "C-" + UUID.randomUUID().toString().substring(0, 6), stage);
		ReflectionTestUtils.setField(subject, "id", UUID.randomUUID());
		return subject;
	}

	private static Case set(Case subject, String field, Object value) {
		ReflectionTestUtils.setField(subject, field, value);
		return subject;
	}

	private static DocumentChecklistItem item(Case of, ChecklistItemStatus status) {
		DocumentChecklistItem row = mock(DocumentChecklistItem.class);
		given(row.getCaseId()).willReturn(of.getId());
		given(row.getStatus()).willReturn(status);
		return row;
	}

	private static ExpertCaseOffer offer(Case of, OfferOutcome outcome, Instant offeredAt, BigDecimal fee, UUID expert) {
		ExpertCaseOffer row = mock(ExpertCaseOffer.class);
		given(row.getCaseId()).willReturn(of.getId());
		given(row.getOutcome()).willReturn(outcome);
		given(row.getOfferedAt()).willReturn(offeredAt);
		given(row.getFee()).willReturn(fee);
		given(row.getExpertId()).willReturn(expert);
		return row;
	}

	private CaseManagerWork run(List<Case> mine, List<DocumentChecklistItem> itemRows, List<ExpertCaseOffer> offerRows) {
		given(cm.myCases()).willReturn(mine);
		given(items.findByBrandIdInAndCaseIdIn(anyCollection(), anyCollection())).willReturn(itemRows);
		given(offers.findByBrandIdInAndCaseIdIn(anyCollection(), anyCollection())).willReturn(offerRows);
		given(experts.findByBrandIdInAndIdIn(any(), any())).willReturn(List.<Expert>of());
		given(brands.findAllById(any())).willReturn(List.<Brand>of());
		return service.forCaller();
	}

	@Test
	void noOpenCasesIsAllZeroAndTouchesNoRepository() {
		given(cm.myCases()).willReturn(List.of(caseAt(Stage.DELIVERED), caseAt(Stage.CLOSED)));
		CaseManagerWork work = service.forCaller();
		assertThat(work.checklist().blockerItems()).isZero();
		assertThat(work.checklist().cases()).isEmpty();
		assertThat(work.offers().rows()).isEmpty();
		assertThat(work.drafts().approved()).isZero();
		verify(items, never()).findByBrandIdInAndCaseIdIn(any(), any());
		verify(offers, never()).findByBrandIdInAndCaseIdIn(any(), any());
	}

	@Test
	void checklistCountsBlockersAndOmitsCasesWithoutItems() {
		Case withItems = caseAt(Stage.DOC_COLLECTION);
		Case without = caseAt(Stage.DRAFT_IN_PROGRESS);
		CaseManagerWork work = run(List.of(withItems, without), List.of(
				item(withItems, ChecklistItemStatus.APPROVED), item(withItems, ChecklistItemStatus.MISSING),
				item(withItems, ChecklistItemStatus.INCORRECT), item(withItems, ChecklistItemStatus.UPLOADED)),
				List.of());
		assertThat(work.checklist().cases()).hasSize(1);
		var row = work.checklist().cases().get(0);
		assertThat(row.caseId()).isEqualTo(withItems.getId());
		assertThat(row.total()).isEqualTo(4);
		assertThat(row.approved()).isEqualTo(1);
		assertThat(row.uploaded()).isEqualTo(1);
		assertThat(work.checklist().blockerItems()).isEqualTo(2);
		assertThat(work.checklist().blockerCases()).isEqualTo(1);
	}

	@Test
	void onlyMyOpenCaseIdsAreRequested() {
		Case mine = caseAt(Stage.DRAFT_IN_PROGRESS);
		Case delivered = caseAt(Stage.DELIVERED);
		given(cm.myCases()).willReturn(List.of(mine, delivered));
		given(items.findByBrandIdInAndCaseIdIn(anyCollection(), anyCollection())).willReturn(List.of());
		given(offers.findByBrandIdInAndCaseIdIn(anyCollection(), anyCollection())).willReturn(List.of());
		service.forCaller();
		verify(items).findByBrandIdInAndCaseIdIn(Set.of(BRAND), Set.of(mine.getId()));
	}

	@Test
	void latestNonSupersededOfferWinsAndSupersededNeverShows() {
		Case subject = caseAt(Stage.PM_REVIEW);
		UUID expertId = UUID.randomUUID();
		Instant now = Instant.now();
		CaseManagerWork work = run(List.of(subject), List.of(), List.of(
				offer(subject, OfferOutcome.SUPERSEDED, now.minusSeconds(900), new BigDecimal("100"), expertId),
				offer(subject, OfferOutcome.DECLINED, now.minusSeconds(600), new BigDecimal("120"), expertId),
				offer(subject, OfferOutcome.OFFERED, now.minusSeconds(60), null, expertId)));
		assertThat(work.offers().rows()).hasSize(1);
		var row = work.offers().rows().get(0);
		assertThat(row.outcome()).isEqualTo(OfferOutcome.OFFERED);
		// Unpriced is null, never 0; and the expert row is gone, so the row stays with no name.
		assertThat(row.fee()).isNull();
		assertThat(row.expertName()).isNull();
		assertThat(work.offers().open()).isEqualTo(1);
	}

	@Test
	void rematchCountsCasesInTheDeclinedExceptionState() {
		Case rematching = set(caseAt(Stage.PM_REVIEW), "exceptionState", ExceptionState.EXPERT_DECLINED_REMATCHING);
		CaseManagerWork work = run(List.of(rematching, caseAt(Stage.PM_REVIEW)), List.of(), List.of());
		assertThat(work.offers().rematch()).isEqualTo(1);
	}

	@Test
	void draftBucketsSumToOpenCases() {
		List<Case> mine = List.of(
				caseAt(Stage.DOC_COLLECTION), caseAt(Stage.PM_REVIEW),
				caseAt(Stage.DRAFT_IN_PROGRESS),
				set(caseAt(Stage.DRAFT_IN_PROGRESS), "pmApprovalStatus", PmApprovalStatus.RETURNED),
				caseAt(Stage.DRAFT_REVIEW),
				caseAt(Stage.READY_TO_SEND), caseAt(Stage.CLIENT_REVIEW),
				caseAt(Stage.CLIENT_APPROVAL), caseAt(Stage.EXPERT_SIGNING), caseAt(Stage.FINAL_QC),
				caseAt(Stage.READY_TO_DELIVER));
		var drafts = run(mine, List.of(), List.of()).drafts();
		assertThat(drafts.beforeDraft()).isEqualTo(2);
		assertThat(drafts.drafting()).isEqualTo(1);
		assertThat(drafts.returned()).isEqualTo(1);
		assertThat(drafts.withPm()).isEqualTo(1);
		assertThat(drafts.withClient()).isEqualTo(2);
		assertThat(drafts.approved()).isEqualTo(4);
		assertThat(drafts.beforeDraft() + drafts.drafting() + drafts.returned() + drafts.withPm()
				+ drafts.withClient() + drafts.approved()).isEqualTo(mine.size());
	}

	/** A draft the PM sent back is listed with the PM's reason from the returned version. */
	@Test
	void aReturnedDraftIsListedWithThePmsReason() {
		Case back = set(set(caseAt(Stage.DRAFT_IN_PROGRESS), "pmApprovalStatus", com.ie.evalos.domain.PmApprovalStatus.RETURNED),
				"stageEnteredAt", java.time.Instant.now());
		given(cm.myCases()).willReturn(java.util.List.of(back));
		com.ie.evalos.domain.CaseDocument v1 = new com.ie.evalos.domain.CaseDocument(BRAND, back.getId(),
				com.ie.evalos.domain.DocumentKind.DRAFT, 1, null, com.ie.evalos.domain.ActorType.STAFF, null);
		v1.reviewed(com.ie.evalos.domain.DocumentStatus.RETURNED, "Fix the course table");
		given(documents.findByBrandIdAndCaseIdInAndKind(org.mockito.ArgumentMatchers.eq(BRAND), anyCollection(),
				org.mockito.ArgumentMatchers.eq(com.ie.evalos.domain.DocumentKind.DRAFT))).willReturn(java.util.List.of(v1));

		var work = service.forCaller();

		org.assertj.core.api.Assertions.assertThat(work.returned()).singleElement()
				.satisfies(r -> org.assertj.core.api.Assertions.assertThat(r.reason()).isEqualTo("Fix the course table"));
	}

	/** Only a hold the EXPERT raised is an evidence request; its note is what is missing. */
	@Test
	void anExpertsHoldIsListedAsAnEvidenceRequest() {
		Case held = set(caseAt(Stage.EXPERT_SIGNING), "exceptionState", com.ie.evalos.domain.ExceptionState.ON_HOLD_AWAITING_CLIENT);
		given(cm.myCases()).willReturn(java.util.List.of(held));
		com.ie.evalos.domain.AuditEvent byExpert = new com.ie.evalos.domain.AuditEvent(BRAND, "CASE", held.getId(),
				com.ie.evalos.domain.AuditAction.UPDATED, null, com.ie.evalos.domain.ActorType.EXPERT, null,
				"{\"exceptionState\":\"ON_HOLD_AWAITING_CLIENT\",\"note\":\"Original transcript\"}");
		ReflectionTestUtils.setField(byExpert, "createdAt", java.time.Instant.now());
		com.ie.evalos.domain.AuditEvent byStaff = new com.ie.evalos.domain.AuditEvent(BRAND, "CASE", held.getId(),
				com.ie.evalos.domain.AuditAction.UPDATED, UUID.randomUUID(), com.ie.evalos.domain.ActorType.STAFF, null,
				"{\"exceptionState\":\"ON_HOLD_AWAITING_CLIENT\",\"note\":\"waiting on payment\"}");
		ReflectionTestUtils.setField(byStaff, "createdAt", java.time.Instant.now());
		given(auditEvents.findCaseActionScoped(org.mockito.ArgumentMatchers.eq("CASE"),
				org.mockito.ArgumentMatchers.eq(com.ie.evalos.domain.AuditAction.UPDATED), anyCollection(), anyCollection()))
				.willReturn(java.util.List.of(byExpert, byStaff));

		var work = service.forCaller();

		org.assertj.core.api.Assertions.assertThat(work.evidence()).singleElement()
				.satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.missing()).isEqualTo("Original transcript"));
	}
}
