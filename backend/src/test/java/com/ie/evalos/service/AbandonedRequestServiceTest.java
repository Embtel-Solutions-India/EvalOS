package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ClientApplication;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientApplicationRepository;
import com.ie.evalos.security.StaffPrincipal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** D54: drafts untouched for 48h, scoped to the caller's brand (the GM reads every brand). */
class AbandonedRequestServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
	private static final Instant CUTOFF = Instant.parse("2026-09-26T12:00:00Z");

	private final ClientApplicationRepository applications = mock(ClientApplicationRepository.class);
	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);
	private final AbandonedRequestService service = new AbandonedRequestService(applications, accounts,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private static void signIn(Role role, UUID brand) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "x@evalos.local", "X", role, brand, null,
				role == Role.SALES ? "pipe_mine" : null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	private static ClientApplication draft(UUID brand, UUID account) {
		ClientApplication draft = mock(ClientApplication.class);
		when(draft.getId()).thenReturn(UUID.randomUUID());
		when(draft.getBrandId()).thenReturn(brand);
		when(draft.getClientAccountId()).thenReturn(account);
		when(draft.getServiceName()).thenReturn("Course by course");
		return draft;
	}

	private static ClientAccount account(UUID id, UUID brand) {
		ClientAccount account = mock(ClientAccount.class);
		when(account.getId()).thenReturn(id);
		when(account.getBrandId()).thenReturn(brand);
		when(account.getEmail()).thenReturn("ada@example.test");
		when(account.getFirstName()).thenReturn("Ada");
		when(account.getLastName()).thenReturn("Lovelace");
		return account;
	}

	@Test
	void aSalespersonSeesTheirBrandsDraftsUntouchedFor48Hours() {
		signIn(Role.SALES, BRAND);
		UUID ada = UUID.randomUUID();
		// Built before the when(...): a mock stubbed inside another stubbing is unfinished stubbing.
		ClientApplication draft = draft(BRAND, ada);
		ClientAccount account = account(ada, BRAND);
		when(applications.findByBrandIdAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(BRAND,
				ClientApplication.Status.DRAFT, CUTOFF)).thenReturn(List.of(draft));
		when(accounts.findAllById(any())).thenReturn(List.of(account));

		List<AbandonedRequestService.Row> rows = service.forCaller();

		assertThat(rows).singleElement().satisfies((row) -> {
			assertThat(row.clientName()).isEqualTo("Ada Lovelace");
			assertThat(row.email()).isEqualTo("ada@example.test");
		});
		verify(applications, never()).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any());
	}

	@Test
	void anAccountFromAnotherBrandLendsTheRowNoContactDetails() {
		signIn(Role.SALES, BRAND);
		UUID stray = UUID.randomUUID();
		ClientApplication draft = draft(BRAND, stray);
		ClientAccount account = account(stray, OTHER_BRAND);
		when(applications.findByBrandIdAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(BRAND,
				ClientApplication.Status.DRAFT, CUTOFF)).thenReturn(List.of(draft));
		when(accounts.findAllById(any())).thenReturn(List.of(account));

		assertThat(service.forCaller()).singleElement().satisfies((row) -> {
			assertThat(row.clientName()).isNull();
			assertThat(row.email()).isNull();
		});
	}

	@Test
	void theGmReadsEveryBrand() {
		signIn(Role.GM, null);
		when(applications.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(ClientApplication.Status.DRAFT, CUTOFF))
				.thenReturn(List.of());

		assertThat(service.forCaller()).isEmpty();
		verify(applications).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(ClientApplication.Status.DRAFT, CUTOFF);
	}

	@Test
	void aBrandlessNonGmSeesNothingRatherThanEverything() {
		signIn(Role.SALES, null);

		assertThat(service.forCaller()).isEmpty();
		verify(applications, never()).findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(any(), any());
	}
}
