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
import com.ie.evalos.domain.IllegalTransitionException;
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
		given(comments.findByBrandIdAndDocumentIdOrderByCreatedAtAsc(BRAND, v3.getId())).willReturn(List.of(staff));

		assertThat(drafts.comments(v3, false)).singleElement()
				.satisfies(view -> assertThat(view.authorName()).isNull());
	}
}
