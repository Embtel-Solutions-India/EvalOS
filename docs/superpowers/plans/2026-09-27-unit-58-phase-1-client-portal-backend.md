# Unit 58 Phase 1 — Drafts as Files, Per-Case Client Routes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drafts become uploaded Word + PDF files on the existing `case_document` DRAFT versions, with an immutable comment thread per version; the client portal gets per-case routes (detail with milestones, documents, drafts, approve / request changes by version, delivered files, paid invoices); the staff case screen gets "Upload draft".

**Architecture:** No new version table. `submitDraft` stores two S3 objects on the version row it already creates; the client's approve / revisions stamp that row (`CLIENT_APPROVED` / new `CHANGES_REQUESTED`). One new service, `CaseDrafts`, owns the version rules (which version is in client review, which are client-visible, comments, file links); `CaseMilestones` derives the client timeline from the audit trail's stage snapshots. `PortalCaseService` keeps being the client's only authorizer (`authorized(principal, caseId)`), and delegates to both.

**Tech Stack:** Spring Boot 3.5 / Java 21 / JPA / Flyway (Postgres), JUnit 5 + Mockito + MockMvc; React 19 + Vite + Vitest (staff app `frontend/`).

**Spec:** `context/specs/58-client-portal.md` (phase 1 of §8).

## Global Constraints

- Brand-scoped by default: every read goes through a scoped load (`lifecycle.load` / `versionsOf` for staff, `PortalCaseService.authorized(principal, caseId)` for clients). No new unscoped finder is called with an id from a request unless the row is then matched to the authorized case.
- Append-only truth: `draft_comments` refuses UPDATE and DELETE by trigger; audit rows are only ever added.
- Schema changes ship as Flyway: `backend/src/main/resources/db/migration/V70__draft_files_and_comments.sql`. The Unit 55 answers drop moves to **V71** (docs only here; not built in this plan).
- Comments: 1–2,000 characters, optional page ≥ 1, only on the version in client review, never edited or deleted.
- Files only through 5-minute presigned links minted per request (`DocumentStore.presignedUrl`), never stored, never an object key in a payload.
- Draft upload: `.docx` sniffed as `UploadedFileType.Kind.DOCX`, `.pdf` as `Kind.PDF`; each within `max-file-size` (15MB).
- Errors: not your case 403; action on a version not in review 409 `DRAFT_NOT_CURRENT`; delivered files before delivery 404 `NOT_FOUND`; wrong type / too large 400; S3 down 502 (existing `DocumentStoreUnavailableException`).
- After each task that changes behaviour, the matching docs row is updated in Task 7 (one docs commit at the end is acceptable because every task lands on the same branch before merge).

## Review Focus

1. **A client naming a draft id from another case** (or a staff-only `SUBMITTED`/`RETURNED` version of their own case) — must get 403 / 404, never the file or thread. Pinned in Task 5 (`aClientCannotReachAReturnedVersionOrAnotherCasesDraft`).
2. **Double click on Approve after a newer version was submitted** — the second POST names a stale draft id; must be 409 `DRAFT_NOT_CURRENT`, not approve the new version. Pinned in Task 3 (`actingOnAnOlderVersionIsDraftNotCurrent`).
3. **Two 15MB files in one upload** — the request must not be refused by `max-request-size` before the controller sees it. Pinned in Task 2 (`UploadLimitsTest.oneRequestHoldsTwoFullSizeFiles`, `max-request-size` 32MB).
4. **Delivered files requested at `FINAL_QC`** (the letter exists but the case is not delivered) — must 404, not leak the signed letter early. Pinned in Task 5 (`deliveredFilesAreRefusedBeforeDelivery`).
5. **A legacy case whose only draft is a pasted link (no object key)** — the drafts list must still load and the URL route must answer 409 `ILLEGAL_TRANSITION` ("no file behind it"), not 500. Pinned in Task 3 (`aLegacyLinkOnlyVersionHasNoFileLink`).

---

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V70__draft_files_and_comments.sql` | PDF columns, widened status check, `draft_comments` + trigger |
| `domain/DocumentStatus.java` (modify) | `CHANGES_REQUESTED` |
| `domain/CaseDocument.java` (modify) | PDF columns; `storedDraft(...)` |
| `domain/DraftComment.java`, `repository/DraftCommentRepository.java` (create) | The comment row |
| `common/NotFoundException.java`, `common/DraftNotCurrentException.java` (create), `common/ApiExceptionHandler.java` (modify) | 404 / 409 `DRAFT_NOT_CURRENT` |
| `service/DraftFile.java` (create) | One uploaded part handed to the service |
| `service/CaseLifecycleService.java` (modify) | `submitDraft(caseId, docx, pdf)`; client approve / revisions stamp the version |
| `service/CaseDrafts.java` (create) | Version-in-review rule, client-visible set, comments, file link |
| `service/CaseMilestones.java` (create) | Client milestone timeline from the audit trail |
| `service/PortalStageProjection.java` (modify) | `clientStepIndex(Stage)` |
| `service/PortalCaseService.java` (modify) | Per-case client reads/writes; delegates to `CaseDrafts` / `CaseMilestones` |
| `web/CaseController.java` (modify) | `POST /{id}/drafts` multipart; staff comment routes; `?pdf=true` on the file URL; `hasPdf` on versions |
| `web/ClientPortalController.java` (modify) | Per-case client routes; `invoices?status=paid` |
| `application.yml` (modify) | `max-request-size` 32MB |
| `frontend/src/features/case/{caseApi.ts, DraftHistory.tsx, UploadDraft.tsx, DraftComments.tsx, draftRules.ts}` | Staff "Upload draft", Word/PDF links, comment thread |
| `frontend/src/features/board/boardRules.ts` (modify) | Remove the `draft/submit` link action |

All backend paths below are relative to `backend/src/main/java/com/ie/evalos/` unless they start with `backend/`.

---

### Task 1: V70, the domain rows, and the two new error answers

**Files:**
- Create: `backend/src/main/resources/db/migration/V70__draft_files_and_comments.sql`
- Modify: `domain/DocumentStatus.java`, `domain/CaseDocument.java`
- Create: `domain/DraftComment.java`, `repository/DraftCommentRepository.java`
- Create: `common/NotFoundException.java`, `common/DraftNotCurrentException.java`
- Modify: `common/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/ie/evalos/repository/LocalPostgresIntegrationTest.java` (append), `backend/src/test/java/com/ie/evalos/common/ApiExceptionHandlerDraftTest.java` (create)

**Interfaces:**
- Produces:
  - `DocumentStatus.CHANGES_REQUESTED`
  - `CaseDocument.storedDraft(String docxKey, String docxFilename, long docxSize, String pdfKey, String pdfFilename, long pdfSize)`, `getPdfObjectKey()`, `getPdfFilename()`, `hasPdf()`
  - `DraftComment(UUID brandId, UUID documentId, DraftComment.AuthorKind authorKind, UUID authorId, String body, Integer page)`; getters `getDocumentId() getAuthorKind() getAuthorId() getBody() getPage() getCreatedAt() getId()`; `enum AuthorKind { STAFF, CLIENT }`
  - `DraftCommentRepository.findByDocumentIdOrderByCreatedAtAsc(UUID)`, `save`
  - `new NotFoundException(String)` → 404 `NOT_FOUND`; `new DraftNotCurrentException(String)` → 409 `DRAFT_NOT_CURRENT`

- [ ] **Step 1: Write the failing handler test**

`backend/src/test/java/com/ie/evalos/common/ApiExceptionHandlerDraftTest.java`:

```java
package com.ie.evalos.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit 58 §6: the two answers the draft routes add. */
class ApiExceptionHandlerDraftTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void aVersionNotInReviewIsAConflictWithItsOwnCode() {
		var response = handler.onDraftNotCurrent(new DraftNotCurrentException("v2 is not the version in review"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().error().code()).isEqualTo("DRAFT_NOT_CURRENT");
	}

	@Test
	void somethingNotThereYetIsNotFound() {
		var response = handler.onNotFound(new NotFoundException("Nothing has been delivered yet"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
	}
}
```

Before writing, open `common/ApiResponse.java` and use its real accessor for the error code (if the record is `ApiResponse(boolean success, T data, ApiError error)` with `ApiError(String code, String message)`, the lines above are right; otherwise adjust the two `.error().code()` calls to match and note it in the ledger). If `ApiExceptionHandler` has no no-arg constructor, construct it the way its existing tests do.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ApiExceptionHandlerDraftTest`
Expected: COMPILATION ERROR — `DraftNotCurrentException` / `NotFoundException` / `onDraftNotCurrent` do not exist.

- [ ] **Step 3: Add the exceptions and handlers**

`common/NotFoundException.java`:

```java
package com.ie.evalos.common;

/**
 * 404 for a thing the caller may ask about but which is not there yet — the delivered files before
 * delivery (Unit 58 §5). Not for "not yours": that stays {@link ForbiddenException}, one answer
 * whether or not the row exists.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}
}
```

`common/DraftNotCurrentException.java`:

```java
package com.ie.evalos.common;

/**
 * 409 {@code DRAFT_NOT_CURRENT}: the caller acted on a draft version that is not the one in client
 * review (Unit 58 §6) — a stale tab approving v2 after v3 was sent, or a comment on history.
 */
public class DraftNotCurrentException extends RuntimeException {

	public DraftNotCurrentException(String message) {
		super(message);
	}
}
```

In `common/ApiExceptionHandler.java`, beside `onAmbiguousCase` (≈ line 122):

```java
	@ExceptionHandler(DraftNotCurrentException.class)
	public ResponseEntity<ApiResponse<Void>> onDraftNotCurrent(DraftNotCurrentException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("DRAFT_NOT_CURRENT", ex.getMessage()));
	}

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiResponse<Void>> onNotFound(NotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", ex.getMessage()));
	}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=ApiExceptionHandlerDraftTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Write the failing schema test**

Append to `LocalPostgresIntegrationTest` (it runs only with `-Devalos.db.test=true` against local Postgres; follow the existing `auditRowsAreWrittenAndCannotBeChanged` shape and the class's existing fixtures for a case — reuse whatever helper it has for inserting an `evalos_case` row, e.g. `caseFor(...)`, and read its brand constant):

```java
	/** Unit 58 §2: comments are a record, the draft carries its PDF, and the new status is legal. */
	@Test
	void draftCommentsAreAppendOnlyAndADraftCarriesItsPdf() {
		UUID caseId = aCaseRowForDrafts();
		UUID documentId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO case_document (id, brand_id, case_id, kind, version, object_key, filename, size_bytes,
				    pdf_object_key, pdf_filename, pdf_size_bytes, uploaded_by_type, status)
				VALUES (?, ?, ?, 'DRAFT', 1, 'k.docx', 'Draft.docx', 10, 'k.pdf', 'Draft.pdf', 20, 'STAFF',
				    'CHANGES_REQUESTED')
				""", documentId, IE_BRAND, caseId);

		UUID commentId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO draft_comments (id, brand_id, document_id, author_kind, author_id, body, page)
				VALUES (?, ?, ?, 'CLIENT', ?, 'Please fix page 2', 2)
				""", commentId, IE_BRAND, documentId, UUID.randomUUID());

		assertThatThrownBy(() -> jdbc.update("UPDATE draft_comments SET body = 'edited' WHERE id = ?", commentId))
				.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM draft_comments WHERE id = ?", commentId))
				.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO draft_comments (id, brand_id, document_id, author_kind, author_id, body, page)
				VALUES (?, ?, ?, 'CLIENT', ?, 'x', 0)
				""", UUID.randomUUID(), IE_BRAND, documentId, UUID.randomUUID()))
				.hasMessageContaining("draft_comments_page_positive");
	}
```

`aCaseRowForDrafts()` and `IE_BRAND` stand for the class's existing case-insert helper and brand constant — use those names from the file (the class already inserts cases in `caseFor(...)` ≈ line 414). Clean-up: `case_document` rows follow whatever cleanup the class uses for cases; the comment row cannot be deleted by design, so give it a fresh case and leave it (the class already does this for `opportunity_note`, see its note ≈ line 1551).

- [ ] **Step 6: Write the migration**

`backend/src/main/resources/db/migration/V70__draft_files_and_comments.sql`:

```sql
-- Unit 58 §2 — drafts become uploaded files on the existing DRAFT versions (V31), with a comment
-- thread per version. No new version table: case_document already numbers and stamps drafts.

-- For a DRAFT row, object_key / filename / size_bytes hold the Word file and these the PDF.
-- No other kind uses them.
ALTER TABLE case_document
    ADD COLUMN pdf_object_key text,
    ADD COLUMN pdf_filename   text,
    ADD COLUMN pdf_size_bytes bigint;

-- CHANGES_REQUESTED: stamped when the client sends a version back (today it is left unmarked).
ALTER TABLE case_document DROP CONSTRAINT case_document_status_known;
ALTER TABLE case_document ADD CONSTRAINT case_document_status_known CHECK (status IN (
    'SUBMITTED', 'RETURNED', 'PM_APPROVED', 'CLIENT_APPROVED', 'CHANGES_REQUESTED', 'SIGNED', 'SUPERSEDED'));

CREATE TABLE draft_comments (
    id          uuid        PRIMARY KEY,
    brand_id    uuid        NOT NULL REFERENCES brand (id),
    document_id uuid        NOT NULL REFERENCES case_document (id),
    author_kind text        NOT NULL,
    -- STAFF: team_member.id. CLIENT: the portal_access id the comment was posted through.
    author_id   uuid        NOT NULL,
    body        text        NOT NULL,
    page        integer,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT draft_comments_author_kind_known CHECK (author_kind IN ('STAFF', 'CLIENT')),
    CONSTRAINT draft_comments_body_length CHECK (char_length(body) BETWEEN 1 AND 2000),
    CONSTRAINT draft_comments_page_positive CHECK (page IS NULL OR page >= 1)
);

CREATE INDEX draft_comments_document_idx ON draft_comments (document_id, created_at);

-- Kept with the case for the Document Retention Policy's seven years, never edited or deleted.
CREATE FUNCTION draft_comments_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'draft_comments is append-only: a comment is never edited or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER draft_comments_append_only
    BEFORE UPDATE OR DELETE ON draft_comments
    FOR EACH ROW EXECUTE FUNCTION draft_comments_append_only();
```

- [ ] **Step 7: Entity and enum changes**

`domain/DocumentStatus.java` — add `CHANGES_REQUESTED` after `CLIENT_APPROVED`, with a one-line javadoc: `/** The client sent this version back (Unit 58). */`.

`domain/CaseDocument.java` — after `attestedName`:

```java
	/** A DRAFT's PDF (Unit 58); {@link #objectKey} holds its Word file. Null on every other kind. */
	@Column(name = "pdf_object_key", updatable = false)
	private String pdfObjectKey;

	@Column(name = "pdf_filename", updatable = false)
	private String pdfFilename;

	@Column(name = "pdf_size_bytes", updatable = false)
	private Long pdfSizeBytes;
```

and beside `attested(...)`:

```java
	/**
	 * The two files of one draft version (Unit 58), set once before the row is first saved — the
	 * columns are not updatable, so a version's files can never be swapped under a comment thread.
	 */
	public void storedDraft(String docxKey, String docxFilename, long docxSize, String pdfKey, String pdfFilename,
			long pdfSize) {
		this.objectKey = docxKey;
		this.filename = docxFilename;
		this.sizeBytes = docxSize;
		this.contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
		this.pdfObjectKey = pdfKey;
		this.pdfFilename = pdfFilename;
		this.pdfSizeBytes = pdfSize;
	}

	public String getPdfObjectKey() {
		return pdfObjectKey;
	}

	public String getPdfFilename() {
		return pdfFilename;
	}

	public boolean hasPdf() {
		return pdfObjectKey != null;
	}
```

`domain/DraftComment.java`:

```java
package com.ie.evalos.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** One comment on one draft version (Unit 58 §1). Immutable: every column is insert-only. */
@Entity
@Table(name = "draft_comments")
public class DraftComment extends ScopedEntity {

	public enum AuthorKind { STAFF, CLIENT }

	@Column(name = "document_id", nullable = false, updatable = false)
	private UUID documentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "author_kind", nullable = false, updatable = false)
	private AuthorKind authorKind;

	@Column(name = "author_id", nullable = false, updatable = false)
	private UUID authorId;

	@Column(name = "body", nullable = false, updatable = false)
	private String body;

	@Column(name = "page", updatable = false)
	private Integer page;

	protected DraftComment() {
		// for JPA
	}

	public DraftComment(UUID brandId, UUID documentId, AuthorKind authorKind, UUID authorId, String body,
			Integer page) {
		super(brandId);
		this.documentId = documentId;
		this.authorKind = authorKind;
		this.authorId = authorId;
		this.body = body;
		this.page = page;
	}

	public UUID getDocumentId() {
		return documentId;
	}

	public AuthorKind getAuthorKind() {
		return authorKind;
	}

	public UUID getAuthorId() {
		return authorId;
	}

	public String getBody() {
		return body;
	}

	public Integer getPage() {
		return page;
	}
}
```

`repository/DraftCommentRepository.java` — append-only by construction, the `AuditEventRepository` shape:

```java
package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.DraftComment;

import org.springframework.data.repository.Repository;

/** Only save and read exist: a comment is never edited or deleted (the V70 trigger agrees). */
public interface DraftCommentRepository extends Repository<DraftComment, UUID> {

	DraftComment save(DraftComment comment);

	List<DraftComment> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);
}
```

- [ ] **Step 8: Run the schema test and the whole suite**

Run: `cd backend && ./mvnw -q test -Devalos.db.test=true -Dtest=LocalPostgresIntegrationTest`
Expected: PASS including `draftCommentsAreAppendOnlyAndADraftCarriesItsPdf` and `everyMigrationApplied` (if Postgres is not running locally the class is skipped — then start it with `docker compose up -d postgres` from the repo root and rerun).

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS (Hibernate `validate` accepts the new columns and table).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V70__draft_files_and_comments.sql backend/src/main/java/com/ie/evalos/domain backend/src/main/java/com/ie/evalos/repository/DraftCommentRepository.java backend/src/main/java/com/ie/evalos/common backend/src/test/java/com/ie/evalos/common/ApiExceptionHandlerDraftTest.java backend/src/test/java/com/ie/evalos/repository/LocalPostgresIntegrationTest.java
git commit -m "feat(drafts): V70 draft PDF columns, CHANGES_REQUESTED, append-only draft comments"
```

---

### Task 2: Submitting a draft uploads Word + PDF; the client's answer stamps the version

**Files:**
- Create: `service/DraftFile.java`
- Modify: `service/CaseLifecycleService.java` (`submitDraft`, `revisions`, `approve`)
- Modify: `web/CaseController.java` (replace `POST /{id}/draft/submit` with `POST /{id}/drafts`)
- Modify: `backend/src/main/resources/application.yml` (`max-request-size`)
- Test: `backend/src/test/java/com/ie/evalos/service/CaseLifecycleServiceTest.java`, `backend/src/test/java/com/ie/evalos/web/CaseControllerTest.java`

**Interfaces:**
- Consumes: `CaseDocument.storedDraft(...)`, `DocumentStatus.CHANGES_REQUESTED` (Task 1)
- Produces:
  - `record DraftFile(String filename, long size, java.io.InputStream body)` with `static DraftFile of(MultipartFile)`
  - `Case CaseLifecycleService.submitDraft(UUID caseId, DraftFile docx, DraftFile pdf)` (the `String draftLink` overload is removed)
  - After `clientApproveDraft*`: newest DRAFT row `CLIENT_APPROVED`; after `clientRequestRevisions*`: newest DRAFT row `CHANGES_REQUESTED` with the notes as `reviewComment`
  - `POST /api/cases/{id}/drafts` multipart parts `docx`, `pdf` → `ApiResponse<CaseSummary>`

- [ ] **Step 1: Point the existing lifecycle tests at files**

In `CaseLifecycleServiceTest`:
- replace the constant `DRAFT_LINK` with

```java
	private static final DraftFile WORD = new DraftFile("Draft v1.docx", 4, new java.io.ByteArrayInputStream(new byte[] { 'P', 'K', 3, 4 }));
	private static final DraftFile PDF = new DraftFile("Draft v1.pdf", 5, new java.io.ByteArrayInputStream("%PDF-".getBytes()));
```

- replace every `lifecycle.submitDraft(CASE_ID, DRAFT_LINK)` with `lifecycle.submitDraft(CASE_ID, WORD, PDF)` (`sed -i 's/submitDraft(CASE_ID, DRAFT_LINK)/submitDraft(CASE_ID, WORD, PDF)/'`).
- delete the assertion at ≈ line 260 (`assertEquals(DRAFT_LINK, subject.getDraftLink(), ...)`) — a submitted draft no longer carries a link.
- add `given(store.isConfigured()).willReturn(true);` to `freshCaseInDocCollection()` if `DocumentStore.put` checks configuration through the mock (it does not when mocked; skip if unnecessary).

- [ ] **Step 2: Write the failing lifecycle tests**

Add to `CaseLifecycleServiceTest`:

```java
	/** Unit 58 §1: the version row carries both files, and both go to S3 under the case. */
	@Test
	void aSubmittedDraftStoresItsWordAndPdfOnTheVersion() {
		walkToDraftGeneration();
		actAs(Role.CASE_MANAGER);

		lifecycle.submitDraft(CASE_ID, WORD, PDF);

		verify(store).put(org.mockito.ArgumentMatchers.startsWith(BRAND + "/case/"), eq(WORD.body()), eq(4L),
				eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
		verify(store).put(org.mockito.ArgumentMatchers.startsWith(BRAND + "/case/"), eq(PDF.body()), eq(5L),
				eq("application/pdf"));
		ArgumentCaptor<CaseDocument> saved = ArgumentCaptor.forClass(CaseDocument.class);
		verify(documents, atLeastOnce()).save(saved.capture());
		CaseDocument version = saved.getAllValues().get(saved.getAllValues().size() - 1);
		assertEquals(DocumentKind.DRAFT, version.getKind());
		assertEquals("Draft v1.docx", version.getFilename());
		assertEquals("Draft v1.pdf", version.getPdfFilename());
		assertTrue(version.hasPdf());
	}

	/** Nothing reaches S3 for a transition the state machine refuses — no orphan per bad click. */
	@Test
	void aDraftSubmittedAtTheWrongStageUploadsNothing() {
		actAs(Role.CASE_MANAGER);
		assertThrows(IllegalTransitionException.class, () -> lifecycle.submitDraft(CASE_ID, WORD, PDF));
		verifyNoInteractions(store);
	}

	/** Unit 58 §1: the client's answer is stamped on the version, not only on the case. */
	@Test
	void theClientsAnswerIsStampedOnTheVersionTheyReviewed() {
		walkToDraftGeneration();
		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID, WORD, PDF);
		CaseDocument v1 = lastSavedDraft();
		given(documents.findFirstByCaseIdAndKindOrderByVersionDesc(any(), eq(DocumentKind.DRAFT)))
				.willReturn(Optional.of(v1));
		actAs(Role.PROJECT_MANAGER);
		lifecycle.pmApproveDraft(CASE_ID, null);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.sendDraftToClient(CASE_ID);
		SecurityContextHolder.clearContext();

		lifecycle.clientRequestRevisionsFromPortal(subject, "Soften the conclusion");

		assertEquals(DocumentStatus.CHANGES_REQUESTED, v1.getStatus());
		assertEquals("Soften the conclusion", v1.getReviewComment());
	}

	@Test
	void theClientsApprovalLocksTheVersion() {
		walkToDraftGeneration();
		actAs(Role.CASE_MANAGER);
		lifecycle.submitDraft(CASE_ID, WORD, PDF);
		CaseDocument v1 = lastSavedDraft();
		given(documents.findFirstByCaseIdAndKindOrderByVersionDesc(any(), eq(DocumentKind.DRAFT)))
				.willReturn(Optional.of(v1));
		actAs(Role.PROJECT_MANAGER);
		lifecycle.pmApproveDraft(CASE_ID, null);
		actAs(Role.PROJECT_COORDINATOR);
		lifecycle.sendDraftToClient(CASE_ID);
		SecurityContextHolder.clearContext();

		lifecycle.clientApproveDraftFromPortal(subject);

		assertEquals(DocumentStatus.CLIENT_APPROVED, v1.getStatus());
	}

	private CaseDocument lastSavedDraft() {
		ArgumentCaptor<CaseDocument> saved = ArgumentCaptor.forClass(CaseDocument.class);
		verify(documents, atLeastOnce()).save(saved.capture());
		return saved.getAllValues().stream().filter(d -> d.getKind() == DocumentKind.DRAFT)
				.reduce((first, second) -> second).orElseThrow();
	}
```

Add the imports the file lacks (`ArgumentCaptor`, `DocumentKind`, `DocumentStatus`, `atLeastOnce`, `assertTrue`, `verifyNoInteractions`) — check the existing import block first; most are already there.

- [ ] **Step 3: Run them to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=CaseLifecycleServiceTest`
Expected: COMPILATION ERROR — `DraftFile` does not exist.

- [ ] **Step 4: Implement**

`service/DraftFile.java`:

```java
package com.ie.evalos.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

/** One uploaded part of a draft version (Unit 58), already sniffed by the controller. */
public record DraftFile(String filename, long size, InputStream body) {

	public static DraftFile of(MultipartFile part) throws IOException {
		return new DraftFile(part.getOriginalFilename(), part.getSize(), part.getInputStream());
	}
}
```

In `CaseLifecycleService`, replace `submitDraft(UUID caseId, String draftLink)` and its javadoc with:

```java
	private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	/**
	 * The Case Manager hands in a draft as two files, Word and PDF (Unit 58 §1).
	 *
	 * <p>The transition is checked <strong>before</strong> anything reaches S3, so a refused click
	 * leaves no object behind. After that the order is {@code upload}'s: objects first, row second —
	 * a failed transaction leaves an invisible orphan, never a row pointing at nothing.
	 *
	 * <p>{@code draft_link} is no longer written: a legacy case keeps the link it has, shown view-only.
	 */
	@Transactional
	public Case submitDraft(UUID caseId, DraftFile docx, DraftFile pdf) {
		Case subject = load(caseId);
		Stage to = CaseTransitions.target(subject, Action.SUBMIT_DRAFT);

		String docxKey = DocumentStore.caseKey(subject.getBrandId(), subject.getId(), "draft", UUID.randomUUID());
		String pdfKey = DocumentStore.caseKey(subject.getBrandId(), subject.getId(), "draft", UUID.randomUUID());
		store.put(docxKey, docx.body(), docx.size(), DOCX);
		store.put(pdfKey, pdf.body(), pdf.size(), "application/pdf");

		// **The previous version is closed before the new one opens.** A version nobody ruled on —
		// the CM resubmitting after a client revision, say — is SUPERSEDED rather than left
		// SUBMITTED forever. A row left open is one that eventually gets counted.
		documents.findFirstByCaseIdAndKindOrderByVersionDesc(subject.getId(), DocumentKind.DRAFT)
				.filter(previous -> previous.getStatus() == DocumentStatus.SUBMITTED)
				.ifPresent(previous -> {
					previous.superseded();
					documents.save(previous);
				});

		Case saved = apply(subject, to, Action.SUBMIT_DRAFT, null, c -> {
			c.setDraftVersionCount(c.getDraftVersionCount() + 1);
			c.setPmApprovalStatus(PmApprovalStatus.PENDING);
			// A new draft is not the draft the client already saw.
			c.setClientApprovalStatus(null);
		});

		// **The version number comes off the case's own counter, not from counting rows** — the
		// counter moves inside this transaction and `uq_case_document_version` refuses a race.
		CaseDocument version = new CaseDocument(saved.getBrandId(), saved.getId(), DocumentKind.DRAFT,
				saved.getDraftVersionCount(), TenantContext.current().memberId(), ActorType.STAFF, null);
		version.storedDraft(docxKey, docx.filename(), docx.size(), pdfKey, pdf.filename(), pdf.size());
		documents.save(version);
		return saved;
	}
```

In `revisions(...)`, before `return apply(...)`:

```java
		// Unit 58: the version the client sent back says so, with their words on it.
		stampLatestDraft(subject, DocumentStatus.CHANGES_REQUESTED, notes);
```

In `approve(...)`, before `return apply(...)`:

```java
		// Unit 58: this is the version the expert signs — locked by status, not only by stage.
		stampLatestDraft(subject, DocumentStatus.CLIENT_APPROVED, null);
```

Add `import com.ie.evalos.integration.DocumentStore;` if missing (the class already has a `store` field of that type).

- [ ] **Step 5: Run the lifecycle tests**

Run: `cd backend && ./mvnw -q test -Dtest=CaseLifecycleServiceTest`
Expected: PASS (all existing tests plus the four new ones).

- [ ] **Step 6: Replace the staff route, failing test first**

In `CaseControllerTest`:
- remove the `new Route("/draft/submit", ...)` row and the `given(lifecycle.submitDraft(any(), any()))` stub;
- delete the two tests around ≈ lines 370–395 that assert `$.data.draftLink` after a JSON submit, if they post to `/draft/submit` (read them first: if they assert the case *detail* payload's `draftLink`, keep them — legacy links are still shown);
- add:

```java
	/** Unit 58 §3: the CM, Coordinator or PM uploads both files; nobody else. */
	@Test
	void aDraftIsUploadedAsWordAndPdfByTheCaseTeamOnly() throws Exception {
		given(lifecycle.submitDraft(any(), any(), any())).willReturn(aCase());
		var docx = new org.springframework.mock.web.MockMultipartFile("docx", "Draft.docx",
				"application/octet-stream", new byte[] { 'P', 'K', 3, 4, 0 });
		var pdf = new org.springframework.mock.web.MockMultipartFile("pdf", "Draft.pdf", "application/pdf",
				"%PDF-1.7".getBytes());

		for (Role role : List.of(Role.CASE_MANAGER, Role.PROJECT_COORDINATOR, Role.PROJECT_MANAGER, Role.GM)) {
			mockMvc.perform(multipart("/api/cases/{id}/drafts", CASE_ID).file(docx).file(pdf)
					.header(HttpHeaders.AUTHORIZATION, bearer(role)))
					.andExpect(status().isOk());
		}
		mockMvc.perform(multipart("/api/cases/{id}/drafts", CASE_ID).file(docx).file(pdf)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
				.andExpect(status().isForbidden());
	}

	/** A renamed file is refused at the door, before the lifecycle is called. */
	@Test
	void aDraftWhosePdfIsNotAPdfIsRefused() throws Exception {
		var docx = new org.springframework.mock.web.MockMultipartFile("docx", "Draft.docx",
				"application/octet-stream", new byte[] { 'P', 'K', 3, 4, 0 });
		var notPdf = new org.springframework.mock.web.MockMultipartFile("pdf", "Draft.pdf", "application/pdf",
				"<html>".getBytes());

		mockMvc.perform(multipart("/api/cases/{id}/drafts", CASE_ID).file(docx).file(notPdf)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isBadRequest());
		verify(lifecycle, never()).submitDraft(any(), any(), any());
	}
```

with `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;` and `never`/`verify` if missing.

Run: `cd backend && ./mvnw -q test -Dtest=CaseControllerTest`
Expected: FAIL — 404 on `/api/cases/{id}/drafts` (compile error on `submitDraft(any(), any(), any())` first if the lifecycle change is not yet visible; it is, from Step 4).

- [ ] **Step 7: Implement the route**

In `CaseController`, replace the `submitDraft` method, its javadoc and the `SubmitDraftRequest` record (search the file for `record SubmitDraftRequest` and delete it) with:

```java
	/**
	 * The next draft version, as Word and PDF (Unit 58 §3). The Case Manager, Coordinator or PM of
	 * the case — the scoped load inside the service is the "of the case". Both parts are sniffed
	 * here, so a renamed file never reaches S3.
	 */
	@PostMapping(value = "/{id}/drafts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(GM_OR + "hasAnyRole('CASE_MANAGER', 'PROJECT_COORDINATOR', 'PROJECT_MANAGER')")
	public ApiResponse<CaseSummary> submitDraft(@PathVariable UUID id, @RequestParam("docx") MultipartFile docx,
			@RequestParam("pdf") MultipartFile pdf) throws IOException {
		UploadedFileType.require(docx, EnumSet.of(UploadedFileType.Kind.DOCX));
		UploadedFileType.require(pdf, EnumSet.of(UploadedFileType.Kind.PDF));
		return summary(lifecycle.submitDraft(id, DraftFile.of(docx), DraftFile.of(pdf)));
	}
```

Imports: `org.springframework.http.MediaType`, `org.springframework.web.multipart.MultipartFile`, `java.io.IOException`, `java.util.EnumSet`, `com.ie.evalos.common.UploadedFileType`, `com.ie.evalos.service.DraftFile`, `org.springframework.web.bind.annotation.RequestParam` (check which already exist).

In `backend/src/main/resources/application.yml`, change `max-request-size: ${UPLOAD_MAX_REQUEST_SIZE:16MB}` to `${UPLOAD_MAX_REQUEST_SIZE:32MB}` and add one comment line above it: `# 32MB: a draft is two files (Word + PDF) of up to max-file-size each, in one request (Unit 58).`

Pin it (Review Focus 3) — `backend/src/test/java/com/ie/evalos/config/UploadLimitsTest.java`:

```java
package com.ie.evalos.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** A draft is two files of up to max-file-size each in one request (Unit 58). */
class UploadLimitsTest {

	@Test
	void oneRequestHoldsTwoFullSizeFiles() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		var props = yaml.getObject();

		assertThat(props.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("${UPLOAD_MAX_SIZE:15MB}");
		assertThat(props.getProperty("spring.servlet.multipart.max-request-size"))
				.isEqualTo("${UPLOAD_MAX_REQUEST_SIZE:32MB}");
	}
}
```

Write it before the yml edit and watch it fail on `16MB`.

- [ ] **Step 8: Run controller and full suite**

Run: `cd backend && ./mvnw -q test -Dtest=CaseControllerTest,UploadLimitsTest`
Expected: PASS.

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS. If any other test called `submitDraft(any(), any())` or posted to `/draft/submit` (grep `draft/submit` in `backend/src/test`), update it to the new shape.

- [ ] **Step 9: Commit**

```bash
git add backend/src/test/java/com/ie/evalos/config/UploadLimitsTest.java backend/src/main/java/com/ie/evalos/service/DraftFile.java backend/src/main/java/com/ie/evalos/service/CaseLifecycleService.java backend/src/main/java/com/ie/evalos/web/CaseController.java backend/src/main/resources/application.yml backend/src/test/java/com/ie/evalos/service/CaseLifecycleServiceTest.java backend/src/test/java/com/ie/evalos/web/CaseControllerTest.java
git commit -m "feat(drafts): submit a draft as Word + PDF; the client's answer stamps the version"
```

---

### Task 3: `CaseDrafts` — the version in review, client visibility, comments, file links; staff routes

**Files:**
- Create: `service/CaseDrafts.java`
- Modify: `service/CaseLifecycleService.java` (`readUrl` gains `boolean pdf`; `requireCaseContent` and `load` stay package-private)
- Modify: `web/CaseController.java` (comments routes; `?pdf=true`; `hasPdf` on `DocumentVersion`)
- Test: `backend/src/test/java/com/ie/evalos/service/CaseDraftsTest.java` (create), `CaseControllerTest` (append)

**Interfaces:**
- Consumes: `DraftComment`, `DraftCommentRepository`, `DraftNotCurrentException`, `CaseDocument.getPdfObjectKey()` (Task 1); `CaseLifecycleService.load(UUID)` and `versionsOf(UUID, DocumentKind)` (existing)
- Produces (all in `CaseDrafts`, a `@Service`):
  - `record CommentView(UUID id, String authorKind, String authorName, String body, Integer page, Instant createdAt)`
  - `record ClientDraftVersion(UUID id, int version, String status, Instant uploadedAt, boolean inReview, boolean hasWord, boolean hasPdf, String reviewComment)`
  - `Optional<CaseDocument> inReview(Case subject)`
  - `List<CaseDocument> clientVisible(Case subject)`
  - `CaseDocument clientVersion(Case subject, UUID draftId)` — 404 `NotFoundException` if not client-visible
  - `CaseDocument requireInReview(Case subject, UUID draftId)` — 409 `DraftNotCurrentException` otherwise
  - `List<CommentView> comments(CaseDocument draft, boolean forStaff)`
  - `CommentView addComment(CaseDocument draft, DraftComment.AuthorKind kind, UUID authorId, String body, Integer page, boolean forStaff)`
  - `String fileUrl(CaseDocument draft, boolean pdf)`
  - staff: `List<CommentView> staffComments(UUID caseId, UUID draftId)`, `CommentView staffAddComment(UUID caseId, UUID draftId, String body, Integer page)`
- `CaseLifecycleService.readUrl(UUID caseId, UUID documentId, boolean pdf)`
- Routes: `GET /api/cases/{id}/documents/{documentId}/url?pdf=true`; `GET|POST /api/cases/{id}/drafts/{draftId}/comments` (POST body `{ "body": "...", "page": 2 }`); `DocumentVersion` gains `boolean hasPdf`

- [ ] **Step 1: Write the failing service tests**

`backend/src/test/java/com/ie/evalos/service/CaseDraftsTest.java`:

```java
package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.DraftNotCurrentException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.domain.DocumentStatus;
import com.ie.evalos.domain.DraftComment;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.DraftCommentRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Unit 58 §1: which version the client may see, act on and comment on. */
class CaseDraftsTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final CaseDocumentRepository documents = mock(CaseDocumentRepository.class);
	private final DraftCommentRepository comments = mock(DraftCommentRepository.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final DocumentStore store = mock(DocumentStore.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final AuditService audit = mock(AuditService.class);
	private final CaseDrafts drafts = new CaseDrafts(documents, comments, teamMembers, store, lifecycle, audit);

	private Case subject;
	private CaseDocument v1;
	private CaseDocument v2;
	private CaseDocument v3;

	private static CaseDocument version(int n, DocumentStatus status, boolean withFiles) {
		CaseDocument d = new CaseDocument(BRAND, CASE_ID, DocumentKind.DRAFT, n, UUID.randomUUID(), ActorType.STAFF, null);
		ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
		if (withFiles) {
			d.storedDraft("k" + n + ".docx", "v" + n + ".docx", 1, "k" + n + ".pdf", "v" + n + ".pdf", 1);
		}
		d.reviewed(status, null);
		return d;
	}

	@BeforeEach
	void threeVersionsTheLatestWithTheClient() {
		subject = new Case(BRAND, "IE-2026-0001", Stage.CLIENT_REVIEW);
		ReflectionTestUtils.setField(subject, "id", CASE_ID);
		subject.setClientApprovalStatus(ClientApprovalStatus.PENDING);
		v1 = version(1, DocumentStatus.CHANGES_REQUESTED, true);
		v2 = version(2, DocumentStatus.RETURNED, true);
		v3 = version(3, DocumentStatus.PM_APPROVED, true);
		given(documents.findByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.DRAFT)).willReturn(List.of(v3, v2, v1));
		given(comments.save(any(DraftComment.class))).willAnswer(call -> call.getArgument(0));
	}

	@Test
	void theLatestPmApprovedVersionIsInReviewWhileTheClientsAnswerIsPending() {
		assertThat(drafts.inReview(subject)).contains(v3);

		subject.setClientApprovalStatus(ClientApprovalStatus.APPROVED);
		assertThat(drafts.inReview(subject)).isEmpty();
	}

	@Test
	void aReturnedVersionNeverReachesTheClient() {
		assertThat(drafts.clientVisible(subject)).containsExactly(v3, v1);
		assertThatThrownBy(() -> drafts.clientVersion(subject, v2.getId())).isInstanceOf(NotFoundException.class);
	}

	/** Review Focus 2: a stale tab approving v1 after v3 was sent. */
	@Test
	void actingOnAnOlderVersionIsDraftNotCurrent() {
		assertThat(drafts.requireInReview(subject, v3.getId())).isSameAs(v3);
		assertThatThrownBy(() -> drafts.requireInReview(subject, v1.getId())).isInstanceOf(DraftNotCurrentException.class);
	}

	@Test
	void aCommentIsOnlyAcceptedOnTheVersionInReviewAndWithinBounds() {
		var added = drafts.addComment(v3, DraftComment.AuthorKind.CLIENT, UUID.randomUUID(), "  Page 2 typo  ", 2, false);
		assertThat(added.body()).isEqualTo("Page 2 typo");
		assertThat(added.page()).isEqualTo(2);

		assertThatThrownBy(() -> drafts.addComment(v1, DraftComment.AuthorKind.CLIENT, UUID.randomUUID(), "late", null, false))
				.isInstanceOf(DraftNotCurrentException.class);
		assertThatThrownBy(() -> drafts.addComment(v3, DraftComment.AuthorKind.CLIENT, UUID.randomUUID(), "   ", null, false))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> drafts.addComment(v3, DraftComment.AuthorKind.CLIENT, UUID.randomUUID(), "x".repeat(2001), null, false))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> drafts.addComment(v3, DraftComment.AuthorKind.CLIENT, UUID.randomUUID(), "ok", 0, false))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theFileLinkNamesTheRightObject() {
		given(store.presignedUrl("k3.pdf")).willReturn("https://s3/pdf");
		given(store.presignedUrl("k3.docx")).willReturn("https://s3/docx");

		assertThat(drafts.fileUrl(v3, true)).isEqualTo("https://s3/pdf");
		assertThat(drafts.fileUrl(v3, false)).isEqualTo("https://s3/docx");
	}

	/** Review Focus 5: a draft from before Unit 58 carried a link and no file. */
	@Test
	void aLegacyLinkOnlyVersionHasNoFileLink() {
		CaseDocument legacy = version(4, DocumentStatus.PM_APPROVED, false);

		assertThatThrownBy(() -> drafts.fileUrl(legacy, true)).isInstanceOf(IllegalTransitionException.class);
		verify(store, never()).presignedUrl(any());
	}

	@Test
	void theClientSeesTheCaseTeamNotAName() {
		DraftComment staff = new DraftComment(BRAND, v3.getId(), DraftComment.AuthorKind.STAFF, UUID.randomUUID(), "Fixed", null);
		given(comments.findByDocumentIdOrderByCreatedAtAsc(v3.getId())).willReturn(List.of(staff));

		assertThat(drafts.comments(v3, false)).singleElement()
				.satisfies(view -> assertThat(view.authorName()).isNull());
	}
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./mvnw -q test -Dtest=CaseDraftsTest`
Expected: COMPILATION ERROR — `CaseDrafts` does not exist.

- [ ] **Step 3: Implement `CaseDrafts`**

`service/CaseDrafts.java`:

```java
package com.ie.evalos.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.common.DraftNotCurrentException;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.domain.DocumentStatus;
import com.ie.evalos.domain.DraftComment;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.DraftCommentRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draft versions as the client and the case team work on them (Unit 58 §1).
 *
 * <p><strong>Every method takes an already-authorized case or goes through the staff scoped
 * load.</strong> Authorization is not repeated here: {@code PortalCaseService.authorized} for the
 * client, {@link CaseLifecycleService#versionsOf} for staff.
 */
@Service
public class CaseDrafts {

	static final int MAX_COMMENT = 2000;

	/** Decided versions the client keeps seeing, beside the one in review. */
	private static final Set<DocumentStatus> CLIENT_HISTORY = EnumSet.of(DocumentStatus.CLIENT_APPROVED,
			DocumentStatus.CHANGES_REQUESTED);

	/**
	 * @param authorName the staff member's name, for staff readers only; null for a client reader
	 *                   (who sees "Your case team") and for client-authored comments
	 */
	public record CommentView(UUID id, String authorKind, String authorName, String body, Integer page,
			Instant createdAt) {
	}

	/** One version as the client sees it. No object key, no uploader, no PM comment. */
	public record ClientDraftVersion(UUID id, int version, String status, Instant uploadedAt, boolean inReview,
			boolean hasWord, boolean hasPdf, String reviewComment) {
	}

	private final CaseDocumentRepository documents;
	private final DraftCommentRepository comments;
	private final TeamMemberRepository teamMembers;
	private final DocumentStore store;
	private final CaseLifecycleService lifecycle;
	private final AuditService audit;

	CaseDrafts(CaseDocumentRepository documents, DraftCommentRepository comments, TeamMemberRepository teamMembers,
			DocumentStore store, CaseLifecycleService lifecycle, AuditService audit) {
		this.documents = documents;
		this.comments = comments;
		this.teamMembers = teamMembers;
		this.store = store;
		this.lifecycle = lifecycle;
		this.audit = audit;
	}

	/**
	 * The version the client is answering: the newest draft, PM-approved, while the case's client
	 * answer is pending — the same flag {@code approve}/{@code revisions} guard on, so this and the
	 * transition can never disagree about whether there is something to answer.
	 */
	public Optional<CaseDocument> inReview(Case subject) {
		if (subject.getClientApprovalStatus() != ClientApprovalStatus.PENDING) {
			return Optional.empty();
		}
		return drafts(subject).stream().findFirst().filter(d -> d.getStatus() == DocumentStatus.PM_APPROVED);
	}

	/** Newest first: the one in review, then every version the client approved or sent back. */
	public List<CaseDocument> clientVisible(Case subject) {
		UUID current = inReview(subject).map(CaseDocument::getId).orElse(null);
		return drafts(subject).stream()
				.filter(d -> d.getId().equals(current) || CLIENT_HISTORY.contains(d.getStatus()))
				.toList();
	}

	public List<ClientDraftVersion> clientVersions(Case subject) {
		UUID current = inReview(subject).map(CaseDocument::getId).orElse(null);
		return clientVisible(subject).stream()
				.map(d -> new ClientDraftVersion(d.getId(), d.getVersion(), d.getStatus().name(), d.getUploadedAt(),
						d.getId().equals(current), d.getObjectKey() != null, d.hasPdf(),
						// The client's own words on a version they sent back; never the PM's comment.
						d.getStatus() == DocumentStatus.CHANGES_REQUESTED ? d.getReviewComment() : null))
				.toList();
	}

	/** 404 for anything the client may not see — a returned version is not "yours" yet. */
	public CaseDocument clientVersion(Case subject, UUID draftId) {
		return clientVisible(subject).stream().filter(d -> d.getId().equals(draftId)).findFirst()
				.orElseThrow(() -> new NotFoundException("No such draft on this case"));
	}

	public CaseDocument requireInReview(Case subject, UUID draftId) {
		return inReview(subject).filter(d -> d.getId().equals(draftId))
				.orElseThrow(() -> new DraftNotCurrentException("That is not the version waiting for an answer"));
	}

	@Transactional(readOnly = true)
	public List<CommentView> comments(CaseDocument draft, boolean forStaff) {
		List<DraftComment> thread = comments.findByDocumentIdOrderByCreatedAtAsc(draft.getId());
		Map<UUID, String> names = forStaff ? staffNames(thread) : Map.of();
		return thread.stream().map(c -> view(c, names)).toList();
	}

	/**
	 * Adds one comment. The version must be the one in review — the case is re-read from the draft's
	 * own case id through the caller's already-authorized {@code subject} by the two entry points.
	 */
	@Transactional
	public CommentView addComment(CaseDocument draft, DraftComment.AuthorKind kind, UUID authorId, String body,
			Integer page, boolean forStaff) {
		if (draft.getStatus() != DocumentStatus.PM_APPROVED) {
			throw new DraftNotCurrentException("Comments are closed on this version");
		}
		String text = body == null ? "" : body.strip();
		if (text.isEmpty() || text.length() > MAX_COMMENT) {
			throw new InvalidRequestException("A comment is 1 to " + MAX_COMMENT + " characters");
		}
		if (page != null && page < 1) {
			throw new InvalidRequestException("A page number starts at 1");
		}
		DraftComment saved = comments.save(new DraftComment(draft.getBrandId(), draft.getId(), kind, authorId, text, page));
		Map<String, Object> after = page == null ? Map.of("comment", "added") : Map.of("comment", "added", "page", page);
		if (kind == DraftComment.AuthorKind.CLIENT) {
			audit.recordPortalEvent(draft.getBrandId(), PortalAudience.CLIENT, "CASE_DOCUMENT", draft.getId(),
					AuditAction.CREATED, null, after);
		}
		else {
			audit.recordEvent("CASE_DOCUMENT", draft.getId(), AuditAction.CREATED, authorId, null, after);
		}
		return view(saved, forStaff ? staffNames(List.of(saved)) : Map.of());
	}

	/** A five-minute link to the Word or PDF file of one version. Never stored. */
	public String fileUrl(CaseDocument draft, boolean pdf) {
		String key = pdf ? draft.getPdfObjectKey() : draft.getObjectKey();
		if (key == null) {
			throw new IllegalTransitionException("that draft predates uploaded drafts and has no file behind it");
		}
		return store.presignedUrl(key);
	}

	// --- staff ---------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<CommentView> staffComments(UUID caseId, UUID draftId) {
		return comments(staffDraft(caseId, draftId), true);
	}

	/** Staff comment only on the version in review too — the thread is the client conversation about it. */
	@Transactional
	public CommentView staffAddComment(UUID caseId, UUID draftId, String body, Integer page) {
		CaseDocument draft = staffDraft(caseId, draftId);
		requireInReview(lifecycle.load(caseId), draftId);
		return addComment(draft, DraftComment.AuthorKind.STAFF, TenantContext.current().memberId(), body, page, true);
	}

	/** Through {@code versionsOf}: the scoped load and the case-content gate come with it. */
	private CaseDocument staffDraft(UUID caseId, UUID draftId) {
		return lifecycle.versionsOf(caseId, DocumentKind.DRAFT).stream()
				.map(CaseLifecycleService.Version::document)
				.filter(d -> d.getId().equals(draftId))
				.findFirst()
				.orElseThrow(() -> new ForbiddenException("No draft " + draftId + " on this case"));
	}

	private List<CaseDocument> drafts(Case subject) {
		return documents.findByCaseIdAndKindOrderByVersionDesc(subject.getId(), DocumentKind.DRAFT);
	}

	private Map<UUID, String> staffNames(List<DraftComment> thread) {
		List<UUID> ids = thread.stream().filter(c -> c.getAuthorKind() == DraftComment.AuthorKind.STAFF)
				.map(DraftComment::getAuthorId).filter(Objects::nonNull).distinct().toList();
		return ids.isEmpty() ? Map.of() : teamMembers.findAllById(ids).stream()
				.collect(Collectors.toMap(TeamMember::getId, TeamMember::getDisplayName));
	}

	private static CommentView view(DraftComment c, Map<UUID, String> names) {
		return new CommentView(c.getId(), c.getAuthorKind().name(), names.get(c.getAuthorId()), c.getBody(),
				c.getPage(), c.getCreatedAt());
	}
}
```

Check `TenantContext`'s package (`grep -rn "class TenantContext" backend/src/main`) and `IllegalTransitionException`'s (it is in `service`, used unqualified by `CaseLifecycleService`) and fix imports accordingly. If `CaseLifecycleService.load` is package-private (it is — no modifier), `CaseDrafts` in the same package can call it.

- [ ] **Step 4: Run the service tests**

Run: `cd backend && ./mvnw -q test -Dtest=CaseDraftsTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Failing controller test for the staff routes**

Add to `CaseControllerTest` (add `@MockitoBean CaseDrafts drafts;` — or `@MockBean`, whichever the class already uses for `lifecycle`):

```java
	/** Unit 58 §3: the case team reads and writes a version's thread; the PDF link is one flag. */
	@Test
	void theCaseTeamReachesDraftCommentsAndThePdfLink() throws Exception {
		UUID draftId = UUID.randomUUID();
		given(drafts.staffComments(CASE_ID, draftId)).willReturn(List.of());
		given(drafts.staffAddComment(eq(CASE_ID), eq(draftId), eq("See page 2"), eq(2)))
				.willReturn(new CaseDrafts.CommentView(UUID.randomUUID(), "STAFF", "Cam", "See page 2", 2, Instant.now()));
		given(lifecycle.readUrl(CASE_ID, draftId, true)).willReturn("https://s3/pdf");

		mockMvc.perform(get("/api/cases/{id}/drafts/{draftId}/comments", CASE_ID, draftId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/cases/{id}/drafts/{draftId}/comments", CASE_ID, draftId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"See page 2\",\"page\":2}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.page").value(2));
		mockMvc.perform(post("/api/cases/{id}/drafts/{draftId}/comments", CASE_ID, draftId)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/cases/{id}/documents/{documentId}/url", CASE_ID, draftId).param("pdf", "true")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(jsonPath("$.data.url").value("https://s3/pdf"));
	}
```

Also change the existing `given(lifecycle.readUrl(any(), any()))` stubs in this file (if any) to three arguments.

Run: `cd backend && ./mvnw -q test -Dtest=CaseControllerTest`
Expected: FAIL — compile error on `readUrl(..., true)` / 404 on `/drafts/{draftId}/comments`.

- [ ] **Step 6: Implement the staff routes**

`CaseLifecycleService.readUrl` — add the flag and pick the key:

```java
	@Transactional
	public String readUrl(UUID caseId, UUID documentId, boolean pdf) {
		Case subject = load(caseId);
		requireCaseContent();
		CaseDocument document = documents.findById(documentId)
				.filter(row -> row.getCaseId().equals(subject.getId()))
				.orElseThrow(() -> new ForbiddenException("No document " + documentId + " on this case"));
		String key = pdf ? document.getPdfObjectKey() : document.getObjectKey();
		requireState(key != null, "that document has no such file behind it");

		audit.recordEvent("CASE_DOCUMENT", document.getId(), AuditAction.EXPORTED,
				TenantContext.current().memberId(), null,
				Map.of("opened", String.valueOf(pdf ? document.getPdfFilename() : document.getFilename())));
		return store.presignedUrl(key);
	}
```

(Keep the existing javadoc and inline comments; the `NoSuchElementException` becomes `ForbiddenException` because an unmapped `NoSuchElementException` answered 500 — note it in the ledger as a ruling. Keep `@Transactional` exactly as the method has it today.)

`CaseController`:

```java
	@GetMapping("/{id}/documents/{documentId}/url")
	public ApiResponse<ReadUrl> documentUrl(@PathVariable UUID id, @PathVariable UUID documentId,
			@RequestParam(defaultValue = "false") boolean pdf) {
		return ApiResponse.ok(new ReadUrl(lifecycle.readUrl(id, documentId, pdf)));
	}

	/** One version's thread (Unit 58). No {@code @PreAuthorize}: the scoped load decides, like every read here. */
	@GetMapping("/{id}/drafts/{draftId}/comments")
	public ApiResponse<List<CaseDrafts.CommentView>> draftComments(@PathVariable UUID id, @PathVariable UUID draftId) {
		return ApiResponse.ok(drafts.staffComments(id, draftId));
	}

	public record CommentRequest(@NotBlank @Size(max = 2000) String body, @Positive Integer page) {
	}

	@PostMapping("/{id}/drafts/{draftId}/comments")
	@PreAuthorize(GM_OR + "hasAnyRole('CASE_MANAGER', 'PROJECT_COORDINATOR', 'PROJECT_MANAGER')")
	public ApiResponse<CaseDrafts.CommentView> addDraftComment(@PathVariable UUID id, @PathVariable UUID draftId,
			@Valid @RequestBody CommentRequest request) {
		return ApiResponse.ok(drafts.staffAddComment(id, draftId, request.body(), request.page()));
	}
```

Inject `CaseDrafts drafts` through the constructor (follow how the controller injects its other services). `DocumentVersion` gains `boolean hasPdf` as its last component, filled with `document.hasPdf()`.

- [ ] **Step 7: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest=CaseControllerTest,CaseDraftsTest,CaseLifecycleServiceTest`
Expected: PASS.

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS.

```bash
git add backend/src/main/java/com/ie/evalos/service/CaseDrafts.java backend/src/main/java/com/ie/evalos/service/CaseLifecycleService.java backend/src/main/java/com/ie/evalos/web/CaseController.java backend/src/test/java/com/ie/evalos/service/CaseDraftsTest.java backend/src/test/java/com/ie/evalos/web/CaseControllerTest.java
git commit -m "feat(drafts): version-in-review rules, comment threads and Word/PDF links for the case team"
```

---

### Task 4: The client's milestone timeline and stepper position

**Files:**
- Create: `service/CaseMilestones.java`
- Modify: `service/PortalStageProjection.java`
- Test: `backend/src/test/java/com/ie/evalos/service/CaseMilestonesTest.java` (create), `PortalStageProjectionTest` (append)

**Interfaces:**
- Consumes: `AuditEventRepository.findByObjectTypeAndObjectIdOrderByCreatedAtAsc(String, UUID)`; audit `afterSnapshot`/`beforeSnapshot` JSON carry `CaseLifecycleService.CaseSnapshot` components `stage` and `draftVersionCount`
- Produces:
  - `record CaseMilestones.Milestone(String label, Instant at)`
  - `List<Milestone> CaseMilestones.of(Case subject)`
  - `static List<Milestone> CaseMilestones.derive(Instant opened, List<AuditEvent> trail, ObjectMapper json)` (pure; what the test drives)
  - `static int PortalStageProjection.clientStepIndex(Stage)` → 0 Upload, 1 Review, 2 Signing, 3 Delivered

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/ie/evalos/service/CaseMilestonesTest.java`:

```java
package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit 58 §3: the client's history is milestones in their words, never the internal trail. */
class CaseMilestonesTest {

	private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");
	private final ObjectMapper json = new ObjectMapper();

	private static AuditEvent move(String from, String to, int version, int minutes) {
		AuditEvent e = new AuditEvent(UUID.randomUUID(), "CASE", UUID.randomUUID(), AuditAction.STAGE_CHANGED, null,
				ActorType.STAFF, "{\"stage\":\"" + from + "\",\"draftVersionCount\":" + version + "}",
				"{\"stage\":\"" + to + "\",\"draftVersionCount\":" + version + "}");
		ReflectionTestUtils.setField(e, "createdAt", T0.plusSeconds(minutes * 60L));
		return e;
	}

	@Test
	void aFullWalkReadsAsSixMilestonesAndNothingInternal() {
		List<AuditEvent> trail = List.of(
				move("DOC_COLLECTION", "PM_REVIEW", 0, 10),
				move("PM_REVIEW", "DRAFT_IN_PROGRESS", 0, 20),      // assignment: internal
				move("DRAFT_IN_PROGRESS", "DRAFT_REVIEW", 1, 30),   // PM review: internal
				move("DRAFT_REVIEW", "DRAFT_IN_PROGRESS", 1, 35),   // PM return: internal
				move("DRAFT_REVIEW", "READY_TO_SEND", 2, 40),
				move("READY_TO_SEND", "CLIENT_REVIEW", 2, 50),
				move("CLIENT_REVIEW", "DRAFT_IN_PROGRESS", 2, 60),  // changes requested
				move("READY_TO_SEND", "CLIENT_REVIEW", 3, 70),
				move("CLIENT_REVIEW", "CLIENT_APPROVAL", 3, 80),
				move("CLIENT_APPROVAL", "EXPERT_SIGNING", 3, 90),
				move("EXPERT_SIGNING", "FINAL_QC", 3, 100),
				move("READY_TO_DELIVER", "DELIVERED", 3, 110));

		assertThat(CaseMilestones.derive(T0, trail, json))
				.extracting(CaseMilestones.Milestone::label)
				.containsExactly("Case opened", "Documents received", "Draft ready — v2", "Draft ready — v3",
						"Draft approved", "Letter signed", "Delivered");
	}

	@Test
	void aNewCaseHasOnlyItsOpening() {
		assertThat(CaseMilestones.derive(T0, List.of(), json)).singleElement()
				.satisfies(m -> assertThat(m.at()).isEqualTo(T0));
	}

	/** A row written before snapshots carried the stage, or by another object, is skipped, not a 500. */
	@Test
	void anUnreadableSnapshotIsSkipped() {
		AuditEvent odd = new AuditEvent(UUID.randomUUID(), "CASE", UUID.randomUUID(), AuditAction.UPDATED, null,
				ActorType.STAFF, null, "not json");
		ReflectionTestUtils.setField(odd, "createdAt", T0.plusSeconds(60));

		assertThat(CaseMilestones.derive(T0, List.of(odd), json)).hasSize(1);
	}
}
```

Append to `PortalStageProjectionTest`:

```java
	@org.junit.jupiter.api.Test
	void theClientStepperHasFourPositions() {
		org.assertj.core.api.Assertions.assertThat(PortalStageProjection.clientStepIndex(Stage.DOC_COLLECTION)).isZero();
		org.assertj.core.api.Assertions.assertThat(PortalStageProjection.clientStepIndex(Stage.DRAFT_REVIEW)).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(PortalStageProjection.clientStepIndex(Stage.CLIENT_REVIEW)).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(PortalStageProjection.clientStepIndex(Stage.CLIENT_APPROVAL)).isEqualTo(2);
		org.assertj.core.api.Assertions.assertThat(PortalStageProjection.clientStepIndex(Stage.READY_TO_DELIVER)).isEqualTo(2);
		org.assertj.core.api.Assertions.assertThat(PortalStageProjection.clientStepIndex(Stage.CLOSED)).isEqualTo(3);
	}
```

(Use the file's existing imports instead of the qualified names if it already has them.)

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./mvnw -q test -Dtest=CaseMilestonesTest,PortalStageProjectionTest`
Expected: COMPILATION ERROR — `CaseMilestones`, `clientStepIndex` missing. If `AuditEvent` has no `createdAt` field named exactly that, read `domain/AuditEvent.java` (line 87 is `private Instant createdAt;`) — it is.

- [ ] **Step 3: Implement**

`PortalStageProjection` — beside `forClient`:

```java
	/** The client stepper: Upload → Review → Signing → Delivered (Unit 58 §4). */
	public static int clientStepIndex(Stage stage) {
		return switch (stage) {
			case DOC_COLLECTION -> 0;
			case PM_REVIEW, DRAFT_IN_PROGRESS, DRAFT_REVIEW, READY_TO_SEND, CLIENT_REVIEW -> 1;
			case CLIENT_APPROVAL, EXPERT_SIGNING, FINAL_QC, READY_TO_DELIVER -> 2;
			case DELIVERED, CLOSED -> 3;
		};
	}
```

`service/CaseMilestones.java`:

```java
package com.ie.evalos.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.repository.AuditEventRepository;

import org.springframework.stereotype.Component;

/**
 * The client's case history (Unit 58 §3), derived at read time from the stage snapshots the
 * lifecycle already writes — no second record of the same facts. Only entries into the stages a
 * client cares about become milestones; PM review, returns, reassignments and holds never appear.
 */
@Component
public class CaseMilestones {

	public record Milestone(String label, Instant at) {
	}

	private final AuditEventRepository trail;
	private final ObjectMapper json;

	CaseMilestones(AuditEventRepository trail, ObjectMapper json) {
		this.trail = trail;
		this.json = json;
	}

	/** {@code subject} is already authorized; its id is the only thing read by. */
	public List<Milestone> of(Case subject) {
		return derive(subject.getCreatedAt(), trail.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("CASE", subject.getId()),
				json);
	}

	static List<Milestone> derive(Instant opened, List<AuditEvent> events, ObjectMapper json) {
		List<Milestone> out = new ArrayList<>();
		out.add(new Milestone("Case opened", opened));
		boolean documentsReceived = false;
		for (AuditEvent event : events) {
			JsonNode after = read(json, event.getAfterSnapshot());
			JsonNode before = read(json, event.getBeforeSnapshot());
			String to = after == null ? null : after.path("stage").asText(null);
			String from = before == null ? null : before.path("stage").asText(null);
			if (to == null || to.equals(from)) {
				continue;
			}
			String label = switch (to) {
				case "PM_REVIEW" -> documentsReceived ? null : "Documents received";
				case "CLIENT_REVIEW" -> "Draft ready — v" + after.path("draftVersionCount").asInt();
				case "CLIENT_APPROVAL" -> "Draft approved";
				case "FINAL_QC" -> "EXPERT_SIGNING".equals(from) ? "Letter signed" : null;
				case "DELIVERED" -> "Delivered";
				default -> null;
			};
			if ("PM_REVIEW".equals(to)) {
				documentsReceived = true;
			}
			if (label != null) {
				out.add(new Milestone(label, event.getCreatedAt()));
			}
		}
		return out;
	}

	private static JsonNode read(ObjectMapper json, String snapshot) {
		if (snapshot == null) {
			return null;
		}
		try {
			return json.readTree(snapshot);
		}
		catch (Exception unreadable) {
			return null;
		}
	}
}
```

Before relying on the `"stage"` key, confirm it once: `grep -n "writeValueAsString" backend/src/main/java/com/ie/evalos/service/AuditService.java` — `CaseSnapshot` is a record serialized by Jackson, so its component `stage` becomes `"stage"`. If the service serializes differently, adjust the two `path("stage")` / `path("draftVersionCount")` reads and ledger it.

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest=CaseMilestonesTest,PortalStageProjectionTest`
Expected: PASS.

```bash
git add backend/src/main/java/com/ie/evalos/service/CaseMilestones.java backend/src/main/java/com/ie/evalos/service/PortalStageProjection.java backend/src/test/java/com/ie/evalos/service/CaseMilestonesTest.java backend/src/test/java/com/ie/evalos/service/PortalStageProjectionTest.java
git commit -m "feat(portal): client milestone timeline and stepper position from the stage trail"
```

---

### Task 5: Per-case client routes

**Files:**
- Modify: `service/PortalCaseService.java`, `web/ClientPortalController.java`
- Test: `backend/src/test/java/com/ie/evalos/service/PortalCaseServiceTest.java` (append + constructor), `backend/src/test/java/com/ie/evalos/web/ClientPortalControllerTest.java` (append if it exists; otherwise the service test is the coverage and a route smoke test is added there)

**Interfaces:**
- Consumes: `CaseDrafts` (Task 3), `CaseMilestones`, `PortalStageProjection.clientStepIndex` (Task 4)
- Produces (all `PortalCaseService`, each first calling `authorized(principal, caseId)`):
  - `ClientDraftView` gains components `String step`, `int stepIndex`, `List<CaseMilestones.Milestone> milestones` (appended at the end)
  - `ClientDocumentsView documents(PortalPrincipal, UUID caseId)`, `CaseDocument upload(PortalPrincipal, UUID caseId, UUID checklistItemId, String filename, String contentType, long size, InputStream body)`, `String documentUrl(PortalPrincipal, UUID caseId, UUID documentId)`
  - `List<CaseDrafts.ClientDraftVersion> drafts(PortalPrincipal, UUID caseId)`
  - `String draftFileUrl(PortalPrincipal, UUID caseId, UUID draftId, boolean pdf)`
  - `List<CaseDrafts.CommentView> draftComments(PortalPrincipal, UUID caseId, UUID draftId)`
  - `CaseDrafts.CommentView addDraftComment(PortalPrincipal, UUID caseId, UUID draftId, String body, Integer page)`
  - `ClientDraftView approveDraft(PortalPrincipal, UUID caseId, UUID draftId)`, `ClientDraftView requestChanges(PortalPrincipal, UUID caseId, UUID draftId, String notes)`
  - `record DeliveredFile(UUID id, String kind, String filename, Instant at)`; `List<DeliveredFile> delivered(PortalPrincipal, UUID caseId)`; `String deliveredUrl(PortalPrincipal, UUID caseId, UUID documentId)`
- Routes under `/api/portal/client`: `GET cases/{caseId}/documents`, `POST cases/{caseId}/documents?checklistItemId=`, `GET cases/{caseId}/documents/{documentId}/url`, `GET cases/{caseId}/drafts`, `GET cases/{caseId}/drafts/{draftId}/files/{file}/url` (`file` ∈ `docx|pdf`), `GET|POST cases/{caseId}/drafts/{draftId}/comments`, `POST cases/{caseId}/drafts/{draftId}/approve`, `POST cases/{caseId}/drafts/{draftId}/request-changes`, `GET cases/{caseId}/delivered`, `GET cases/{caseId}/delivered/{documentId}/url`; `GET invoices?status=paid`

- [ ] **Step 1: Update the test constructor and the whitelist assertion**

In `PortalCaseServiceTest`:

```java
	private final CaseDrafts drafts = mock(CaseDrafts.class);
	private final CaseMilestones milestones = mock(CaseMilestones.class);

	private final PortalCaseService portal = new PortalCaseService(cases, contacts, lifecycle, checklistItems, documents,
			store, audit, drafts, milestones);
```

and extend the record-component assertion (≈ line 261) to
`containsExactly("clientName", "serviceType", "caseReference", "draftLink", "draftVersion", "approvalStatus", "awaitingAnswer", "step", "stepIndex", "milestones")`.

- [ ] **Step 2: Write the failing tests**

Append to `PortalCaseServiceTest` (the fixture's case is `CASE_ID`, contact `ghl-1`, brand `BRAND`; `partyTokenFor(BRAND, "ghl-1")` admits it):

```java
	/** Review Focus 1: the draft id must be on this case and client-visible. */
	@Test
	void aClientCannotReachAReturnedVersionOrAnotherCasesDraft() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID returned = UUID.randomUUID();
		given(drafts.clientVersion(subject, returned)).willThrow(new com.ie.evalos.common.NotFoundException("No such draft"));

		assertThatThrownBy(() -> portal.draftFileUrl(me, CASE_ID, returned, true))
				.isInstanceOf(com.ie.evalos.common.NotFoundException.class);
		// Another client's case: the ownership check refuses before the draft is looked at.
		assertThatThrownBy(() -> portal.draftFileUrl(partyTokenFor(BRAND, "ghl-someone-else"), CASE_ID, returned, true))
				.isInstanceOf(ForbiddenException.class);
		verify(drafts, times(1)).clientVersion(any(), any());
	}

	@Test
	void approvingNamesTheVersionAndRunsTheExistingTransition() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID v3 = UUID.randomUUID();
		given(lifecycle.clientApproveDraftFromPortal(subject)).willReturn(subject);

		portal.approveDraft(me, CASE_ID, v3);

		verify(drafts).requireInReview(subject, v3);
		verify(lifecycle).clientApproveDraftFromPortal(subject);
	}

	@Test
	void aStaleApprovalNeverReachesTheTransition() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID stale = UUID.randomUUID();
		given(drafts.requireInReview(subject, stale)).willThrow(new com.ie.evalos.common.DraftNotCurrentException("stale"));

		assertThatThrownBy(() -> portal.approveDraft(me, CASE_ID, stale))
				.isInstanceOf(com.ie.evalos.common.DraftNotCurrentException.class);
		assertThatThrownBy(() -> portal.requestChanges(me, CASE_ID, stale, "no"))
				.isInstanceOf(com.ie.evalos.common.DraftNotCurrentException.class);
		verify(lifecycle, never()).clientApproveDraftFromPortal(any());
		verify(lifecycle, never()).clientRequestRevisionsFromPortal(any(), any());
	}

	@Test
	void aClientCommentIsAttributedToTheirCredential() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		UUID v3 = UUID.randomUUID();
		CaseDocument draft = new CaseDocument(BRAND, CASE_ID, DocumentKind.DRAFT, 3, null, ActorType.STAFF, null);
		given(drafts.requireInReview(subject, v3)).willReturn(draft);

		portal.addDraftComment(me, CASE_ID, v3, "Page 2", 2);

		verify(drafts).addComment(draft, com.ie.evalos.domain.DraftComment.AuthorKind.CLIENT, me.portalAccessId(),
				"Page 2", 2, false);
	}

	/** Review Focus 4: the signed letter exists at FINAL_QC, and is still not the client's. */
	@Test
	void deliveredFilesAreRefusedBeforeDelivery() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		subject.setCurrentStage(Stage.FINAL_QC);

		assertThatThrownBy(() -> portal.delivered(me, CASE_ID)).isInstanceOf(com.ie.evalos.common.NotFoundException.class);
		assertThatThrownBy(() -> portal.deliveredUrl(me, CASE_ID, UUID.randomUUID()))
				.isInstanceOf(com.ie.evalos.common.NotFoundException.class);
		verify(store, never()).presignedUrl(any());
	}

	@Test
	void onceDeliveredTheClientGetsTheSignedLetterAndTheApprovedDraft() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		subject.setCurrentStage(Stage.DELIVERED);
		CaseDocument letter = new CaseDocument(BRAND, CASE_ID, DocumentKind.SIGNED_LETTER, 1, null, ActorType.EXPERT, null);
		ReflectionTestUtils.setField(letter, "id", UUID.randomUUID());
		letter.setObjectKey("signed.pdf");
		letter.setFilename("Signed letter.pdf");
		CaseDocument approved = new CaseDocument(BRAND, CASE_ID, DocumentKind.DRAFT, 2, null, ActorType.STAFF, null);
		ReflectionTestUtils.setField(approved, "id", UUID.randomUUID());
		approved.storedDraft("d.docx", "Draft.docx", 1, "d.pdf", "Draft.pdf", 1);
		approved.reviewed(com.ie.evalos.domain.DocumentStatus.CLIENT_APPROVED, null);
		given(documents.findFirstByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.SIGNED_LETTER))
				.willReturn(Optional.of(letter));
		given(documents.findByCaseIdAndKindOrderByVersionDesc(CASE_ID, DocumentKind.DRAFT)).willReturn(java.util.List.of(approved));
		given(store.presignedUrl("signed.pdf")).willReturn("https://s3/signed");

		assertThat(portal.delivered(me, CASE_ID)).extracting(PortalCaseService.DeliveredFile::kind)
				.containsExactly("SIGNED_LETTER", "APPROVED_DRAFT");
		assertThat(portal.deliveredUrl(me, CASE_ID, letter.getId())).isEqualTo("https://s3/signed");
	}

	@Test
	void theCaseDetailCarriesTheStepAndMilestones() {
		PortalPrincipal me = partyTokenFor(BRAND, "ghl-1");
		subject.setCurrentStage(Stage.CLIENT_REVIEW);
		given(milestones.of(subject)).willReturn(java.util.List.of(new CaseMilestones.Milestone("Case opened", Instant.now())));

		PortalCaseService.ClientDraftView view = portal.clientView(me, CASE_ID);

		assertThat(view.step()).isEqualTo("Review");
		assertThat(view.stepIndex()).isEqualTo(1);
		assertThat(view.milestones()).hasSize(1);
	}
```

Check: the fixture must let `partyTokenFor(BRAND, "ghl-1")` pass `authorized(principal, caseId)` — it reads `contacts.findById(CONTACT_ID)` → `theContact.getGhlContactId()` = `"ghl-1"`, already stubbed in `@BeforeEach`.

- [ ] **Step 3: Run to verify failure**

Run: `cd backend && ./mvnw -q test -Dtest=PortalCaseServiceTest`
Expected: COMPILATION ERROR — constructor arity, `draftFileUrl`, `approveDraft`, `delivered`, etc. missing.

- [ ] **Step 4: Implement in `PortalCaseService`**

Constructor: add `CaseDrafts drafts, CaseMilestones milestones` (fields of the same names).

`ClientDraftView` — append three components with javadoc lines:

```java
			boolean awaitingAnswer,
			/** D5's projected label for the stage. */
			String step,
			/** Stepper position, Upload 0 → Delivered 3 (Unit 58 §4). */
			int stepIndex,
			/** Client-language history (Unit 58 §3). */
			java.util.List<CaseMilestones.Milestone> milestones) {
```

and in `view(Case subject)` pass
`PortalStageProjection.forClient(subject.getCurrentStage()).label(), PortalStageProjection.clientStepIndex(subject.getCurrentStage()), milestones.of(subject)` after the `awaitingAnswer` argument.

Documents by case — refactor the three existing methods so the body after authorization is shared:

```java
	/** This case's checklist and the client's uploads to it (Unit 58 — replaces the case-less read). */
	@Transactional(readOnly = true)
	public ClientDocumentsView documents(PortalPrincipal principal, UUID caseId) {
		return documentsOf(authorized(principal, caseId));
	}
```

Move the existing body of `documents(PortalPrincipal)` from `java.util.List<ChecklistItemView> checklist = …` through `return new ClientDocumentsView(checklist, uploaded);` into `private ClientDocumentsView documentsOf(Case subject)`, and make the old method end with `return documentsOf(authorized(principal));`. Do the same for `documentUrl` (`documentUrlOf(Case subject, UUID documentId)`) and `upload` (`uploadTo(Case subject, UUID checklistItemId, String filename, String contentType, long size, InputStream body)`), adding the public per-case overloads:

```java
	@Transactional
	public String documentUrl(PortalPrincipal principal, UUID caseId, UUID documentId) {
		return documentUrlOf(authorized(principal, caseId), documentId);
	}

	@Transactional
	public CaseDocument upload(PortalPrincipal principal, UUID caseId, UUID checklistItemId, String filename,
			String contentType, long size, java.io.InputStream body) {
		return uploadTo(authorized(principal, caseId), checklistItemId, filename, contentType, size, body);
	}
```

Drafts, comments, answers, delivered:

```java
	// --- Unit 58: drafts as uploaded versions ---------------------------------

	@Transactional(readOnly = true)
	public java.util.List<CaseDrafts.ClientDraftVersion> drafts(PortalPrincipal principal, UUID caseId) {
		return drafts.clientVersions(authorized(principal, caseId));
	}

	/** A five-minute link to one client-visible version's Word or PDF, audited as the client's. */
	@Transactional
	public String draftFileUrl(PortalPrincipal principal, UUID caseId, UUID draftId, boolean pdf) {
		Case subject = authorized(principal, caseId);
		CaseDocument draft = drafts.clientVersion(subject, draftId);
		String url = drafts.fileUrl(draft, pdf);
		audit.recordPortalEvent(subject.getBrandId(), PortalAudience.CLIENT, "CASE_DOCUMENT", draft.getId(),
				AuditAction.EXPORTED, null, java.util.Map.of("opened", "draft v" + draft.getVersion() + (pdf ? " (PDF)" : " (Word)")));
		return url;
	}

	@Transactional(readOnly = true)
	public java.util.List<CaseDrafts.CommentView> draftComments(PortalPrincipal principal, UUID caseId, UUID draftId) {
		return drafts.comments(drafts.clientVersion(authorized(principal, caseId), draftId), false);
	}

	/** Author is the credential the client posted through — the one identity every token has. */
	@Transactional
	public CaseDrafts.CommentView addDraftComment(PortalPrincipal principal, UUID caseId, UUID draftId, String body,
			Integer page) {
		CaseDocument draft = drafts.requireInReview(authorized(principal, caseId), draftId);
		return drafts.addComment(draft, DraftComment.AuthorKind.CLIENT, principal.portalAccessId(), body, page, false);
	}

	/** Approve names the version, so a stale tab cannot approve a newer one it never saw. */
	@Transactional
	public ClientDraftView approveDraft(PortalPrincipal principal, UUID caseId, UUID draftId) {
		Case subject = authorized(principal, caseId);
		drafts.requireInReview(subject, draftId);
		return view(lifecycle.clientApproveDraftFromPortal(subject));
	}

	@Transactional
	public ClientDraftView requestChanges(PortalPrincipal principal, UUID caseId, UUID draftId, String notes) {
		Case subject = authorized(principal, caseId);
		drafts.requireInReview(subject, draftId);
		return view(lifecycle.clientRequestRevisionsFromPortal(subject, notes));
	}

	/** One file the client receives at delivery. {@code kind}: SIGNED_LETTER or APPROVED_DRAFT. */
	public record DeliveredFile(UUID id, String kind, String filename, Instant at) {
	}

	/**
	 * The signed letter and the approved draft — <strong>refused by the server before delivery</strong>
	 * (Unit 58 §5), not merely hidden: the letter exists from signing onward, and FINAL_QC may still
	 * send it back.
	 */
	@Transactional(readOnly = true)
	public java.util.List<DeliveredFile> delivered(PortalPrincipal principal, UUID caseId) {
		Case subject = deliveredCase(principal, caseId);
		java.util.List<DeliveredFile> out = new java.util.ArrayList<>();
		documents.findFirstByCaseIdAndKindOrderByVersionDesc(subject.getId(), DocumentKind.SIGNED_LETTER)
				.filter(d -> d.getObjectKey() != null)
				.ifPresent(d -> out.add(new DeliveredFile(d.getId(), "SIGNED_LETTER", d.getFilename(), d.getUploadedAt())));
		approvedDraft(subject)
				.ifPresent(d -> out.add(new DeliveredFile(d.getId(), "APPROVED_DRAFT", d.getPdfFilename(), d.getUploadedAt())));
		return out;
	}

	/** The approved draft is served as its PDF; the letter as itself. */
	@Transactional
	public String deliveredUrl(PortalPrincipal principal, UUID caseId, UUID documentId) {
		Case subject = deliveredCase(principal, caseId);
		String key = documents.findFirstByCaseIdAndKindOrderByVersionDesc(subject.getId(), DocumentKind.SIGNED_LETTER)
				.filter(d -> d.getId().equals(documentId)).map(CaseDocument::getObjectKey)
				.or(() -> approvedDraft(subject).filter(d -> d.getId().equals(documentId)).map(CaseDocument::getPdfObjectKey))
				.orElseThrow(() -> new NotFoundException("No such delivered file"));
		audit.recordPortalEvent(subject.getBrandId(), PortalAudience.CLIENT, "CASE_DOCUMENT", documentId,
				AuditAction.EXPORTED, null, java.util.Map.of("opened", "delivered file"));
		return store.presignedUrl(key);
	}

	private Case deliveredCase(PortalPrincipal principal, UUID caseId) {
		Case subject = authorized(principal, caseId);
		if (subject.getCurrentStage() != Stage.DELIVERED && subject.getCurrentStage() != Stage.CLOSED) {
			throw new NotFoundException("Nothing has been delivered on this case yet");
		}
		return subject;
	}

	private Optional<CaseDocument> approvedDraft(Case subject) {
		return documents.findByCaseIdAndKindOrderByVersionDesc(subject.getId(), DocumentKind.DRAFT).stream()
				.filter(d -> d.getStatus() == DocumentStatus.CLIENT_APPROVED && d.hasPdf())
				.findFirst();
	}
```

Imports: `com.ie.evalos.common.NotFoundException`, `com.ie.evalos.domain.DocumentStatus`, `com.ie.evalos.domain.DraftComment`, `com.ie.evalos.domain.Stage`, `com.ie.evalos.domain.AuditAction` (check which exist).

- [ ] **Step 5: Run the service tests**

Run: `cd backend && ./mvnw -q test -Dtest=PortalCaseServiceTest`
Expected: PASS (existing + 7 new).

- [ ] **Step 6: Controller routes**

In `ClientPortalController`, add (placing them after `requestRevisionsOnCase`):

```java
	// --- Unit 58: per-case routes ---------------------------------------------

	@GetMapping("/cases/{caseId}/documents")
	public ApiResponse<PortalCaseService.ClientDocumentsView> caseDocuments(@PathVariable UUID caseId) {
		return ApiResponse.ok(portal.documents(client(), caseId));
	}

	@PostMapping("/cases/{caseId}/documents")
	public ApiResponse<UploadedView> uploadToCase(@PathVariable UUID caseId, @RequestParam UUID checklistItemId,
			@RequestParam("file") MultipartFile file) throws IOException {
		UploadedFileType.require(file, UploadedFileType.CLIENT_DOCUMENT);
		CaseDocument saved = portal.upload(client(), caseId, checklistItemId, file.getOriginalFilename(),
				file.getContentType(), file.getSize(), file.getInputStream());
		return ApiResponse.ok(new UploadedView(saved.getId(), saved.getFilename(), saved.getVersion()));
	}

	@GetMapping("/cases/{caseId}/documents/{documentId}/url")
	public ApiResponse<ReadUrl> caseDocumentUrl(@PathVariable UUID caseId, @PathVariable UUID documentId) {
		return ApiResponse.ok(new ReadUrl(portal.documentUrl(client(), caseId, documentId)));
	}

	@GetMapping("/cases/{caseId}/drafts")
	public ApiResponse<List<CaseDrafts.ClientDraftVersion>> drafts(@PathVariable UUID caseId) {
		return ApiResponse.ok(portal.drafts(client(), caseId));
	}

	@GetMapping("/cases/{caseId}/drafts/{draftId}/files/{file}/url")
	public ApiResponse<ReadUrl> draftFileUrl(@PathVariable UUID caseId, @PathVariable UUID draftId,
			@PathVariable String file) {
		if (!file.equals("docx") && !file.equals("pdf")) {
			throw new InvalidRequestException("file is docx or pdf");
		}
		return ApiResponse.ok(new ReadUrl(portal.draftFileUrl(client(), caseId, draftId, file.equals("pdf"))));
	}

	@GetMapping("/cases/{caseId}/drafts/{draftId}/comments")
	public ApiResponse<List<CaseDrafts.CommentView>> draftComments(@PathVariable UUID caseId, @PathVariable UUID draftId) {
		return ApiResponse.ok(portal.draftComments(client(), caseId, draftId));
	}

	public record CommentRequest(@NotBlank @Size(max = 2000) String body, @Positive Integer page) {
	}

	@PostMapping("/cases/{caseId}/drafts/{draftId}/comments")
	public ApiResponse<CaseDrafts.CommentView> addDraftComment(@PathVariable UUID caseId, @PathVariable UUID draftId,
			@Valid @RequestBody CommentRequest request) {
		return ApiResponse.ok(portal.addDraftComment(client(), caseId, draftId, request.body(), request.page()));
	}

	@PostMapping("/cases/{caseId}/drafts/{draftId}/approve")
	public ApiResponse<PortalCaseService.ClientDraftView> approveDraft(@PathVariable UUID caseId,
			@PathVariable UUID draftId) {
		return ApiResponse.ok(portal.approveDraft(client(), caseId, draftId));
	}

	/** The note is optional here (the thread carries the detail), unlike the legacy route. */
	public record ChangesRequest(@Size(max = 2000) String notes) {
	}

	@PostMapping("/cases/{caseId}/drafts/{draftId}/request-changes")
	public ApiResponse<PortalCaseService.ClientDraftView> requestChanges(@PathVariable UUID caseId,
			@PathVariable UUID draftId, @Valid @RequestBody(required = false) ChangesRequest request) {
		return ApiResponse.ok(portal.requestChanges(client(), caseId, draftId, request == null ? null : request.notes()));
	}

	@GetMapping("/cases/{caseId}/delivered")
	public ApiResponse<List<PortalCaseService.DeliveredFile>> delivered(@PathVariable UUID caseId) {
		return ApiResponse.ok(portal.delivered(client(), caseId));
	}

	@GetMapping("/cases/{caseId}/delivered/{documentId}/url")
	public ApiResponse<ReadUrl> deliveredUrl(@PathVariable UUID caseId, @PathVariable UUID documentId) {
		return ApiResponse.ok(new ReadUrl(portal.deliveredUrl(client(), caseId, documentId)));
	}
```

Mirror the existing `upload` route's exact parameter names and sniffing call (read it at ≈ line 191 first; if it uses a different part name than `file` or a different `UploadedFileType` set, use the same). Change `invoices()` to:

```java
	@GetMapping("/invoices")
	public ApiResponse<List<GhlInvoiceClient.ClientInvoice>> invoices(@RequestParam(required = false) String status) {
		List<GhlInvoiceClient.ClientInvoice> mine = portalInvoices.forCaller(client());
		// Unit 58: the portal's Invoices page shows settled bills only — GHL's own `paid` status.
		return ApiResponse.ok(status == null ? mine
				: mine.stream().filter(i -> status.equalsIgnoreCase(i.status())).toList());
	}
```

Add a controller-level test where the class has one (`ClientPortalControllerTest`, if present — `ls backend/src/test/java/com/ie/evalos/web`), covering: `GET invoices?status=paid` filters to `paid` rows, and `GET cases/{id}/drafts/{draftId}/files/exe/url` answers 400. If no such test class exists, add the paid-filter assertion to `PortalInvoiceServiceTest` is not possible (the filter is in the controller) — then create `ClientPortalControllerTest` with `@WebMvcTest(ClientPortalController.class)` following `CaseControllerTest`'s setup for portal auth, or, if portal auth makes a slice test heavy, move the filter into `PortalInvoiceService.forCaller(PortalPrincipal, String status)` and test it there. Ledger which one you chose.

- [ ] **Step 7: Run and commit**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS.

```bash
git add backend/src/main/java/com/ie/evalos/service/PortalCaseService.java backend/src/main/java/com/ie/evalos/web/ClientPortalController.java backend/src/test/java/com/ie/evalos
git commit -m "feat(portal): per-case client routes for documents, drafts, comments, answers, delivered files and paid invoices"
```

---

### Task 6: Staff "Upload draft", Word/PDF links and the comment thread

**Files:**
- Create: `frontend/src/features/case/draftRules.ts`, `draftRules.test.ts`, `UploadDraft.tsx`, `DraftComments.tsx`
- Modify: `frontend/src/features/case/caseApi.ts`, `DraftHistory.tsx`, `CaseDetail.tsx`
- Modify: `frontend/src/features/board/boardRules.ts`, `boardRules.test.ts`

**Interfaces:**
- Consumes: `POST /api/cases/{id}/drafts` (Task 2); `GET|POST /api/cases/{id}/drafts/{draftId}/comments`, `GET …/documents/{id}/url?pdf=true`, `DocumentVersion.hasPdf` (Task 3)
- Produces:
  - `mayUploadDraft(stage: string, role: Role): boolean`, `mayComment(status: string, clientApprovalStatus: string | null): boolean` in `draftRules.ts`
  - `uploadDraft(caseId, docx: File, pdf: File)`, `fetchDraftComments(caseId, draftId)`, `postDraftComment(caseId, draftId, body, page)`, `fetchDocumentUrl(caseId, documentId, pdf = false)` in `caseApi.ts`

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/case/draftRules.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { mayComment, mayUploadDraft } from './draftRules'

describe('mayUploadDraft', () => {
  it('lets the case team upload while the draft is being written', () => {
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'CASE_MANAGER')).toBe(true)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'PROJECT_COORDINATOR')).toBe(true)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'PROJECT_MANAGER')).toBe(true)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'GM')).toBe(true)
  })

  it('offers nothing at another stage or to another role', () => {
    expect(mayUploadDraft('DRAFT_REVIEW', 'CASE_MANAGER')).toBe(false)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'EXPERT_NETWORK_MANAGER')).toBe(false)
    expect(mayUploadDraft('DRAFT_IN_PROGRESS', 'SALES')).toBe(false)
  })
})

describe('mayComment', () => {
  it('is open only on the PM-approved version while the client answer is pending', () => {
    expect(mayComment('PM_APPROVED', 'PENDING')).toBe(true)
    expect(mayComment('PM_APPROVED', null)).toBe(false)
    expect(mayComment('CHANGES_REQUESTED', 'REVISION_REQUESTED')).toBe(false)
  })
})
```

In `boardRules.test.ts` ≈ line 186, replace
`expect(paths('DRAFT_IN_PROGRESS', 'CASE_MANAGER')).toContain('draft/submit')`
with
`expect(paths('DRAFT_IN_PROGRESS', 'CASE_MANAGER')).not.toContain('draft/submit') // Unit 58: "Upload draft" on the case`.

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx vitest run src/features/case/draftRules.test.ts src/features/board/boardRules.test.ts`
Expected: FAIL — `./draftRules` not found; `draft/submit` still offered.

- [ ] **Step 3: Implement the rules and API**

`frontend/src/features/case/draftRules.ts`:

```ts
import type { Role } from '../../lib/session'

/** Who may upload the next draft version (Unit 58): the case team, while the draft is being written. */
const UPLOADERS: Role[] = ['CASE_MANAGER', 'PROJECT_COORDINATOR', 'PROJECT_MANAGER', 'GM']

export function mayUploadDraft(stage: string, role: Role): boolean {
  return stage === 'DRAFT_IN_PROGRESS' && UPLOADERS.includes(role)
}

/** The server's rule, mirrored so the box is not offered where it would 409 (DRAFT_NOT_CURRENT). */
export function mayComment(status: string, clientApprovalStatus: string | null): boolean {
  return status === 'PM_APPROVED' && clientApprovalStatus === 'PENDING'
}
```

`boardRules.ts` — delete the whole `{ path: 'draft/submit', … }` entry (≈ lines 394–404) and its comment.

`caseApi.ts` — add `hasPdf: boolean` to `DraftVersion`, change `fetchDocumentUrl`, and add:

```ts
export async function fetchDocumentUrl(caseId: string, documentId: string, pdf = false): Promise<string> {
  const { url } = await unwrap<{ url: string }>(
    api.get(`/cases/${caseId}/documents/${documentId}/url`, { params: pdf ? { pdf: true } : {} }),
  )
  return url
}

/** Both files of the next version in one request (Unit 58). The browser sets the multipart boundary. */
export async function uploadDraft(caseId: string, docx: File, pdf: File): Promise<void> {
  const body = new FormData()
  body.append('docx', docx)
  body.append('pdf', pdf)
  await unwrap(api.post(`/cases/${caseId}/drafts`, body, { headers: { 'Content-Type': undefined } }))
}

export type DraftComment = {
  id: string
  authorKind: 'STAFF' | 'CLIENT'
  authorName: string | null
  body: string
  page: number | null
  createdAt: string
}

export async function fetchDraftComments(caseId: string, draftId: string): Promise<DraftComment[]> {
  return unwrap<DraftComment[]>(api.get(`/cases/${caseId}/drafts/${draftId}/comments`))
}

export async function postDraftComment(
  caseId: string,
  draftId: string,
  body: string,
  page: number | null,
): Promise<DraftComment> {
  return unwrap<DraftComment>(api.post(`/cases/${caseId}/drafts/${draftId}/comments`, { body, page }))
}
```

- [ ] **Step 4: Run the rule tests**

Run: `cd frontend && npx vitest run src/features/case/draftRules.test.ts src/features/board/boardRules.test.ts`
Expected: PASS.

- [ ] **Step 5: The components**

`frontend/src/features/case/UploadDraft.tsx`:

```tsx
import { useState } from 'react'
import { uploadDraft } from './caseApi'

/**
 * The next draft version as Word + PDF, one action (Unit 58). Replaces the "Link to the draft"
 * field: the client now views and downloads the files themselves.
 */
export default function UploadDraft({ caseId, onUploaded }: { caseId: string; onUploaded: () => void }) {
  const [docx, setDocx] = useState<File | null>(null)
  const [pdf, setPdf] = useState<File | null>(null)
  const [state, setState] = useState<'idle' | 'sending' | 'failed'>('idle')

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!docx || !pdf) return
    setState('sending')
    try {
      await uploadDraft(caseId, docx, pdf)
      setState('idle')
      onUploaded()
    } catch {
      setState('failed')
    }
  }

  return (
    <form onSubmit={submit} className="mt-3 flex flex-col gap-2 text-sm">
      <label className="flex flex-col gap-1">
        <span style={{ color: 'var(--text-muted)' }}>Word file (.docx)</span>
        <input type="file" accept=".docx" required onChange={(e) => setDocx(e.target.files?.[0] ?? null)} />
      </label>
      <label className="flex flex-col gap-1">
        <span style={{ color: 'var(--text-muted)' }}>PDF of the same draft</span>
        <input type="file" accept="application/pdf,.pdf" required onChange={(e) => setPdf(e.target.files?.[0] ?? null)} />
      </label>
      <button
        type="submit"
        disabled={!docx || !pdf || state === 'sending'}
        className="self-start rounded-md px-3 py-1.5 font-medium"
        style={{ background: 'var(--accent-primary)', color: 'var(--text-on-accent, white)' }}
      >
        {state === 'sending' ? 'Uploading…' : 'Upload draft'}
      </button>
      {state === 'failed' && (
        <p role="alert" style={{ color: 'var(--status-red)' }}>
          The draft was not uploaded. Check both files are a real Word document and PDF under 15MB, then try again.
        </p>
      )}
    </form>
  )
}
```

`frontend/src/features/case/DraftComments.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { fetchDraftComments, postDraftComment, type DraftComment } from './caseApi'

/** One version's thread with the client (Unit 58). Read-only unless the version is in client review. */
export default function DraftComments({ caseId, draftId, open }: { caseId: string; draftId: string; open: boolean }) {
  const [thread, setThread] = useState<DraftComment[] | null>(null)
  const [body, setBody] = useState('')
  const [page, setPage] = useState('')
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    fetchDraftComments(caseId, draftId).then(setThread).catch(() => setFailed(true))
  }, [caseId, draftId])

  async function send(event: React.FormEvent) {
    event.preventDefault()
    const text = body.trim()
    if (!text) return
    try {
      const added = await postDraftComment(caseId, draftId, text, page ? Number(page) : null)
      setThread((t) => [...(t ?? []), added])
      setBody('')
      setPage('')
    } catch {
      setFailed(true)
    }
  }

  if (failed) return <p className="text-sm" style={{ color: 'var(--status-red)' }}>Could not load or send comments.</p>
  if (!thread) return null

  return (
    <div className="mt-2 flex flex-col gap-2">
      {thread.length === 0 && <p className="text-xs" style={{ color: 'var(--text-muted)' }}>No comments on this version.</p>}
      <ul className="flex flex-col gap-1">
        {thread.map((c) => (
          <li key={c.id} className="text-sm">
            <span className="font-medium">{c.authorKind === 'CLIENT' ? 'Client' : (c.authorName ?? 'Case team')}</span>
            {c.page != null && <span style={{ color: 'var(--text-muted)' }}> · page {c.page}</span>}
            <span style={{ color: 'var(--text-muted)' }}> · {new Date(c.createdAt).toLocaleString()}</span>
            <p className="whitespace-pre-wrap">{c.body}</p>
          </li>
        ))}
      </ul>
      {open && (
        <form onSubmit={send} className="flex flex-wrap items-end gap-2">
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            maxLength={2000}
            rows={2}
            aria-label="Comment"
            className="min-w-[16rem] flex-1 rounded-md border p-1.5 text-sm"
          />
          <input
            type="number"
            min={1}
            value={page}
            onChange={(e) => setPage(e.target.value)}
            aria-label="Page (optional)"
            placeholder="Page"
            className="w-20 rounded-md border p-1.5 text-sm"
          />
          <button type="submit" className="rounded-md px-3 py-1.5 text-sm font-medium" style={{ color: 'var(--accent-primary)' }}>
            Comment
          </button>
        </form>
      )}
    </div>
  )
}
```

`DraftHistory.tsx`:
- signature becomes `DraftHistory({ caseId, clientApprovalStatus, reloadKey }: { caseId: string; clientApprovalStatus: string | null; reloadKey: number })` and the effect depends on `[caseId, reloadKey]`;
- add `CHANGES_REQUESTED: { label: 'Client asked for changes', color: 'var(--status-red)' }` to `STATUS_TONE`;
- inside each `<li>`, after the review comment, render:

```tsx
                {version.filename && (
                  <p className="flex gap-3 text-sm">
                    <button type="button" onClick={() => open(version.id, false)} style={{ color: 'var(--accent-primary)' }}>
                      Word
                    </button>
                    {version.hasPdf && (
                      <button type="button" onClick={() => open(version.id, true)} style={{ color: 'var(--accent-primary)' }}>
                        PDF
                      </button>
                    )}
                  </p>
                )}
                {version.hasPdf && (
                  <DraftComments caseId={caseId} draftId={version.id} open={mayComment(version.status, clientApprovalStatus)} />
                )}
```

with, in the component body,

```tsx
  // Minted at the click, never stored: a held presigned URL expires while the page sits open.
  async function open(documentId: string, pdf: boolean) {
    window.open(await fetchDocumentUrl(caseId, documentId, pdf), '_blank', 'noopener')
  }
```

and imports for `fetchDocumentUrl`, `DraftComments`, `mayComment`.

`DraftPanel` is left as it is (it still shows a legacy link when one exists). In `CaseDetail.tsx`, where `<DraftHistory caseId={detail.summary.id} />` is rendered (≈ line 190), render instead:

```tsx
          {mayUploadDraft(detail.summary.stage, me.role) && (
            <UploadDraft caseId={detail.summary.id} onUploaded={() => { setDraftsReload((n) => n + 1); reload() }} />
          )}
          <DraftHistory
            caseId={detail.summary.id}
            clientApprovalStatus={detail.summary.clientApprovalStatus}
            reloadKey={draftsReload}
          />
```

with `const [draftsReload, setDraftsReload] = useState(0)` in the component, imports for `UploadDraft` and `mayUploadDraft`, and `reload()` standing for however `CaseDetail` already refetches the case after a stage action (find the function the stage-action header calls on success and use it; if the field on `BoardCard` is `currentStage` rather than `stage`, use that name). Wrap `UploadDraft` in the same bordered `<section>` styling `DraftPanel` uses, with heading "Upload draft".

- [ ] **Step 6: Type-check, test, build**

Run: `cd frontend && npx vitest run && npm run build`
Expected: all tests pass; `tsc -b` and `vite build` succeed.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/case frontend/src/features/board/boardRules.ts frontend/src/features/board/boardRules.test.ts
git commit -m "feat(staff): upload a draft as Word + PDF, open either file, and comment on the version in review"
```

---

### Task 7: Docs, decisions and memories

**Files:**
- Modify: `context/specs/58-client-portal.md` (§3 staff routes as built, §8 phase 1 BUILT, status line)
- Modify: `context/specs/55-remove-questionnaire.md` (answers drop V70 → **V71**)
- Modify: `context/specs/57-case-chat.md` §7 (client-portal row points to spec 58)
- Modify: `.claude/current-decisions.md` (new **D51**; D33 note), `.claude/data-model.md` (CURRENT: V70 columns, `draft_comments`, `CHANGES_REQUESTED`), `.claude/workflows.md` (CURRENT: draft submit = upload; client answer stamps the version; client per-case routes), `.claude/implementation-status.md` (Unit 58 phase 1 row with evidence: test class names + commits), `.claude/open-decisions.md` (only if something stayed open)
- Modify: the matching `.serena/memories/*` files (the ones mirroring the seven baseline docs)

- [ ] **Step 1: Spec 58 amendments (edits, not notes beside the old text)**
  - §3 Staff: `POST /api/cases/{id}/drafts` as built; the staff version list and file links **reuse** `GET /api/cases/{id}/documents?kind=DRAFT` (now with `hasPdf`) and `GET …/documents/{docId}/url?pdf=true`; comments at `GET/POST /api/cases/{id}/drafts/{draftId}/comments`.
  - §1 "in client review": the latest `PM_APPROVED` version **while the case's client answer is `PENDING`** (the flag the approve/revisions guards use), replacing "while the case is at CLIENT_REVIEW / CLIENT_APPROVAL".
  - §3 Client: `GET cases/{id}` returns the existing view plus `step`, `stepIndex`, `milestones`; the checklist comes from `GET cases/{id}/documents`. Add `GET cases/{id}/delivered/{docId}/url` (the delivered letter needs a link route). A client comment's `author_id` is the portal credential id.
  - Status line: "phase 1 BUILT 2026-09-xx".

- [ ] **Step 2: Decisions** — add D51 to `.claude/current-decisions.md` exactly as spec 58 §9 words it; append to D33 the sentence "Drafts are now stored files (Word + PDF) on their `case_document` DRAFT versions, alongside the request documents (Unit 58, D51)." Edit the file's decision count in its header if it states one.

- [ ] **Step 3: data-model / workflows / status** — CURRENT sections only; the status row cites `CaseDraftsTest`, `CaseMilestonesTest`, `PortalCaseServiceTest`, `CaseLifecycleServiceTest`, `LocalPostgresIntegrationTest#draftCommentsAreAppendOnlyAndADraftCarriesItsPdf`, `draftRules.test.ts`, and the commit range.

- [ ] **Step 4: Serena memories** — update the mirrors of the files touched in Steps 2–3 (`ls .serena/memories` and edit the matching ones; if the Serena MCP server is unavailable, edit the files directly).

- [ ] **Step 5: Verify nothing still claims otherwise**

Run: `grep -rn "V70" context/specs/55-remove-questionnaire.md .claude .serena/memories` — expected: every mention of the answers drop says V71; V70 is the draft migration.
Run: `grep -rn "draft_link\|Link to the draft" .claude/workflows.md` — expected: only in the legacy-link sentence.

- [ ] **Step 6: Commit**

```bash
git add context .claude .serena
git commit -m "docs(unit-58): phase 1 built — D51, drafts as files, per-case client routes, answers drop moves to V71"
```
