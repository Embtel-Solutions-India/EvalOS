# Unit 06 — In-app notification center

**Phase:** 1 — Structure the data (the spine)
**Depends on:** 04
**Unlocks:** 07 (the shell's notification bell), and the notification side of
every later automation
**Gating open questions:** none

## Goal

The staff notification channel — EvalOS runs no mail server. Domain events
published by the lifecycle (Unit 04) and intake (Unit 05) are mapped to
**in-app** notifications for the right brand-scoped recipients. Client-facing
notifications are re-published as domain events for GHL to deliver (Unit 18);
EvalOS never emails them itself.

**Verifiable result:** a lifecycle event creates a notification for exactly the
correct recipient(s) within the right brand; a recipient can list and mark them
read; client-facing events produce a published event but no in-app notification
and no email.

## In scope

- `NotificationService.create(...)` (brand-scoped).
- Event listeners mapping lifecycle/intake events → staff notifications.
- Recipient resolution (role → the right member for the case's brand/team).
- The notifications REST API (list, unread count, mark read).
- Emitting client-facing notification events for the outbound dispatcher.

## Out of scope

- The notification **bell UI** — Unit 07.
- Actual delivery of client messages — GHL, via Unit 18.
- SLA/countdown *scheduling* — Unit 19 (it will call this service when a timer
  fires).

## Event → notification map (staff, in-app)
| Domain event | Recipient(s) (within the case's brand) |
| --- | --- |
| `case.created` (pool arrival) | GM + Brand Manager |
| `documents.completed` | assigned PM |
| `expert.assigned` (case → CM) | assigned Case Manager |
| `draft.submitted` | assigned PM |
| `draft.pm_approved` | Coordinator |
| `draft.returned` | assigned Case Manager |
| `draft.client_approved` | assigned Case Manager |
| `draft.revision_requested` | assigned Case Manager |
| `expert.signed` | assigned PM |
| `sla.breached` / `sla.escalation` (from Unit 19) | assigned PM + Brand Manager |
| `case.refund_requested` | GM |
| `kpi.threshold_breached` (from Unit 17/19) | Brand Manager + GM |

**Client-facing events** (`checklist.requested`, `draft.ready_for_client`,
`case.delivered_to_client`): **not** turned into in-app notifications. Unit 06
ensures they are published as domain events so Unit 18 can hand them to GHL. No
email is sent by EvalOS.

## Recipient resolution
Given a case, resolve: its brand's Brand Manager and the GM (brand_id NULL); the
case's `assigned_pm`, `assigned_cm`, and the brand's Coordinator(s)/ENM as
needed. All lookups are brand-scoped; a recipient in another brand can never be
selected.

## Endpoints (own notifications only, brand-scoped)
| Method | Path | Notes |
| --- | --- | --- |
| GET | /api/notifications | caller's own, unread-first, paged |
| GET | /api/notifications/unread-count | integer badge value |
| POST | /api/notifications/{id}/read | mark one read (must be caller's) |
| POST | /api/notifications/read-all | mark all caller's read |

## Acceptance criteria
- [ ] Firing each mapped event creates a notification for exactly the mapped
      recipient(s), tagged with the case's brand; staff in other brands receive
      nothing.
- [ ] A recipient's `GET /api/notifications` returns only their own; another
      user cannot read or mark-read someone else's (403/404).
- [ ] Unread count is accurate before/after mark-read and read-all.
- [ ] Client-facing events (`checklist.requested`, etc.) publish an event
      (assert via test subscriber) but create no notification row and trigger no
      mail.
- [ ] Creating a notification writes through the brand-scoped path (no
      cross-brand recipient possible).
- [ ] `./mvnw verify` green.

## Invariants honored
Brand isolation on recipients (1); no EvalOS mail — staff in-app, client via GHL
events (14); event-driven, no manual triggers (architecture principle 4);
listeners are thin and call the service (12).

## Files touched (created)
`.../notification/{NotificationService, NotificationListeners,
RecipientResolver}.java`, `.../web/NotificationController.java` (+ DTOs),
`.../repository/NotificationRepository.java` (scoped finders). Uses the
`notification` table from Unit 03; no new migration.
