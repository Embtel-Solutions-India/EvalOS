# Unit 12 — Match scoring engine (assist mode)

**Phase:** 2 — Connect the seams
**Depends on:** 11, 04
**Unlocks:** 15 (rematching after a decline proposes the next expert), 20 (the
AI layer ranks on top of this)
**Gating open questions:** none. The `FieldTag` vocabulary this scorer matches on
is settled in Unit 11; if that list is still unconfirmed, **Unit 11 is not
finished** and this unit does not start.

## Goal

When a PM staffs a case they pick an expert out of the whole roster by eye. This
unit ranks the roster for them: a **top-3 shortlist** scored on field match,
letter-type experience, acceptance rate and current load, brand-scoped, shown at
the moment of assignment.

**Assist mode, and the word is load-bearing.** The engine suggests; a human
confirms. There is no auto-assign, and the shortlist is never a precondition —
a PM who wants the fourth-ranked expert picks them, and the assignment endpoint
neither knows nor cares whether the expert was on the list.

**Verifiable result:** a PM opening the assignment dialog on a case in
`EXPERT_ASSIGNMENT` sees three ranked experts with a per-factor breakdown of why
each scored what it did; can assign any of them or anyone else; and gets an
honest empty state — naming which factor eliminated everyone — when the roster
has no match.

## In scope

- The scoring function and its four factors, brand-scoped.
- `GET /api/cases/{id}/expert-shortlist` and the PM-facing shortlist UI inside
  the existing assignment dialog.
- The **offer record** that makes acceptance rate computable at all (see below).
- The score breakdown, because an unexplained ranking gets ignored.

## Out of scope

- **AI-enhanced ranking and anomaly detection** — Unit 20, layered on top of this
  rule-based score, not replacing it.
- Auto-assignment. Not in this unit and not in any unit.
- Changing how assignment works. `POST /api/cases/{id}/assign-cm` is Unit 04's
  and keeps its exact contract.
- The expert's own view of an offer — Unit 15.

## What the case requires, and where that comes from

Scoring needs the case's field and letter type. The case has `service_type`,
`service_subtype` and `visa_category`; it has **no field tag**. Nothing in the
system records that a case is a mechanical-engineering matter.

**The required field is chosen by the PM at shortlist time, not stored on the
case.** The shortlist endpoint takes `fieldTag` (required) and derives the letter
type from `service_type`. Reasons, in order:

- The PM has just read the documents and written the strategy notes — they are the
  only person who knows what discipline the case needs, and they know it at
  exactly this moment.
- A field tag on the case would have to be populated at intake, by a GHL webhook
  that does not carry one, and would then be a stale guess the PM works around.
- Nothing else in the system needs it. A column exists to be read by more than
  one caller.

If a later unit finds a second consumer, add the column then, with a real source
for it. Recorded as a deliberate omission, not an oversight.

`ServiceType → LetterType` is a small declared map in the scorer — not a rename
of one enum into the other, because `ServiceType.TRANSLATION` and
`LetterType.TRANSLATION_CERTIFICATION` are the same matter under two names and a
`valueOf` would throw on the pair that does not line up.

## The offer record — the acceptance-rate problem, stated plainly

Acceptance rate is one of the four factors and **there is no data for it today.**
`Expert` has `performance_flags` carrying a `DECLINED_CASES` marker, which is a
flag, not a rate. `evalos_case.expert_id` is overwritten by `reassignExpert`, so
the case row does not remember who declined it. The decline is in the audit trail,
inside a `before_snapshot` jsonb blob — derivable in principle, and a query no
scorer should be built on.

So this unit creates the record. New migration (next free `V`-number),
`expert_case_offer`:

| column | note |
| --- | --- |
| `id`, `brand_id`, `created_at` | the `ScopedEntity` shape |
| `case_id`, `expert_id` | who was offered what |
| `offered_at` | when |
| `outcome` | `OFFERED` · `ACCEPTED` · `DECLINED` · `TIMED_OUT` · `SUPERSEDED` |
| `outcome_at`, `decline_reason` | filled when the outcome lands |

- **Append-only in spirit, one mutable field in fact.** A row's `outcome` moves
  from `OFFERED` exactly once and never again; everything else is
  `updatable = false`. **First write wins, and a later write of the same outcome is
  a no-op rather than an error** — Unit 15 has two acts that both mean accepted (the
  expert pressing Accept in the portal, then Dropbox Sign's `signed` callback), and
  on the ordinary happy path both fire. The guard belongs here, in the one place
  that owns the column, not in each caller. This is not the audit table and does not pretend to be —
  the audit trail already records each transition, and this row exists to be
  *aggregated*, which is the thing a jsonb trail is bad at.
- **Written by the transitions that already exist**, not by a new endpoint: a row
  at `ASSIGN_CASE_MANAGER` and `REASSIGN_EXPERT` (an offer), stamped `DECLINED`
  at `EXPERT_DECLINED` with the reason, `ACCEPTED` at `EXPERT_SIGNED`.
  (`ASSIGN_CASE_MANAGER` is where an expert offer comes from because that action
  assigns **both** — `assignCaseManager(caseId, cmId, expertId)`, publishing
  `CaseEvents.Type.EXPERT_ASSIGNED`. The name reads as staff-only and is not.)
  `SUPERSEDED` when a case is reassigned while an offer is still open, so a
  rematched case does not leave an `OFFERED` row that never resolves.
- **`TIMED_OUT` is declared here and written by nobody until Unit 15**, by that
  unit's `EXPERT_TIMED_OUT` transition — a staff act, prompted by Unit 19's 24h
  timer but never fired by it. Corrected from an earlier draft that said Unit 19
  writes it: a job cannot, because reaching `TIMED_OUT` also means opening a
  rematch, and `REASSIGN_EXPERT` is gated on an exception state only a declared
  transition can set. See Unit 15's sign-SLA section.
- Unit 15's portal fills `ACCEPTED` from the real Dropbox Sign callback instead of
  the staff-recorded stand-in. The column does not change.

Index `(brand_id, expert_id, outcome)` — the aggregate this unit runs.

## Scoring

`service/ExpertMatchService.score(...)`, a pure function of an expert plus the
case requirement, held as **one weighted table** rather than four branches, for
the reason `NotificationListeners` and `navigation.ts` give: a weight that lives
in a literal table is a data diff when it changes.

| Factor | Weight | Computed from |
| --- | --- | --- |
| Field match | 40 | `fieldTag ∈ primary_fields` full; `∈ secondary_fields` half; neither, zero |
| Letter-type experience | 25 | the derived `LetterType` ∈ `letter_types` |
| Acceptance rate | 20 | `ACCEPTED / (ACCEPTED + DECLINED + TIMED_OUT)` from `expert_case_offer` |
| Current load | 15 | inverse of the **derived** active count from `ExpertLoadService` (Unit 11) — never `current_active_count`, which nothing maintains |

Rules the weights do not express:

- **Eligibility is a filter, not a low score.** Only `AVAILABLE` experts are
  scored at all — `CaseLifecycleService.availableExpert` refuses anything else, so
  a shortlist offering them would be offering what the write side rejects. This is
  the same rule Unit 08 applied to the picker.
- **`quality_score` is a tie-break, not a fifth factor.** The build plan names four
  factors; quality is a human judgement already reflected in tier and in whether
  the ENM keeps the expert available. It orders equal scores and nothing more.
- **An expert with no offer history scores neutral on acceptance, not zero.** A
  new expert would otherwise be permanently last and never get the case that would
  give them a record — the cold-start trap. Below a threshold of resolved offers
  (3), the factor returns the roster's mean.
- **The performance flags are shown, not scored.** `SLOW_RESPONSE`,
  `QUALITY_ISSUE`, `CLIENT_COMPLAINT` appear on the card as warnings for the PM to
  weigh. Folding a `CLIENT_COMPLAINT` into a number hides the one thing a human
  should see before assigning.

## Backend

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/cases/{id}/expert-shortlist?fieldTag= | GM · Brand Manager · PM | top 3 by score, each with the per-factor breakdown and the performance flags. Brand-scoped through the case read |

One route. The case is loaded with `CaseLifecycleService.read` so scope is decided
where the rest of the system decides it, and the roster is read with
`ExpertRepository.findScoped` — **no new scoped query and no second scoping
path**, per the rule `CaseBoardService` and `ChecklistService` both follow.

`GET /api/experts` (the Unit 08 picker) and `POST .../assign-cm` are unchanged. The
shortlist is a read that sits beside the picker; the PM can ignore it entirely.

Case Managers, Coordinators and the ENM are **not** on this route. The ENM owns
the roster but does not staff cases, and the shortlist necessarily reveals which
case needs which discipline — supply-side access does not extend to case content
(`architecture.md`, scope tiers).

## Frontend deliverables

1. **Shortlist inside the existing assignment dialog**
   (`features/board/QuickActionDialog` for `assign-cm`): pick the field tag →
   three ranked cards. Each card shows name, institution, tier, the score, the
   **per-factor breakdown**, current load and any performance flags.
2. **The full picker stays one click away.** The `GET /api/experts` dropdown is
   not replaced by the shortlist — it sits under it as "choose someone else", so
   the PM is never forced through the ranking.
3. **An honest empty state.** When nothing scores, say which factor emptied the
   list — "no available expert carries the Mechanical Engineering tag" is
   actionable (the ENM recruits, or frees someone up); "no matches" is not. This
   is the Unit 08 rule that an empty dropdown must say why.
4. Field tag is a **select over `FieldTag`**, never free text — same reasoning as
   Unit 11's form.

## Acceptance criteria

- [ ] The shortlist returns at most 3 experts, only `AVAILABLE` ones, only from
      the case's brand — a higher-scoring expert in another brand is absent.
- [ ] Ranking is correct and explained: an expert with the tag in
      `primary_fields` outranks one with it in `secondary_fields`, all else equal,
      and the breakdown shown to the PM adds up to the score shown.
- [ ] An expert with no resolved offers is not ranked last purely for that
      (the cold-start rule), asserted directly.
- [ ] Load comes from the derived count: an expert with two open cases ranks below
      an otherwise identical expert with none, **while both rows still hold
      `current_active_count = 0`**.
- [ ] Assigning a **non-shortlisted** expert succeeds — the engine cannot become a
      precondition. Asserted, because this is the property "assist mode" means.
- [ ] `assign-cm` and `reassign-expert` each write an `OFFERED` row;
      `expert/declined` stamps `DECLINED` with the reason; a reassignment marks
      the open offer `SUPERSEDED`, leaving no permanently-open row.
- [ ] A Case Manager and an ENM both get 403 from the shortlist route.
- [ ] `npm run build` green; `./mvnw verify` green.

## Invariants honored

Brand isolation — the shortlist reads through `findScoped` and the case through
`CaseLifecycleService.read` (1); role checked at the route and scope in the
service (3); `payment_detail` is not on the expert card and not in the shortlist
DTO (4); the scorer is a pure function in `service`, the controller stays thin
(6, boundaries); no transition happens here, so nothing bypasses Unit 04's table
(13 — the offer rows are written by the existing transitions, which already
audit); new migration, `V7` untouched (9).

## Files touched

**Created.** Backend: `service/ExpertMatchService.java` (the scorer + the weight
table + the `ServiceType → LetterType` map), `domain/ExpertCaseOffer.java`,
`domain/OfferOutcome.java`, `repository/ExpertCaseOfferRepository.java`,
`web/ExpertShortlistController.java` (+ shortlist DTOs), migration
`db/migration/V<next>__expert_case_offer.sql`. Frontend:
`frontend/src/features/experts/ShortlistPanel.tsx` + its rules test.

**Modified.** `service/CaseLifecycleService.java` — the offer row written inside
the existing `assignCaseManager` / `reassignExpert` / `expertDeclined`
transactions, so an offer and the transition that caused it commit together or
not at all. `frontend/src/features/board/QuickActionDialog.tsx` (the shortlist in
the `assign-cm` dialog).

**Not touched.** `service/CaseTransitions.java` — this unit declares no new
action. `web/ExpertPickerController.java`, `service/ScopePredicate.java`, every
applied migration.
