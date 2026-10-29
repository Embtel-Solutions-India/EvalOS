package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.integration.GhlFailure;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The deal behind a case is read through the case: its scope, its brand, and a role that reads case content. */
class CaseOpportunityServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final OpportunityMirrorService mirror = mock(OpportunityMirrorService.class);
	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final CaseOpportunityService service = new CaseOpportunityService(lifecycle, mirror, ghl, contacts);

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	private void actAs(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "s@evalos.local", "Staff", role, BRAND,
				UUID.randomUUID(), null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	private Case caseWithDeal(String ghlId) {
		Case subject = new Case(BRAND, "IE-1", Stage.PM_REVIEW);
		subject.setGhlOpportunityId(ghlId);
		given(lifecycle.load(CASE_ID)).willReturn(subject);
		return subject;
	}

	@Test
	void aCaseManagerReadsTheDealTheCaseWasWonFrom() {
		actAs(Role.CASE_MANAGER);
		caseWithDeal("opp-1");
		Opportunity deal = new Opportunity(BRAND, "opp-1", UUID.randomUUID());
		given(mirror.byGhlId("opp-1")).willReturn(Optional.of(deal));

		assertThat(service.of(CASE_ID)).containsSame(deal);
	}

	@Test
	void aDealInAnotherBrandIsNotReturned() {
		actAs(Role.PROJECT_MANAGER);
		caseWithDeal("opp-1");
		given(mirror.byGhlId("opp-1")).willReturn(Optional.of(new Opportunity(UUID.randomUUID(), "opp-1", UUID.randomUUID())));

		assertThat(service.of(CASE_ID)).isEmpty();
	}

	@Test
	void aCaseWithNoDealAnswersEmpty() {
		actAs(Role.PROJECT_COORDINATOR);
		caseWithDeal(null);

		assertThat(service.of(CASE_ID)).isEmpty();
	}

	private static GhlPipelineClient.Opportunity ghlDeal(String id, String status, String createdAt) {
		return new GhlPipelineClient.Opportunity(id, "Deal", "contact1Real", "pipe", "stage", status, null, null, null,
				java.time.Instant.parse(createdAt), null, null, null, List.of(), null, List.of(), List.of());
	}

	private void withContact(Case subject) {
		UUID contactId = UUID.randomUUID();
		ReflectionTestUtils.setField(subject, "contactId", contactId);
		given(contacts.findById(contactId)).willReturn(Optional.of(new ContactSnapshot(BRAND, "contact1Real")));
	}

	/** 2026-10-29: fetched by its own id first, so a case with no linked contact still opens its deal. */
	@Test
	void aDealTheMirrorLacksIsFetchedFromGhlByItsId() {
		actAs(Role.CASE_MANAGER);
		caseWithDeal("opp1Real");
		GhlPipelineClient.Opportunity row = ghlDeal("opp1Real", "won", "2026-10-01T00:00:00Z");
		given(ghl.byId("opp1Real")).willReturn(row);
		Opportunity deal = new Opportunity(BRAND, "opp1Real", UUID.randomUUID());
		given(mirror.byGhlId("opp1Real")).willReturn(Optional.empty(), Optional.of(deal));

		assertThat(service.of(CASE_ID)).containsSame(deal);
		verify(mirror).absorbForContact(List.of(row));
		verify(ghl, never()).forContact(any());
	}

	/** GHL refusing the id read is not the end: the case contact's deals are read next. */
	@Test
	void whenTheIdReadFailsTheCaseContactsDealsAreRead() {
		actAs(Role.CASE_MANAGER);
		Case subject = caseWithDeal("opp1Real");
		withContact(subject);
		given(ghl.byId("opp1Real")).willThrow(new GhlUnavailableException("404", null, GhlFailure.REFUSED, 404));
		List<GhlPipelineClient.Opportunity> fromGhl = List.of(ghlDeal("opp1Real", "won", "2026-10-01T00:00:00Z"));
		given(ghl.forContact("contact1Real")).willReturn(fromGhl);
		Opportunity deal = new Opportunity(BRAND, "opp1Real", UUID.randomUUID());
		given(mirror.byGhlId("opp1Real")).willReturn(Optional.empty(), Optional.empty(), Optional.of(deal));

		assertThat(service.of(CASE_ID)).containsSame(deal);
		verify(mirror).absorbForContact(fromGhl);
	}

	/** A case that names no deal opens its contact's: the won one before a newer open one. */
	@Test
	void aCaseWithNoDealIdOpensItsContactsWonDeal() {
		actAs(Role.CASE_MANAGER);
		Case subject = caseWithDeal(null);
		withContact(subject);
		given(ghl.forContact("contact1Real")).willReturn(List.of(
				ghlDeal("oppNewer", "open", "2026-10-20T00:00:00Z"), ghlDeal("oppWon", "won", "2026-09-01T00:00:00Z")));
		Opportunity won = new Opportunity(BRAND, "oppWon", UUID.randomUUID());
		given(mirror.byGhlId("oppWon")).willReturn(Optional.of(won));
		given(mirror.byGhlId("oppNewer")).willReturn(Optional.of(new Opportunity(BRAND, "oppNewer", UUID.randomUUID())));

		assertThat(service.of(CASE_ID)).containsSame(won);
	}

	@Test
	void aSeededDealIsNeverSentToGhl() {
		actAs(Role.CASE_MANAGER);
		caseWithDeal("ghl-opp-tp-ie-0001");
		given(mirror.byGhlId("ghl-opp-tp-ie-0001")).willReturn(Optional.empty());

		assertThat(service.of(CASE_ID)).isEmpty();
		verifyNoInteractions(ghl);
	}

	@Test
	void theEnmDoesNotReadClientDetails() {
		actAs(Role.EXPERT_NETWORK_MANAGER);
		caseWithDeal("opp-1");

		assertThatThrownBy(() -> service.of(CASE_ID)).isInstanceOf(ForbiddenException.class);
	}
}
