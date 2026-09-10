# Unit 40 — The sales desk

> **Status: SPECCED 2026-09-10, not built.** Programme decisions: `00b-ghl-operational-programme.md`.
>
> **Scoped, not detailed** — same reasoning as Unit 39's header. The contract is fixed here;
> screen detail is written when it is built.

**Phase:** 3 — EvalOS as the operational system
**Depends on:** 37 (the write door), 38 (the board), 39 (the note table)
**Unlocks:** nothing — this is the last unit of the operating half
**Migration:** none expected (the note table is Unit 39's)
**Gating open questions:** none; **blocked on an external ask** (§4)

---

## 1. What changes, in one paragraph

A `SALES` employee works their pipeline without opening GHL: update an opportunity, move it
between stages, mark it won or lost, write sales notes, **schedule a meeting**, and set a
**follow-up**. Every field write goes to GHL and displays GHL's answer; notes are EvalOS rows on
Unit 39's table.

## 2. Stage moves, and the one that is not ours

`PUT /opportunities/{id}` moves an opportunity between stages; `PUT /opportunities/{id}/status`
sets won/lost. Both are ordinary writes through Unit 37's door.

**Marking an opportunity won is the trigger for Handoff A** — GHL fires `opportunity.won`, the
webhook creates the case, and the case is born paid from the opportunity's amount. Invariant 8 is
untouched and this unit must keep it that way:

**EvalOS does not create the case.** It tells GHL the opportunity is won and **waits for the
webhook** like any other source. There is no shortcut, no "and also create the case", no local
optimisation of a round trip that would give EvalOS a second intake path.
`DomainInvariantsTest` already refuses the shape (only `GhlOpportunityHandler` may depend on
`CaseIntakeService`), and that test is the backstop, not the argument.

**A consequence worth stating**, because it will look like a bug the first time: after a
salesperson marks an opportunity won, **the case appears a moment later**, when the webhook lands.
The screen must show that as a pending state rather than as nothing.

## 3. Notes travel with the opportunity

They do so by construction: notes are keyed on `ghl_opportunity_id` (Unit 39 §3), and a stage move
does not change that id. **A stage move therefore carries the whole history with it and no code is
needed to make that true** — which is the property the business asked for.

The same is true across the marketing-to-sales handoff: GHL's workflow moves the opportunity to
the sales pipeline **keeping its id**, so the marketer's notes are already there when the
salesperson opens it.

**One thing to check at build time, because the design leans on it:** that GHL's pipeline-move
automation preserves `ghl_opportunity_id` rather than creating a new opportunity. If it creates a
new one, notes do **not** follow, and this unit needs a note-migration step keyed off the
`opportunity.stage_change` webhook. **Verify before building, not after.**

## 4. Meetings and follow-ups — the external ask

| Need | Endpoint | Scope | Status |
| --- | --- | --- | --- |
| Schedule a meeting | `POST /calendars/events/appointments` | `calendars/events.write` | **not granted** |
| Reschedule | `PUT /calendars/events/appointments/{eventId}` | `calendars/events.write` | **not granted** |
| Pick a calendar | calendars list | `calendars.readonly` | **not granted** |

**This unit cannot be built until those two scopes are granted.** They belong in
`00-build-plan.md`'s Step 0 table. Everything else in this unit — opportunity CRUD, stage moves,
notes — needs only what is already granted, so **the desk ships without meetings if the grant is
slow**, and meetings land as a follow-on.

**A follow-up is a GHL task, not an EvalOS reminder.** GHL has tasks and its automation can act on
them; an EvalOS-side reminder would need `job`, would duplicate a thing GHL already does, and
would reach nobody (invariant 14 — EvalOS has no outbound channel). If tasks turn out not to fit,
the fallback is a due date on a note, decided then.

**Invariant 14 holds and `00b` §2 is where the ruling lives**: EvalOS instructs, GHL delivers.
EvalOS composing and sending a message itself is still refused, and the first "quick email from
the opportunity screen" request is exactly the thing that ruling exists to answer.

## 5. What this unit deliberately does not do

- **No case transitions.** Sales acts on opportunities; a case that exists belongs to PM/PC/CM.
- **No invoice raising from EvalOS.** Sales raises invoices **in GHL**, GHL's QuickBooks
  integration does the accounting, and EvalOS **reads** the result (Unit 41). Building an invoice
  form here would put EvalOS back in the invoicing business for no gain.
- **No case creation** (§2).
- **No EvalOS-side reminder job** (§4).

## 6. Acceptance criteria

- [ ] A `SALES` caller can update, stage-move and win/lose an opportunity **on their own pipeline
      only**; another pipeline's opportunity answers 403.
- [ ] Marking won writes to GHL and **creates no case** — the case arrives by webhook, asserted by
      the absence of any path from this unit to `CaseIntakeService`.
- [ ] Notes written before a stage move are readable after it, with no migration step.
- [ ] Every write produces an audit row.
- [ ] The screen shows a pending state between "won" and the case appearing (§2).
- [ ] **Verified before build:** GHL's marketing→sales automation preserves `ghl_opportunity_id`
      (§3). If it does not, this spec is amended before code.
- [ ] Meetings are behind the `calendars/*` grant and their absence does not block the rest.
- [ ] `./mvnw verify` green; frontend builds.
