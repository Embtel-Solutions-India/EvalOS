# Unit 66b — The expert opens the case's documents

**Built 2026-09-30** (branch `feature/unit-66-case-workspace`), as specced. Not browser-checked:
no local case has an approved draft or a client file.

Specced 2026-09-30 from a business decision in chat: *every person associated with a case can see
and download its documents and the draft; the Case Manager uploads the draft and its versions.*
Recommendation taken: the **Expert** gains the files, the **ENM** stays out (D63).

**Backend + expert portal. No migration.**

## 0. Today, before this unit

- **Staff already match the decision.** Every staff role that can load the case and whose role
  `seesCaseContent()` (GM, BM, PM, PC, CM) lists and downloads every kind on
  `GET /api/cases/{id}/documents` + `…/documents/{documentId}/url`. The CM (and PC, PM, GM, D51)
  uploads each draft version in `DRAFT_IN_PROGRESS`. Nothing changes for staff.
- **The ENM is refused all case documents** (`Tier.SUPPLY`, `CaseLifecycleService.requireCaseContent`).
- **The expert sees only labels.** `ExpertCaseView.evidence` is the names of the completed checklist
  items; no endpoint hands the expert a client file.
- **Bug: the expert cannot open a file draft.** The portal draws "Open the letter" only when
  `view.draftLink` is set. Unit 58 drafts are files and leave `draftLink` null, so on every such case
  the letter card says *"not ready yet"* even after the client approved it — the server's
  `GET /api/portal/expert/letter` would have answered, but nothing calls it.

## 1. Decisions inside this unit

1. **What the expert gets: the client's current uploads and the approved draft.** Client uploads
   not `SUPERSEDED`; the newest `DRAFT` version in `CLIENT_APPROVED`, as Word and (when present)
   PDF. Earlier draft versions, PM returns and client change requests stay internal. The expert's
   own signed letter is not re-listed (they uploaded it; the page already confirms it).
2. **When: whenever this expert is the case's expert** — the same gate as the case view
   (`authorizedCase`: brand, `expert_id`, and a case-scoped token pinned to its case). The offered
   expert needs the evidence to decide whether to accept or request more; the view already names
   the applicant to them. A rematch removes access at once, because the gate reads `expert_id`.
3. **View and download, same rule as staff (D51).** Only the approved draft's PDF opens inline;
   every client file and the Word draft are `attachment`.
4. **Every open is audited** as a portal `EXPORTED` event naming the file, like `letterLink`.
5. **The ENM stays refused.** Unchanged; now stated as D63 rather than only in code.

## 2. API

`ExpertCaseView` gains `documents: ExpertDocument[]`, newest first within each kind:

```
ExpertDocument { id, kind: "CLIENT_UPLOAD" | "LETTER", filename, uploadedAt, hasPdf }
```

`GET /api/portal/expert/documents/{documentId}/url?caseId=&pdf=false&view=false` → `{ url }`, a
five-minute presigned URL. 403 when the document is not on the authorized case or is not one of
§1.1's; 400 for `view` on anything but the letter's PDF; 409 when the requested file is missing.

`GET /api/portal/expert/letter` stays for a legacy pasted `draftLink`.

## 3. Expert portal

- **The letter card** lists the approved draft: *View PDF · Download PDF · Download Word*. With no
  approved version and a legacy `draftLink`, the existing *Open the letter* button. Otherwise
  *"not ready yet"*. The **Letter** fact reads from the same rule.
- **"What the opinion rests on"** lists the client's files, each with *Download*; the accepted
  checklist labels stay above them. Empty state unchanged.

## 4. Acceptance

- [ ] Expert on the case: sees and downloads the client's current files and the approved draft.
- [ ] Expert not on the case (or rematched away): 403 on the URL route; nothing listed.
- [ ] A superseded upload, a returned draft, a pending draft: not listed; URL route 403.
- [ ] `view=true` on a client file: 400.
- [ ] Each URL writes one portal `EXPORTED` audit row.
- [ ] A file-draft case in `EXPERT_SIGNING` shows the letter's buttons (the §0 bug).
- [ ] ENM unchanged: 403 on the staff document routes.
