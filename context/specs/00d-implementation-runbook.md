# 00d Runbook — how to implement the audit

> **This does not replace `context/ai-workflow-rules.md`.** That file already defines the loop:
> spec-driven, one unit at a time, non-negotiables per unit, split rules, protected files, the
> doc-sync map, and the five-point *Before Moving to the Next Unit* checklist. **Follow it.**
>
> This runbook adds only the four things the audit produced that the existing loop does not
> cover: **Phase 0 is not a unit**, a **regression guard** that did not exist before, a set of
> **documents that must be amended before certain units start**, and **exit criteria per phase**.

---

## 1. The loop, unchanged

Per unit, from `ai-workflow-rules.md`:

```
spec (context/specs/NN-name.md)  →  backend  →  frontend  →  verify  →  docs + memories
```

Its five-point completion gate stands as written: works end to end · no invariant violated ·
`progress-tracker.md` updated · `./mvnw verify` green **and** `npm run build` green in every
touched frontend (**`tsc -b`, never a bare `tsc --noEmit`** — `frontend/tsconfig.json` is
`files: []` with project references, so `--noEmit` typechecks nothing and exits 0) · affected
Serena memories updated with none still describing the old behaviour.

**Three additions to that gate, from this audit:**

6. **The do-not-break list has been consulted** — `context/audit/2026-09-13/audit-backend.md`
   → *Do-not-break list*, 23 items. Most were bought with a bug. If the unit touches the portal
   chain, the payout window, `PortalAccessService.retire`, the GHL pacer, `ScopePredicate`'s
   PIPELINE arm, or the multipart config, **read the relevant entry before editing, not after**.
7. **Any document this unit reverses has been amended first, not alongside** — `00d` §13 lists
   them. A changed decision is an **edit to the document that states the old one**; a note beside
   it is the failure mode this repo has avoided so far.
8. **The appendix row is closed** — `00d-appendix-screen-and-api-decisions.md` names a decision
   per route and per screen. If the unit leaves a row's decision unmet, the unit is not done.

---

## 2. Phase 0 is a runbook, not a unit

It is repair work. It has **no spec**, it is mostly not code, and **the ordering is the
content** — each step is what makes the next one testable.

> **Progress, 2026-09-15.** Done: **step 3** (the local `location-id` default is
> `WY6bW2xUCI8Tz8gw7aLJ`), step 4 **for the seeded desks only** (`V909`, a new file — the local
> profile relaxes `*:missing`, never `*:checksum`), **step 7 in part** (the Google Ads funnel was
> *removed* rather than repointed — the new account has no paid-search pipeline, so the property,
> the route and the nav entry are gone from all three profiles), and code items **1**, **2** and
> the first of **6** (`BoardView`'s `reloads` deps bug).
> Open: **steps 1, 2, 5, 6**, the boot-time `(pipeline name → id)` log, step 4 **for the live
> desks** (item C4, still a curl), and code
> items **3, 4, 5, the rest of 6, and 7**. **Step 1 is still the thing nothing else is testable
> without.**

### 2a. Operational, in this exact order

| # | Step | Done when |
|---|---|---|
| 1 | **Recreate the `opportunity.won` workflow** in `WY6bW2xUCI8Tz8gw7aLJ`, pointing at `POST /api/webhooks/ghl/{brand.webhook_endpoint_token}`. The EvalOS token is **unchanged** — do not reissue it. | **One real won opportunity** produces a case at `DOC_COLLECTION`, paid, with a contact snapshot, an opened checklist, and `NEW_CASE_IN_POOL` to the PM and Coordinator. Nothing else in this list is testable until this passes. |
| 2 | **Mint the new PIT** with all six scopes at once: `opportunities.readonly`+`.write`, `contacts.readonly`+`.write`, `invoices.readonly`, `calendars.readonly`, `calendars/events.write`. Full `pit-` prefix, 40 chars. | Boot log shows `token length=40`. A bare 36-char UUID 401s in a way that reads like a scope problem — that is the trap this step exists to avoid. |
| 3 | **Change the local config default** — `application-local.yml:112` — off `kBumF0uUOmMBB5bneYjx`. | A developer's empty screens are real emptiness, not a dead account. |
| 4 | **Reassign every active SALES/MARKETING member's `ghl_pipeline_id`** to a pipeline in the new location. **There is no UI** — `PUT /api/team-members/{id}/ghl-pipeline` has zero frontend callers, so this is a curl or a DB write. Record the `(name → id)` pairs. | Each desk draws its columns. An empty board and a wrong id are indistinguishable, which is why this is verified per person, not per deploy. |
| 5 | **`TRUNCATE ghl_opportunity_cache`.** | Droppable by design; this is the situation it was designed for. |
| 6 | **Confirm *Allow Duplicate Contact* is off** in the new location. | A provisioning obligation EvalOS cannot detect or enforce (`39` §3a). It is on no other checklist, which is how it gets forgotten. |
| 7 | **Set the pipeline-name properties** for the new account, and set `GHL_INTAKE_PIPELINE_NAME` — Unit 43 shipped 2026-09-15 and **blocks on it**: blank means every client request answers 502 and opens no opportunity. There is **no `GHL_HOT_STAGE_NAME`**; EvalOS sends no stage and GHL's automation places the deal. | The GM funnel screens resolve instead of 502-ing, and a client can actually send a request. |

**Add a boot-time `(pipeline name → id)` log as part of step 4.** A delete-and-recreate in GHL
keeps the name and changes the id, so the dashboards keep working while every access key points
at nothing, silently. That is what happened here, and the log is what makes the next one visible
in one grep.

### 2b. Code, after step 1 passes

Ordered by blast radius, not by size.

1. **`PortalCaseService.upload`** — replace `cases.findById(principal.caseId())` with
   `authorized(principal)`. **Ship it with the test that would have caught it**: call `upload`
   with a party-scoped `PortalPrincipal`. The existing controller test stubs the service with
   Mockito and proves nothing about it. This also closes the latent cross-brand write, because
   `upload` skips the brand check `byId` applies.
2. **Re-key client documents** off `ghl_contact_id` onto an EvalOS id (`43` §5). **Done
   2026-09-14, as `contact_snapshot.id` rather than `client_account.id`** — `upload`'s caller
   holds a case, and a case always has a snapshot; the funnel keeps `client_account.id` for the
   step before a case exists. See `00d` §2's amendment.
   ⚠ **Existing objects do not move.** Documents written before this change live under the old
   `ghl_contact_id` prefix, and `case_document.object_key` still points at them — so **read must
   fall back to the stored key** and only *writes* take the new shape. A bulk re-key is an S3
   object copy, not a migration, and is not required for correctness. Decide explicitly whether
   you ever do it; the stored key is authoritative either way.
3. **Add the three per-case document routes** (`/cases/{caseId}/documents…`) and the client's
   `CasePicker`, which `DraftReview` already implements. Without this a client with two cases
   gets a 409 and can never upload.
4. **`WebhookReplaySweep`** — with `00d` §2.3's three `19` constraints (the
   `SweepRegistrationTest` triple, session-scoped `JobLock`, and *when* never *what*).
5. **`GET /api/admin/ghl/status`** on `features/jobs`, GM-only gated twice. Do this before the
   rest of Phase 0's fixes: it is what makes steps 1–7 verifiable instead of hopeful.
6. **The three interaction defects** — the board's `reloads` deps bug, cache-on-write from GHL's
   own response, and confirmations on the four irreversible one-click actions.
7. **The four stale `unavailable` cards** and the `/brands` nav entry with no screen.

### 2c. Phase 0 exit criteria

- A live won opportunity creates a case, observed end to end.
- A client signs in, uploads a document, and sees it accepted.
- Every Sales and Marketing desk draws its own pipeline's columns.
- A deliberately failed webhook appears in the status surface and is replayed by the sweep.
- `./mvnw verify` green; all three frontends build.

**Do not start Phase 1 until all five hold.** Phase 1's value is a status answer; an unreliable
answer is worse than none.

---

## 3. Phases 1–6 run as normal units

With three standing rules:

**Spec before code, where the audit reverses something.** These units do not start until the
named document is amended:

| Unit / work | Amend first |
|---|---|
| The outbox (Phase 4) | `37` §7, `39` §3a, `43` §7 — *"writes do not retry"* is refused in all three |
| Client mail, four messages (Phase 2) | `43` §10, `40` §4, `19`'s invariant line |
| ENM pipeline (Unit 50) | `22` slice 4's written refusal, `17-dashboards.md`'s *GHL* rows |
| The webhook replay (Phase 0) | `05` §Gateway step 6 and `05b` criterion 4 both assume a redelivery that does not happen |
| Anything touching the mirror | `00c` — six amendments in `00d` §6 |

**Sequencing decisions that are still open** — resolve before the phase, not inside it:
Unit 43's placement (it is a P0 dependency sitting in Phase 6, and it owns the
`pipeline`/`pipeline_stage` tables Unit 44 needs), the client delivery decision, and the expert's
questionnaire subset (`00d` §12 g2).

**When the audit and a spec disagree, the spec wins unless the audit shows the code contradicts
it.** This is the lesson of the verification pass: five of the audit's own recommendations
reversed decisions that were already argued and priced, and one failed the build. The audit's
authority is over *what the code does*; the specs' authority is over *what was decided*.

---

## 4. Not breaking production while this runs

- **Branch per unit off `development`**, as now. Phase 0's operational steps are not code and do
  not branch — they are environment changes, and step 1 is reversible only by rebuilding it.
- **The 23-item do-not-break list is the regression guard.** It is not a style preference: each
  entry names behaviour bought with a bug, and several are invisible in review
  (`saveAndFlush` ordering, `noRollbackFor` on `signIn`, mail-before-insert, the null-deadline
  arm of `findScoped`, `file-size-threshold == max-file-size`).
- **Migrations are additive, always.** Never edit an applied one — `MigrationTreeTest` enforces
  it, and both `05` and `ai-workflow-rules.md` state it.
- **Composite brand FKs (Phase 3) land before the mirror adds four tables**, or the new tables
  inherit the convention instead of the constraint.
- **Run `./mvnw verify` and all three frontend builds per unit**, not per phase. 966 tests is a
  fast, honest gate; the value of it is that it is currently green.

---

## 5. What to do first, in one line

**Rebuild the `opportunity.won` workflow and fire one real won opportunity through it.** Until a
paid deal creates a case, the business is not taking orders and nothing else on this list can be
verified.
