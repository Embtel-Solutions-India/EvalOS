# Unit 76 — The client's status card, remarks and history

**Decided 2026-10-06 by the business (D74).** Replaces the 4-step progress bar on the client
portal's case page. **Status: BUILT 2026-10-06** (see `implementation-status.md`).

## 1. The six statuses (the business's words)

| Status | Meaning shown to the client | Stages that project to it |
|---|---|---|
| Awaiting Documents | Required documents are being collected and checked. | `DOC_COLLECTION` |
| In Preparation | Documents are complete, and assessment or drafting is underway. | `PM_REVIEW`, `DRAFT_IN_PROGRESS`, `DRAFT_REVIEW`, `READY_TO_SEND` |
| Awaiting Client Review | The draft is ready for the client to review and confirm factual details. | `CLIENT_REVIEW` |
| Under Expert Review | The expert is independently reviewing and finalizing the opinion. | `CLIENT_APPROVAL`, `EXPERT_SIGNING`, `FINAL_QC`, `READY_TO_DELIVER` |
| Delivered | Final documents are available to download. | `DELIVERED`, `CLOSED` |
| On Hold | Work is paused, with the reason displayed. | `exception_state = ON_HOLD_AWAITING_CLIENT`, any stage |

**A status is a projection, never stored** (`PortalStageProjection.ClientStatus.of(stage, exception)`).
No new stage, no new column on `evalos_case`.

## 2. Rules, and where each lives

- **Changes requested → In Preparation.** `CLIENT_REQUEST_REVISIONS` already moves the case to
  `DRAFT_IN_PROGRESS`, which projects to In Preparation. Nothing to build.
- **On Hold requires a reason and keeps the previous stage.** A hold is an exception state: the
  stage never changes, so Resume returns the case to the stage it stopped at. `POST …/hold` already
  refuses a blank reason. **The reason is now client-visible** — it is the On Hold remark, read from
  the hold's audit note — and the staff hold dialog says so.
- **Services may skip review steps.** A case only ever shows statuses it entered; a service whose
  flow never reaches `CLIENT_REVIEW` has no Awaiting Client Review row. No per-service config.
- **Client-facing remarks vs internal notes.** `case_client_remark` (`V85`) holds only what the
  client may read. Internal notes (timeline notes, strategy notes, the Sales handoff note, the
  audit trail) are other tables and never reach the portal. **Append-only** (trigger refuses
  UPDATE/DELETE); a correction is a newer remark. 1–2,000 characters.
- **Latest remark and its date under each status.** `CaseStatusHistory` gives each status period
  the latest remark dated inside it; the card shows the current period's, the history shows each's.
- **Dated history of status changes.** Derived at read time from the stage snapshots in
  `audit_event` (`stage` + `exceptionState`), one row per status change; stage moves within one
  status make no row. Replaces Unit 58's milestones (`CaseMilestones`, removed).

## 3. API

- Client: `GET /api/portal/client/cases/{id}` — `ClientDraftView` drops `milestones`, gains
  `status` (`{key, label, description, at, remark{body, at}|null}`) and `history` (the same, oldest
  first). `step` and `stepIndex` stay: Home splits active from delivered on them.
- Staff: `GET|POST /api/cases/{id}/client-remarks` — write: GM, PM, PC, CM; read adds the brand
  manager; the ENM has neither (case content, D63). Case load is scoped like every case write.

## 4. Screens

- Client case page: the stepper is gone; a **Current status** card (label, description, latest
  remark with its date) heads the page, and **History** lists each status with its date and remark.
- Staff case page: an **Update for the client** panel (compose + list), separate from the timeline.
  The Put on hold dialog's field reads "Reason (the client sees this)".

## 5. Tests

`CaseStatusHistoryTest` (status rows only on a status change; remark under its status; hold reason
and resume to the same stage; a trail without snapshots still shows the current status),
`PortalCaseServiceTest`, `DomainInvariantsTest` (the new scoped repository), portals 112, staff 194.

## 6. Not done

Not browser-checked. A remark is `CaseOwned`, so open client screens re-read through the existing
`case.changed` signal. A case whose trail predates stage snapshots shows its current status only
until its next change.
