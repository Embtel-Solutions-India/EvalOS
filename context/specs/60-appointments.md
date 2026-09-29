# Unit 60 — Appointments on GHL's calendar APIs

**Decided 2026-09-29 by the business (D55, closing Q9):** cancel, guests, blocked-off time and
notes go through GHL's own calendar / appointment endpoints — GHL remains the calendar — and a
GHL user is joined to a `team_member` (`team_member.ghl_user_id`) for availability.
**Status: BUILT 2026-09-29**, guests excepted (§4).

## 0. Source

Every endpoint below is from GHL's OpenAPI spec (`GoHighLevel/highlevel-api-docs`,
`apps/calendars.json`, `apps/users.json`), which `marketplace.gohighlevel.com/docs/ghl` is
generated from, read 2026-09-29. Nothing here is inferred.

| Act | GHL call | Scope |
|---|---|---|
| Cancel | `PUT /calendars/events/appointments/{id}` `{"appointmentStatus":"cancelled"}` | `calendars/events.write` |
| Notes — list | `GET /calendars/appointments/{id}/notes?limit=20&offset=` → `{notes, hasMore}` | `calendars/events.readonly` |
| Notes — add / edit / delete | `POST …/notes` `{body ≤5000}`, `PUT …/notes/{noteId}`, `DELETE …/notes/{noteId}` | `calendars/events.write` |
| Blocked time — create | `POST /calendars/events/block-slots` `{locationId, assignedUserId, title, startTime, endTime}` | `calendars/events.write` |
| Blocked time — list | `GET /calendars/blocked-slots?locationId&userId&startTime&endTime` (millis) → `{events}` | `calendars/events.readonly` |
| Blocked time — remove | `DELETE /calendars/events/{id}` (GHL has no block-slot delete) | `calendars/events.write` |
| Free slots per person | `GET /calendars/{id}/free-slots?…&userId=` | `calendars.readonly` |

## 1. Decisions inside this unit

| # | Question | Answer (recommendation taken) |
|---|---|---|
| 1 | Cancel: status or delete? | **Status `cancelled`.** It keeps the appointment in GHL's history and runs GHL's cancellation automations exactly as a cancel in GHL's own UI does; `DELETE` is a hard delete the spec does not say notifies anyone. The mirror row keeps GHL's answer as its status. |
| 2 | Which appointment may a desk touch? | **Only one the mirror holds on that opportunity.** Reschedule, cancel and notes all start at `requireMine(opportunityId)` and then require a `meeting` row with that appointment id **on that opportunity**. Before this, reschedule accepted any appointment id under a deal the caller owned — a cross-deal write. A meeting booked before `V47` has no row and can no longer be moved from EvalOS; it can be in GHL. |
| 3 | How is `ghl_user_id` filled? | **By email, on the `REFERENCE_MIRROR` pass**: a location user whose email matches a `team_member.email` (case-insensitive) fills that member's null `ghl_user_id`. No GM screen — none exists for team members at all — and a set value is never overwritten, so a hand correction in SQL sticks. Unique where not null, as V29 had it. |
| 4 | Whose blocked time? | **The caller's own**, keyed by their `ghl_user_id`, sent as `assignedUserId` with no `calendarId` (the spec says "either, not both"). A caller with no linked GHL user is refused with the reason. |
| 5 | Removing a block | Checked against **the caller's own blocks** from 1 day ago to 366 days ahead before `DELETE` — EvalOS stores no block rows, and a delete by bare id would reach anyone's. |
| 6 | Per-person availability | The booking form passes its chosen team member to free slots as `userId`; "Calendar default" sends none, as today. |
| 7 | Notes: stored in EvalOS? | **No.** Read live from GHL, 20 per page (GHL's cap). Notes are GHL's record; a mirror would be a second copy with no reader but this panel. |

## 2. Routes (all `SALES`)

- `PUT  /api/sales/opportunities/{opp}/meetings/{appt}/cancel`
- `GET  /api/sales/opportunities/{opp}/meetings/{appt}/notes?offset=`
- `POST /api/sales/opportunities/{opp}/meetings/{appt}/notes` `{body}`
- `PUT  /api/sales/opportunities/{opp}/meetings/{appt}/notes/{noteId}` `{body}`
- `DELETE /api/sales/opportunities/{opp}/meetings/{appt}/notes/{noteId}`
- `GET  /api/sales/blocked-time?from&to`, `POST /api/sales/blocked-time` `{title,startTime,endTime}`,
  `DELETE /api/sales/blocked-time/{id}`
- `GET  /api/sales/calendars/{id}/slots` gains optional `userId`

## 3. UI

`MeetingsPage`: each diary row gets **Notes** (list, add, delete) and **Cancel** (confirm; hidden
once cancelled). A **Blocked time** card lists the caller's next 30 days and adds / removes a block.
`BookingForm`: free slots follow the chosen team member.

## 4. Not built: guests

GHL's appointment create and update take exactly one `contactId` and have **no guest or attendee
field**; `users[]` (secondary owners) is response-only. The only extra-recipient settings are per
calendar (`/calendars/{id}/notifications`), not per appointment. D55's "guests through GHL's own
endpoints" has no endpoint to go through — logged as `open-decisions.md` Q14.

## 5. Tests

`GhlCalendarClientHttpTest` pins each new wire shape; `SalesMeetingServiceTest` pins the
appointment-on-this-deal check, the unlinked-user refusal and the foreign-block refusal;
`ReferenceMirrorServiceTest` (or the repository test) pins the email link.
