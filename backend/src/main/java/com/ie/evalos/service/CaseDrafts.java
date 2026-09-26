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
import com.ie.evalos.domain.IllegalTransitionException;
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
			boolean hasWord, boolean hasPdf) {
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
						d.getId().equals(current), d.getObjectKey() != null, d.hasPdf()))
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
		List<DraftComment> thread = comments.findByBrandIdAndDocumentIdOrderByCreatedAtAsc(draft.getBrandId(),
				draft.getId());
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
