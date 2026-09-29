package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.integration.DocumentStore;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit 35 and D1: what a <strong>party</strong>-scoped token reaches, and what it must not.
 *
 * <p>The case-scoped token's behaviour is not re-asserted here — {@code PortalCaseServiceTest} and
 * {@code ExpertPortalServiceTest} already own it, and "nothing regresses" is a claim those two make
 * by continuing to pass. This file is only about the credential D1 added.
 *
 * <p><strong>The refusals are the point.</strong> A party token widens what one link opens, so
 * every test below that asserts an exception is asserting the boundary that widening created:
 * another contact's case, another brand's case, another expert's assignment, and the ambiguity that
 * would otherwise let somebody approve the wrong draft.
 */
class PartyScopedPortalAccessTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final String CONTACT = "ghl-contact-abc";
	private static final UUID CONTACT_ROW = UUID.randomUUID();
	private static final UUID EXPERT = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final DocumentChecklistItemRepository checklistItems = mock(DocumentChecklistItemRepository.class);
	private final CaseDocumentRepository documents = mock(CaseDocumentRepository.class);
	private final DocumentStore store = mock(DocumentStore.class);
	private final AuditService audit = mock(AuditService.class);
	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final SlaCalculator sla = mock(SlaCalculator.class);

	private final PortalCaseService clientPortal = new PortalCaseService(
			cases, contacts, lifecycle, checklistItems, documents, store, audit, mock(CaseDrafts.class),
			mock(CaseMilestones.class));

	private final ExpertPortalService expertPortal = new ExpertPortalService(
			cases, contacts, experts, checklistItems, documents, lifecycle, sla, store, audit);

	// --- fixtures ------------------------------------------------------------

	private static Case caseAt(UUID brandId, String reference, Stage stage) {
		Case subject = new Case(brandId, reference, stage);
		ReflectionTestUtils.setField(subject, "id", UUID.randomUUID());
		subject.setServiceType(ServiceType.EXPERT_OPINION_LETTER);
		return subject;
	}

	private static PortalPrincipal clientParty(String ghlContactId) {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, null, PortalAudience.CLIENT, null, ghlContactId);
	}

	private static PortalPrincipal expertParty(UUID expertId) {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, null, PortalAudience.EXPERT, expertId, null);
	}

	private void contactResolves() {
		ContactSnapshot snapshot = new ContactSnapshot(BRAND, CONTACT);
		// The id is @GeneratedValue with no setter, and the case points at it — an unsaved
		// entity's null id would make the ownership check fail for the wrong reason.
		ReflectionTestUtils.setField(snapshot, "id", CONTACT_ROW);
		given(contacts.findByBrandIdAndGhlContactId(BRAND, CONTACT)).willReturn(Optional.of(snapshot));
		given(contacts.findById(CONTACT_ROW)).willReturn(Optional.of(snapshot));
	}

	// --- D1: the client's list ------------------------------------------------

	@Test
	void aClientPartyTokenListsEveryCaseThatContactHas() {
		contactResolves();
		Case first = caseAt(BRAND, "IE-2026-0001", Stage.DOC_COLLECTION);
		Case second = caseAt(BRAND, "IE-2026-0002", Stage.DELIVERED);
		given(cases.findByBrandIdAndContactIdOrderByCreatedAtDesc(BRAND, CONTACT_ROW))
				.willReturn(List.of(first, second));

		List<PortalCaseService.ClientCaseSummary> mine = clientPortal.clientCases(clientParty(CONTACT));

		// Both, and a delivered one among them: the closed case is the one they came back for, and
		// a list that hid it would look like lost work.
		assertThat(mine).extracting(PortalCaseService.ClientCaseSummary::caseReference)
				.containsExactly("IE-2026-0001", "IE-2026-0002");
		// D5's words, served by EvalOS, with the flag as a field rather than a suffix to parse.
		assertThat(mine.get(0).step()).isEqualTo("Upload");
		assertThat(mine.get(0).actionRequired()).isTrue();
		assertThat(mine.get(1).step()).isEqualTo("Ready to download");
		assertThat(mine.get(1).actionRequired()).isFalse();
		// The stepper position Home splits active from delivered on (Unit 58).
		assertThat(mine.get(0).stepIndex()).isZero();
		assertThat(mine.get(1).stepIndex()).isEqualTo(3);
	}

	@Test
	void anotherContactInTheSameBrandIsNotOnTheList() {
		// The scope is the contact, not the brand. This is the failure that would turn one client's
		// link into a directory of the brand's work.
		given(contacts.findByBrandIdAndGhlContactId(BRAND, "ghl-somebody-else")).willReturn(Optional.empty());

		assertThat(clientPortal.clientCases(clientParty("ghl-somebody-else"))).isEmpty();
	}

	@Test
	void theSameContactInAnotherBrandResolvesSeparately() {
		// A contact may legitimately be a client of two brands (V16). The token carries one brand,
		// and the read must not reach across — invariant 1, on a credential that has no
		// TenantContext behind it to enforce it.
		contactResolves();
		given(contacts.findByBrandIdAndGhlContactId(OTHER_BRAND, CONTACT)).willReturn(Optional.empty());

		PortalPrincipal otherBrandToken = new PortalPrincipal(
				UUID.randomUUID(), OTHER_BRAND, null, PortalAudience.CLIENT, null, CONTACT);

		assertThat(clientPortal.clientCases(otherBrandToken)).isEmpty();
	}

	@Test
	void aCaseScopedTokenIsRefusedTheList() {
		PortalPrincipal caseToken = new PortalPrincipal(
				UUID.randomUUID(), BRAND, UUID.randomUUID(), PortalAudience.CLIENT, null);

		assertThatThrownBy(() -> clientPortal.clientCases(caseToken))
				.isInstanceOf(ForbiddenException.class);
	}

	// --- D1: naming a case, and the 409 ---------------------------------------

	@Test
	void aPartyTokenReachesItsOwnCaseById() {
		contactResolves();
		Case mine = caseAt(BRAND, "IE-2026-0001", Stage.CLIENT_REVIEW);
		mine.setContactId(CONTACT_ROW);
		given(cases.findById(mine.getId())).willReturn(Optional.of(mine));

		assertThat(clientPortal.clientView(clientParty(CONTACT), mine.getId()).caseReference())
				.isEqualTo("IE-2026-0001");
	}

	@Test
	void aPartyTokenIsRefusedAnotherContactsCaseWithForbiddenNotNotFound() {
		contactResolves();
		Case theirs = caseAt(BRAND, "IE-2026-0009", Stage.CLIENT_REVIEW);
		theirs.setContactId(UUID.randomUUID());
		given(cases.findById(theirs.getId())).willReturn(Optional.of(theirs));
		given(contacts.findById(theirs.getContactId())).willReturn(Optional.empty());

		// 403 rather than 404: a 404 here would confirm the difference between "no such case" and
		// "not yours", which is how one link becomes a way to count the brand's cases.
		assertThatThrownBy(() -> clientPortal.clientView(clientParty(CONTACT), theirs.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

	// --- D1: the expert's list -------------------------------------------------

	@Test
	void anExpertPartyTokenListsOnlyItsOwnAssignments() {
		Case mine = caseAt(BRAND, "IE-2026-0004", Stage.EXPERT_SIGNING);
		mine.setExpertId(EXPERT);
		given(cases.findByBrandIdAndExpertIdOrderByCreatedAtDesc(BRAND, EXPERT)).willReturn(List.of(mine));

		List<ExpertPortalService.ExpertCaseSummary> assignments = expertPortal.expertCases(expertParty(EXPERT));

		assertThat(assignments).extracting(ExpertPortalService.ExpertCaseSummary::caseReference)
				.containsExactly("IE-2026-0004");
		assertThat(assignments.get(0).step()).isEqualTo("Sign");
		assertThat(assignments.get(0).actionRequired()).isTrue();
	}

	@Test
	void anExpertIsRefusedAnotherExpertsCaseById() {
		Case theirs = caseAt(BRAND, "IE-2026-0007", Stage.EXPERT_SIGNING);
		theirs.setExpertId(UUID.randomUUID());
		given(cases.findById(theirs.getId())).willReturn(Optional.of(theirs));

		// V37's bind, inherited: the token names an expert, and a case whose expert is somebody
		// else is refused whether or not anybody remembered to revoke the link.
		assertThatThrownBy(() -> expertPortal.view(expertParty(EXPERT), theirs.getId()))
				.isInstanceOf(ForbiddenException.class);
	}

}
