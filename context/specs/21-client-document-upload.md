# Unit 21 — Client document upload (A07)

Closes the A07 gap: the client puts their own documents in, against their own
checklist, and the Coordinator reviews what arrives.

**Phase:** 2 — Connect the seams
**Depends on:** 10 (checklist), 13 (Drive client), 14 (portal token model)
**Unlocks:** A07 in `context/process-automation.md`; removes the last manual step in
document collection

---

## Why this unit is small

Everything it needs already exists. This is a wiring unit, not a new subsystem:

| Need | Already built | Where |
|---|---|---|
| Authenticate a client without a password | `portal_access` link tokens, `audience = 'CLIENT'`, revocable, one-unrevoked enforced by `V23`'s partial unique index | Unit 14 |
| A place to put files | The case's own Google Drive folder, from `evalos_case.drive_link` | Unit 13 |
| A Drive API client | `integration/GoogleDriveClient` over an injected `Drive` | Unit 13 |
| "Which document is this?" | One `document_checklist_item` per required document | Unit 10 |
| A vocabulary for what arrived | `ChecklistItemStatus` — `REQUIRED · UPLOADED · APPROVED · MISSING · INCORRECT` | Unit 10 |
| A client actor in the audit trail | `audit_event.actor_type = 'CLIENT'` | Unit 14 / `V22` |

**No new enum values, no new auth surface, no object storage, no new dependency.**
`ChecklistItemStatus` in particular already carries A07's flags: an upload sets
`UPLOADED`, and the Coordinator's review sets `MISSING` or `INCORRECT`, which is the
"flags missing or incorrect items" loop the build spec asks for. Do not add a
`FLAGGED` or `REJECTED` value beside them.

**Not in this unit:** AI review of uploads. Ruled out by decision — the Coordinator
does the review. Unit 20 records the same exclusion.

---

## In scope

- One upload endpoint on the **portal** chain, per checklist item.
- Streaming the bytes through to the case's Drive folder.
- Recording `drive_file_id` + `uploaded_at` on the item and flipping it to
  `UPLOADED`.
- One audit row per upload, actor the client.
- The client-side view: their checklist, what is still wanted, what was flagged, and
  an upload control per item.
- Re-upload replaces.

## Out of scope

- AI or automated review of the content (decision above).
- Coordinator-side review UI — Unit 10 already has it; this unit only feeds it.
- Staff upload. Staff work in Drive directly; adding a second write path would be a
  second answer to "how does a file get into the folder".
- Deleting files. A client withdrawing a document is a conversation, not a button;
  re-upload covers the real case (wrong file sent).
- The client *notification* that a document was flagged (T4). That is a touchpoint
  with an undecided channel — see `process-automation.md`.

---

## The endpoint

```
POST /api/portal/client/checklist/{itemId}/upload
     X-Portal-Token: <token>          ← header, never the path or a query param
     multipart/form-data, one part: file
  200 → { itemId, status: "UPLOADED", uploadedAt }
  400   unacceptable file (type, size, empty)
  401   PORTAL_LINK_INVALID — missing, unknown, expired or revoked token
  403   wrong audience (an EXPERT token on a client route)
  404   item not on this token's case
  409   item already APPROVED, or the case has no Drive folder to file into
  429   rate limit
  503   Drive unavailable — nothing recorded, safe to retry
```

**The token travels in the `X-Portal-Token` header and nowhere else.** This is not a
style choice: `PortalTokenFilter` rejects a query parameter because it "lands in
access logs, `Referer` headers and browser history", and a **path segment is worse on
all three**. It is also what lets `PortalSecurityConfig` disable CSRF — the reason
recorded there is that "the credential is a header the browser does not attach on its
own". Put the token in the URL and that reasoning collapses, and one leaked access
log hands a stranger write access to a client's case. The SPA already reads the token
out of the URL fragment and sends it as a header; this route does the same.

Route shape follows `ClientPortalController`'s existing `/api/portal/client/**`, so it
inherits `PortalSecurityConfig`'s chain. `{itemId}` in the path is fine and is not a
case id — the token still names the case, and the item's parentage is checked against
it (404 if it does not match).

**Status codes match the existing portal behaviour, which deliberately conflates
failures.** `PortalSecurityConfig`'s entry point answers **401 `PORTAL_LINK_INVALID`**
for missing, unknown, expired *and* revoked tokens — one identical refusal, so a
caller learns nothing about which. **403 is only** the audience mismatch thrown by
`PortalPrincipal.current`. Do not invent a 403 for a revoked link.

---

## Security — the rules, not discovered later

This is **the first place an outside party sends EvalOS bytes**. Every rule below
is a requirement, not a preference.

**Authorization**
- The token's `audience` must be `CLIENT`, unrevoked and unexpired. Reuse
  `PortalPrincipal.current(CLIENT)`; do not re-implement the check.
- The item must belong to the token's case. A client holding a valid token for case
  A must not be able to write to case B's item by id — check the parent, return
  404 (whether another case's item id exists is not their information).
- An `APPROVED` item is closed to the client (409). Only the Coordinator reopens it
  by setting `INCORRECT` or `MISSING`.

**The file**
- **Allowlist, by content, not by claim.** PDF, JPEG, PNG only. Sniff the leading
  bytes; never trust the `Content-Type` header or the filename extension, both of
  which are attacker-controlled.
- **Size cap** enforced by Spring's multipart limits *and* checked before streaming,
  so an oversized body is refused rather than half-uploaded.
- **Reject empty files** — a zero-byte upload that flips an item to `UPLOADED` is
  worse than no upload, because it silently satisfies `markDocsComplete`.
- **The filename is data.** EvalOS generates the stored name
  (`<caseCode>-<item label slug>-<short id>.<ext>`); the client's filename is kept
  only as a label if at all. No path separators, no traversal, no null bytes, and
  nothing echoed back into HTML.
- **Per-token rate limit — and this is new work, not reuse.** `PortalTokenFilter`
  already has a limiter, but it keys on **`getRemoteAddr()`**, not the token, and
  behind a proxy without `server.forward-headers-strategy=framework` every caller
  shares one budget. An upload limit has to key on the `portal_access` id, because
  the thing being protected is one case's Drive folder. Extend the existing limiter
  with a second key rather than adding a parallel one, and do not assume the IP
  limiter already covers this.

**Storage**
- **The bytes stream through and are never persisted by EvalOS.** Two mechanisms,
  both already established by Unit 11's sheet import — copy them rather than
  reinventing:
  1. `spring.servlet.multipart.file-size-threshold` set **equal to**
     `max-file-size`, so the servlet container cannot spool the upload to a temp
     file behind your back. This is the one that is easy to miss, because the
     default silently writes to disk.
  2. `InputStreamContent` into Drive, not `ByteArrayContent` — buffering the whole
     file would put an attacker-sized allocation on the heap.

  State it as a **testable property**: EvalOS writes no file to local disk and holds
  no blob column. Invariant 14 ("EvalOS hosts no files") survives only because of
  this, and an upload endpoint reads like a violation of it otherwise — so the spec
  and the code comment must both say why it is not.

**Trail**
- One `audit_event` per upload with `actor_type = 'CLIENT'`, naming the item. The
  case timeline then shows the client acting, which is the point of `V22`.

**Open question — antivirus.** Google Drive scans on ingest, which is not the same
as EvalOS having an AV posture for files it accepts from a public link. Accepting
attachments from an unauthenticated-ish URL is a business-standard AV question and
is recorded in the Gap Register rather than assumed solved. It does not block the
unit; it is a decision someone must take knowingly.

---

## Reliability

- **The case may have no Drive folder, and that is the normal state.**
  `evalos_case.drive_link` is nullable (`V5`), nothing in intake guarantees it, and
  `folderIdOf` yields nothing for a null or non-folder link — so a fresh
  `DOC_COLLECTION` case, which is *exactly* when the client is asked to upload, can
  have nowhere to file into. **This is the unit's most likely failure in practice
  and it must be handled explicitly**, not left to whatever `folderIdOf` throws:
  answer **409** naming the missing folder, tell the Coordinator (the case is
  unworkable until a Drive folder exists), and never mark the item `UPLOADED`. The
  client-facing copy must not read as their fault.
- **Drive first, database second.** Upload, then record `drive_file_id`. If Drive
  fails, `DriveUnavailableException` → 503 and **nothing is recorded**, so a retry
  is clean. The reverse order would leave an item marked `UPLOADED` with no file
  behind it — the worst outcome available, because it satisfies the docs-complete
  gate.
- **Re-upload replaces.** A second upload against the same item writes a new Drive
  file and overwrites `drive_file_id` / `uploaded_at`. The old file is left in Drive
  (Drive has versions and a bin; EvalOS deleting a client's document is not a
  drive-by) and the audit trail keeps both events, so "they sent it twice" stays
  visible.
- **One current file per item.** `drive_file_id` is a single column, not a
  collection — the checklist asks for one document per row. A client with two pages
  of one document merges them; that is what the label says.
- Synchronous, deliberately: the client is waiting and needs to know it landed. No
  queue, no retry job. The outbox exists for machine-to-machine delivery, not for
  a request a human is watching.

---

## Data

**`V25__checklist_item_upload.sql`** — two columns on `document_checklist_item`:

| Column | Type | Why |
|---|---|---|
| `drive_file_id` | `text` | the file behind an `UPLOADED` item; nullable because most rows never have one |
| `uploaded_at` | `timestamptz` | when it arrived, for the Coordinator's aging view |

No `uploaded_by`: the audit trail records who, and a second record of the same fact
is a second thing that can disagree — the same reason `case.paid` has no `paid_by`.

No status CHECK, consistent with every other enum column here except `V3.role` and
`V18`'s two vocabularies.

*(Numbering: `V24` is Case Creation v2.0's `ghl_opportunity_id`. If 05b has not
landed when this is built, take the next free number and say so in the header.)*

---

## Code

| Change | Note |
|---|---|
| `GoogleDriveClient.uploadFile(folderId, name, contentType, InputStream, size)` | New method beside `uploadHtmlAsDoc`, same injected `Drive`, `InputStreamContent` so it streams. One class gains one method; nothing is abstracted. |
| Extract `folderIdOf(driveLink)` | It lives in `RedactedProfileService` today and now has a second caller. Extract it to a shared helper — **do not copy it**; two link parsers that disagree about what counts as a folder is exactly the kind of split this codebase avoids elsewhere (`ChecklistItemStatus.isComplete()` is on the enum for the same reason: three callers had to agree and disagreement would have been invisible). Its "no folder in this link" result becomes the 409 above. |
| `PortalUploadService` (or extend `PortalCaseService`) | Validation → Drive → item update → audit, in that order. Extend if it stays small; split only if it does not. |
| Portal controller route | Beside Unit 14's, on the portal chain. |
| `frontend/src/features/client-portal/` | Checklist view with a per-item upload control, showing `REQUIRED` / `UPLOADED` / flagged-with-reason. Reuse `portalRules.ts`; keep the minimal-chrome, no-nav portal conventions from `ui-context.md`. |

**Nothing about `markDocsComplete` changes.** The Coordinator still owns it, and it
still refuses while any item is incomplete. `ChecklistItemStatus.isComplete()` stays
the one definition — note it counts `UPLOADED` as complete, which is correct here:
the human gate is the Coordinator choosing to mark the case complete, and a bad
document gets `INCORRECT`, which makes it incomplete again.

---

## Acceptance criteria

1. A client with a valid `CLIENT` token uploads a PDF against a `REQUIRED` item →
   the file is in the case's Drive folder, the item is `UPLOADED` with
   `drive_file_id` and `uploaded_at` set, and one `CLIENT`-actor audit row exists.
2. The Coordinator sees it on the Unit 10 board and can set `INCORRECT` with a
   reason; the item is then incomplete again and the client can re-upload.
3. A `.exe` renamed to `.pdf` is **refused** — content sniffing, not the extension.
4. An oversized file and a zero-byte file are both refused, and neither changes the
   item.
5. A token for case A cannot write to an item on case B (404).
6. An `APPROVED` item refuses a client upload (409).
7. A revoked token is refused **401 `PORTAL_LINK_INVALID`** — identical to an unknown
   and an expired one. An **EXPERT** token on this route is **403**.
8. **A case with no `drive_link` answers 409 and leaves the item `REQUIRED`** — the
   likely real-world state, so it is asserted rather than discovered.
9. Drive unavailable → 503, and the item is unchanged — verified by asserting the
   item's status and `drive_file_id` after the failure.
10. **The token is never accepted from the path or a query string** — only the
    `X-Portal-Token` header. Asserted, because accepting it elsewhere would leak the
    credential into access logs and break the CSRF reasoning.
11. **No file is written to local disk and no blob column exists** — the invariant-14
    property, asserted rather than assumed.
12. `markDocsComplete` still refuses while any item is `REQUIRED`, `MISSING` or
    `INCORRECT`.
13. `./mvnw verify` green with `V25` applied and `ddl-auto=validate` passing.

---

## Docs this unit must leave consistent

`10-doc-checklist-coordinator.md` (its "client upload out of scope" line becomes a
pointer here), `context/architecture.md` (invariant 14's streaming nuance, the
portal gaining an upload surface), `context/project-overview.md` ("source-document
upload is not in the client portal" flips), `process-automation.md` (A07 moves from
**gap** to **built**), and the memories `backend/security`, `backend/persistence`,
`frontend/core`.
