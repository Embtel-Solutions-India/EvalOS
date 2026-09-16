# Unit 53 — Request documents

**Decided 2026-09-17 (D33, D34).** The client uploads documents **with the request**, at
questionnaire submit. Sales opens the opportunity and sees the answers and the documents together.
Handoff A carries them into the case.

This is the DOCUMENT SUBMISSION step `workflows.md` §2 has always named and Unit 43 deferred
(`43` §5: *"a missing document is a thing Sales chases, not a wall the funnel puts in front of a
lead"*). That posture survives — **submit is never gated on completeness.** What changes is that
there is now a place to put them.

---

## 1. The key is the one that already exists, so nothing new is invented

`DocumentStore.clientKey(brandId, ghlContactId, documentId)` → `{brand}/client/{ghl_contact_id}/{doc}`.

The `ponytail:` note on that method predicted this unit and named the blocker: the funnel uploaded
"before any case or contact exists". **That blocker is gone.** The contact is created at
set-password, at the next sign-in, or at the first request that needs one (D3d/D3c) — and submit
ensures the id before it opens the opportunity, so by the moment a document can be attached the
contact and its GHL id both exist.

- **The prefix is the contact, not the application**, so a repeat client's second request lands
  beside their first. The documents belong to the person.
- **The GHL contact id, per D41** — one id represents a contact everywhere, so the prefix resolves
  in both systems with no mapping table. It was `contact_snapshot.id` between 2026-09-14 and
  2026-09-17; `DocumentStore`'s javadoc carries that history and the exposure that comes back with
  the GHL id.
- **A contact with no GHL id is refused**, with a message naming the repair, rather than filed under
  a guessed prefix. Nothing would ever look for it there.
- **No second prefix, no `applicationKey`.** One shape, and the carry-forward below is what that
  buys.

## 2. `application_document`

Mirrors `case_document`'s shape and drops what only a case has.

| Column | Note |
|---|---|
| `id` | PK |
| `brand_id` | NOT NULL — scoped like everything else |
| `client_application_id` | NOT NULL, FK |
| `contact_id` | NOT NULL, FK `contact_snapshot` — a real foreign key, because D41 names contacts by GHL id without touching primary keys (D18). The key's GHL id is reached through it |
| `object_key` | authoritative for reads, exactly as on `case_document` |
| `filename`, `content_type`, `size_bytes` | the real name is data, never a path |
| `uploaded_at`, `uploaded_by_client` | |
| `carried_to_case_document_id` | nullable FK; set once at Handoff A, so the carry-forward is idempotent and visible |

**No checklist item.** A request has no checklist — that is a case concept and Unit 10's.

## 3. Routes

```
POST   /api/portal/applications/{id}/documents     multipart, own draft only, DRAFT or SUBMITTED
GET    /api/portal/applications/{id}/documents     the client's own list
DELETE /api/portal/applications/{id}/documents/{d} only while DRAFT
GET    /api/opportunities/{id}/documents           Sales' list — its own route (D34)
GET    /api/opportunities/{id}/documents/{d}/url   5-min presign, audited
```

**Its own route and its own tab beside the application, and the same permission** (D34). Sales
reaches it by already being able to open that opportunity (D19b/D19c) — the documents ask no new
authorisation question, and there is no case access anywhere in this unit.

## 4. Carry-forward at Handoff A

`CaseIntakeService` creates the case from `opportunity.won`. It then inserts one `case_document` per
`application_document` **pointing at the same `object_key`** and stamps
`carried_to_case_document_id`. Nothing copies in S3 and nothing re-keys — the object never moved,
because the key was never case-scoped.

- Source `CLIENT_UPLOAD`, as if the client had uploaded it to the case.
- **Idempotent:** a replayed webhook finds `carried_to_case_document_id` set and skips.
- **Never fails the case.** A carry-forward error is logged and audited; a case that exists with a
  document still on the request is recoverable, a `opportunity.won` that 500s is not (invariant 8:
  this is the only door).

## 5. What this unit does not do

- **No submit gate.** Zero documents submits fine (`43` §5, unchanged).
- **No GHL upload.** The files stay in EvalOS's S3; GHL gets nothing (invariant 14's sibling — we
  host no files *for* GHL either). Sales reads them in EvalOS, which is the whole point of D14.
- **No request-stage checklist.** Chasing is Sales' conversation, not a state machine.

## 6. Acceptance

1. A client with a `DRAFT` application uploads two files → two rows, two S3 objects under
   `{brand}/client/{contact}/…`, and the client's list returns both.
2. Another brand's staff and another client's token both 404 the same document id.
3. Submit with zero documents succeeds.
4. `GET /api/opportunities/{id}/application` on a portal-born deal returns the answers **and** the
   document list; on a deal that did not come from the portal it still answers `null` + 200.
5. `opportunity.won` creates the case and one `case_document` per request document, sharing the
   `object_key`; firing the same webhook twice leaves exactly one of each.
6. A document upload from a client whose application belongs to someone else is refused before S3
   is touched.
