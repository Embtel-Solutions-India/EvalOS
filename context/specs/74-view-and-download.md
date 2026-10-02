# Unit 74 — View and Download on every document

**Decided 2026-10-02 by the business** ("every person who can see the documents, give them a
download and a view button"). **D51 edited.** **Status: BUILT 2026-10-02** (branch
`feature/unit-74-view-and-download`; see `implementation-status.md`).

## 0. Today, before this unit

| Who | Where | Offered |
|---|---|---|
| Staff with case content | Client uploads (`DocumentList`) | one link — downloads |
| Staff | Signed letter (`ExpertCard` → `DocumentList`) | one link — downloads |
| Staff | Draft history (`DraftHistory`) | View PDF · "Word" · "PDF" (the last two download, unlabelled as such) |
| Client | Own uploads (`CaseDocuments`) | Download |
| Client | Draft (`CaseDraft`) | View PDF · Download PDF · Download Word |
| Client | Delivered files (`CaseDelivered`) | Download |
| Expert | The case's client files (`ExpertCasePortal`) | Download |
| Expert | The letter (`ExpertCasePortal`) | View PDF · Download PDF · Download Word |

Viewing was a draft PDF's alone (D51): `DocumentStore.presignedUrl` is always
`Content-Disposition: attachment`, and only `presignedPdfView` serves `inline`.

## 1. Decisions

| # | Question | Answer |
|---|---|---|
| 1 | Who gets the buttons? | **Exactly whoever already sees the document.** No read rule changes: staff behind `requireCaseContent` + the case scope, the client on their own uploads and delivered files, the expert on the documents `expertDocuments` already lists. |
| 2 | What can be viewed? | **A PDF, PNG or JPEG.** Word files (`.doc`, `.docx`) have no browser viewer, so they keep **Download** only and the screen says so with no View button. |
| 3 | How is a view kept safe? | **The response type is forced, never taken from the file.** A view is served `inline` with `Content-Type` fixed to `application/pdf`, `image/png` or `image/jpeg`, chosen from the stored filename's extension. The browser opens it in its PDF or image viewer and never as a page, so an uploaded file still cannot execute — the same reasoning D51 used for the draft PDF, now for the three types that have a viewer. A file whose bytes do not match its extension shows as a broken image or a PDF error; it is never rendered as anything else. Uploads are already limited by magic bytes to PDF / JPEG / PNG / DOC / DOCX (`UploadedFileType.CLIENT_DOCUMENT`). |
| 4 | A draft's PDF | Unchanged: served `application/pdf` inline from its `pdf_object_key`. |
| 5 | Audit | Unchanged: every read, view or download, writes its `EXPORTED` row. |
| 6 | Order of buttons | **View first, then Download** (D51's "view first"), on every row. |

## 2. Contract

- `DocumentStore.presignedView(key, filename)` — inline with the forced type for `.pdf` /
  `.png` / `.jpg` / `.jpeg`; otherwise `InvalidRequestException` ("that file can't be opened in the
  browser — download it instead"). `presignedPdfView(key)` stays for a draft's PDF file.
  The local-directory reader carries the forced type instead of a boolean.
- `GET /api/cases/{id}/documents/{docId}/url?pdf&view` and
  `GET /api/portal/expert/documents/{docId}/url?pdf&view` — `view` is accepted for any document the
  caller may already read, under the rule above (was: a draft's PDF only).
- `GET /api/portal/client/cases/{id}/documents/{docId}/url?view` and
  `GET /api/portal/client/cases/{id}/delivered/{docId}/url?view` — gain `view`.

## 3. Screens

Every list above shows **View** (when the file is a PDF / PNG / JPEG) and **Download** on each row.
The staff draft history reads View PDF · Download PDF · Download Word, as the portals already do.

## 4. Not in this unit

- An in-page viewer (a modal). The browser's own viewer in a new tab is the viewer.
- Previewing Word files (would need conversion or a third-party viewer, which would send client
  files to another service).
