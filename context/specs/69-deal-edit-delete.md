# Unit 69 — Edit every field of a deal, and delete it

2026-09-30, the business: "Give an update and delete opportunity button, that allows to update all
fields of opportunity and able to delete." Sales desk only (the Edit button is already SALES-only).

## 1. Edit

`PUT /api/sales/opportunities/{id}` takes, all optional, at least one required:
`name`, `monetaryValue`, `stageId`, `expectedCloseDate`, `assignedTo`, `customFields`.

- **Name, value, stage** — unchanged path (D44): mirror first, outbox push. The stage is checked
  against the deal's own pipeline, as `moveToStage` does.
- **Expected close, owner, custom fields** — **inline GHL `PUT /opportunities/{id}`**, for D46's
  reason: the outbox stores an id, and the mirror holds no close date. GHL is called **before** the
  local queue so a refusal changes nothing. The mirror then takes the owner and the custom-field
  values at once, so the reload shows them.
- The owner is GHL-owned under D42; writing it *to GHL* is not the mirror arguing with GHL, so D42
  stands. The body never carries `pipelineId` or `status` (routing and Handoff A stay GHL's).
- Same field set as the create form: the intake custom fields only; status stays on Won/Lost/
  Abandoned; pipeline is never editable. Contact fields (email, phone, company) are the contact's,
  not the deal's, and are out of scope.
- A blank custom field is not sent (the create's rule), so the dialog cannot clear one.
  The close date is not mirrored, so the dialog starts it blank and blank means "keep".

## 2. Delete

`DELETE /api/sales/opportunities/{id}` (SALES, `requireMine`).

- **Refused on a won deal** — Handoff A has made (or is making) its case, and the case names the
  opportunity.
- **Refused while an outbox push is pending** for it — the drain would push to a deleted
  opportunity and dead-letter. Retry after the drain (≤2m).
- **Inline GHL `DELETE /opportunities/{id}`**, 404 counted as done; audited `DELETED` by
  `GhlWriteClient` (D16). The mirror row is then stamped `missing_since` — never deleted — so the
  board drops it at once and its notes and history stay.

## 3. UI

`DealEditDialog` gains stage, expected close, owner and the intake custom fields (reusing
`NewDealForm`'s `CustomFieldInput`), prefilled from the deal and `GET …/contact` (which gains
`assignedToId` and `dealFieldValues`, id → value). A **Delete** button in the dialog footer
confirms inline (no `window.confirm`), then returns to the board.
