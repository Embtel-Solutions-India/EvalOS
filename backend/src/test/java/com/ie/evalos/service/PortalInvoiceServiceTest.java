package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.integration.GhlInvoiceClient;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which credential may read a client's billing, and whose contact it reads.
 *
 * <p>The whole access model is one line — the contact id comes off the credential — so the tests
 * are about the two ways that could go wrong: a credential that should not reach this at all,
 * and a contact that is not the caller's.
 */
class PortalInvoiceServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final String CONTACT = "contact_ada";

	private final GhlInvoiceClient client = mock(GhlInvoiceClient.class);
	private final PortalInvoiceService service = new PortalInvoiceService(client);

	private static PortalPrincipal partyLink(String contactId) {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, null, PortalAudience.CLIENT, null, contactId);
	}

	private static PortalPrincipal caseLink() {
		return new PortalPrincipal(UUID.randomUUID(), BRAND, UUID.randomUUID(), PortalAudience.CLIENT, null);
	}

	/**
	 * <strong>The contact read is the credential's, and it is the only thing that could be.</strong>
	 *
	 * <p>There is no parameter and no lookup: Unit 35 made the portal credential name a
	 * {@code ghl_contact_id}, and {@code GET /invoices/} filters by exactly that. The key the
	 * portal already held is the key the invoice API wanted.
	 */
	@Test
	void readsTheContactNamedByTheCredential() {
		when(client.forContact(CONTACT)).thenReturn(List.of(new GhlInvoiceClient.ClientInvoice(
				"INV-1", "paid", new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO,
				"USD", "2026-09-01", "2026-09-15")));

		assertThat(service.forCaller(partyLink(CONTACT))).hasSize(1);

		verify(client).forContact(CONTACT);
	}

	/** A different party's link reads a different contact. There is no shared path between them. */
	@Test
	void anotherPartysLinkReadsAnotherContact() {
		when(client.forContact(any())).thenReturn(List.of());

		service.forCaller(partyLink("contact_someone_else"));

		verify(client).forContact("contact_someone_else");
		verify(client, never()).forContact(CONTACT);
	}

	/**
	 * A case-scoped credential is refused rather than given a filtered answer.
	 *
	 * <p>It names one engagement; an invoice belongs to the client, who may have several. GHL
	 * offers no per-case invoice filter, so "the invoices for this case" is not a thing that
	 * could be answered honestly — and answering with all of them would hand a narrow credential
	 * the wide one's reply.
	 */
	@Test
	void aCaseScopedCredentialIsRefused() {
		assertThatThrownBy(() -> service.forCaller(caseLink()))
				.isInstanceOf(ForbiddenException.class)
				.hasMessageContaining("one case");

		verify(client, never()).forContact(any());
	}

	/** No invoices is an empty list, not a failure — a new client simply has none yet. */
	@Test
	void aClientWithNoInvoicesGetsAnEmptyList() {
		when(client.forContact(CONTACT)).thenReturn(List.of());

		assertThat(service.forCaller(partyLink(CONTACT))).isEmpty();
	}

	/**
	 * <strong>An account-scoped credential answers empty, not a GHL call with a null id</strong>
	 * (Unit 42). A client who signed in but has no GHL contact behind them has no invoices in GHL;
	 * passing their null contact through would ask {@code GET /invoices/} for the whole location.
	 */
	@Test
	void anAccountScopedCredentialAnswersEmptyWithoutCallingGhl() {
		assertThat(service.forCaller(partyLink(null))).isEmpty();

		verify(client, never()).forContact(any());
	}
}
