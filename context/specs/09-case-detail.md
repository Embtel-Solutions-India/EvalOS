# Unit 09 — Case detail page

> **⚠ AMENDED by Unit 31 — Production lifecycle v2 (SPECCED 2026-09-02, not built).**
> The pipeline becomes **twelve explicit stages, each with one owner, one primary action and
> one next owner**, drawn as **eight board columns**. Three facts this codebase currently carries as *sub-statuses on a stage*
> — PM approval, client approval, and the QC/delivered split — become **stages**, and the
> draft becomes a **versioned file** rather than a link. Two transitions are added
> (`qc-fail`, `send-to-expert`) and several gates move, notably the Case Manager taking
> ownership of expert signing and reassignment.
> **Read `context/specs/31-production-lifecycle-v2.md` before changing anything below.**

**Phase:** 1 — Structure the data (the spine)
**Depends on:** 04, 08
**Unlocks:** 10 (checklist opens from here), 15 (expert panel), the working
surface for PM / Case Manager / Coordinator
**Gating open questions:** none

## Goal

The single case's working surface: a two-column view — documents / draft /
expert on the left, the append-only timeline on the right — with stage-action
controls, the PM strategy-notes panel, and the draft sub-status chips. Every
action routes through the Unit 04 transitions.

**Verifiable result:** opening a case shows its documents (Drive link), current
draft state, assigned expert, and a chronological timeline built from the audit
trail; the correct role sees the correct action buttons; a PM can edit strategy
notes; performing an action updates the case and appends a timeline entry.

## In scope

- The case detail layout + data wiring (`GET /api/cases/{id}`).
- The timeline read (audit events for the case, role-filtered).
- The PM strategy-notes panel (edit for PM).
- Draft sub-status chips (PM review / client review) from
  `pm_approval_status` / `client_approval_status`.
- The sticky stage-action header (buttons = legal transitions for role+stage).

## Out of scope

- The checklist board itself (Unit 10) — this page links to it and shows a
  summary.
- Client portal draft view (Unit 14) and expert portal (Unit 15) — this is the
  internal view.
- Editing documents (files live in Drive; EvalOS holds the link only).

## Backend
| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | /api/cases/{id} | scoped | full case DTO (`deal_value` only for PM/BM/GM) |
| GET | /api/cases/{id}/timeline | scoped | audit events for the case, oldest→newest, with restricted fields projected out |
| PATCH | /api/cases/{id}/strategy-notes | **PM** | update `pm_strategy_notes` (writes an audit entry) |

The timeline endpoint reads `audit_event` filtered to this case + brand, and must
**not** surface fields the caller isn't authorized to see (e.g. deal value in a
Case Manager's timeline). Transition endpoints already exist (Unit 04).

## Frontend deliverables
1. **Layout** (`features/case`): two columns per `ui-context.md`. Left —
   **Documents** panel (Drive link + checklist summary chip from Unit 10),
   **Draft** panel (version count, pm/client approval chips, link to the current
   draft in Drive), **Expert** card (assigned expert, tier, sign status). Right —
   **Timeline** (audit trail, newest actions visible, actor + action + timestamp
   in `--font-num`).
2. **Sticky header**: case code (`--font-mono`), client, service type, deadline
   RAG, stage, exception badge; and the **stage-action controls** = the legal
   transitions for the viewer's role/stage (Submit draft, PM approve/return, Send
   to client, QC approve, Deliver, Close, Hold/Resume, Assign CM + expert, etc.).
   Actions needing input open a dialog; a 409 shows the reason inline.
3. **PM strategy-notes panel**: read-only for non-PM; inline edit for the PM
   (`PATCH .../strategy-notes`). Visible to PM + CM per the data model.
4. **Draft sub-status chips**: PM review (`pm_approval_status`) and Client review
   (`client_approval_status`) rendered as RAG-ish state chips; revision count
   shown.
5. **Restricted fields**: `deal_value` and any PM-only note hidden from Case
   Manager / Coordinator views by using the projected DTO, not client-side
   hiding alone.

## Acceptance criteria
- [ ] Opening a case (from the board) shows documents, draft state, expert, and a
      timeline reconstructed from `audit_event` in order.
- [ ] The stage-action header shows exactly the legal actions for the viewer's
      role + stage; illegal actions aren't rendered, and a race-losing action
      returns 409 with a visible reason.
- [ ] A PM edits strategy notes and the change persists and appends a timeline
      entry; a Case Manager sees the notes read-only; a Coordinator/other brand
      cannot open the case.
- [ ] `deal_value` is absent from the Case Manager and Coordinator payloads and
      timeline.
- [ ] Draft chips reflect `pm_approval_status` / `client_approval_status` and
      update after each sub-action.
- [ ] `npm run build` green; `./mvnw verify` green.

## Invariants honored
Brand + role + ownership before any read/mutation (1, 3); restricted-field
projection by role (3); actions go through Unit 04 guarded transitions with audit
per hop (3, 13); timeline is the append-only audit, never editable (13); no file
hosting — Drive links only (14).

## Files touched (created)
Backend: `.../web/CaseTimelineController.java` (+ timeline DTO),
extend `CaseController` with `strategy-notes`; `.../service/CaseTimelineService.java`.
Frontend: `frontend/src/features/case/*` (`CaseDetail`, `DocumentsPanel`,
`DraftPanel`, `ExpertCard`, `Timeline`, `StageActions`, `StrategyNotes`,
`caseApi`).
