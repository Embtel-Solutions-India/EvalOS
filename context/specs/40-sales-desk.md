# Unit 40 — The sales desk

> **Status: BUILT 2026-09-11, meetings included** (added the same day, once the calendar
> scopes were probed rather than assumed). Programme decisions:
> `00b-ghl-operational-programme.md`.
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
| Schedule a meeting | `POST /calendars/events/appointments` | `calendars/events.write` | **unverified** — confirming it means booking a real appointment on a live calendar, so it has not been probed |
| Reschedule | `PUT /calendars/events/appointments/{eventId}` | `calendars/events.write` | **unverified**, as above |
| Pick a calendar | `GET /calendars/?locationId=` | `calendars.readonly` | ✅ **granted, verified live 2026-09-11** (HTTP 200) |
| Read existing appointments | `GET /calendars/events` | `calendars/events.readonly` | ✅ **granted, verified live 2026-09-11** (HTTP 200) |

> **Corrected 2026-09-11.** All three rows above said "not granted", and two of them were wrong —
> the grants were recorded as asks when this spec was written and never re-probed. **Meetings are
> not scope-blocked; they are unbuilt.** There is no calendar client, no endpoint and no UI in
> EvalOS: `GhlWriteClient.createFollowUp` writes a GHL *task* (`contacts.write`), which is a
> different thing and was always specced as such.

~~**This unit cannot be built until those two scopes are granted.**~~ **Wrong, and it cost the
unit a day.** Both read scopes were already granted; nobody probed. Meetings shipped as the
follow-on this paragraph predicted, on 2026-09-11:

- `integration/GhlCalendarClient` — list calendars, book, reschedule. Audits against the
  **opportunity**, so a meeting lands in the same history as that deal's stage moves.
- `service/SalesMeetingService` — `PipelineScope.requireMine` first, then the refusals GHL would
  not make for you.
- `POST|PUT /api/sales/opportunities/{id}/meetings`, `GET /api/sales/calendars`.
- `DealActions` gains a calendar picker, a title, a start and a duration.

**`calendars/events.write` remains unverified** and a booking may answer 502 naming it. The two
read scopes are proven, so the picker works either way and the form hides itself when the
calendar list comes back empty.

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

- [x] A `SALES` caller can update, stage-move and win/lose an opportunity **on their own pipeline
      only**; another pipeline's opportunity answers 403.
- [x] Marking won writes to GHL and **creates no case** — the case arrives by webhook, asserted by
      the absence of any path from this unit to `CaseIntakeService`.
- [x] Notes written before a stage move are readable after it, with no migration step.
- [x] Every write produces an audit row.
- [x] The screen shows a pending state between "won" and the case appearing (§2).
- [x] **Verified before build:** GHL's marketing→sales automation preserves `ghl_opportunity_id`
      (§3). If it does not, this spec is amended before code.
- [x] **Meetings ARE built** (2026-09-11). Was: `calendars/events.write` and `calendars.readonly` are still
      ungranted. Their absence blocked nothing else, which is what this criterion asked.
- [x] `./mvnw verify` green; frontend builds.


## 7. What the build found and changed

### The gating check passed, and here is the evidence

§3 said to verify **before building** that GHL's marketing→sales automation preserves
`ghl_opportunity_id`, because notes are keyed on it and would not follow a re-created deal.

**It does.** `PUT /opportunities/{id}` accepts a **`pipelineId`** in its body — GHL treats the
pipeline as a mutable field on the opportunity, not as part of its identity. A workflow that
moves a deal to another pipeline is doing that same update, so the id survives and the note
stream comes with it. No migration step, and `GhlWriteClientTest.movingAStageIsAnUpdateOnTheSameOpportunityId`
pins it.

**The limit of that evidence, stated honestly:** this verifies GHL's *API*, not the business's
particular workflow, which could in principle be configured to create a new opportunity instead.
The API evidence is strong — moving *is* an update — but the definitive check is watching one
real lead get promoted, and that has not been done.

### Follow-ups shipped; meetings did not

Both were listed as blocked on the `calendars/*` grant. **Only meetings actually are.** A
follow-up is a GHL task (`POST /contacts/{contactId}/tasks`) and needs only `contacts.write`,
which has been granted all along — so the desk ships with follow-ups working rather than with
that half deferred too. Checking the scope per endpoint rather than per feature is what found it.

### The refactor Unit 39 set up

Unit 39 put notes on `MarketingLeadService` with a note that Sales would share the table.
Sharing a table through a class named for the other desk is how the second caller ends up with a
copy, so this unit split it:

- **`OpportunityNoteService` + `OpportunityNoteController`** at
  `/api/opportunities/{id}/notes` — **not** under `/marketing` or `/sales`. The conversation
  belongs to the *deal*, which is nurtured by one desk and closed by the other.
- **`PipelineScope`** — "which pipeline is mine" and "is this deal in it", extracted because
  three desks were about to hold three copies of one security check. Three copies is three
  places for one to drift permissive, invisibly, since each looks right alone.
- **`GhlLeadClient` → `GhlWriteClient`.** It was named for the desk that first used it; Sales now
  closes deals and sets follow-ups through the same class.

### Two absences asserted as tests

`thereIsNoRouteToMoveADealBetweenPipelines` and `thereIsNoRouteToBookAMeeting` both expect 404.
The first is a **design boundary** — promotion is GHL's workflow, and a second path here would
race the automation the business owns. The second is an **ungranted scope**, written as a test so
the gap stays visible instead of being rediscovered as "why is there no meeting button".

### `open` is not a closable status

`close` accepts `won`, `lost`, `abandoned` — not GHL's other two. **Re-opening a won deal would
not un-create the case its webhook already made**, so it is a correction with a case-side answer
rather than a sales action. Case-sensitivity is asserted too: GHL's enum is lowercase, and
accepting `WON` here would send it something it refuses.