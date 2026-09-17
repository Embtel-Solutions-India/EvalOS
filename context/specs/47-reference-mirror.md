# Unit 47 — The tier-2 and tier-3 mirror

`00c` §3 names this unit *"custom fields, tags, notes, tasks, calendars"*. `00d` §6.6 then
overrules the shape of it, and that ruling is the whole design here:

> `00c` §2c commits to mirroring *"everything the API exposes"*. `00c` §6 already argues against
> itself: *"sync surface with no consumer is pure drift risk with no offsetting benefit."*
> **Follow that. Rewrite Unit 47 as "mirror what 46 needs", not as a completeness exercise.**

So the question this unit answers is not "what is in tiers 2 and 3". It is: **after Unit 46, what
does a desk screen still read live from GHL?** Everything on that list gets a mirror. Everything
off it does not, and §4 says why for each.

---

## 1. What is still live, after 46

| Read | Screen | Verdict |
|---|---|---|
| `GET /sales/opportunity-fields` — custom field definitions | `NewDealForm` | **mirror (47a)** |
| `GET /sales/calendars` — the calendar list | `BookingForm`, `DealActions` | **mirror (47b)** |
| `GET /sales/users` — the location's users | `BookingForm`'s team-member picker | **mirror (47c)** |
| `GET /sales/calendars/{id}/slots` — free slots | `BookingForm` | **stays live, always — §3** |

That is the whole list. The board went local at 46; the diary (`meeting`, `V47`) and the follow-up
list (`follow_up`) were already local.

**All three are the same kind of thing**: a small, slow-changing, location-scoped list that a form
reads on every render. That is the shape 44a already solved for pipelines, and this unit is the
same move three more times — which is why it is one table each, **one service and one sweep**,
rather than three of everything.

---

## 2. The three tables (`V60`)

Each mirrors GHL's own id, is stamped `synced_at`, and is **never deleted** — a row GHL stops
returning gets `missing_since`, exactly as `pipeline` does (D11a). A calendar archived for an
afternoon must not take a booking form's option away permanently.

| Table | Columns beyond the mirror's usual | Source |
|---|---|---|
| `ghl_custom_field` | `model` (`opportunity` today), `name`, `field_key`, `data_type`, `picklist_options` (jsonb) | `GET /locations/{id}/customFields` |
| `ghl_calendar` | `name`, `active`, `slot_minutes`, `title_template` | `GET /calendars` |
| `ghl_user` | `name`, `email` | `GET /users` |

**Prefixed `ghl_`, unlike `pipeline` and `opportunity`.** `user` and `calendar` are words Postgres
and half the codebase already use for other things; a table called `user` is a quoting problem in
every query that touches it. The three are named consistently rather than one of them being the
odd one out.

**Brand is the selling brand**, as on every other mirror row: the location belongs to
`evalos.ghl.sales-brand` and these lists belong to the location.

---

## 3. Free slots stay live, and that is not an omission

Availability is GHL's to compute — open hours, buffers, per-day caps, the assignee's other
appointments, minimum notice. A mirrored slot is **wrong within a minute** of being written, and a
booking form that offers a slot somebody else has taken is worse than one that waits 300ms.

This is the line the tier list does not draw and this unit does: **mirror the structure, never the
availability.** A calendar is a fact about the location; a free slot is a fact about right now.

**Unit 48 must know this.** With the sync disabled, booking a meeting still needs GHL — the
business can run its boards, desks and production without it, but it cannot take a new booking.
That belongs on 48's list of what degrades, not in a claim that everything works.

---

## 4. The cuts, reversed — Unit 47b (BUILT 2026-09-17)

This section used to cut tags, GHL notes, custom field **values** and task read-back under §6.6.
**The business overruled it the same day, and one of the four cuts rested on a claim about the GHL
API that is simply false.** It read:

> "GHL lists tasks only per contact, so a desk-wide refresh is one request per contact — which the
> 100-per-10s budget refuses. It needs a list endpoint GHL does not offer."

`GET /opportunities/search` — **the read the mirror already makes, once per pipeline** — accepts
`getNotes`, `getTasks` and `getCalendarEvents`, and returns `customFields`, `notes`, `tasks` and
`calendarEvents` on every row. Verified against the live operation contract before a line was
written, not inferred. The fan-out that justified the cut does not exist, and all four arrive for
**zero extra requests**.

| Cut | Now | Where |
|---|---|---|
| Custom field **values** | mirrored, keyed by **GHL field id** | `opportunity.custom_fields` (jsonb, `V62`) |
| GHL notes | mirrored, read-only | `ghl_note` (`V62`) |
| Tags | mirrored — the vocabulary | `ghl_tag` (`V62`), on the `REFERENCE_MIRROR` sweep |
| Task read-back | a task completed in GHL closes on the desk | `FollowUp.syncFromGhl` — no migration |
| Appointment read-back | a cancellation in GHL reaches the diary | `Meeting.syncFromGhl` — no migration |

**Keyed by id, never by name.** A field renamed in GHL keeps its id; `ghl_custom_field` (V60) turns
that id back into a label. Keying values by name would lose them on a rename, silently.

**`ghl_note` is not `opportunity_note`, and they must never merge.** `opportunity_note` is EvalOS
staff prose — append-only by database trigger, EvalOS-owned, never synced (45e). `ghl_note` is
GHL's: written in GHL's UI, owned there, read-only here. Merging them would put an append-only
trigger over rows a sync has to update, and make "who said this" unanswerable on a screen showing
both.

**A task EvalOS never created is not invented.** Read-back updates rows EvalOS already has; a task
created in GHL's own UI gets no `follow_up` row, because putting unrequested work on somebody's
desk list is a decision with a screen behind it, not a side effect of a sync.

**GHL wins outright on all five**, so 45e never applies: notes, tasks and appointments are GHL's
objects, EvalOS creates some and edits none. Custom field values are the same until the unit that
lets a desk edit them — and **that unit is now unblocked**, because D46's reason for keeping desk
creates synchronous was that the mirror had nowhere to hold the values. It does now.

**Tags are read, never written.** GHL workflows key off tags, so a tag EvalOS applied would be
EvalOS triggering an automation the business wrote for its own reasons.

**Still not mirrored:** free slots, for §3's reason, which is about the nature of the data rather
than about consumers and does not change.

## 5. One sweep, because these three change at the same speed

`REFERENCE_MIRROR`, hourly, refreshing all three lists in one pass. The interval argument is
44a's, unchanged: this is structure, not traffic — somebody adds a calendar or a custom field a few
times a year, and a fifteen-minute pass would spend the budget re-reading an answer that has not
moved in twenty passes.

Three sweeps would mean three intervals, three ledger rows an hour and three chances for one of
them to be the one that quietly stopped. One sweep is one thing to watch.

### 5.1 An empty mirror refreshes itself once

A read against an empty table does one live refresh before answering. This is **not** a reintroduced
refill-on-read: it can only fire while a table has never been populated, which is the window between
a fresh deployment and its first sweep. Without it, a new environment's booking form has no
calendars for up to an hour and the fix — "run the sweep" — is one nobody unfamiliar would guess.

After the first successful pass it is dead code that never executes again, which is the right price
for removing a first-run cliff.

---

## 6. Acceptance — BUILT 2026-09-17

- [x] `GET /sales/opportunity-fields`, `/sales/calendars` and `/sales/users` answer **from the
      mirror** and make no GHL request.
- [x] `GET /sales/calendars/{id}/slots` still calls GHL on every request.
- [x] The sweep upserts on GHL's id: a renamed calendar updates in place rather than duplicating.
- [x] A row GHL stops returning is stamped `missing_since` and kept, and is excluded from what a
      form is offered.
- [x] A row that comes back clears `missing_since`; one already missing keeps its original date,
      because the column means "since when" and not "as of the last sweep".
- [x] A read against an empty table refreshes once; a read against a populated one never does.
- [x] One list refusing (a missing scope) still refreshes the other two.
- [x] A blank `sales-brand` mirrors nothing and logs rather than throwing.
- [x] Nothing writes a tag, a GHL note, a custom field value or a task read-back.

`ReferenceMirrorServiceTest` (9), plus `SalesMeetingServiceTest`'s
`theCalendarListIsMirroredAndTheSlotsAreStillLive`, which is the assertion that fails if somebody
"finishes" the mirror by caching slots. Suite: backend **1065**, frontend **127**, both green.

**The payload shapes did not change**, so the three forms were not touched: the controller maps
mirror rows back into `GhlCustomFieldClient.CustomField`, `GhlCalendarClient.CalendarOption` and
`GhlUserClient.User` rather than growing view twins that would one day disagree.
