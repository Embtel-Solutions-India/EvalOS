# Unit 58 — The client portal, rebuilt around the case

**Decided 2026-09-26/27 by the business, in a brainstorming session.** The client portal becomes
case-first: a Home of cases, a case detail page that holds everything about one case, Invoices,
and Conversations. Drafts stop being a pasted link and become **uploaded versions** (Word + PDF)
that the client views, comments on, downloads and approves. The expert portal is a separate unit
that follows this one. **Status: all four phases BUILT 2026-09-28.** The draft PDF opens in the browser first, downloads beside it (D51, 2026-09-28).

## 0. What was decided, and by whom

| # | Question | Answer |
|---|---|---|
| 1 | Which portal first? | **Client** (clients can already sign in; experts cannot) |
| 2 | What does "Download draft" download? | The Case Manager **uploads each draft version as Word and PDF**; both are stored and downloadable |
| 3 | How does the client comment? | **Comments on the draft version**, each optionally tagged with a page number (not inline annotations) |
| 4 | When does the client see the signed letter? | **Once the case is Delivered** (after Final QC) |
| 5 | What is a case's "history"? | A **milestone timeline** in client language, not the audit trail |
| 6 | Navigation | **Home, Invoices, Conversations, My requests.** Meetings removed; Documents moves into each case |

The flow the business described: the Case Manager uploads a draft → the client views, downloads,
comments, approves → the expert downloads the approved version and uploads it back signed → the
case holds the approved draft and the signed letter, both visible to the production team and (once
delivered) the client.

## 1. Drafts become uploaded versions

### Where they fit the existing flow

The stage flow is unchanged (`CaseTransitions`): Case Manager submits → PM approves or returns →
sent to client → client approves (the case enters `EXPERT_SIGNING`) or requests revisions (back to
`DRAFT_IN_PROGRESS`). What changes is what is submitted:

- **Draft versions already exist** — `case_document` rows of kind `DRAFT`, numbered per case,
  created by `submitDraft` and stamped by the PM (`V31`). **This unit reuses them** (decided
  2026-09-27, replacing a new-table design that would have duplicated that versioning).
- **`SUBMIT_DRAFT` takes two files instead of a link**: a `.docx` and a `.pdf` of the same draft,
  stored on the version row `submitDraft` already creates. Both must pass the existing content
  sniffing (a real Word document, a real PDF) and the upload size limit.
- **The client sees a version only once it is sent to them** (`SEND_DRAFT_TO_CLIENT`). A version the
  PM returns never reaches the client.
- **Approve and Request changes act on the version under client review** and drive the existing
  `CLIENT_APPROVE_DRAFT` / `CLIENT_REQUEST_REVISIONS` transitions, so everything downstream — the
  expert signing step, the revision count — behaves as today.
- **The expert later downloads exactly the approved version** (the expert portal unit).
- **Legacy cases** whose draft is a pasted `draft_link` keep showing it as "Draft (link)" with
  View only; no data migration is attempted.
- **Until the new portal ships (phase 3), the live screens keep working on uploaded drafts:** the
  client view's `draftLink` carries a per-read 5-minute link to the newest client-visible version's
  PDF, the expert's letter link hands over the `CLIENT_APPROVED` version's Word file, and the PM's
  draft queue links to the case page's Word / PDF buttons.
- **The client's answer stamps the version's status only**; the PM's review comment on it stays.
  The client's words are on the audit trail and in the comment thread.

### Version status (the existing `DocumentStatus`, plus one value)

`SUBMITTED` (with the PM) → `RETURNED` (PM sent it back; never client-visible) or `PM_APPROVED` →
sent to the client (the case enters `CLIENT_REVIEW`) → `CLIENT_APPROVED` (the version the expert
signs, locked) or **`CHANGES_REQUESTED`** (new — stamped when the client requests changes; today
that leaves the version unmarked). `SUPERSEDED` (a newer version replaced it unruled) and `SIGNED`
stay as they are. **The version "in client review"** is the latest `PM_APPROVED` version while the
case's client answer is `PENDING` — the flag the approve / request-changes guards already use, so
the two cannot disagree; only it can be commented on, approved or sent back. **Client-visible versions:** that one, plus every `CLIENT_APPROVED` and `CHANGES_REQUESTED`
version. `SUBMITTED`, `RETURNED` and `SUPERSEDED` never reach the client.

### Comments

- A thread per version, between the client and the case's staff (Case Manager, Coordinator, PM).
- Each comment: author, text (1–2,000 characters), an optional page number, time. A client's
  comment records the portal credential it was posted through as its author.
- **Only on the version currently in `CLIENT_REVIEW`**; earlier threads stay readable.
- "Request changes" hands the thread to the Case Manager; the next version starts a fresh thread.
- Comments are kept with the case (the Document Retention Policy's 7 years), never edited or
  deleted.

## 2. Data — migration `V70`

- **`case_document` gains the PDF**: `pdf_object_key`, `pdf_filename`, `pdf_size_bytes` (nullable).
  For a `DRAFT` row the existing `object_key` / `filename` / `size_bytes` hold the Word file and the
  new columns the PDF. No other kind uses them.
- **`DocumentStatus` gains `CHANGES_REQUESTED`** — the status check constraint is widened to allow it.
- **`draft_comments`** — `id`, `brand_id`, `document_id` → `case_document` (a `DRAFT` row),
  `author_kind` (`STAFF`/`CLIENT`), `author_id`, `body`, `page` (nullable, ≥ 1), `created_at`.
  **A trigger refuses UPDATE and DELETE** — comments are a record.
- The pending `client_application.answers` drop (Unit 55) moves from `V70` to **`V71`**.

## 3. Backend

### Staff (production)

- `POST /api/cases/{id}/drafts` (multipart: `docx`, `pdf`) — the Case Manager submits the next
  version; runs `SUBMIT_DRAFT`. Case Manager, Coordinator or PM of the case only. It replaced
  `POST /{id}/draft/submit` (the link). One request carries both files, so `max-request-size` is 32MB.
- The PM's return / approve and the send-to-client steps stay as they are and move the version's
  status alongside the stage.
- The versions and their files **reuse the existing document routes**: `GET /api/cases/{id}/documents?kind=DRAFT`
  (each version now says `hasPdf`) and `GET …/documents/{docId}/url?pdf=true` (5-minute link; without
  the flag, the Word file). `GET/POST /api/cases/{id}/drafts/{draftId}/comments` — the production team
  sees every version and thread.
- The staff case screen gains **"Upload draft"** (both files, one action) in place of the link
  field, and the draft versions with their comments.

### Client (`/api/portal/client/…`, every route proves the case is the caller's)

| Route | Does |
|---|---|
| `GET cases` | active and delivered cases, each with case code, service, client-facing step, "needs you" |
| `GET cases/{id}` | detail: the existing view plus `step`, `stepIndex` (stepper position) and `milestones`; the checklist comes from `GET cases/{id}/documents` |
| `GET cases/{id}/documents`, `POST cases/{id}/documents?checklistItemId=` | this case's checklist and uploads (replaces the case-less `/documents`) |
| `GET cases/{id}/documents/{docId}/url` | 5-minute download link to the client's own file |
| `GET cases/{id}/drafts` | client-visible versions only (§1) |
| `GET cases/{id}/drafts/{draftId}/files/{docx\|pdf}/url` | 5-minute link |
| `GET` / `POST cases/{id}/drafts/{draftId}/comments` | read / add (post only on the version in review) |
| `POST cases/{id}/drafts/{draftId}/approve` | `CLIENT_APPROVE_DRAFT` on that version |
| `POST cases/{id}/drafts/{draftId}/request-changes` | `CLIENT_REQUEST_REVISIONS`, optional note |
| `GET cases/{id}/delivered` | the `SIGNED_LETTER` and the `CLIENT_APPROVED` draft — **only at `DELIVERED` or `CLOSED`** |
| `GET cases/{id}/delivered/{docId}/url` | 5-minute link to either delivered file (the approved draft as its PDF); same gate |
| `GET invoices?status=paid` | paid invoices only (GHL's `paid` status) |

The old token-scoped `/approve` and `/request-revisions` and the case-less `/documents` routes are
removed once the new portal ships (phase 3), so nothing answers `SAY_WHICH_CASE` any more.

### Milestones

Derived at read time from what EvalOS already records, in client words, each with a date:
Case opened (`CASE_CREATED`) → Documents received (checklist complete) → Draft ready — v1, v2…
(each `SEND_DRAFT_TO_CLIENT`) → Draft approved (`CLIENT_APPROVE_DRAFT`) → Letter signed
(`EXPERT_SIGNED`) → Delivered (`CASE_DELIVERED`). Nothing internal (PM review, returns,
reassignments, holds) appears.

## 4. Client portal UI

| Route | Page |
|---|---|
| `/dashboard` — Home | Active cases, then **Delivered cases**. Each card: case code, service, step, "needs you" |
| `/cases/:caseId` | **Case detail** (below) |
| `/conversations` | The client's case conversations (Unit 57), grouped by case, unread badges |
| `/invoices` | Paid invoices |
| `/requests`, `/requests/new` | Unchanged |
| removed | `/documents`, `/meetings`; `/draft` and `/draft/:caseId` redirect to `/cases/:caseId` |

**Case detail — "Case #IE-1042":**
- **Header:** case code, service, a status stepper over the client-facing steps (Upload → Review →
  Signing → Delivered).
- **Left column:** *Documents* (this case's checklist, upload per item, status, download own files) ·
  *Draft* (version tabs; the PDF in the browser's own viewer via a 5-minute link; **Download PDF**,
  **Download Word**; the comment thread with an optional "page N"; **Approve** and **Request
  changes**, each with an inline confirmation) · *Delivered* (signed letter and approved draft, once
  delivered) · *History* (the milestone timeline).
- **Right:** the case's Client conversation (Unit 57), live; a **Messages** tab on phones.
- A browser that cannot show the PDF inline still offers both downloads.

**Chat** is built as `packages/evalos-chat` (Unit 57 §7) — in this unit only the pieces the portal
needs: inbox, conversation panel, composer, replies, reactions, unread badge, the Ably connection
and REST catch-up. The staff app reuses it later.

**Push** (Unit 57 §6): a service worker in the client app and a "Turn on notifications" card in
Conversations; permission is never requested on its own.

**Navigation:** Home · Invoices · Conversations · My requests.

## 5. Security

- Ownership on every client route through the existing account / contact rule; another client's case
  answers **403**, the same whether or not it exists.
- Draft and document files only through 5-minute links minted per click.
- Delivered files are refused by the server before `DELIVERED`, not merely hidden.
- Draft upload: the case's Case Manager, Coordinator or PM only; content-sniffed; size-limited.
- Every upload, comment, approval and change request is audited.

## 6. Errors

| Situation | Answer |
|---|---|
| Not your case | 403 |
| Acting on a version that is not the one in client review | 409 `DRAFT_NOT_CURRENT` |
| Commenting on a read-only version | 409 `DRAFT_NOT_CURRENT` |
| Delivered files before delivery | 404 |
| Wrong file type, or too large | 400 |
| S3 unavailable | 502; the page offers retry |

## 7. Tests

- Backend: version numbering; only the latest version can be in client review; approve and request
  changes only on it (409 otherwise); returned versions never client-visible; comments immutable
  (trigger) and only on the current version; ownership on every route; delivered gating; milestone
  derivation; paid-invoice filter; draft upload by role and by file type.
- Portal: stepper and milestone mapping, chat reducers, API mapping; both portals type-check and
  build.

## 8. Phases — each shippable

1. **Backend** — `V70` (draft PDF columns, `CHANGES_REQUESTED`, comments), per-case client routes, delivered gating,
   milestones, paid filter, staff upload and the staff "Upload draft" control.
2. **`packages/evalos-chat`** — the portal subset (no search, typing, presence, seen-by, edit/delete UI).
3. **Client portal** — Home, case detail, Conversations, Invoices; Meetings and Documents removed;
   `/draft` redirects; old token-scoped routes removed.
4. **Push** — service worker and opt-in.

## 9. Decisions this edits

- **New D51** — drafts are uploaded versions (Word + PDF) on the existing `case_document` draft
  versions, with per-version comments; approval attaches to a version; the client sees the signed
  letter only once delivered.
- **D33** — notes that drafts are now stored files alongside the request documents.
- **Spec 57 §7** — the client-portal row now points here.
- `data-model.md`, `workflows.md`, `implementation-status.md` and the Serena memories follow each
  phase.

## 10. Not in this unit

Inline PDF annotations; the expert portal (its own unit: expert accounts first); document categories
and verification status; e-signature; editing a draft in the browser; a history of anything
internal.
