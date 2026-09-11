package com.ie.evalos.service;

import java.util.List;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.integration.GhlInvoiceClient;
import com.ie.evalos.security.PortalPrincipal;

import org.springframework.stereotype.Service;

/**
 * The client's own invoices, for the Client Portal.
 *
 * <p><strong>This is the cheapest unit in the programme, and Unit 35 is why.</strong> That unit
 * made a portal credential name a <em>party</em> — a {@code ghl_contact_id} — and
 * {@code GET /invoices/} filters by exactly that. The key the portal already holds is the key
 * the invoice API wants: no mapping, no new identity, no lookup.
 *
 * <p><strong>The contact id comes off the credential and can come from nowhere else.</strong>
 * There is no route parameter for it and there must never be one. A client cannot ask for
 * another contact's invoices because they cannot name a contact at all — the same rule Unit 34c's
 * document filter and Unit 35's case routes follow.
 *
 * <p><strong>Nothing is stored.</strong> No table, no cache, no migration. Unit 38's cache exists
 * because a board is ~115 cursor pages; one client's invoices are one page, and the rate limiter
 * in {@code GhlHttp} already paces it. So {@code 00b} §1.3's "GHL is truth" holds here in its
 * strongest form — EvalOS holds no invoice fact at all.
 */
@Service
public class PortalInvoiceService {

	private final GhlInvoiceClient invoices;

	PortalInvoiceService(GhlInvoiceClient invoices) {
		this.invoices = invoices;
	}

	/**
	 * Every invoice for the party this credential names.
	 *
	 * <p><strong>A case-scoped credential is refused.</strong> It names one engagement; an
	 * invoice belongs to the <em>client</em>, who may have several. Answering with the one case's
	 * invoices would be inventing a filter GHL does not offer, and answering with all of them
	 * would hand a narrow credential a wide reply. Same rule, same wording as
	 * {@code PortalCaseService.clientCases}: the narrow credential does not get the wide one's
	 * answer.
	 *
	 * <p><strong>An account-scoped credential answers empty</strong> (Unit 42). A client who
	 * signed in but has no GHL contact behind them has no invoices <em>in GHL</em> to fetch — the
	 * id the API filters by does not exist — so the honest answer is nothing, not a refusal and
	 * certainly not a call to GHL with a null contact, which asks for every invoice in the
	 * location. This is {@code 00c} §1c's predicted degradation arriving as code.
	 */
	public List<GhlInvoiceClient.ClientInvoice> forCaller(PortalPrincipal principal) {
		if (!principal.isPartyScoped()) {
			throw new ForbiddenException("This link admits you to one case, not to your billing");
		}
		if (principal.ghlContactId() == null) {
			return List.of();
		}
		return invoices.forContact(principal.ghlContactId());
	}
}
