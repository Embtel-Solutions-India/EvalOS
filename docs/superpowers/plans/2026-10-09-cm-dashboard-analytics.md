# CM Dashboard Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a Case Manager what blocks their cases — missing/incorrect checklist items, open and declined expert offers (with the fee), and where each draft sits — each row linking to the case.

**Architecture:** One new caller-scoped read endpoint `GET /api/metrics/case-manager/work`, backed by a new `CaseManagerWorkService` that takes "my cases" from `CaseManagerMetricsService` (one scope read) and batch-loads checklist items and offers (no per-case queries). The frontend adds a second, independent `useMetrics` load and three presentational pieces to `CaseManagerDashboard`; the existing `/case-manager` call and tiles stay.

**Tech Stack:** Spring Boot (JUnit 5 + Mockito + AssertJ, `@WebMvcTest`), React + TypeScript, `useMetrics` (TanStack Query), Vitest.

**Spec:** `context/specs/80-cm-dashboard-analytics.md` (read first; §1 definitions and §4 unavailable list are binding; Task 5 amends §1 for two rulings below).

## Global Constraints

- No change to RBAC, roles, workflow or actions. Route gate is exactly `@PreAuthorize("hasAnyRole('GM', 'CASE_MANAGER')")`, copied from `/case-manager`.
- Caller is the scope: "my cases" = `CaseManagerMetricsService`'s set (assigned CM = `TenantContext.memberId()`). No brand parameter on the endpoint.
- Read-only. No new `AuditAction`, migration, column or write path.
- "Open" case = stage not `DELIVERED` and not `CLOSED`. All figures are over open cases only.
- A metric with no data source renders `unavailable`, never 0. A null fee renders "—", never 0.
- Counts are labelled by what they count: **cases**, **checklist items**, **offers** — never mixed in one tile.
- Existing `/api/metrics/case-manager` contract is unchanged.
- Backend tests: `cd backend && ./mvnw -q -Dtest=<Class> test`. Frontend: `cd frontend && npx vitest run <file>` and `npx tsc -p tsconfig.app.json --noEmit` (root `tsc -p .` checks nothing).
- Reuse, do not duplicate: `ageText` from `pmOverviewRules.ts`, `formatPayout` from `lib/money.ts`, `riskLabel`/`riskColor` from `queues/queueRules.ts`, `emptyWhen`/`useMetrics`, `Card`/`KpiCard`.

## Review Focus

- A CM with no open cases: every count 0, `rows`/`cases` empty, no repository call with an empty id list. Pinned in Task 1.
- A case with no checklist items is absent from `checklist.cases` (not a 0-of-0 row). Pinned in Task 1.
- Items/offers are requested only for *my* open case ids and *their* brands; another CM's case never appears. Pinned in Task 1.
- An offer with no fee shows `fee: null` and "—"; a brand with no currency shows the bare number. Pinned in Tasks 1 and 3.
- An offer whose expert row is gone keeps its row with `expertName: null` ("—"). Pinned in Task 1.
- Several offers on one case: only the latest non-superseded one is shown; superseded never. Pinned in Task 1.
- Draft buckets sum to the open-case count. Pinned in Task 1.

## Rulings carried from planning (record in the ledger at execution)

- **R1** Spec §1's draft buckets did not cover cases before drafting, so the sum could not equal open cases. Added a sixth bucket `beforeDraft` (`DOC_COLLECTION`, `PM_REVIEW`); `approved` = `CLIENT_APPROVAL`, `EXPERT_SIGNING`, `FINAL_QC`, `READY_TO_DELIVER`. Task 5 edits the spec.
- **R2** "Returned drafts" KPI reads `drafts.returned` from the new payload (returned = `DRAFT_IN_PROGRESS` with `pmApprovalStatus == RETURNED`, labelled "Returned by PM") rather than reusing `revisionsRequested`, so one tile cannot disagree with the lifecycle card beside it. The existing "Draft status" card is left as is.
- **R3** Offer rows carry `currency` (from `Brand.getCurrency`, null if unset) because an offer has no currency column and `formatPayout` needs one.

## File Structure

| File | Action | Responsibility |
| ---- | ------ | -------------- |
| `backend/.../service/CaseManagerMetricsService.java` | modify | package-private `myCases()` accessor shared with the new service |
| `backend/.../repository/ExpertCaseOfferRepository.java` | modify | `findByBrandIdInAndCaseIdIn` batch finder |
| `backend/.../service/CaseManagerWorkService.java` | create | checklist counts, offer rows, draft buckets |
| `backend/src/test/.../service/CaseManagerWorkServiceTest.java` | create | unit tests |
| `backend/.../web/MetricsController.java` | modify | `GET /case-manager/work` |
| `backend/src/test/.../web/CaseManagerWorkRouteTest.java` | create | role gate |
| `backend/src/test/.../web/GmOverviewRouteTest.java`, `PmOverviewRouteTest.java` | modify | add `@MockitoBean CaseManagerWorkService` (constructor changed) |
| `frontend/src/features/dashboards/pmMetricsApi.ts` | modify | wire types + `fetchCaseManagerWork` |
| `frontend/src/features/dashboards/cmWorkRules.ts` (+ `.test.ts`) | create | pure helpers: stacked-bar segments, offer labels, fee text |
| `frontend/src/features/dashboards/ChecklistProgress.tsx`, `ExpertOffers.tsx` | create | presentational cards |
| `frontend/src/features/dashboards/CaseManagerDashboard.tsx` | modify | second load, "needs me" strip, new cards, "Due now" note fix |
| `context/specs/80-cm-dashboard-analytics.md`, `.claude/implementation-status.md`, `.serena/memories/implementation_status.md` | modify | same-step documentation |

---

### Task 1: `CaseManagerWorkService` (backend logic)

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/CaseManagerMetricsService.java`
- Modify: `backend/src/main/java/com/ie/evalos/repository/ExpertCaseOfferRepository.java`
- Create: `backend/src/main/java/com/ie/evalos/service/CaseManagerWorkService.java`
- Create: `backend/src/test/java/com/ie/evalos/service/CaseManagerWorkServiceTest.java`

**Interfaces:**
- Consumes: `CaseManagerMetricsService.myCases(): List<Case>` (added here); `DocumentChecklistItemRepository.findByBrandIdInAndCaseIdIn(Collection<UUID>, Collection<UUID>)` (exists); `ExpertCaseOfferRepository.findByBrandIdInAndCaseIdIn(...)` (added here); `ExpertRepository.findAllById`, `BrandRepository.findAllById` (JpaRepository); `BusinessCalendar.elapsedBusinessTime`.
- Produces: `CaseManagerWorkService.forCaller(): CaseManagerWork` and the records in Step 5 (JSON consumed by Task 3).

- [ ] **Step 1: Confirm the accessors the tests mock**

Run: `grep -n "public .* get\(Status\|CaseId\)()" backend/src/main/java/com/ie/evalos/domain/DocumentChecklistItem.java; grep -n "public .* getCurrency\|public UUID getId" backend/src/main/java/com/ie/evalos/domain/Brand.java; grep -n "private .* \(assignedCm\|pmApprovalStatus\|exceptionState\)" backend/src/main/java/com/ie/evalos/domain/Case.java`
Expected: `getStatus`/`getCaseId` on the item, `getCurrency`/`getId` on Brand, and the three `Case` fields. If a name differs, use the real one in Steps 3 and 5.

- [ ] **Step 2: Add the two seams**

In `CaseManagerMetricsService`, add and use:

```java
	/** The caller's assigned cases — shared with {@link CaseManagerWorkService} so there is one scope read. */
	List<Case> myCases() {
		UUID me = TenantContext.current().memberId();
		return lifecycle.list(null, null, null).stream()
				.filter(subject -> me != null && me.equals(subject.getAssignedCm()))
				.toList();
	}
```

In `forCaller()` replace the `List<Case> mine = lifecycle.list(...)...toList();` statement with `List<Case> mine = myCases();` (keep `TenantContext ctx = TenantContext.current();` — it is still used for `clientFeedback(mine, ctx)`; drop the now-unused `UUID me = ...` line if the compiler reports it unused).

In `ExpertCaseOfferRepository` add (with the same javadoc convention as the checklist finder — brands in the signature so it fails closed):

```java
	/**
	 * Every offer on a set of cases, in one query rather than one per case. Takes the brands as well as
	 * the case ids so a foreign case id matches nothing; pass the distinct brands of the cases a scoped
	 * read returned (the CM dashboard's batch read).
	 */
	List<ExpertCaseOffer> findByBrandIdInAndCaseIdIn(Collection<UUID> brandIds, Collection<UUID> caseIds);
```

- [ ] **Step 3: Write the failing tests**

```java
package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExceptionState;
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
	private final CaseManagerWorkService service = new CaseManagerWorkService(cm, items, offers, experts, brands,
			new BusinessCalendar());

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
		given(experts.findAllById(any())).willReturn(List.<Expert>of());
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
		verify(items).findByBrandIdInAndCaseIdIn(java.util.Set.of(BRAND), java.util.Set.of(mine.getId()));
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
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd backend && ./mvnw -q -Dtest=CaseManagerWorkServiceTest test`
Expected: compile FAIL — `CaseManagerWorkService` does not exist.

- [ ] **Step 5: Implement**

```java
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

	/** Rows returned per list; the screen says "N more" rather than paging. */
	static final int ROW_CAP = 25;

	private final CaseManagerMetricsService cm;
	private final DocumentChecklistItemRepository items;
	private final ExpertCaseOfferRepository offers;
	private final ExpertRepository experts;
	private final BrandRepository brands;
	private final BusinessCalendar calendar;

	CaseManagerWorkService(CaseManagerMetricsService cm, DocumentChecklistItemRepository items,
			ExpertCaseOfferRepository offers, ExpertRepository experts, BrandRepository brands,
			BusinessCalendar calendar) {
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

	public record CaseManagerWork(Checklist checklist, Offers offers, Drafts drafts) {
	}

	@Transactional(readOnly = true)
	public CaseManagerWork forCaller() {
		List<Case> open = cm.myCases().stream()
				.filter(c -> c.getCurrentStage() != Stage.DELIVERED && c.getCurrentStage() != Stage.CLOSED)
				.toList();
		if (open.isEmpty()) {
			return new CaseManagerWork(new Checklist(0, 0, List.of()), new Offers(0, 0, List.of()),
					new Drafts(0, 0, 0, 0, 0, 0));
		}
		Set<UUID> brandIds = open.stream().map(Case::getBrandId).collect(Collectors.toSet());
		Set<UUID> caseIds = open.stream().map(Case::getId).collect(Collectors.toSet());
		Map<UUID, Case> byId = open.stream().collect(Collectors.toMap(Case::getId, c -> c));
		return new CaseManagerWork(checklist(brandIds, caseIds, byId), offers(open, brandIds, caseIds, byId),
				drafts(open));
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
		int open = (int) rows.stream().filter(r -> r.outcome() == OfferOutcome.OFFERED).count();
		int rematch = (int) open().stream().filter(c -> c.getExceptionState() == ExceptionState.EXPERT_DECLINED_REMATCHING).count();
		List<OfferRow> shown = rows.stream()
				.sorted(Comparator.comparingInt((OfferRow r) -> rank(r.outcome()))
						.thenComparing(Comparator.comparingLong(OfferRow::ageBusinessHours).reversed()))
				.limit(ROW_CAP)
				.toList();
		return new Offers(open, rematch, shown);
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
			for (Expert expert : experts.findAllById(ids)) {
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
		int before = 0, drafting = 0, returned = 0, withPm = 0, withClient = 0, approved = 0;
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
```

Fix before compiling: in `offers(...)` the line computing `rematch` must read `open.stream()` — the method's parameter is named `open` but a local `int open` shadows it. Rename the local to `openOffers` and the parameter use to `open`:

```java
		int openOffers = (int) rows.stream().filter(r -> r.outcome() == OfferOutcome.OFFERED).count();
		int rematch = (int) open.stream().filter(c -> c.getExceptionState() == ExceptionState.EXPERT_DECLINED_REMATCHING).count();
		...
		return new Offers(openOffers, rematch, shown);
```

- [ ] **Step 6: Run to verify it passes**

Run: `cd backend && ./mvnw -q -Dtest=CaseManagerWorkServiceTest test`
Expected: PASS, 6 tests (check `target/surefire-reports/TEST-com.ie.evalos.service.CaseManagerWorkServiceTest.xml` for `tests="6" failures="0" errors="0"`; `-q` prints nothing on success).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main
git add backend/src/test/java/com/ie/evalos/service/CaseManagerWorkServiceTest.java
git commit -m "feat(cm): work service — checklist blockers, expert offers with fee, draft lifecycle (spec 80)"
```

---

### Task 2: `GET /api/metrics/case-manager/work` and its role gate

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/web/MetricsController.java`
- Modify: `backend/src/test/java/com/ie/evalos/web/GmOverviewRouteTest.java`, `PmOverviewRouteTest.java`
- Create: `backend/src/test/java/com/ie/evalos/web/CaseManagerWorkRouteTest.java`

**Interfaces:**
- Consumes: `CaseManagerWorkService.forCaller(): CaseManagerWork` (Task 1).
- Produces: `GET /api/metrics/case-manager/work` → `ApiResponse<CaseManagerWork>`.

- [ ] **Step 1: Write the failing route test**

Copy `PmOverviewRouteTest.java` to `CaseManagerWorkRouteTest.java` (same annotations, mock beans, `bearer` helper) and change: class name; add `@MockitoBean PmOverviewService pmOverview;` and `@MockitoBean CaseManagerWorkService work;`; the constant and tests:

```java
	private static final CaseManagerWorkService.CaseManagerWork EMPTY = new CaseManagerWorkService.CaseManagerWork(
			new CaseManagerWorkService.Checklist(0, 0, List.of()),
			new CaseManagerWorkService.Offers(0, 0, List.of()),
			new CaseManagerWorkService.Drafts(0, 0, 0, 0, 0, 0));

	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "GM", "CASE_MANAGER" })
	void workIsOpenToTheSameRolesAsTheCmDocket(Role role) throws Exception {
		given(work.forCaller()).willReturn(EMPTY);
		mockMvc.perform(get("/api/metrics/case-manager/work").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isOk());
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = { "GM", "CASE_MANAGER" })
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mockMvc.perform(get("/api/metrics/case-manager/work").header(HttpHeaders.AUTHORIZATION, bearer(role)))
				.andExpect(status().isForbidden());
		then(work).should(never()).forCaller();
	}
```

(Keep the `PmOverviewService` mock so the controller's context still builds; remove the unused `PmOverview` imports.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q -Dtest=CaseManagerWorkRouteTest test`
Expected: FAIL — status 404 on the route.

- [ ] **Step 3: Implement**

In `MetricsController`: add field `private final CaseManagerWorkService cmWork;`, append `CaseManagerWorkService cmWork` as the last constructor parameter and assign it, import the class, and add below `caseManager()`:

```java
	/** What blocks my cases: checklist blockers, expert offers, draft lifecycle. Same gate and scope as {@code /case-manager}. */
	@GetMapping("/case-manager/work")
	@PreAuthorize("hasAnyRole('GM', 'CASE_MANAGER')")
	public ApiResponse<CaseManagerWorkService.CaseManagerWork> caseManagerWork() {
		return ApiResponse.ok(cmWork.forCaller());
	}
```

In `GmOverviewRouteTest` and `PmOverviewRouteTest` add `@MockitoBean com.ie.evalos.service.CaseManagerWorkService cmWork;` beside the other service mocks (the constructor changed, so those slices will not start without it).

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw -q -Dtest='CaseManagerWorkRouteTest,PmOverviewRouteTest,GmOverviewRouteTest' test`
Expected: PASS (read the three `TEST-…xml` files for `failures="0" errors="0"`).

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "feat(cm): GET /api/metrics/case-manager/work, CM and GM only (spec 80)"
```

---

### Task 3: Frontend wire types and pure rules

**Files:**
- Modify: `frontend/src/features/dashboards/pmMetricsApi.ts`
- Create: `frontend/src/features/dashboards/cmWorkRules.ts`
- Create: `frontend/src/features/dashboards/cmWorkRules.test.ts`

**Interfaces:**
- Produces (used by Task 4): `CaseManagerWork`, `ChecklistCaseRow`, `OfferRow`, `OfferOutcome`, `fetchCaseManagerWork(signal)`; from rules: `segmentPercents(row: ChecklistCaseRow): Segment[]` where `Segment = { key: string; label: string; color: string; count: number; pct: number }`, `OFFER_LABEL: Record<OfferOutcome, string>`, `feeText(fee: number | null, currency: string | null): string`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import type { ChecklistCaseRow } from './pmMetricsApi'
import { feeText, OFFER_LABEL, segmentPercents } from './cmWorkRules'

const row = (overrides: Partial<ChecklistCaseRow>): ChecklistCaseRow => ({
  caseId: 'c', caseCode: 'IE-1', total: 0, approved: 0, uploaded: 0, required: 0, missing: 0, incorrect: 0,
  ...overrides,
})

describe('cmWorkRules', () => {
  it('drops empty segments and the rest add up to the whole bar', () => {
    const segments = segmentPercents(row({ total: 4, approved: 2, missing: 1, incorrect: 1 }))
    expect(segments.map((s) => s.key)).toEqual(['approved', 'missing', 'incorrect'])
    expect(segments.reduce((sum, s) => sum + s.pct, 0)).toBe(100)
  })
  it('a case with no items has no bar at all, not a 0-wide one', () => {
    expect(segmentPercents(row({ total: 0 }))).toEqual([])
  })
  it('an unpriced offer is a dash, never 0, and a brand with no currency shows the bare number', () => {
    expect(feeText(null, 'USD')).toBe('—')
    expect(feeText(350, 'USD')).toBe('$350.00')
    expect(feeText(350, null)).toBe('350')
  })
  it('every outcome has words, so status is never colour alone', () => {
    expect(OFFER_LABEL.OFFERED).toBe('Waiting for expert')
    expect(Object.keys(OFFER_LABEL)).toHaveLength(5)
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/features/dashboards/cmWorkRules.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

`cmWorkRules.ts`:

```ts
import { formatPayout } from '../../lib/money'
import type { ChecklistCaseRow, OfferOutcome } from './pmMetricsApi'

export type Segment = { key: string; label: string; color: string; count: number; pct: number }

/** In bar order. Words as well as colour: status is never carried by colour alone. */
const SEGMENTS = [
  { key: 'approved', label: 'Approved', color: 'var(--status-green)' },
  { key: 'uploaded', label: 'Awaiting approval', color: 'var(--accent-primary)' },
  { key: 'required', label: 'Still required', color: 'var(--bg-raised)' },
  { key: 'missing', label: 'Missing', color: 'var(--status-red)' },
  { key: 'incorrect', label: 'Incorrect', color: 'var(--status-amber)' },
] as const

/** Non-empty segments of one case's checklist as whole percentages that always total 100. */
export function segmentPercents(row: ChecklistCaseRow): Segment[] {
  if (row.total <= 0) return []
  const live = SEGMENTS.map((s) => ({ ...s, count: row[s.key] })).filter((s) => s.count > 0)
  let used = 0
  return live.map((s, i) => {
    // The last segment takes the remainder, so rounding can never leave the bar short or long.
    const pct = i === live.length - 1 ? 100 - used : Math.round((s.count / row.total) * 100)
    used += pct
    return { ...s, pct }
  })
}

export const OFFER_LABEL: Record<OfferOutcome, string> = {
  OFFERED: 'Waiting for expert',
  ACCEPTED: 'Accepted',
  DECLINED: 'Declined',
  TIMED_OUT: 'Timed out',
  SUPERSEDED: 'Replaced',
}

/** An unpriced offer is "—", never 0. A brand with no currency shows the bare number, as ExpertBalances does. */
export function feeText(fee: number | null, currency: string | null): string {
  if (fee === null) return '—'
  return currency ? formatPayout(fee, currency) : String(fee)
}
```

In `pmMetricsApi.ts`, append after `fetchCaseManagerMetrics`:

```ts
/** `CaseManagerWorkService.CaseManagerWork` — see spec 80 §2. */
export type ChecklistCaseRow = {
  caseId: string
  caseCode: string
  total: number
  approved: number
  uploaded: number
  required: number
  missing: number
  incorrect: number
}
export type OfferOutcome = 'OFFERED' | 'ACCEPTED' | 'DECLINED' | 'TIMED_OUT' | 'SUPERSEDED'
export type OfferRow = {
  caseId: string
  caseCode: string
  /** Null when the expert's record is gone — the row stays, the name is "—". */
  expertName: string | null
  outcome: OfferOutcome
  /** Null when unpriced — never 0. */
  fee: number | null
  /** Null when the brand has no currency set. */
  currency: string | null
  offeredAt: string
  ageBusinessHours: number
  declineReason: string | null
}
export type CaseManagerWork = {
  /** `blockerItems` counts checklist items, `blockerCases` counts cases. */
  checklist: { blockerItems: number; blockerCases: number; cases: ChecklistCaseRow[] }
  /** `open` counts offers, `rematch` counts cases. */
  offers: { open: number; rematch: number; rows: OfferRow[] }
  /** Open cases, each in exactly one bucket. */
  drafts: { beforeDraft: number; drafting: number; returned: number; withPm: number; withClient: number; approved: number }
}

export async function fetchCaseManagerWork(signal?: AbortSignal): Promise<CaseManagerWork> {
  return unwrap<CaseManagerWork>(api.get('/metrics/case-manager/work', { signal }))
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npx vitest run src/features/dashboards/cmWorkRules.test.ts && npx tsc -p tsconfig.app.json --noEmit`
Expected: PASS (4 tests), `tsc` exit 0.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/dashboards/pmMetricsApi.ts frontend/src/features/dashboards/cmWorkRules.ts frontend/src/features/dashboards/cmWorkRules.test.ts
git commit -m "feat(cm): work wire types and checklist/offer rules (spec 80)"
```

---

### Task 4: Cards and dashboard wiring

**Files:**
- Create: `frontend/src/features/dashboards/ChecklistProgress.tsx`, `ExpertOffers.tsx`
- Modify: `frontend/src/features/dashboards/CaseManagerDashboard.tsx`

**Interfaces:**
- Consumes: Task 3 types/rules; `Card`, `CardState`, `KpiCard`; `ageText` from `pmOverviewRules`; `emptyWhen`/`useMetrics`.
- Produces: `<ChecklistProgress cases={ChecklistCaseRow[] | undefined} state={CardState} />`, `<ExpertOffers rows={OfferRow[] | undefined} state={CardState} />`.

- [ ] **Step 1: `ChecklistProgress.tsx`**

```tsx
import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import { emptyWhen } from './useMetrics'
import type { ChecklistCaseRow } from './pmMetricsApi'
import { segmentPercents } from './cmWorkRules'

/** One stacked bar per case: how much of its checklist is in, waiting for approval, missing or wrong. Read-only. */
export function ChecklistProgress({ cases, state, className }: { cases?: ChecklistCaseRow[]; state: CardState; className?: string }) {
  return (
    <Card
      title="Checklist progress"
      wide
      className={className}
      note="Checklist items per case, worst first. Missing and incorrect items hold a case up; the Coordinator sends and approves them."
      state={emptyWhen(state, cases?.length === 0, 'None of your open cases has a checklist yet.')}
    >
      <ul className="max-h-72 space-y-3 overflow-y-auto">
        {cases?.map((row) => (
          <li key={row.caseId}>
            <div className="flex items-baseline justify-between gap-2 text-sm">
              <Link to={`/cases/${row.caseId}`} className="font-mono text-xs font-medium">{row.caseCode}</Link>
              <span className="font-num text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                {row.approved} of {row.total} items approved
              </span>
            </div>
            <div className="mt-1 flex h-2 w-full overflow-hidden rounded-md" style={{ background: 'var(--bg-raised)' }}>
              {segmentPercents(row).map((s) => (
                <span key={s.key} title={`${s.label}: ${s.count}`} style={{ width: `${s.pct}%`, background: s.color }} />
              ))}
            </div>
            <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
              {segmentPercents(row).map((s) => `${s.count} ${s.label.toLowerCase()}`).join(' · ')}
            </p>
          </li>
        ))}
      </ul>
    </Card>
  )
}
```

- [ ] **Step 2: `ExpertOffers.tsx`**

```tsx
import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import { emptyWhen } from './useMetrics'
import type { OfferRow } from './pmMetricsApi'
import { feeText, OFFER_LABEL } from './cmWorkRules'
import { ageText } from './pmOverviewRules'

const TONE = {
  OFFERED: 'var(--text-primary)',
  ACCEPTED: 'var(--status-green)',
  DECLINED: 'var(--status-red)',
  TIMED_OUT: 'var(--status-amber)',
  SUPERSEDED: 'var(--text-muted)',
} as const

/** The latest expert offer on each of my cases — status, who, the fee, and how long it has been out. */
export function ExpertOffers({ rows, state, className }: { rows?: OfferRow[]; state: CardState; className?: string }) {
  return (
    <Card
      title="Expert offers"
      wide
      className={className}
      note="Latest offer per case, waiting ones first. Age is business hours since offered."
      state={emptyWhen(state, rows?.length === 0, 'No expert has been offered a case of yours yet.')}
    >
      <table className="w-full text-left text-sm">
        <thead>
          <tr style={{ color: 'var(--text-muted)' }}>
            <th className="py-1 font-normal">Case</th>
            <th className="py-1 font-normal">Expert</th>
            <th className="py-1 font-normal">Status</th>
            <th className="py-1 text-right font-normal">Fee</th>
            <th className="py-1 text-right font-normal">Out for</th>
          </tr>
        </thead>
        <tbody>
          {rows?.map((row) => (
            <tr key={row.caseId}>
              <td className="py-1.5">
                <Link to={`/cases/${row.caseId}`} className="font-mono text-xs font-medium">{row.caseCode}</Link>
              </td>
              <td className="py-1.5">{row.expertName ?? '—'}</td>
              <td className="py-1.5" style={{ color: TONE[row.outcome] }} title={row.declineReason ?? undefined}>
                {OFFER_LABEL[row.outcome]}
              </td>
              <td className="font-num py-1.5 text-right tabular-nums">{feeText(row.fee, row.currency)}</td>
              <td className="font-num py-1.5 text-right tabular-nums">{ageText(row.ageBusinessHours)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  )
}
```

- [ ] **Step 3: Wire `CaseManagerDashboard.tsx`**

Imports: add `fetchCaseManagerWork, type CaseManagerWork` to the existing `./pmMetricsApi` import; `import { ChecklistProgress } from './ChecklistProgress'`, `import { ExpertOffers } from './ExpertOffers'`.

Under the existing `useMetrics` call add (a separate load, so a failed `/work` cannot blank the existing tiles or the reverse):

```tsx
  const { data: work, state: workState } = useMetrics<CaseManagerWork>(
    (signal) => fetchCaseManagerWork(signal),
    [],
  )
```

Insert directly before the existing `<div className="mt-4 grid …">`:

```tsx
      <p className="mt-4 text-xs" style={{ color: 'var(--text-muted)' }}>
        Right now — what is holding your open cases up.
      </p>
      <div className="mt-2 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <KpiCard
          title="Returned by PM"
          state={workState}
          value={work?.drafts.returned ?? null}
          denominator="cases"
          tone={work === null ? undefined : work.drafts.returned > 0 ? 'warn' : 'good'}
        />
        <KpiCard
          title="Checklist blockers"
          state={workState}
          value={work?.checklist.blockerItems ?? null}
          denominator={work ? `items missing or incorrect, on ${work.checklist.blockerCases} cases` : undefined}
          tone={work === null ? undefined : work.checklist.blockerItems > 0 ? 'bad' : 'good'}
        />
        <KpiCard
          title="Open expert offers"
          state={workState}
          value={work?.offers.open ?? null}
          denominator="offers waiting for an answer"
        />
        <KpiCard
          title="Rematch needed"
          state={workState}
          value={work?.offers.rematch ?? null}
          denominator="cases whose expert declined"
          tone={work === null ? undefined : work.offers.rematch > 0 ? 'bad' : 'good'}
        />
      </div>
```

Inside the existing grid, just before `</div>` that closes it (after the "Client feedback" card), add:

```tsx
        <ChecklistProgress cases={work?.checklist.cases} state={workState} />
        <ExpertOffers rows={work?.offers.rows} state={workState} />
        <Card title="Draft lifecycle" state={workState} note="Your open cases by where the draft sits.">
          {work && (
            <dl className="grid grid-cols-3 gap-3">
              <Figure label="Before drafting" value={work.drafts.beforeDraft} />
              <Figure label="Drafting" value={work.drafts.drafting} />
              <Figure label="Returned by PM" value={work.drafts.returned}
                tone={work.drafts.returned > 0 ? 'var(--status-amber)' : undefined} />
              <Figure label="With the PM" value={work.drafts.withPm} />
              <Figure label="With the client" value={work.drafts.withClient} />
              <Figure label="Approved onward" value={work.drafts.approved} />
            </dl>
          )}
        </Card>
```

Fix the "Due now" note: replace `note="Deadline inside 24 business hours, or already past. Zero overdue is the daily goal."` with `note="Red deadline band: past the date or inside 24 business hours. Zero critical is the daily goal."`.

- [ ] **Step 4: Verify**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npx vitest run`
Expected: tsc exit 0; all tests green. (`KpiCard` props used here — `denominator`, `tone`, `value` — are the ones `PmDashboard` already passes.)

- [ ] **Step 5: Browser check (spec §5)**

If a backend built from this branch can be run (the dev servers on 8080/5173 were the old build on 2026-10-09, and Docker was not running — do not restart someone else's servers), sign in as a CM and open `/dashboard`: strip, checklist bars, offers table with fee, draft lifecycle render at 1440/1024/390 with no horizontal scroll; block `/api/metrics/case-manager/work` in devtools → only the new cards error, the existing tiles stay; sign in as a Coordinator and request the route directly → 403. If it cannot be run, say so plainly in the report; do not claim it.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/dashboards/
git commit -m "feat(cm): CM dashboard — needs-me strip, checklist progress, expert offers, draft lifecycle (spec 80)"
```

---

### Task 5: Documentation, in the same step

**Files:**
- Modify: `context/specs/80-cm-dashboard-analytics.md`, `.claude/implementation-status.md`, `.serena/memories/implementation_status.md`

- [ ] **Step 1:** Edit spec 80 in place: `Status: built <date>`; §1 draft-lifecycle row gets the sixth bucket `beforeDraft` (`DOC_COLLECTION`, `PM_REVIEW`) and "approved = `CLIENT_APPROVAL`, `EXPERT_SIGNING`, `FINAL_QC`, `READY_TO_DELIVER`; delivered and closed cases are not open and not counted" (R1); "Returned drafts" row says it reads the new payload's `drafts.returned` (R2); §2 payload gains `currency` on offer rows (R3).
- [ ] **Step 2:** Append a "CM dashboard analytics, <date> (spec 80)" entry to `.claude/implementation-status.md` with the endpoint, what was added to the dashboard, the three rulings, evidence (test class names + counts, `tsc -p tsconfig.app.json` exit 0, vitest count, full backend suite result) and what was **not** browser-checked; mirror it into `.serena/memories/implementation_status.md` (the Serena MCP server may be unavailable — edit the file directly).
- [ ] **Step 3:** Run the full backend suite once (`cd backend && ./mvnw -q test`, in the background — it takes several minutes) and record the real count from `target/surefire-reports`, not an estimate.
- [ ] **Step 4:** Commit: `git add -A .claude .serena context && git commit -m "docs(cm): spec 80 built — status and Serena memory"`

## Self-review notes

- **Spec coverage:** §1 definitions → Task 1 (each tested; buckets amended by R1); §2 endpoint → Tasks 1–2; §3 frontend (strip, checklist card, offers card with fee, draft card, second independent load, "Due now" note) → Task 4; §4 unavailable list → deliberately not rendered as tiles (no data; the spec table is the record); §5 verification → Task 4 Step 5 and Task 5.
- **Deviations from the spec**, all in "Rulings carried from planning" and written back to the spec in Task 5: sixth `beforeDraft` bucket, KPI reads `drafts.returned`, `currency` on offer rows.
- **Type consistency:** record and field names (`Checklist.blockerItems/blockerCases/cases`, `Offers.open/rematch/rows`, `Drafts.*`, `OfferRow.*`) are identical in Java, `pmMetricsApi.ts`, `cmWorkRules.ts` and the components.
- **Known risk:** the derived finder `findByBrandIdInAndCaseIdIn` on `ExpertCaseOfferRepository` is validated by Spring Data at application start, not by the unit tests (they mock the repository). It mirrors the existing `DocumentChecklistItemRepository` finder name; if an integration test exists that boots JPA, run it, otherwise the first real start of the backend is the check — say so in the report.
