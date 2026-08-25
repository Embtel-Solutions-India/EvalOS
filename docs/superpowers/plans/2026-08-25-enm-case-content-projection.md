# ENM Case-Content Projection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the Expert Network Manager receiving client identity and case content, which `architecture.md` says they must never see and which the code currently returns on two endpoints.

**Architecture:** Field-level projection, derived from `Role.Tier.SUPPLY` rather than from a new role list. The ENM keeps its brand-wide case *row* read — its three signing transitions need it — while the client-identity and case-content fields move behind a predicate. A `maySeeCaseContent` flag travels with the payload so the UI can say "withheld" instead of silently rendering a fallback that states something false.

**Tech Stack:** Java 21, Spring Boot, Spring Security (`@PreAuthorize` + ABAC tiers), JUnit 5 + MockMvc, React 19 + TypeScript.

**Spec:** `context/specs/22-role-operations-ui.md` — the **Prerequisite** section. This plan implements that section only; slices 1–5 get their own plans.

## Global Constraints

- **Brand-scoped by default.** Every scoped query filters `brand_id`. This change must not widen any scope — it only narrows a projection.
- **Append-only audit.** No update/delete path on audit rows. This change writes no audit rows.
- **One home per fact.** Do not introduce a second list of who sees case content. `Role.Tier` is the single source of truth for scoping, stated in `Role.java`'s own javadoc.
- **No new dependencies.** This change installs nothing.
- **Verification gate** (`ai-workflow-rules.md`): backend `./mvnw verify` passes and the app starts cleanly; frontend `npm run build` passes with no TypeScript or console errors.
- **Docs travel with code.** `progress-tracker.md`, `architecture.md` and the Serena memories are updated in the same change, not afterwards.
- Reference screen for any visual check is **1366 × 768**.

## Naming note (supersedes the spec's provisional name)

The spec's Prerequisite section calls the predicate `SEES_CLIENT_IDENTITY`. This plan names it **`seesCaseContent`**, because it gates three fields of which only one is identity — `driveLink` and `draftLink` are case *content*. That is also the exact wording of `Tier.SUPPLY`'s javadoc. Task 4 corrects the spec to match.

---

### Task 1: Withhold case content on the board endpoint

The wider hole, so it goes first. `CaseBoardController` has no `@PreAuthorize` — deliberately, and documented — so every staff role including the ENM can call it and receive every client name in the brand in one request.

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/web/CaseController.java` (add predicate beside `SEES_DEAL_VALUE`, line ~73)
- Modify: `backend/src/main/java/com/ie/evalos/web/CaseBoardController.java:73` (`BoardCard.of`)
- Test: `backend/src/test/java/com/ie/evalos/web/CaseBoardControllerTest.java`

**Interfaces:**
- Consumes: `Role.Tier` (existing), `CaseController.SEES_DEAL_VALUE` pattern (existing)
- Produces: `static boolean CaseController.seesCaseContent(Role role)` — used by Task 2 and by `CaseBoardController`

- [ ] **Step 1: Write the failing test**

Add to `CaseBoardControllerTest`, directly below `theDealValueIsProjectedForTheCommercialRolesAndNobodyElse`:

```java
/**
 * The Expert Network Manager is supply-side: they may see that a case needs an expert in a
 * field, never who the client is. {@code Tier.SUPPLY} says so in its own javadoc.
 */
@Test
void theClientNameIsWithheldFromTheSupplySideRole() throws Exception {
	given(board.forCaller(any(), any())).willReturn(List.of(
			row("IE-2026-0001", Stage.DOC_COLLECTION, ExceptionState.NONE, null)));

	for (Role sees : List.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER,
			Role.PROJECT_COORDINATOR, Role.CASE_MANAGER)) {
		mockMvc.perform(get("/api/cases/board").header(HttpHeaders.AUTHORIZATION, bearer(sees)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.stages.DOC_COLLECTION[0].clientName").value("Anita Rao"));
	}

	mockMvc.perform(get("/api/cases/board")
					.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
			.andExpect(status().isOk())
			// The card itself still reads — an empty board would hide work they must act on.
			.andExpect(jsonPath("$.data.stages.DOC_COLLECTION[0].caseCode").value("IE-2026-0001"))
			.andExpect(jsonPath("$.data.stages.DOC_COLLECTION[0].clientName").doesNotExist());
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
cd backend && ./mvnw test -Dtest=CaseBoardControllerTest#theClientNameIsWithheldFromTheSupplySideRole
```

Expected: **FAIL**, on the last assertion — `clientName` exists with value `"Anita Rao"` for the ENM. If it passes, stop: the defect is not present on this branch and the plan needs re-checking against `HEAD`.

- [ ] **Step 3: Add the predicate**

In `CaseController.java`, immediately after the `SEES_DEAL_VALUE` declaration (~line 73):

```java
/**
 * Whether this caller may see who the client is and what the case says.
 *
 * <p>Gates {@code clientName}, {@code driveLink} and {@code draftLink} on both the board and
 * the detail projection.
 *
 * <p><strong>Derived from the scope tier, not written as a role set</strong>, unlike
 * {@link #SEES_DEAL_VALUE} above. {@link Role.Tier#SUPPLY} already means precisely this —
 * "own brand's expert/roster supply side, not case content" — and {@code Role}'s javadoc
 * states the tier is the single source of truth for scoping. A role list here would be a
 * second copy of that fact, and the copy is what goes stale when a seventh role is added.
 */
static boolean seesCaseContent(Role role) {
	return role.tier() != Role.Tier.SUPPLY;
}
```

- [ ] **Step 4: Apply it in the board projection**

In `CaseBoardController.java`, in `BoardCard.of`, replace `row.clientName()` with the projected form. The call becomes:

```java
static BoardCard of(BoardRow row, TenantContext ctx) {
	Case subject = row.subject();
	return new BoardCard(subject.getId(), subject.getCaseCode(),
			CaseController.seesCaseContent(ctx.role()) ? row.clientName() : null,
			subject.getServiceType(), subject.getDeadline(), subject.getSlaStatus(),
			subject.getCurrentStage(), subject.getExceptionState(), subject.getPoolStatus(),
			subject.getAssignedPm(), subject.getAssignedCm(), subject.getAssignedCoordinator(),
			subject.getExpertSignStatus(), subject.getPmApprovalStatus(), subject.getClientApprovalStatus(),
			CaseController.SEES_DEAL_VALUE.contains(ctx.role()) ? subject.getDealValue() : null);
}
```

Also update the class javadoc, which currently claims only one field is role-dependent. Replace the sentence *"What \*is\* role-dependent is one field, `dealValue`, gated by the same list `CaseController` uses."* with:

```java
 * <p>Two fields are role-dependent, both gated from {@code CaseController}: {@code dealValue}
 * by {@code SEES_DEAL_VALUE}, and {@code clientName} by {@code seesCaseContent} — the supply-side
 * role works the board without ever learning who the client is.
```

- [ ] **Step 5: Run the test and confirm it passes**

```bash
cd backend && ./mvnw test -Dtest=CaseBoardControllerTest
```

Expected: **PASS**, all tests in the class, including the pre-existing four.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/web/CaseController.java \
        backend/src/main/java/com/ie/evalos/web/CaseBoardController.java \
        backend/src/test/java/com/ie/evalos/web/CaseBoardControllerTest.java
git commit -m "fix: withhold client identity from the supply-side role on the board

CaseBoardController has no @PreAuthorize by design, so every staff role
reaches it. It gated dealValue by role and passed clientName through
unconditionally on the adjacent line, which let an Expert Network Manager
read every client name in their brand in a single request.

Tier.SUPPLY already means 'not case content' and was referenced nowhere;
the predicate now derives from it rather than adding a second role list.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Withhold case content on the case detail endpoint

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/web/CaseController.java:155-200` (the `CaseDetail` record and its factory)
- Test: `backend/src/test/java/com/ie/evalos/web/CaseControllerTest.java` (extend the test at ~line 265)

**Interfaces:**
- Consumes: `CaseController.seesCaseContent(Role)` from Task 1
- Produces: `CaseDetail.maySeeCaseContent` — a boolean field on the JSON payload, consumed by Task 3

- [ ] **Step 1: Write the failing test**

Add to `CaseControllerTest`, below the existing strategy-notes projection test:

```java
/**
 * The supply-side role may act on expert signing without learning who the client is or
 * reading what was drafted. Mirrors the board's projection so the two screens cannot
 * disagree about the same case.
 */
@Test
void caseContentIsWithheldFromTheSupplySideRole() throws Exception {
	givenDetail();

	for (Role sees : List.of(Role.GM, Role.BRAND_MANAGER, Role.PROJECT_MANAGER,
			Role.PROJECT_COORDINATOR, Role.CASE_MANAGER)) {
		mockMvc.perform(get("/api/cases/{id}", CASE_ID).header(HttpHeaders.AUTHORIZATION, bearer(sees)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.maySeeCaseContent").value(true))
				.andExpect(jsonPath("$.data.clientName").exists());
	}

	mockMvc.perform(get("/api/cases/{id}", CASE_ID)
					.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
			.andExpect(status().isOk())
			// The case still reads; the three fields are absent, not blanked.
			.andExpect(jsonPath("$.data.summary.caseCode").value("IE-2026-0001"))
			.andExpect(jsonPath("$.data.maySeeCaseContent").value(false))
			.andExpect(jsonPath("$.data.clientName").doesNotExist())
			.andExpect(jsonPath("$.data.driveLink").doesNotExist())
			.andExpect(jsonPath("$.data.draftLink").doesNotExist());
}
```

- [ ] **Step 2: Run the test and confirm it fails**

```bash
cd backend && ./mvnw test -Dtest=CaseControllerTest#caseContentIsWithheldFromTheSupplySideRole
```

Expected: **FAIL** — `maySeeCaseContent` does not exist on the payload yet, so the first `sees` iteration fails on that jsonPath.

- [ ] **Step 3: Add the field to the record**

In `CaseController.CaseDetail`, add `maySeeCaseContent` immediately after `mayEditStrategyNotes`. The record's closing parameters become:

```java
			boolean maySeeStrategyNotes,
			/** Whether this caller may write the notes, so the client need not re-derive the rule. */
			boolean mayEditStrategyNotes,
			/**
			 * Whether this caller may see {@code clientName}, {@code driveLink} and {@code draftLink}.
			 *
			 * <p>Stated rather than inferred, for the reason {@link #maySeeStrategyNotes} is:
			 * {@code clientName} is <em>already</em> legitimately null when a case has no linked
			 * contact, and the UI renders that as "Unnamed contact". Without this flag a withheld
			 * name would render as a claim about the client that is not true.
			 */
			boolean maySeeCaseContent) {
```

- [ ] **Step 4: Apply the projection in the factory**

Replace the body of `CaseDetail.of`:

```java
		static CaseDetail of(CaseDetailService.CaseWithContext context, TenantContext ctx) {
			Case subject = context.subject();
			boolean seesNotes = SEES_STRATEGY_NOTES.contains(ctx.role());
			boolean seesContent = seesCaseContent(ctx.role());
			return new CaseDetail(
					CaseSummary.of(subject, ctx),
					seesContent ? context.clientName() : null,
					seesContent ? subject.getDriveLink() : null,
					seesContent ? subject.getDraftLink() : null,
					context.expertName(),
					context.expertTier(),
					context.checklist().total(),
					context.checklist().complete(),
					seesNotes ? subject.getPmStrategyNotes() : null,
					seesNotes,
					MAY_EDIT_STRATEGY_NOTES.contains(ctx.role()),
					seesContent);
		}
```

`expertName` and `expertTier` stay unprojected on purpose: the ENM's job *is* the expert, and the roster is already theirs.

- [ ] **Step 5: Run the full backend suite**

```bash
cd backend && ./mvnw verify
```

Expected: **PASS**, all tests, none skipped. The pre-existing `CaseControllerTest` strategy-notes test must still pass unchanged — if it fails, the record's parameter order was edited rather than appended.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/web/CaseController.java \
        backend/src/test/java/com/ie/evalos/web/CaseControllerTest.java
git commit -m "fix: withhold client identity and case content on case detail

CaseDetail returned clientName, driveLink and draftLink unconditionally;
pmStrategyNotes was the only projected field. The existing test at
CaseControllerTest asserted the ENM reads case detail successfully and
never asked what came back with it.

maySeeCaseContent travels with the payload for the same reason
maySeeStrategyNotes does: clientName is already legitimately null when no
contact is linked, so absence alone cannot distinguish withheld from unset.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Render withheld honestly in the UI

Without this the fix is correct on the wire and misleading on screen: `StageActions.tsx:73` renders `clientName ?? 'Unnamed contact'`, so a withheld name would assert the client has no name.

**Files:**
- Modify: `frontend/src/features/case/caseApi.ts:14-33` (the `CaseDetail` type)
- Modify: `frontend/src/features/case/StageActions.tsx:73`
- Modify: `frontend/src/features/case/DocumentsPanel.tsx:26`

**Interfaces:**
- Consumes: `maySeeCaseContent: boolean` from Task 2's payload
- Produces: nothing consumed downstream

**On testing:** no frontend test is added here, deliberately. The repository has **no component-test harness** — every frontend test (`boardRules`, `checklistRules`, `redactionRules`, `portalRules`, `expertRules`, `shortlistRules`, `navigation`) is a pure-function rules test with no DOM. Introducing a rendering harness for a two-branch display change is scaffolding the codebase has explicitly avoided. The behaviour is guarded by Task 2's server tests, and TypeScript makes the new field non-optional so every consumer must handle it.

- [ ] **Step 1: Add the field to the type**

In `caseApi.ts`, inside `export type CaseDetail = {`, add beside the existing `maySeeStrategyNotes`:

```ts
  /**
   * Whether the server sent `clientName`, `driveLink` and `draftLink` at all.
   *
   * `clientName` is null both when it is withheld and when no contact is linked to the case,
   * so the value alone cannot tell those apart — same reason `maySeeStrategyNotes` exists.
   */
  maySeeCaseContent: boolean
```

- [ ] **Step 2: Run the type check and confirm it fails**

```bash
cd frontend && npx tsc -b
```

Expected: **FAIL** — every place constructing a `CaseDetail` literal (test fixtures, if any) now misses a required property. If it passes, the type was added to the wrong shape.

- [ ] **Step 3: Distinguish withheld from unnamed in the header**

In `StageActions.tsx`, replace line 73's expression:

```tsx
{detail.maySeeCaseContent ? (detail.clientName ?? 'Unnamed contact') : 'Client withheld'}
```

- [ ] **Step 4: Say why the documents folder is absent**

In `DocumentsPanel.tsx`, the existing guard at line 26 is `{detail.driveLink ? (`. Its else-branch currently covers "no folder linked". Extend the condition so the two causes read differently — replace the guard's else-branch content with a conditional message:

```tsx
{!detail.maySeeCaseContent
  ? 'The client document folder is not available to your role.'
  : 'No document folder is linked to this case yet.'}
```

- [ ] **Step 5: Build and confirm clean**

```bash
cd frontend && npm run build && npm test
```

Expected: **PASS** — `tsc -b` clean, vite build clean, all existing frontend tests green.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/case/caseApi.ts \
        frontend/src/features/case/StageActions.tsx \
        frontend/src/features/case/DocumentsPanel.tsx
git commit -m "fix: say 'withheld' rather than 'Unnamed contact' for a hidden client

clientName is null both when withheld and when no contact is linked, so the
fallback asserted something false about the client for the supply-side role.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Correct the comments that stated the opposite, and sync the docs

Two comments in the codebase currently assert what the code did not do. Per `ai-workflow-rules.md`, docs and Serena memories are updated in the same change, not as a later tidy-up.

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/domain/Role.java` (the `SUPPLY` tier javadoc)
- Modify: `frontend/src/features/board/boardRules.ts` (`STAGE_ACCESS`, the ENM row)
- Modify: `context/specs/22-role-operations-ui.md` (rename `SEES_CLIENT_IDENTITY` → `seesCaseContent`)
- Modify: `context/progress-tracker.md`
- Modify: `context/architecture.md`
- Modify: `.serena/memories/backend/` — the security memory

- [ ] **Step 1: Make `Tier.SUPPLY`'s javadoc describe what it does**

In `Role.java`, replace the `SUPPLY` entry's comment:

```java
		/**
		 * Own brand, like {@link #BRAND} — the row scope is identical and this tier adds no
		 * predicate in {@code ScopePredicate}.
		 *
		 * <p>What makes it supply-side is <strong>field</strong> projection, not row scope:
		 * {@code CaseController.seesCaseContent} withholds {@code clientName}, {@code driveLink}
		 * and {@code draftLink} from this tier on every case payload. The row is readable because
		 * the Expert Network Manager has three case transitions (expert signed / declined /
		 * reassign) that must load it.
		 *
		 * <p>This javadoc used to say "not case content" while the tier was referenced nowhere and
		 * excluded nothing.
		 */
		SUPPLY
```

- [ ] **Step 2: Note the unreachable `STAGE_ACCESS` row**

In `boardRules.ts`, above the `EXPERT_NETWORK_MANAGER` entry inside `STAGE_ACCESS`:

```ts
  // Unreachable today: the ENM has no `/board` entry in NAV_ITEMS, and `boardPathFor` says so
  // in as many words. The row stays because STAGE_ACCESS is `Record<Role, ...>` — dropping it
  // would weaken the type that forces a new role to declare its board access. Delete it only
  // together with the type change, never to tidy up.
  EXPERT_NETWORK_MANAGER: {
```

- [ ] **Step 3: Align the spec's provisional name**

In `context/specs/22-role-operations-ui.md`, in the Prerequisite section, replace both occurrences of `SEES_CLIENT_IDENTITY` with `seesCaseContent`, and `maySeeClientIdentity` with `maySeeCaseContent`. The surrounding reasoning is unchanged and stays.

- [ ] **Step 4: Record the finding in the tracker**

Append to `context/progress-tracker.md` under the security section:

```markdown
- **The supply-side tier excluded nothing (fixed 2026-08-25).** `Role.Tier.SUPPLY`'s javadoc
  said "not case content"; `ScopePredicate` handled it under `default -> {}` alongside `BRAND`,
  and `SUPPLY` was referenced nowhere else in the codebase. Combined with `CaseBoardController`
  having no `@PreAuthorize` — deliberate, and sound only if the scope narrows — an Expert
  Network Manager could `GET /api/cases/board` and receive **every client name in their brand
  in one request**. `CaseController.CaseDetail` leaked the same field plus `driveLink` and
  `draftLink`, one case at a time. Bounded: authenticated ENM, own brand only, no cross-tenant
  reach.
  Fixed by **field projection derived from the tier** (`CaseController.seesCaseContent`), not by
  narrowing the row scope — the ENM's three signing transitions must still load the case.
  `maySeeCaseContent` ships with the payload because `clientName` is already legitimately null
  when no contact is linked, and the UI rendered that as "Unnamed contact".
  **`CaseControllerTest` had been asserting the ENM reads case detail successfully** and never
  asked what came back with it; that test is now extended rather than replaced.
```

- [ ] **Step 5: State the invariant is enforced, not just declared**

In `context/architecture.md`, at the ENM/supply-side axis description, add:

```markdown
This axis is enforced in code as of 2026-08-25: `CaseController.seesCaseContent` derives from
`Role.Tier.SUPPLY` and withholds `clientName`, `driveLink` and `draftLink` from every case
payload — the board's and the detail's alike. The tier governs **field** visibility; row scope
for this tier is brand-wide, because the ENM's signing transitions must load the case.
```

- [ ] **Step 6: Update the Serena security memory**

Edit the backend security memory in `.serena/memories/backend/` — do **not** append a note beside the old statement. If it describes `Tier.SUPPLY` as restricting case reads, that sentence is wrong and is replaced by: the tier is brand-wide on rows and restrictive on fields, via `seesCaseContent`.

- [ ] **Step 7: Full verification**

```bash
cd backend && ./mvnw verify
cd ../frontend && npm run build && npm test
```

Expected: backend all green with none skipped; frontend build clean and tests green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/domain/Role.java \
        frontend/src/features/board/boardRules.ts \
        context/ .serena/memories/
git commit -m "docs: correct the two comments that asserted what the code did not do

Tier.SUPPLY's javadoc claimed 'not case content' while adding no predicate
and being referenced nowhere. STAGE_ACCESS's ENM row is unreachable and now
says so, along with why it must not simply be deleted.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

## Self-review

**Spec coverage.** Every element of the Prerequisite section maps to a task: the board exposure → Task 1; the case-detail exposure and `maySeeCaseContent` → Task 2; the "withheld vs Unnamed contact" reasoning → Task 3; the `Tier.SUPPLY` javadoc correction, the `STAGE_ACCESS` dead-row note, and the doc/memory sync → Task 4. The spec's "field projection, not tier narrowing" rationale is carried into Task 2 step 4 and Task 4 step 1 so it survives where a reader will meet it.

**Deliberate non-coverage.** The spec's fix-surface table lists `ChecklistController` as already safe behind `COORDINATION`; no task touches it. That is verified, not assumed.

**Type consistency.** `seesCaseContent(Role)` is defined in Task 1 step 3 and consumed under that exact name in Task 1 step 4, Task 2 step 4, and Task 4 step 1. `maySeeCaseContent` is defined in Task 2 step 3 and consumed under that exact name in Task 2's test, Task 3 steps 1/3/4, and Task 4 step 3. The spec's older `SEES_CLIENT_IDENTITY` / `maySeeClientIdentity` names are reconciled in Task 4 step 3 rather than left to disagree.

**Ordering.** Task 1 precedes Task 2 because it closes the wider hole and defines the shared predicate. Task 3 must follow Task 2, since the field it renders does not exist until then.

**Risk.** The one place this could regress is the ENM's three signing transitions, which need the case row. Row scope is untouched by design; Task 2 step 5 runs the full suite, which covers those routes.
