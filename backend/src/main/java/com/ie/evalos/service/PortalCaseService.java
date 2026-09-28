package com.ie.evalos.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;


import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.domain.DocumentStatus;
import com.ie.evalos.domain.DraftComment;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a client may see of their own case, and the two things they may do to it.
 *
 * <p><strong>A narrow read of its own, not a widened {@code CaseDetailService}.</strong> That DTO
 * carries the deal value, the PM's strategy notes, the expert's identity, the assignment slots and
 * the audit timeline — every one of which is somebody else's information. This projects a
 * <strong>whitelist</strong> instead, for the same reason Unit 13's redaction is one: a field added
 * to {@code Case} in a later unit does not appear here, because the person adding it does not have
 * to remember a rule in a file they are not editing.
 *
 * <p><strong>Every method names its case, and the credential proves it.</strong>
 * {@link #authorized} matches the case id against the token's party (or, for a case-scoped
 * token, its one case) before anything is read. {@code ScopePredicate} is not involved and no
 * synthetic {@code TenantContext} is manufactured: a client is not a staff user with a narrower
 * tier. The case-less overloads, which resolved the case from the token alone, went in Unit 58
 * phase 3.
 */
@Service
public class PortalCaseService {

	/**
	 * The whole client-facing view of a case.
	 *
	 * <p>Deliberately absent, each because it belongs to somebody else: {@code deal_value},
	 * {@code pm_strategy_notes}, the expert's name and institution, {@code invoice_ref},
	 * {@code campaign_attribution}, every assignment field, the audit timeline, the document
	 * checklist, and <strong>the client's own documents</strong> — which are their own document
	 * folder and is never sent here, not renamed, not aliased and not defaulted to when
	 * {@code draftLink} is missing.
	 *
	 * @param caseReference   the human-facing case code, so client and staff quote the same thing
	 * @param draftLink       null until a draft has been submitted, which the portal reads as
	 *                        "not ready" rather than substituting another link
	 * @param awaitingAnswer  whether the two actions are live. Stated by the server rather than
	 *                        derived from {@code approvalStatus} in the client, because the
	 *                        transition's own guard is the authority on it
	 * <p><strong>The client is told nothing about the expert (Unit 13 removed, 2026-09-02).</strong>
	 * This payload used to carry a redacted profile — credentials with the name, institution and
	 * contact details stripped. Withholding the expert's identity entirely is the stronger position
	 * and it is now the only one: there is no redaction to get wrong, and no generated document to
	 * keep anonymous.
	 */
	public record ClientDraftView(
			String clientName,
			ServiceType serviceType,
			String caseReference,
			String draftLink,
			int draftVersion,
			ClientApprovalStatus approvalStatus,
			boolean awaitingAnswer,
			/** D5's projected label for the stage. */
			String step,
			/** Stepper position, Upload 0 → Delivered 3 (Unit 58 §4). */
			int stepIndex,
			/** Client-language history (Unit 58 §3). */
			java.util.List<CaseMilestones.Milestone> milestones) {
	}

	/**
	 * One row of "my cases" (Unit 35, D1 + D5).
	 *
	 * <p><strong>Narrower than {@link ClientDraftView} on purpose.</strong> A list is read by
	 * anyone the link was forwarded to before they pick a case, so it carries the least that still
	 * lets someone recognise their own work: the reference, the service, the step, and whether the
	 * step is theirs. No client name — the reader is the client, and echoing the name back into a
	 * list turns a forwarded link into a way to confirm who it belongs to. No draft link: that is
	 * the detail read, behind a case id.
	 *
	 * @param step           D5's projected label, EvalOS's word for the stage
	 * @param actionRequired whether this case is waiting on the client, so the SPA can mark it
	 *                       without parsing {@code step}
	 */
	public record ClientCaseSummary(
			UUID caseId,
			String caseReference,
			ServiceType serviceType,
			String step,
			boolean actionRequired,
			/** Stepper position, Upload 0 → Delivered 3 — what Home splits active from delivered on. */
			int stepIndex) {
	}

	private final CaseRepository cases;
	private final ContactSnapshotRepository contacts;
	private final CaseLifecycleService lifecycle;
	private final DocumentChecklistItemRepository checklistItems;
	private final CaseDocumentRepository documents;
	private final DocumentStore store;
	private final AuditService audit;
	private final CaseDrafts drafts;
	private final CaseMilestones milestones;

	PortalCaseService(CaseRepository cases, ContactSnapshotRepository contacts,
			CaseLifecycleService lifecycle, DocumentChecklistItemRepository checklistItems,
			CaseDocumentRepository documents, DocumentStore store, AuditService audit, CaseDrafts drafts,
			CaseMilestones milestones) {
		this.drafts = drafts;
		this.milestones = milestones;
		this.cases = cases;
		this.contacts = contacts;
		this.lifecycle = lifecycle;
		this.checklistItems = checklistItems;
		this.documents = documents;
		this.store = store;
		this.audit = audit;
	}

	private static void requireState(boolean condition, String why) {
		if (!condition) {
			throw new IllegalTransitionException(why);
		}
	}

	/**
	 * Every case this client party has, newest first (Unit 35, D1) — the read no case-scoped token
	 * could answer, and the reason D1 was worth taking.
	 *
	 * <p>Refused outright for a case-scoped token rather than answering a one-element list. The two
	 * credentials are different things and a caller that holds the narrow one should not be able to
	 * discover the shape of the wide one's reply; a client holding a case link asks the case route.
	 *
	 * <p>{@code readOnly}, unlike {@link #clientView}: there is no case here to stamp
	 * {@code client_portal_read_at} on. Opening a list is not reading a draft, and treating it as
	 * one would tell a Case Manager the client had seen something they have not.
	 */
	@Transactional(readOnly = true)
	public java.util.List<ClientCaseSummary> clientCases(PortalPrincipal principal) {
		if (!principal.isPartyScoped()) {
			throw new ForbiddenException("This link admits you to one case, not a list");
		}
		return partyCases(principal).stream().map(subject -> {
			PortalStageProjection.PortalStep step = PortalStageProjection.forClient(subject.getCurrentStage());
			return new ClientCaseSummary(subject.getId(), subject.getCaseCode(), subject.getServiceType(),
					step.label(), step.actionRequired(),
					PortalStageProjection.clientStepIndex(subject.getCurrentStage()));
		}).toList();
	}

	/**
	 * The client's one screen, and the read receipt.
	 *
	 * <p>{@code client_portal_read_at} is stamped <strong>once</strong>, on the first read: it
	 * answers "has the client seen this at all", which is what the Case Manager needs to know before
	 * chasing. "When did they last look" is {@code portal_access.last_seen_at}, which
	 * {@code PortalAccessService.resolve} moves on every request. Two fields because they are two
	 * questions; one that did both would answer neither.
	 *
	 * <p>Not {@code readOnly} for exactly that stamp. A case that is not this caller's answers
	 * <strong>403, not 404</strong> — 404 would be the oracle a client would use to count the brand's
	 * cases.
	 */
	@Transactional
	public ClientDraftView clientView(PortalPrincipal principal, UUID caseId) {
		Case subject = authorized(principal, caseId);
		if (subject.getClientPortalReadAt() == null) {
			subject.setClientPortalReadAt(Instant.now());
			cases.save(subject);
		}
		return view(subject);
	}

	/** The projection itself, shared with the two writes so they answer the page's new state. */
	private ClientDraftView view(Case subject) {
		// Both lookups are by an id that came off the authorized case, which is the same
		// provenance the batched staff finders rely on — and unlike them there is no
		// TenantContext here to scope with. Neither id ever arrives from a request.
		String clientName = Optional.ofNullable(subject.getContactId())
				.flatMap(contacts::findById)
				.map(ContactSnapshot::getFullName)
				.orElse(null);

		return new ClientDraftView(
				clientName,
				subject.getServiceType(),
				subject.getCaseCode(),
				draftLink(subject),
				subject.getDraftVersionCount(),
				subject.getClientApprovalStatus(),
				subject.getClientApprovalStatus() == ClientApprovalStatus.PENDING,
				PortalStageProjection.forClient(subject.getCurrentStage()).label(),
				PortalStageProjection.clientStepIndex(subject.getCurrentStage()),
				milestones.of(subject));
	}

	/**
	 * The link the live portal opens (Unit 58, final review #1). A draft submitted as files has no
	 * {@code draft_link}, so the newest client-visible version's PDF is minted for this read — five
	 * minutes, never stored. A legacy pasted link still wins.
	 */
	private String draftLink(Case subject) {
		if (subject.getDraftLink() != null && !subject.getDraftLink().isBlank()) {
			return subject.getDraftLink();
		}
		return drafts.clientVisible(subject).stream().findFirst().filter(CaseDocument::hasPdf)
				.map(d -> drafts.fileUrl(d, true)).orElse(null);
	}

	/**
	 * What the client must send, and what they have sent (Unit 34c).
	 *
	 * <p><strong>Why this exists at all:</strong> {@link #upload} takes a {@code checklistItemId}
	 * and there was no way for a client to learn one. The upload endpoint shipped in Unit 30
	 * unreachable — not insecure, just uncallable — and this is the read that closes it.
	 *
	 * <p><strong>Not a widening of {@link ClientDraftView}.</strong> That record's javadoc says the
	 * checklist and the client's own documents are excluded, and it stays that way: the draft view
	 * is one screen's whitelist and this is another's. Keeping them apart means a field added to
	 * either does not silently appear on the other, which is the same argument that made
	 * {@code ClientDraftView} a projection rather than a narrowed staff DTO.
	 *
	 * <p><strong>Only the client's own uploads.</strong> {@code DocumentKind.DRAFT} and
	 * {@code SIGNED_LETTER} are filtered out here and again in {@link #documentUrl}. A draft
	 * reaches the client through {@code draftLink} on the other screen, under the approval guard
	 * that belongs to it; the signed letter is Unit 15's and delivery is a decision nobody has
	 * taken. Widening this filter is how either of those leaks early.
	 */
	public record ClientDocumentsView(java.util.List<ChecklistItemView> checklist,
			java.util.List<UploadedDocumentView> uploaded) {
	}

	/**
	 * One required document.
	 *
	 * <p>The status is <strong>Unit 10's own vocabulary, unmapped</strong> — {@code REQUIRED},
	 * {@code UPLOADED}, {@code APPROVED}, {@code MISSING}, {@code INCORRECT}. No portal-specific
	 * status enum is invented, because a second vocabulary for the same fact is a second thing that
	 * can disagree with the Coordinator's screen. {@code MISSING} and {@code INCORRECT} reaching
	 * the client is the point rather than a leak: it is touchpoint T4 — "your upload was flagged" —
	 * arriving as a state the client can see instead of a message EvalOS has no way to send.
	 */
	public record ChecklistItemView(UUID id, String label, ChecklistItemStatus status) {
	}

	/**
	 * One document the client sent.
	 *
	 * <p><strong>No object key</strong>, for the reason {@code CaseController.DocumentVersion}
	 * gives: a key is an internal address, and a client-side copy of one is a pointer somebody
	 * eventually tries to turn into a URL. The bytes are reached through {@link #documentUrl},
	 * which mints a five-minute capability per request.
	 *
	 * @param checklistLabel which requirement this answered, so a client can tell two PDFs apart
	 */
	public record UploadedDocumentView(UUID id, String filename, int version, Instant uploadedAt,
			String checklistLabel) {
	}

	/** This case's checklist and the client's uploads to it. Read-only: no receipt is stamped here. */
	@Transactional(readOnly = true)
	public ClientDocumentsView documents(PortalPrincipal principal, UUID caseId) {
		return documentsOf(authorized(principal, caseId));
	}

	private ClientDocumentsView documentsOf(Case subject) {
		java.util.List<ChecklistItemView> checklist = checklistItems.findByCaseId(subject.getId()).stream()
				.map(item -> new ChecklistItemView(item.getId(), item.getLabel(), item.getStatus()))
				.toList();

		java.util.List<UploadedDocumentView> uploaded = documents
				.findByCaseIdAndKindOrderByVersionDesc(subject.getId(), DocumentKind.CLIENT_UPLOAD).stream()
				.map(row -> new UploadedDocumentView(row.getId(), row.getFilename(), row.getVersion(),
						row.getUploadedAt(), row.getNotes()))
				.toList();

		return new ClientDocumentsView(checklist, uploaded);
	}

	/**
	 * A five-minute URL for one document the client themselves uploaded (Unit 34c).
	 *
	 * <p><strong>The token's case is the authorization and the kind filter is the second half of
	 * it.</strong> {@link #authorized} proves the caller holds the case; the document is then
	 * matched against that case <em>and</em> against {@code CLIENT_UPLOAD}. Without the case match
	 * a client could name any document id in the system; without the kind match they could name the
	 * draft or the expert's signed letter on their own case and read it outside the approval flow.
	 *
	 * <p>The URL is minted <strong>after</strong> both checks, never before — a presigned URL
	 * created ahead of the check is a URL that leaked ahead of the check. It is never stored.
	 *
	 * <p>Every issue writes an {@code EXPORTED} row with {@code actor_type = CLIENT}, which is what
	 * makes "the client opened their own passport scan on the 4th" a fact the trail holds rather
	 * than an inference from a web log.
	 */
	@Transactional
	public String documentUrl(PortalPrincipal principal, UUID caseId, UUID documentId) {
		return documentUrlOf(authorized(principal, caseId), documentId);
	}

	private String documentUrlOf(Case subject, UUID documentId) {
		CaseDocument document = documents.findById(documentId)
				.filter(row -> row.getCaseId().equals(subject.getId()))
				.filter(row -> row.getKind() == DocumentKind.CLIENT_UPLOAD)
				.orElseThrow(() -> new ForbiddenException("That document is not one of yours"));

		requireState(document.getObjectKey() != null,
				"that document predates the document store and has no file behind it");

		audit.recordPortalEvent(subject.getBrandId(), PortalAudience.CLIENT, "CASE_DOCUMENT",
				document.getId(), AuditAction.EXPORTED, null,
				java.util.Map.of("opened", String.valueOf(document.getFilename())));
		return store.presignedUrl(document.getObjectKey());
	}

	/**
	 * The client uploads one document against one checklist item (Unit 30).
	 *
	 * <p><strong>No file on disk and no blob column — but the part IS buffered in heap, and the
	 * distinction matters.</strong> {@code spring.servlet.multipart.file-size-threshold} equals
	 * {@code max-file-size}, so the container holds the whole part in memory rather than spooling
	 * it to a temp file; {@code getInputStream()} therefore reads from a byte array, not a socket.
	 * That is a deliberate trade for invariant 14's "hosts no files" — see the comment on that
	 * block — and it bounds heap exposure at the upload cap. What this method adds is that EvalOS
	 * makes <em>no further copy</em>: the stream goes straight to S3 and nothing is retained after.
	 *
	 * <p><strong>Object first, row second, and the order is the whole design.</strong> The reverse
	 * leaves a row pointing at an object that does not exist — a broken link on the Coordinator's
	 * screen, and one they cannot fix. This order can leave an orphaned object if the transaction
	 * then fails: invisible, cheap, and swept by a bucket lifecycle rule. <strong>Prefer the orphan
	 * to the dangling pointer.</strong>
	 *
	 * <p><strong>Never overwrites.</strong> Every upload mints a new document id and therefore a
	 * new key. A client replacing a rejected transcript adds a version; it does not destroy the one
	 * the Coordinator rejected, which is the evidence of why they rejected it.
	 *
	 * <p>The checklist item moves to {@code UPLOADED} — the vocabulary Unit 10 already has, which
	 * is why this unit adds no status value.
	 */
	@Transactional
	public CaseDocument upload(PortalPrincipal principal, UUID caseId, UUID checklistItemId, String filename,
			String contentType, long size, java.io.InputStream body) {
		return uploadTo(authorized(principal, caseId), checklistItemId, filename, contentType, size, body);
	}

	private CaseDocument uploadTo(Case subject, UUID checklistItemId, String filename, String contentType, long size,
			java.io.InputStream body) {
		DocumentChecklistItem item = checklistItems.findById(checklistItemId)
				.filter(row -> row.getCaseId() != null && row.getCaseId().equals(subject.getId()))
				.orElseThrow(() -> new IllegalTransitionException(
						"that checklist item is not on this case"));

		// **The client id comes off the case's contact, never off the request.** A key built from
		// anything the caller sent would let one client write into another's prefix.
		//
		// It is the **GHL contact id** — the one identifier that represents a contact everywhere in
		// this system, ruled 2026-09-17. It was `contact_snapshot.id` from 2026-09-14 until then;
		// see DocumentStore.clientKey for why that swap happened and what makes the GHL id safe to
		// key on again. Reads are unaffected either way: `case_document.object_key` is what a read
		// resolves through, so objects written under the old shape stay readable.
		String clientId = Optional.ofNullable(subject.getContactId())
				.flatMap(contacts::findById)
				.map(ContactSnapshot::getGhlContactId)
				.filter((id) -> !id.isBlank())
				.orElse(null);
		requireState(clientId != null,
				"this case's contact has no GHL contact id yet, so there is nowhere to file the "
						+ "document. It is backfilled at the client's next sign-in or request (D3c)");

		UUID documentId = UUID.randomUUID();
		String key = DocumentStore.clientKey(subject.getBrandId(), clientId, documentId);
		store.put(key, body, size, contentType);

		CaseDocument document = new CaseDocument(subject.getBrandId(), subject.getId(),
				DocumentKind.CLIENT_UPLOAD, nextVersion(subject.getId()), null, ActorType.CLIENT,
				item.getLabel());
		document.setObjectKey(key);
		document.setFilename(filename);
		documents.save(document);

		item.markStatus(ChecklistItemStatus.UPLOADED);
		checklistItems.save(item);

		// The `after` snapshot names the item and the file, not the object key: a key is an
		// internal address and the trail is read by people asking what the client sent.
		audit.recordPortalEvent(subject.getBrandId(), PortalAudience.CLIENT, "CASE", subject.getId(),
				AuditAction.UPDATED, null,
				java.util.Map.of("uploaded", filename, "checklistItem", String.valueOf(item.getLabel())));
		return document;
	}

	/**
	 * The next version number for this case's client uploads.
	 *
	 * <p>A client upload has no counter on the case the way a draft does, so this reads the highest
	 * and adds one. <strong>The race is closed by {@code uq_case_document_version}</strong>: two
	 * simultaneous uploads on one case both read N, both try N+1, and the database refuses the
	 * second.
	 *
	 * <p><strong>There is no retry, and this comment used to claim there was.</strong> The loser
	 * gets a 500 and its object is left orphaned in S3 — which is the cheap failure by design (see
	 * {@link #upload}: prefer the orphan to the dangling pointer), but the client has to upload
	 * again. Acceptable because one client uploading two documents at the same instant to the same
	 * case is rare; <strong>if it stops being rare, catch the constraint violation and retry rather
	 * than taking a lock</strong>, which would serialise every upload to buy nothing the index does
	 * not already guarantee.
	 */
	private int nextVersion(UUID caseId) {
		return documents.findFirstByCaseIdAndKindOrderByVersionDesc(caseId, DocumentKind.CLIENT_UPLOAD)
				.map(latest -> latest.getVersion() + 1)
				.orElse(1);
	}

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
				AuditAction.EXPORTED, null,
				java.util.Map.of("opened", "draft v" + draft.getVersion() + (pdf ? " (PDF)" : " (Word)")));
		return url;
	}

	@Transactional(readOnly = true)
	public java.util.List<CaseDrafts.CommentView> draftComments(PortalPrincipal principal, UUID caseId,
			UUID draftId) {
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
		cases.lockById(caseId);
		Case subject = authorized(principal, caseId);
		drafts.requireInReview(subject, draftId);
		return view(lifecycle.clientApproveDraftFromPortal(subject));
	}

	@Transactional
	public ClientDraftView requestChanges(PortalPrincipal principal, UUID caseId, UUID draftId, String notes) {
		cases.lockById(caseId);
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
		approvedDraft(subject).ifPresent(
				d -> out.add(new DeliveredFile(d.getId(), "APPROVED_DRAFT", d.getPdfFilename(), d.getUploadedAt())));
		return out;
	}

	/** The approved draft is served as its PDF; the letter as itself. */
	@Transactional
	public String deliveredUrl(PortalPrincipal principal, UUID caseId, UUID documentId) {
		Case subject = deliveredCase(principal, caseId);
		String key = documents.findFirstByCaseIdAndKindOrderByVersionDesc(subject.getId(), DocumentKind.SIGNED_LETTER)
				.filter(d -> d.getId().equals(documentId))
				.map(CaseDocument::getObjectKey)
				.or(() -> approvedDraft(subject).filter(d -> d.getId().equals(documentId))
						.map(CaseDocument::getPdfObjectKey))
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

	/**
	 * The case the caller named, checked against the credential — every route's whole
	 * authorization.
	 *
	 * <p>{@code findById} and not {@code findScoped}, the one deliberate exception to
	 * {@code ScopedRepository}'s rule: there is no {@code TenantContext} on this surface to scope
	 * by, so the party match below and the brand check in {@link #byId} are the predicate.
	 *
	 * <p>A case-scoped token may also reach here, and must be pinned to its own case: without the
	 * equality check, the narrow credential would gain the wide one's reach the moment a path
	 * variable existed to carry another id.
	 */
	private Case authorized(PortalPrincipal principal, UUID caseId) {
		Case subject = byId(principal, caseId);
		if (principal.isPartyScoped()) {
			String mine = principal.ghlContactId();
			String theirs = Optional.ofNullable(subject.getContactId())
					.flatMap(contacts::findById)
					.map(ContactSnapshot::getGhlContactId)
					.orElse(null);
			if (mine == null || !mine.equals(theirs)) {
				throw new ForbiddenException("This link does not admit you to that case");
			}
		} else if (!caseId.equals(principal.caseId())) {
			throw new ForbiddenException("This link does not admit you to that case");
		}
		return subject;
	}

	/**
	 * The brand check both shapes share. Refuses with the same sentence a missing case gets, so
	 * the two are one answer.
	 */
	private Case byId(PortalPrincipal principal, UUID caseId) {
		Case subject = cases.findById(caseId)
				.orElseThrow(() -> new ForbiddenException("This link no longer points at a case"));
		if (!subject.getBrandId().equals(principal.brandId())) {
			throw new ForbiddenException("This link no longer points at a case");
		}
		return subject;
	}

	/**
	 * The cases behind a client party token: its GHL contact, resolved in the token's own brand.
	 *
	 * <p>Empty rather than an error when the contact has no cases — a client whose only case was
	 * merged away holds a working link to an empty list, which is a truthful answer. Same answer
	 * for an account-scoped token with no contact at all (Unit 42): a client who has just signed
	 * up has no cases, and this is the truthful way to say so. The guard is not decoration —
	 * without it a null would be matched against a contact row whose own {@code ghl_contact_id} is
	 * null the day any lookup stops treating {@code = NULL} as unknown, and that contact's cases
	 * are somebody else's.
	 */
	private java.util.List<Case> partyCases(PortalPrincipal principal) {
		if (principal.ghlContactId() == null) {
			return java.util.List.of();
		}
		return contacts.findByBrandIdAndGhlContactId(principal.brandId(), principal.ghlContactId())
				.map(contact -> cases.findByBrandIdAndContactIdOrderByCreatedAtDesc(
						principal.brandId(), contact.getId()))
				.orElseGet(java.util.List::of);
	}
}
