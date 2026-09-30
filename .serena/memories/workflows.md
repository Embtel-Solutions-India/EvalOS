# Workflows

**The authoritative file is `.claude/workflows.md`. It states CURRENT IMPLEMENTATION and TARGET
WORKFLOW separately — never present a target as if it exists.**

Lifecycle (**Unit 64, built 2026-09-29** — spec `64-remove-client-requests.md`):

```
GHL (form · call · Sales · Marketing) → OPPORTUNITY → WON → CASE (+ portal account, set-password mail)
  → PC / CM send checklist → CLIENT uploads on the case → PRODUCTION → DELIVERY
```

**There is no client request.** The request, its documents, the opportunity EvalOS opened at
submit, the `INTAKE` purpose, public sign-up, Sales' Application / Request documents tabs and the
Unfinished requests screen were all removed. Nothing below describes them.

Production, and it is now a stated business rule rather than an accident of the code (D36):
Handoff A creates the case → a **PM** takes it → the PM assigns **Coordinator**, **Case Manager**
and **Expert** → the **CM uploads the draft as Word + PDF** → the **client comments and approves that
version in the portal** (Unit 58) → only then
the **expert** downloads, signs and uploads back. `CaseLifecycleService` already implements all of
it. **Sales sees none of this** (D19c).

Client identity (`ClientAccountService`): `identify` answers three ways (plus `UNKNOWN`: "your
account opens when your first case starts"); `signIn` creates nothing; **there is no sign-up**.
The account is opened by `CasePortalAccountListener` on `CASE_CREATED` (after commit, portal brand
only, never throws) via `openForCase` — by GHL contact, then email; `created_via = 'CASE'`, linked;
never relinks another contact's account; flags the case on no email / no GHL id / other contact /
mail down. If GHL is down at set-password or sign-in, `ensureCrmIdentity` backfills later (D3c).

Two GHL create paths, two verbs: `SalesDeskService.newDeal` (createOpportunity) and
`MarketingLeadService.openLead` (**upsertOpportunity** — the one place a repeat enquiry reuses an
open deal; **D56:** skipped when that deal has a pending `sync_outbox` push — the queued edit wins).
Set-password and sign-in may `upsertContact` (`ensureCrmIdentity`). The portal opens no deal.

**Documents** enter **only at the case**, against a sent checklist item (D33, D60), keyed by the
**GHL contact id** (D41 — one id names a contact everywhere; `DocumentStore.clientKey`). **Notifications** are in-app today; D37 makes them in-app **and push**, never mail.

**Conversations do not exist** — no table, no route, no component, anywhere. **Notes** are
synced both ways (Unit 54, built 2026-09-24): pushed once to the GHL contact via the
outbox, GHL notes shown on the deal from the existing `ghl_note` mirror.

**GHL → EvalOS (45d, 2026-09-17).** `contact.created`/`contact.updated` → `contact_snapshot`;
`opportunity.create|created|update|updated|stage_changed|status_changed` → re-read
`forContact` → `absorbForContact`. `opportunity.won` stays Handoff A alone. `MIRROR_DELTA` (15m)
re-reads only pipelines nobody has looked at inside `evalos.ghl.delta-ttl`.

**Contact backfill (2026-09-22).** `ContactSnapshotService.findOrFetch`: mirror first, and only on a
miss `GhlContactClient.byId` → `GET /contacts/{id}` (`contacts.readonly`, already granted), saved
through `findOrCreate` so the email-match and contradiction rules still apply. **Why it had to
exist:** every writer of `contact_snapshot` is an EvalOS-side event (Handoff A, set-password, the
`contact.*` webhook) and NO SWEEP PULLS CONTACTS — `MIRROR_DELTA` refreshes opportunities. A deal
typed straight into GHL therefore carried a `ghl_contact_id` and no contact row, and the deal screen
read "it arrives with the next sync", naming a sync that does not exist. A GHL failure returns empty
and logs rather than throwing, so a blip does not take the whole screen down with the contact card.
It is a backfill, not a mirror; `CONTACT_MIRROR` is the bulk pull. Since 2026-09-30 both write the
contact's country, tags and custom field values (`Details.fromGhl`); a webhook or sign-up never
clears them.

**Desk writes (46, 2026-09-17).** Edits — `update`, `moveToStage`, `close`, Marketing's `value` —
are `editLocally` + `enqueue(UPSERT|CLOSE)` and return the local row. Creates — `createDeal`,
`openLead` — still call GHL inline. Boards call GHL **never**. `moveToStage` refuses a stage not
live on the row's own pipeline (Q12 → D44, 2026-09-24).

**Desk edits, as of the 2026-09-18 review pass.** Edit the mirror row, stamp `local_updated_at`
**and record which shared fields were touched** (`locally_edited_fields`), enqueue, answer from the
row. `SYNC_OUTBOX` (2m) sends **only those fields** — sending all four made a rename undo a GHL
workflow's stage move, the mirror's stage being up to one `MIRROR_DELTA` behind. The push's
confirmation is conditional (`confirmPushed`): if the row was edited again during the round trip
nothing is cleared and the drain re-queues, after marking the first row sent so the pending-row
collapse cannot swallow it. **Both creates absorb GHL's reply into the mirror before answering**, or
the next edit of a just-created deal is refused as "not in the mirror yet" for a full sweep.

**Case chat (Unit 57 phase 1, backend).** Three conversations per case created at CASE_CREATED; membership
follows assignment and expert-offer events and the hourly CHAT_RECONCILE; read-only at CLOSED. Messages
over REST, live via each member's private Ably channel, web push when the app is closed. No screens yet.

**Expert sign-in (Unit 59):** sign-up → roster match in the portal's brand → set/reset mail (else nothing, by decision; 204 always) → set-password → party-scoped token → `/cases` → `/case?caseId=`. No staff-minted links exist (V73 revoked the live ones).

**Appointments (Unit 60, 2026-09-29).** Book, reschedule, **cancel** (GHL status `cancelled`), **notes** (live from GHL), **blocked time** (the caller's own, by `team_member.ghl_user_id`) and **per-member free slots**. Every per-meeting route requires a `meeting` row on that deal. No guests: GHL has no field (Q14).

**Checklist send (Unit 61, D60, 2026-09-29).** PC or CM adds items (unsent, hidden from the portal) → **Send** publishes every unsent item, stamps `sent_at/sent_by`, publishes `CHECKLIST_REQUESTED` → client uploads against each sent item. Nothing unsent = "already sent by … on …". Evidence-request items are unsent too; the board lists any case with unsent items.

**Expert lifecycle (Unit 63, 2026-09-29).** GHL hiring pipeline (tagged `EXPERT_HIRING`; stages are GHL's) → ENM board `/hiring` (stage moves go mirror + outbox → GHL; GHL-side moves notify the ENMs) → Onboarded → pre-filled expert create → directory (verify credentials, case history) → declined case may be retaken (D62) → delivered → payout Pending → ENM records transfer (Processing) → **expert confirms in portal** (Paid) → weekly / monthly summary + CSV. **Unit 65 (built 2026-09-30):** the offer carries the amount (editable while open, expert sees it and accepts it, stale fee → 409), delivery pays that amount, staff Payouts module = Overview · Cases register (per-offer log) · Experts · Pay run.
