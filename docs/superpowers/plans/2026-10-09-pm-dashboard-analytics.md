# PM Dashboard Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Project Manager an 11-stage pipeline funnel with stage aging, Draft Review and Final QC queues, current-state KPIs, CM workload with overdue work, and a delivery-throughput trend — every figure drilling into real cases.

**Architecture:** One new read endpoint `GET /api/metrics/pm/overview` backed by a new `PmOverviewService` that reuses `PmMetricsService`'s scoped case read (one scope predicate, never two). The frontend adds a second, independent `useMetrics` load plus three presentational components and a small pure-rules module; the existing `/api/metrics/pm` call and its six tiles stay.

**Tech Stack:** Spring Boot (Java, JUnit 5 + Mockito + AssertJ, `@WebMvcTest`), React + TypeScript, TanStack Query via `useMetrics`, Recharts, Vitest.

**Spec:** `context/specs/79-pm-dashboard-analytics.md` (read it first; §2 definitions and §5 unavailable list are binding).

## Global Constraints

- No change to RBAC, roles, workflow transitions or actions. The new route uses exactly `@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")`, copied from `/pm`.
- Brand-scoped by default: all case reads go through `PmMetricsService.scoped(brandId)`, which wraps `lifecycle.list(null, null, null)` (the already-scoped read). No new repository query.
- Append-only truth: nothing here writes. No new `AuditAction`, no migration, no new column.
- `GET /api/metrics/pm` response keeps every existing field; the only change is one **added** field `overdue` on each `workload` row.
- A metric with no data source renders `{ kind: 'unavailable', blockedBy }`, never `0` (first-pass QC rate → "Q21").
- *Now* tiles ignore the period filter and say so; *period* tiles show `rangeLabel(dateRange)`.
- Counts are **cases**. Tile copy says "cases".
- Stage labels come from `STAGE_SHORT` / `STAGE_ORDER` in `frontend/src/features/case/caseProgress.ts` — do not create a second label table.
- Backend tests: `cd backend && ./mvnw -q -Dtest=<Class> test`. Frontend: `cd frontend && npx vitest run <file>` and `npx tsc -b`.

## Review Focus

- Empty scope (a brand with no cases): stages all zero, `throughput` is a zero-filled series, `active` is 0 — never null/NaN. Pinned in Task 1.
- A case with `stageEnteredAt == null`: counted in its stage, excluded from the median, age `null` not 0. Pinned in Task 1.
- A case that names a deactivated Case Manager/PM: row says "Former team member", is not dropped. Pinned in Task 1.
- A delivery exactly on the window's `to` instant belongs to the next window (half-open `[from, to)`), like `PmMetricsService.tally`. Pinned in Task 1.
- Overview request fails while `/pm` succeeds (and vice versa): the other half of the page still renders. Pinned in Task 6 by structure (two independent hooks) and browser check.

## File Structure

| File | Action | Responsibility |
| ---- | ------ | -------------- |
| `backend/.../service/PmMetricsService.java` | modify | expose `scoped(brandId)`; add `overdue` to `CmWorkload` |
| `backend/.../service/PmOverviewService.java` | create | stage counts + median age, KPI counts, queues, throughput |
| `backend/.../web/MetricsController.java` | modify | `GET /pm/overview` |
| `backend/src/test/.../service/PmOverviewServiceTest.java` | create | unit tests |
| `backend/src/test/.../service/PmMetricsServiceTest.java` | modify | one `overdue` assertion |
| `backend/src/test/.../web/GmOverviewRouteTest.java` | modify | add `@MockitoBean PmOverviewService` (constructor changed) |
| `backend/src/test/.../web/PmOverviewRouteTest.java` | create | role gate |
| `frontend/src/features/dashboards/pmMetricsApi.ts` | modify | wire types + `fetchPmOverview`; `overdue` on `CmWorkload` |
| `frontend/src/features/dashboards/pmOverviewRules.ts` (+ `.test.ts`) | create | pure helpers: funnel stages, bar width, age text, drill link |
| `frontend/src/features/queues/queueRules.ts` (+ `queueRules.test.ts`) | modify | optional `stage` filter on `inboxQueue` |
| `frontend/src/features/queues/InboxPage.tsx` | modify | read `?stage=` and show a clearable chip |
| `frontend/src/components/ui/card.tsx` | modify | `CapacityBar` optional `overdue` |
| `frontend/src/features/dashboards/StageFunnel.tsx`, `QueueTable.tsx`, `ThroughputCard.tsx` | create | presentational cards |
| `frontend/src/features/dashboards/PmDashboard.tsx` | modify | second load, KPI strip, new cards |
| `.claude/implementation-status.md`, `.claude/workflows.md`, `.serena/memories/implementation_status.md`, `.serena/memories/workflows.md` | modify | same-step documentation |

---

### Task 1: `PmOverviewService` (backend logic)

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/PmMetricsService.java`
- Create: `backend/src/main/java/com/ie/evalos/service/PmOverviewService.java`
- Create: `backend/src/test/java/com/ie/evalos/service/PmOverviewServiceTest.java`
- Modify: `backend/src/test/java/com/ie/evalos/service/PmMetricsServiceTest.java`

**Interfaces:**
- Consumes: `PmMetricsService.scoped(UUID brandId): List<Case>` (added here), `DeadlineRiskCalculator.riskOf(Case, Instant): DeadlineRisk`, `BusinessCalendar.elapsedBusinessTime(Instant, Instant): Duration`, `TeamMemberQueryService.assignable(Role): List<TeamMember>`.
- Produces: `PmOverviewService.forCaller(Instant from, Instant to, UUID brandId): PmOverview` and the records below (JSON shape consumed by Task 3).

- [ ] **Step 1: Confirm the `Case` field names the test will set by reflection**

Run: `grep -n "private .* \(exceptionState\|assignedCm\|assignedPm\|deadline\|deliveryDate\|stageEnteredAt\|serviceType\);" backend/src/main/java/com/ie/evalos/domain/Case.java`
Expected: all seven fields listed. If a name differs, use the real one in Step 3's `set(...)` calls.

- [ ] **Step 2: Add the seams to `PmMetricsService`**

In `PmMetricsService.java` replace the `CmWorkload` record and extract the scope read:

```java
	public record CmWorkload(UUID cmId, String name, int active, int overdue, int capacity) {
	}
```

```java
	/** The already-scoped case list, optionally narrowed to one brand. Shared with {@link PmOverviewService}. */
	List<Case> scoped(UUID brandId) {
		return lifecycle.list(null, null, null).stream()
				.filter(subject -> brandId == null || brandId.equals(subject.getBrandId()))
				.toList();
	}
```

In `forCaller` replace the `List<Case> scoped = lifecycle.list(...)...toList();` statement with `List<Case> scoped = scoped(brandId);`. Change `private static String name(` to `static String name(` (package-private) so the overview reuses "Former team member".

Replace the `workload(...)` method body so each CM also carries `overdue`:

```java
	private List<CmWorkload> workload(List<Case> scoped, Map<UUID, String> names, Instant now) {
		Map<UUID, int[]> counts = new LinkedHashMap<>();
		names.keySet().forEach(id -> counts.put(id, new int[2]));
		for (Case subject : scoped) {
			UUID cm = subject.getAssignedCm();
			if (cm == null || subject.getCurrentStage() == Stage.CLOSED) {
				continue;
			}
			int[] row = counts.computeIfAbsent(cm, key -> new int[2]);
			row[0]++;
			if (deadlines.riskOf(subject, now) == DeadlineRisk.OVERDUE) {
				row[1]++;
			}
		}
		return counts.entrySet().stream()
				.map(entry -> new CmWorkload(entry.getKey(), name(names, entry.getKey()),
						entry.getValue()[0], entry.getValue()[1], casesPerCm))
				.sorted(Comparator.comparing(CmWorkload::name))
				.toList();
	}
```

and in `forCaller` call `workload(scoped, names, now)`.

- [ ] **Step 3: Write the failing tests**

Create `PmOverviewServiceTest.java`:

```java
package com.ie.evalos.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.service.PmOverviewService.PmOverview;
import com.ie.evalos.service.PmOverviewService.StageCount;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PmOverviewServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID SARAH = UUID.randomUUID();

	private final PmMetricsService pm = mock(PmMetricsService.class);
	private final TeamMemberQueryService members = mock(TeamMemberQueryService.class);
	private final BusinessCalendar calendar = new BusinessCalendar();
	private final PmOverviewService overview = new PmOverviewService(pm, members,
			new DeadlineRiskCalculator(calendar), calendar);

	private static Instant pt(int month, int day, int hour) {
		return LocalDateTime.of(2026, month, day, hour, 0).atZone(BusinessCalendar.ZONE).toInstant();
	}

	private static Case caseAt(Stage stage) {
		return new Case(BRAND, "C-" + UUID.randomUUID().toString().substring(0, 6), stage);
	}

	private static Case set(Case subject, String field, Object value) {
		ReflectionTestUtils.setField(subject, field, value);
		return subject;
	}

	private PmOverview run(List<Case> cases) {
		given(pm.scoped(any())).willReturn(cases);
		given(members.assignable(any(Role.class))).willReturn(List.<TeamMember>of());
		return overview.forCaller(pt(7, 1, 0), pt(7, 8, 0), null);
	}

	private static StageCount stage(PmOverview result, Stage which) {
		return result.stages().stream().filter(row -> row.stage() == which).findFirst().orElseThrow();
	}

	@Test
	void emptyScopeIsZeroFilledNotNull() {
		PmOverview result = run(List.of());
		assertThat(result.stages()).hasSize(11).allMatch(row -> row.count() == 0);
		assertThat(result.active()).isZero();
		assertThat(result.throughput()).hasSize(7).allMatch(point -> point.delivered() == 0);
		assertThat(result.queues().draftReview()).isEmpty();
	}

	@Test
	void stageCountsSumToActivePlusDelivered() {
		PmOverview result = run(List.of(caseAt(Stage.DOC_COLLECTION), caseAt(Stage.DRAFT_REVIEW),
				caseAt(Stage.FINAL_QC), caseAt(Stage.CLOSED),
				set(caseAt(Stage.DELIVERED), "deliveryDate", pt(7, 3, 12))));
		assertThat(result.active()).isEqualTo(3);
		assertThat(result.stages().stream().mapToInt(StageCount::count).sum()).isEqualTo(4);
		assertThat(result.awaitingReview()).isEqualTo(1);
		assertThat(result.awaitingQc()).isEqualTo(1);
	}

	@Test
	void blockedIsAnyExceptionStateOnAnOpenCase() {
		PmOverview result = run(List.of(
				set(caseAt(Stage.DOC_COLLECTION), "exceptionState", ExceptionState.ON_HOLD_AWAITING_CLIENT),
				set(caseAt(Stage.DRAFT_REVIEW), "exceptionState", ExceptionState.NONE)));
		assertThat(result.blocked()).isEqualTo(1);
	}

	@Test
	void caseWithoutStageEnteredAtIsCountedButHasNoAge() {
		PmOverview result = run(List.of(caseAt(Stage.FINAL_QC)));
		StageCount qc = stage(result, Stage.FINAL_QC);
		assertThat(qc.count()).isEqualTo(1);
		assertThat(qc.medianAgeBusinessHours()).isNull();
	}

	@Test
	void queueIsOldestWaitingFirstAndNamesFormerMembers() {
		Case older = set(set(caseAt(Stage.DRAFT_REVIEW), "stageEnteredAt", Instant.now().minusSeconds(86400 * 3)),
				"assignedCm", SARAH);
		Case newer = set(caseAt(Stage.DRAFT_REVIEW), "stageEnteredAt", Instant.now().minusSeconds(3600));
		PmOverview result = run(List.of(newer, older));
		assertThat(result.queues().draftReview()).hasSize(2);
		assertThat(result.queues().draftReview().get(0).caseId()).isEqualTo(older.getId());
		// SARAH is not on the (empty) roster: dropped rows would lose a case; a name makes it visible.
		assertThat(result.queues().draftReview().get(0).ownerName()).isEqualTo("Former team member");
	}

	@Test
	void deliveryOnTheWindowEndBelongsToTheNextWindow() {
		PmOverview result = run(List.of(set(caseAt(Stage.DELIVERED), "deliveryDate", pt(7, 8, 0)),
				set(caseAt(Stage.DELIVERED), "deliveryDate", pt(7, 1, 0))));
		assertThat(stage(result, Stage.DELIVERED).count()).isEqualTo(1);
		assertThat(result.throughput().stream().mapToInt(PmOverviewService.Throughput::delivered).sum()).isEqualTo(1);
	}
}
```

Add to `PmMetricsServiceTest`:

```java
	@Test
	void workloadCountsOverdueCasesPerCaseManager() {
		givenCaseManagers(SARAH);
		Case late = aCase(BRAND_IE, "C-LATE", Stage.DRAFT_IN_PROGRESS);
		ReflectionTestUtils.setField(late, "assignedCm", SARAH);
		ReflectionTestUtils.setField(late, "deadline", NOW.minus(2, ChronoUnit.DAYS));
		PmMetrics result = compute(List.of(late), null);
		assertThat(result.workload()).singleElement()
				.satisfies(row -> assertThat(row.active()).isEqualTo(1));
		assertThat(result.workload().get(0).overdue()).isEqualTo(1);
	}
```

- [ ] **Step 4: Run to verify they fail**

Run: `cd backend && ./mvnw -q -Dtest=PmOverviewServiceTest,PmMetricsServiceTest test`
Expected: compile FAIL — `PmOverviewService` does not exist.

- [ ] **Step 5: Implement `PmOverviewService`**

```java
package com.ie.evalos.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where the Project Manager's work is sitting, how long it has sat, and what is waiting on them.
 *
 * <p>Like {@link PmMetricsService}, every figure derives from its already-scoped case read — there
 * is no second scope predicate to drift. Computed live, nothing stored. Counts are <em>cases</em>.
 *
 * <p>ponytail: in-memory grouping over the scoped list, as {@code PmMetricsService} does; move to SQL
 * only when a brand reaches six-figure case counts.
 */
@Service
public class PmOverviewService {

	/** Queue rows returned per queue; the screen links to the full queue for the rest. */
	static final int QUEUE_CAP = 25;
	/** Daily buckets up to this many days, ISO-week buckets beyond. */
	static final int DAILY_MAX_DAYS = 31;

	private final PmMetricsService pm;
	private final TeamMemberQueryService members;
	private final DeadlineRiskCalculator deadlines;
	private final BusinessCalendar calendar;

	PmOverviewService(PmMetricsService pm, TeamMemberQueryService members,
			DeadlineRiskCalculator deadlines, BusinessCalendar calendar) {
		this.pm = pm;
		this.members = members;
		this.deadlines = deadlines;
		this.calendar = calendar;
	}

	/** {@code medianAgeBusinessHours} is null when no case in the stage has a stage-entry time; DELIVERED has none. */
	public record StageCount(Stage stage, int count, Long medianAgeBusinessHours) {
	}

	public record QueueRow(UUID caseId, String caseCode, ServiceType serviceType, String ownerName,
			Instant deadline, DeadlineRisk risk, Long waitingBusinessHours) {
	}

	public record Queues(List<QueueRow> draftReview, List<QueueRow> finalQc) {
	}

	public record Throughput(LocalDate bucket, int delivered) {
	}

	/**
	 * @param stages     the 11 funnel stages in pipeline order. Ten are live; {@code DELIVERED} counts
	 *                   deliveries inside the window.
	 * @param blocked    open cases with an exception state — see Q20
	 * @param throughput zero-filled: a day with no deliveries is a real zero, unlike an unmeasured metric
	 */
	public record PmOverview(List<StageCount> stages, int active, int blocked, int awaitingReview,
			int awaitingQc, int readyToDeliver, Queues queues, List<Throughput> throughput) {
	}

	@Transactional(readOnly = true)
	public PmOverview forCaller(Instant from, Instant to, UUID brandId) {
		List<Case> scoped = pm.scoped(brandId);
		Instant now = Instant.now();

		Map<Stage, List<Case>> byStage = new EnumMap<>(Stage.class);
		for (Case subject : scoped) {
			byStage.computeIfAbsent(subject.getCurrentStage(), key -> new ArrayList<>()).add(subject);
		}

		List<StageCount> stages = new ArrayList<>();
		for (Stage stage : Stage.values()) {
			if (stage == Stage.CLOSED) {
				continue;
			}
			List<Case> inStage = byStage.getOrDefault(stage, List.of());
			if (stage == Stage.DELIVERED) {
				stages.add(new StageCount(stage, (int) inStage.stream().filter(c -> inWindow(c, from, to)).count(), null));
			} else {
				stages.add(new StageCount(stage, inStage.size(), medianAge(inStage, now)));
			}
		}

		List<Case> open = scoped.stream()
				.filter(c -> c.getCurrentStage() != Stage.DELIVERED && c.getCurrentStage() != Stage.CLOSED)
				.toList();

		Map<UUID, String> cmNames = names(Role.CASE_MANAGER);
		Map<UUID, String> pmNames = names(Role.PROJECT_MANAGER);

		return new PmOverview(
				stages,
				open.size(),
				(int) open.stream().filter(c -> c.getExceptionState() != ExceptionState.NONE).count(),
				byStage.getOrDefault(Stage.DRAFT_REVIEW, List.of()).size(),
				byStage.getOrDefault(Stage.FINAL_QC, List.of()).size(),
				byStage.getOrDefault(Stage.READY_TO_DELIVER, List.of()).size(),
				new Queues(
						// Draft review waits on the PM but the draft is the CM's work — the owner shown is its author.
						queue(byStage.get(Stage.DRAFT_REVIEW), Case::getAssignedCm, cmNames, now),
						queue(byStage.get(Stage.FINAL_QC), Case::getAssignedPm, pmNames, now)),
				throughput(scoped, from, to));
	}

	private static boolean inWindow(Case subject, Instant from, Instant to) {
		Instant date = subject.getDeliveryDate();
		// Half-open [from, to), for the reason PmMetricsService.tally states at length.
		return date != null && !date.isBefore(from) && date.isBefore(to);
	}

	private Long medianAge(List<Case> inStage, Instant now) {
		List<Long> hours = inStage.stream()
				.filter(c -> c.getStageEnteredAt() != null)
				.map(c -> calendar.elapsedBusinessTime(c.getStageEnteredAt(), now).toHours())
				.sorted()
				.toList();
		if (hours.isEmpty()) {
			return null;
		}
		int size = hours.size();
		return size % 2 == 1 ? hours.get(size / 2) : (hours.get(size / 2 - 1) + hours.get(size / 2)) / 2;
	}

	private List<QueueRow> queue(List<Case> cases, java.util.function.Function<Case, UUID> owner,
			Map<UUID, String> names, Instant now) {
		if (cases == null) {
			return List.of();
		}
		return cases.stream()
				.map(c -> new QueueRow(c.getId(), c.getCaseCode(), c.getServiceType(),
						owner.apply(c) == null ? null : PmMetricsService.name(names, owner.apply(c)),
						c.getDeadline(), deadlines.riskOf(c, now),
						c.getStageEnteredAt() == null ? null
								: calendar.elapsedBusinessTime(c.getStageEnteredAt(), now).toHours()))
				// Longest-waiting first; a case with no entry time has no wait to rank, so it goes last.
				.sorted(Comparator.comparing(QueueRow::waitingBusinessHours,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(QUEUE_CAP)
				.toList();
	}

	private List<Throughput> throughput(List<Case> scoped, Instant from, Instant to) {
		ZoneId zone = BusinessCalendar.ZONE;
		LocalDate first = from.atZone(zone).toLocalDate();
		LocalDate last = to.minusNanos(1).atZone(zone).toLocalDate();
		boolean daily = ChronoUnit.DAYS.between(first, last) < DAILY_MAX_DAYS;

		Map<LocalDate, Integer> buckets = new LinkedHashMap<>();
		LocalDate cursor = daily ? first : first.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
		while (!cursor.isAfter(last)) {
			buckets.put(cursor, 0);
			cursor = cursor.plusDays(daily ? 1 : 7);
		}
		for (Case subject : scoped) {
			if (!inWindow(subject, from, to)) {
				continue;
			}
			LocalDate day = subject.getDeliveryDate().atZone(zone).toLocalDate();
			buckets.merge(daily ? day : day.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)),
					1, Integer::sum);
		}
		return buckets.entrySet().stream().map(e -> new Throughput(e.getKey(), e.getValue())).toList();
	}

	private Map<UUID, String> names(Role role) {
		Map<UUID, String> names = new LinkedHashMap<>();
		for (TeamMember member : members.assignable(role)) {
			names.put(member.getId(), member.getDisplayName());
		}
		return names;
	}
}
```

- [ ] **Step 6: Run to verify they pass**

Run: `cd backend && ./mvnw -q -Dtest=PmOverviewServiceTest,PmMetricsServiceTest test`
Expected: PASS. If `Case#getId()` is absent in the test, use the real id getter (`grep -n "UUID getId" backend/src/main/java/com/ie/evalos/domain/*.java`).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/service/PmMetricsService.java backend/src/main/java/com/ie/evalos/service/PmOverviewService.java backend/src/test/java/com/ie/evalos/service/PmOverviewServiceTest.java backend/src/test/java/com/ie/evalos/service/PmMetricsServiceTest.java
git commit -m "feat(pm): overview service — stage funnel, aging, review/QC queues, throughput (spec 79)"
```

---

### Task 2: `GET /api/metrics/pm/overview` and its role gate

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/web/MetricsController.java`
- Modify: `backend/src/test/java/com/ie/evalos/web/GmOverviewRouteTest.java`
- Create: `backend/src/test/java/com/ie/evalos/web/PmOverviewRouteTest.java`

**Interfaces:**
- Consumes: `PmOverviewService.forCaller(Instant, Instant, UUID): PmOverview` (Task 1).
- Produces: `GET /api/metrics/pm/overview?range=&from=&to=&brandId=` → `ApiResponse<PmOverview>`.

- [ ] **Step 1: Write the failing route test**

Copy the annotations, `@Import`, `@TestPropertySource`, and the `@MockitoBean` list and token helper from `GmOverviewRouteTest.java` (read it; reuse its token-building helper verbatim), add `@MockitoBean PmOverviewService overview;`, and write:

```java
	@ParameterizedTest
	@EnumSource(value = Role.class, names = { "GM", "BRAND_MANAGER", "PROJECT_MANAGER" })
	void pmOverviewIsOpenToTheSameRolesAsPm(Role role) throws Exception {
		given(overview.forCaller(any(), any(), any())).willReturn(null);
		mvc.perform(get("/api/metrics/pm/overview").header(HttpHeaders.AUTHORIZATION, tokenFor(role)))
				.andExpect(status().isOk());
	}

	@ParameterizedTest
	@EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = { "GM", "BRAND_MANAGER", "PROJECT_MANAGER" })
	void everyOtherRoleIsRefused(Role role) throws Exception {
		mvc.perform(get("/api/metrics/pm/overview").header(HttpHeaders.AUTHORIZATION, tokenFor(role)))
				.andExpect(status().isForbidden());
		then(overview).should(never()).forCaller(any(), any(), any());
	}
```

(`tokenFor` is whatever the existing test names its helper; match it.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q -Dtest=PmOverviewRouteTest test`
Expected: FAIL — 404/compile error (no route, no bean).

- [ ] **Step 3: Implement**

In `MetricsController`: add field `private final PmOverviewService pmOverview;`, add `PmOverviewService pmOverview` as the last constructor parameter and assign it, then add below `pm(...)`:

```java
	/** Stage funnel, aging, review/QC queues and throughput. Same gate and period vocabulary as {@code /pm}. */
	@GetMapping("/pm/overview")
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<PmOverviewService.PmOverview> pmOverview(@RequestParam(defaultValue = "month") String range,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) UUID brandId) {
		DateWindow window = DateWindow.of(range, from, to, BusinessCalendar.clock());
		return ApiResponse.ok(pmOverview.forCaller(window.startInstant(), window.endInstant(), brandId));
	}
```

Add `import com.ie.evalos.service.PmOverviewService;` as needed. In `GmOverviewRouteTest` add `@MockitoBean PmOverviewService pmOverview;` (the controller constructor changed, so the slice will not start without it).

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw -q -Dtest=PmOverviewRouteTest,GmOverviewRouteTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/web/MetricsController.java backend/src/test/java/com/ie/evalos/web/
git commit -m "feat(pm): GET /api/metrics/pm/overview, PM/GM/Brand Manager only (spec 79)"
```

---

### Task 3: Frontend wire types and pure rules

**Files:**
- Modify: `frontend/src/features/dashboards/pmMetricsApi.ts`
- Create: `frontend/src/features/dashboards/pmOverviewRules.ts`
- Create: `frontend/src/features/dashboards/pmOverviewRules.test.ts`

**Interfaces:**
- Produces (used by Tasks 5–6): `PmOverview`, `StageCount`, `QueueRow`, `ThroughputPoint`, `fetchPmOverview(range, brandId, signal)`, and from rules: `FUNNEL_STAGES: readonly Stage[]`, `barPercent(count: number, max: number): number`, `ageText(hours: number | null): string`, `stageHref(stage: Stage): string | null`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { ageText, barPercent, FUNNEL_STAGES, stageHref } from './pmOverviewRules'

describe('pmOverviewRules', () => {
  it('funnel is the eleven stages without CLOSED, in pipeline order', () => {
    expect(FUNNEL_STAGES).toHaveLength(11)
    expect(FUNNEL_STAGES[0]).toBe('DOC_COLLECTION')
    expect(FUNNEL_STAGES.at(-1)).toBe('DELIVERED')
    expect(FUNNEL_STAGES).not.toContain('CLOSED')
  })
  it('a non-empty stage always has a visible bar, an empty one none', () => {
    expect(barPercent(1, 200)).toBeGreaterThanOrEqual(2)
    expect(barPercent(0, 200)).toBe(0)
    expect(barPercent(5, 0)).toBe(0)
    expect(barPercent(200, 200)).toBe(100)
  })
  it('age is hours, and unknown is a dash rather than 0h', () => {
    expect(ageText(null)).toBe('—')
    expect(ageText(0)).toBe('0h')
    expect(ageText(31)).toBe('31h')
  })
  it('delivered cases are not on the board, so that row does not link to the inbox', () => {
    expect(stageHref('DELIVERED')).toBeNull()
    expect(stageHref('FINAL_QC')).toBe('/inbox?stage=FINAL_QC')
  })
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/features/dashboards/pmOverviewRules.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

`pmOverviewRules.ts`:

```ts
import { STAGE_ORDER } from '../case/caseProgress'
import type { Stage } from '../board/boardRules'

/** The funnel draws every stage but CLOSED, so its rows reconcile with "active" plus delivered. */
export const FUNNEL_STAGES: readonly Stage[] = STAGE_ORDER.filter((stage) => stage !== 'CLOSED')

/** Bar width in percent. A non-empty stage is never invisible; an empty one has no bar. */
export function barPercent(count: number, max: number): number {
  if (count <= 0 || max <= 0) return 0
  return Math.max(2, Math.round((count / max) * 100))
}

/** Business hours, not clock hours — the funnel's note says so. Unknown is not zero. */
export function ageText(hours: number | null): string {
  return hours === null ? '—' : `${hours}h`
}

/** Drill target. `/api/cases/board` omits DELIVERED, so that row cannot open an inbox. */
export function stageHref(stage: Stage): string | null {
  return stage === 'DELIVERED' ? null : `/inbox?stage=${stage}`
}
```

In `pmMetricsApi.ts` add `overdue: number` to `CmWorkload`, and append after `fetchPmMetrics`:

```ts
/** `PmOverviewService.PmOverview` — see spec 79 §3. */
export type StageCount = { stage: Stage; count: number; medianAgeBusinessHours: number | null }
export type QueueRow = {
  caseId: string
  caseCode: string
  serviceType: string | null
  ownerName: string | null
  deadline: string | null
  risk: DeadlineRisk | null
  /** Null when the case has no stage-entry time — unknown, not zero. */
  waitingBusinessHours: number | null
}
export type ThroughputPoint = { bucket: string; delivered: number }
export type PmOverview = {
  stages: StageCount[]
  active: number
  blocked: number
  awaitingReview: number
  awaitingQc: number
  readyToDeliver: number
  queues: { draftReview: QueueRow[]; finalQc: QueueRow[] }
  throughput: ThroughputPoint[]
}

export async function fetchPmOverview(
  range: DateRange,
  brandId: string | null,
  signal?: AbortSignal,
): Promise<PmOverview> {
  const params: Record<string, string> = rangeParams(range)
  if (brandId) params.brandId = brandId
  return unwrap<PmOverview>(api.get('/metrics/pm/overview', { params, signal }))
}
```

Change the top import to `import type { DeadlineRisk, Stage } from '../board/boardRules'`.

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npx vitest run src/features/dashboards/pmOverviewRules.test.ts && npx tsc -b`
Expected: PASS, tsc clean (an `AssignPopover` or test fixture constructing `CmWorkload` needs `overdue: 0` — fix any such error `tsc` reports).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/dashboards/pmMetricsApi.ts frontend/src/features/dashboards/pmOverviewRules.ts frontend/src/features/dashboards/pmOverviewRules.test.ts
git commit -m "feat(pm): overview wire types and funnel rules (spec 79)"
```

---

### Task 4: Inbox `?stage=` filter

**Files:**
- Modify: `frontend/src/features/queues/queueRules.ts`
- Modify: `frontend/src/features/queues/queueRules.test.ts`
- Modify: `frontend/src/features/queues/InboxPage.tsx`

**Interfaces:**
- Produces: `inboxQueue(data, view, now?, stage?: Stage | null)`; `/inbox?stage=<STAGE>` narrows the list to that stage (exception-lane cards included, so counts match the funnel).

- [ ] **Step 1: Write the failing test**

In `queueRules.test.ts`, using that file's existing board/card fixture helpers (read the top of the file and reuse them), add:

```ts
  it('narrows the inbox to one stage, exception-lane cases included', () => {
    const data = boardOf([
      card({ id: 'a', currentStage: 'FINAL_QC' }),
      card({ id: 'b', currentStage: 'DRAFT_REVIEW' }),
    ])
    expect(inboxQueue(data, 'all', new Date(), 'FINAL_QC').map((c) => c.id)).toEqual(['a'])
    expect(inboxQueue(data, 'all', new Date(), null)).toHaveLength(2)
  })
```

(Adapt `boardOf`/`card` to the real helper names in the file.)

- [ ] **Step 2: Run to verify it fails**

Run: `cd frontend && npx vitest run src/features/queues/queueRules.test.ts`
Expected: FAIL — extra argument ignored, both cards returned.

- [ ] **Step 3: Implement**

`queueRules.ts`:

```ts
export function inboxQueue(
  data: BoardData,
  view: InboxView,
  now: Date = new Date(),
  stage: Stage | null = null,
): BoardCard[] {
  return byDeadline(
    allCards(data).filter((card) => (stage === null || card.currentStage === stage) && matches(card, view, now)),
  )
}
```

(import `Stage` in the existing type import.) In `InboxPage.tsx`: import `STAGE_ORDER, STAGE_SHORT` from `../case/caseProgress`, then after `view`:

```tsx
  const rawStage = params.get('stage')
  const stage = STAGE_ORDER.find((entry) => entry === rawStage) ?? null
  const rows = data ? inboxQueue(data, view, new Date(), stage) : []
```

(replace the existing `rows` line) and render, directly under the `<header>`:

```tsx
      {stage && (
        <button
          type="button"
          onClick={() => setParams(view === 'all' ? {} : { view })}
          className="mt-3 h-8 px-3 text-sm"
          style={{ borderRadius: 'var(--radius-md)', background: 'var(--accent-soft)', border: '1px solid var(--accent-primary)', color: 'var(--accent-primary)' }}
        >
          Stage: {STAGE_SHORT[stage]} ✕
        </button>
      )}
```

Make the view buttons keep the stage: change their `onClick` to `setParams({ ...(entry.view === 'all' ? {} : { view: entry.view }), ...(stage ? { stage } : {}) })`. Change the header count to `inboxQueue(data, 'all', new Date(), stage).length`.

- [ ] **Step 4: Run to verify it passes**

Run: `cd frontend && npx vitest run src/features/queues && npx tsc -b`
Expected: PASS, tsc clean.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/queues/
git commit -m "feat(inbox): ?stage= filter so a funnel stage opens its cases (spec 79)"
```

---

### Task 5: Presentational cards

**Files:**
- Modify: `frontend/src/components/ui/card.tsx` (`CapacityBar`)
- Create: `frontend/src/features/dashboards/StageFunnel.tsx`, `QueueTable.tsx`, `ThroughputCard.tsx`

**Interfaces:**
- Consumes: Task 3 types/rules; `Card`, `CardState` from `components/ui/card`.
- Produces: `<StageFunnel stages={StageCount[] | undefined} state={CardState} />`, `<QueueTable title note rows state emptyNote className? />`, `<ThroughputCard points={ThroughputPoint[] | undefined} state period />`, `CapacityBar` gains optional `overdue?: number`.

- [ ] **Step 1: `CapacityBar` overdue**

Add `overdue` to the props type (`overdue?: number`) and the destructuring, and in the right-hand `<span>` after the percent span:

```tsx
          {overdue ? (
            <span className="ml-2" style={{ color: "var(--status-red)" }}>
              {overdue} overdue
            </span>
          ) : null}
```

Add `, ${overdue ?? 0} overdue` to the bar's `aria-label`.

- [ ] **Step 2: `StageFunnel.tsx`**

```tsx
import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import { STAGE_SHORT } from '../case/caseProgress'
import type { StageCount } from './pmMetricsApi'
import { ageText, barPercent, stageHref } from './pmOverviewRules'

/** One bar per pipeline stage: how many cases are there, and how long the typical one has waited. */
export function StageFunnel({ stages, state, className }: { stages?: StageCount[]; state: CardState; className?: string }) {
  const max = Math.max(0, ...(stages ?? []).map((row) => row.count))
  return (
    <Card
      title="Pipeline by stage"
      note="Cases per stage right now (Delivered: in the period). Age is the median business hours in the stage."
      state={state}
      className={className}
    >
      <ul className="space-y-1">
        {stages?.map((row) => {
          const href = stageHref(row.stage)
          const body = (
            <>
              <span className="w-28 shrink-0 truncate text-sm">{STAGE_SHORT[row.stage]}</span>
              <span className="h-3 flex-1 overflow-hidden rounded-md" style={{ background: 'var(--bg-raised)' }}>
                <span
                  className="block h-full rounded-md"
                  style={{ width: `${barPercent(row.count, max)}%`, background: 'var(--accent-primary)' }}
                />
              </span>
              <span className="font-num w-10 text-right text-sm tabular-nums">{row.count}</span>
              <span className="font-num w-14 text-right text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                {row.stage === 'DELIVERED' ? '' : ageText(row.medianAgeBusinessHours)}
              </span>
            </>
          )
          return (
            <li key={row.stage}>
              {href ? (
                <Link to={href} className="flex items-center gap-3 py-1">{body}</Link>
              ) : (
                <div className="flex items-center gap-3 py-1">{body}</div>
              )}
            </li>
          )
        })}
      </ul>
    </Card>
  )
}
```

- [ ] **Step 3: `QueueTable.tsx`**

```tsx
import { Link } from 'react-router-dom'
import { Card, type CardState } from '../../components/ui/card'
import type { QueueRow } from './pmMetricsApi'
import { ageText } from './pmOverviewRules'

const RISK_COLOR = { OVERDUE: 'var(--status-red)', AT_RISK: 'var(--status-amber)', ON_TRACK: 'var(--text-muted)' } as const
const RISK_TEXT = { OVERDUE: 'Overdue', AT_RISK: 'At risk', ON_TRACK: 'On track' } as const

/** A waiting queue, longest wait first. Rows open the existing case page; no new actions live here. */
export function QueueTable({
  title, note, rows, state, emptyNote, className,
}: { title: string; note: string; rows?: QueueRow[]; state: CardState; emptyNote: string; className?: string }) {
  const shown: CardState = state.kind === 'ok' && rows?.length === 0 ? { kind: 'empty', note: emptyNote } : state
  return (
    <Card title={title} note={note} state={shown} className={className}>
      <table className="w-full text-left text-sm">
        <thead>
          <tr style={{ color: 'var(--text-muted)' }}>
            <th className="py-1 font-normal">Case</th>
            <th className="py-1 font-normal">Owner</th>
            <th className="py-1 font-normal">Deadline</th>
            <th className="py-1 text-right font-normal">Waiting</th>
          </tr>
        </thead>
        <tbody>
          {rows?.map((row) => (
            <tr key={row.caseId}>
              <td className="py-1.5">
                <Link to={`/cases/${row.caseId}`} className="font-mono text-xs font-medium">{row.caseCode}</Link>
              </td>
              <td className="py-1.5">{row.ownerName ?? '—'}</td>
              <td className="py-1.5">
                {/* Status is text as well as colour: colour alone never carries it. */}
                {row.risk ? <span style={{ color: RISK_COLOR[row.risk] }}>{RISK_TEXT[row.risk]}</span> : '—'}
                {row.deadline ? (
                  <span className="font-num ml-2 text-xs tabular-nums" style={{ color: 'var(--text-muted)' }}>
                    {new Date(row.deadline).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                  </span>
                ) : null}
              </td>
              <td className="font-num py-1.5 text-right tabular-nums">{ageText(row.waitingBusinessHours)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  )
}
```

- [ ] **Step 4: `ThroughputCard.tsx`**

```tsx
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, type CardState } from '../../components/ui/card'
import type { ThroughputPoint } from './pmMetricsApi'

const label = (iso: string) =>
  new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })

/** Cases delivered per day (up to a month) or per week, over the period the header names. */
export function ThroughputCard({ points, state, period, className }: { points?: ThroughputPoint[]; state: CardState; period: string; className?: string }) {
  return (
    <Card title="Delivered over time" note={`Cases delivered, ${period}.`} state={state} className={className}>
      <div className="h-56 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={points ?? []}>
            <CartesianGrid stroke="var(--border-default)" vertical={false} />
            <XAxis dataKey="bucket" tickFormatter={label} tick={{ fontSize: 12 }} />
            <YAxis allowDecimals={false} tick={{ fontSize: 12 }} width={28} />
            <Tooltip labelFormatter={label} formatter={(value) => [value, 'Delivered']} />
            <Line dataKey="delivered" stroke="var(--accent-primary)" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </Card>
  )
}
```

- [ ] **Step 5: Type-check**

Run: `cd frontend && npx tsc -b`
Expected: clean. (`QueueTable.tsx`/`StageFunnel.tsx` use only tokens already used in `card.tsx`; if a `--status-*` or `--bg-raised` token name differs, grep `tokens.css` and use the real one.)

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/ui/card.tsx frontend/src/features/dashboards/
git commit -m "feat(pm): StageFunnel, QueueTable, ThroughputCard, CapacityBar overdue (spec 79)"
```

---

### Task 6: Wire `PmDashboard`

**Files:**
- Modify: `frontend/src/features/dashboards/PmDashboard.tsx`

**Interfaces:**
- Consumes: Tasks 3 and 5.

- [ ] **Step 1: Add the second, independent load**

Imports: `fetchPmOverview, type PmOverview` from `./pmMetricsApi`; `StageFunnel`, `QueueTable`, `ThroughputCard` from their files. Under the existing `useMetrics` call:

```tsx
  // A separate load, so a failed overview cannot blank the six tiles above it or the reverse.
  const { data: overview, state: overviewState } = useMetrics<PmOverview>(
    (signal) => fetchPmOverview(dateRange, activeBrandId, signal),
    [dateRange, activeBrandId],
  )
```

- [ ] **Step 2: Add the "right now" KPI strip and the new cards**

Directly under the existing `<header>` and before the current grid, insert (a `now` strip is labelled as ignoring the period):

```tsx
      <p className="mt-4 text-xs" style={{ color: 'var(--text-muted)' }}>
        Right now — these cases ignore the period above.
      </p>
      <div className="mt-2 grid gap-4 md:grid-cols-2 xl:grid-cols-5">
        <KpiCard title="Active cases" state={overviewState} to="/inbox" value={overview?.active ?? null} />
        <KpiCard title="Blocked cases" state={overviewState} value={overview?.blocked ?? null}
          note="On hold, expert declined, or refund requested." />
        <KpiCard title="Drafts awaiting review" state={overviewState} to="/drafts" value={overview?.awaitingReview ?? null} />
        <KpiCard title="Cases awaiting QC" state={overviewState} to="/inbox?stage=FINAL_QC" value={overview?.awaitingQc ?? null} />
        <KpiCard title="Ready to deliver" state={overviewState} to="/inbox?stage=READY_TO_DELIVER" value={overview?.readyToDeliver ?? null} />
      </div>
```

After the existing grid's last card (before `</div></section>`), inside a new grid `<div className="mt-4 grid gap-4 xl:grid-cols-2">`:

```tsx
        <StageFunnel stages={overview?.stages} state={overviewState} />
        <ThroughputCard points={overview?.throughput} state={overviewState} period={rangeLabel(dateRange)} />
        <QueueTable title="Draft review queue" note="Cases waiting on a PM decision. Owner is the draft's author."
          rows={overview?.queues.draftReview} state={overviewState} emptyNote="No drafts are waiting for review." />
        <QueueTable title="Final QC queue" note="Cases waiting on final quality check. Owner is the assigned PM."
          rows={overview?.queues.finalQc} state={overviewState} emptyNote="Nothing is waiting for final QC." />
        <Card title="First-pass QC rate" state={{ kind: 'unavailable', blockedBy: 'Q21 — no QC outcome is recorded per case' }} />
```

In the existing workload card pass the overdue count: `<CapacityBar key={row.cmId} label={row.name} used={row.active} capacity={row.capacity} overdue={row.overdue} />`.

- [ ] **Step 3: Verify**

Run: `cd frontend && npx tsc -b && npx vitest run`
Expected: clean, all green.

- [ ] **Step 4: Browser check (spec §6)**

Start the app (use the `run` skill / project's dev script). As a PM, open `/dashboard`: the strip, funnel, two queues, throughput and the unavailable QC card render at 1440/1024/390 without horizontal scroll; click a funnel row → `/inbox?stage=…` shows exactly that row's count and a clearable chip; click "Drafts awaiting review" → `/drafts`. Block `/api/metrics/pm/overview` in devtools → only the new cards show an error, the original six tiles stay. Sign in as a Case Manager and open `/dashboard` directly → CM dashboard, not this one. State plainly in the final report which of these were actually run (local backend `/api/metrics/*` answered 500 on 2026-10-08, per `implementation-status.md`).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/features/dashboards/PmDashboard.tsx
git commit -m "feat(pm): PM dashboard — right-now strip, funnel, review/QC queues, throughput (spec 79)"
```

---

### Task 7: Documentation, in the same step

**Files:**
- Modify: `.claude/implementation-status.md`, `.claude/workflows.md`, `.serena/memories/implementation_status.md`, `.serena/memories/workflows.md`, `.claude/open-decisions.md` (only if Q20–Q22 are answered), `context/specs/79-pm-dashboard-analytics.md`

- [ ] **Step 1:** Edit `context/specs/79-pm-dashboard-analytics.md` in place: `Status: built <date>`; §3 workload is `CmWorkload.overdue` on `/pm` (not duplicated in the overview); throughput reads `deliveryDate` (the field `onTime` already uses), not audit rows; §4 `ThroughputCard` is new because `TrendCard` is GM/money-specific.
- [ ] **Step 2:** Add a row to `.claude/implementation-status.md` with evidence (test class names and counts, `tsc` result, what was and was not browser-checked) and update the PM paragraph in `.claude/workflows.md` CURRENT IMPLEMENTATION.
- [ ] **Step 3:** Mirror both into `.serena/memories/implementation_status.md` and `workflows.md` as edits to the existing lines, not appended contradictions. (The Serena MCP server failed to connect this session; edit the files directly.)
- [ ] **Step 4:** Commit: `git add .claude .serena context/specs && git commit -m "docs(pm): spec 79 built — status, workflows, memories"`

## Self-review notes

- **Spec coverage:** §1 stages → Task 3/5 (11-stage funnel, reuses `STAGE_SHORT`); §2 definitions → Task 1 (each tested); §3 endpoint → Tasks 1–2; §4 components and states → Tasks 5–6; §5 unavailable → Task 6 QC card, Expert-response tile left as is; §6 verification → Task 6 Step 4. PC/Expert capacity bars and "reassignment" are intentionally not added (spec §5).
- **Deviations from the spec, recorded in Task 7:** workload overdue rides `/pm` (additive field) instead of a duplicate in the overview; throughput from `deliveryDate`; new `ThroughputCard`.
- **Known pre-existing issue, not fixed here:** the "Delivered on time" tile links to `/inbox?view=delivered`, which is not an `InboxView` and falls back to "All open"; delivered cases are not on the board, so the fix is a separate decision.
- **Type consistency:** `PmOverview`/`StageCount`/`QueueRow`/`Throughput` field names are identical in Java records, `pmMetricsApi.ts` and the components.
