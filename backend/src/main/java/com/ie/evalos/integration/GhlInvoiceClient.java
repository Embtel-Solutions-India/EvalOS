package com.ie.evalos.integration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * One client's invoices, read from GHL for the Client Portal.
 *
 * <p><strong>Read-only, and it must stay that way.</strong> {@code architecture.md} invariant 2
 * lost most of its clauses to this programme but kept this one: <em>invoicing is GHL's, full
 * stop</em>. Sales raises invoices in GHL, GHL's QuickBooks integration does the accounting, and
 * EvalOS reads the outcome. There is no invoice write here and adding one is a decision with its
 * own argument to make.
 *
 * <p><strong>The scope this needs is not granted yet.</strong> {@code invoices.readonly} is the
 * one outstanding ask for this unit; until it lands, GHL answers 401 and
 * {@link GhlUnavailableException} carries the status. {@link #missingScopeHint} is what turns
 * that into something a provisioner can act on rather than a bare 502.
 */
@Component
public class GhlInvoiceClient {

	/** GHL's page maximum on the invoice list. One client's invoices are one page in practice. */
	private static final int PAGE_SIZE = 100;

	/** The scope this client needs, named in one place so the diagnostic cannot drift from it. */
	public static final String REQUIRED_SCOPE = "invoices.readonly";

	/**
	 * One invoice, narrowed to what a client is owed sight of.
	 *
	 * <p>The narrowing is the point, and it is the same discipline Units 14, 15 and 35 apply:
	 * GHL's invoice payload carries line items, internal ids, the business's own contact block
	 * and more. A client needs to know what they were billed, when, and whether it is settled.
	 */
	public record ClientInvoice(String invoiceNumber, String status, BigDecimal total,
			BigDecimal amountPaid, BigDecimal amountDue, String currency, String issueDate,
			String dueDate) {
	}

	private final GhlHttp http;

	GhlInvoiceClient(GhlHttp http) {
		this.http = http;
	}

	/**
	 * Every invoice GHL holds for this contact, newest first as GHL returns them.
	 *
	 * <p><strong>{@code contactId} is not optional and is never taken from a request.</strong>
	 * The caller passes the id off the portal credential; there is no route that lets a client
	 * name a contact. That is the whole access model for this screen.
	 *
	 * @throws GhlUnavailableException if GHL is not configured, refused, or has not been granted
	 *                                 {@link #REQUIRED_SCOPE}
	 */
	public List<ClientInvoice> forContact(String contactId) {
		try {
			InvoiceListResponse response = http.get(InvoiceListResponse.class,
					(uri) -> uri.path("/invoices/")
							// **`altId`/`altType` rather than `locationId`.** GHL's invoice API
							// addresses the sub-account through this pair, unlike the opportunity
							// endpoints. Pinned in `GhlInvoiceClientHttpTest`, because getting it
							// wrong is a 422 and nothing else.
							.queryParam("altId", http.locationId())
							.queryParam("altType", "location")
							.queryParam("contactId", contactId)
							.queryParam("limit", PAGE_SIZE)
							.queryParam("offset", 0)
							.build());

			return Optional.ofNullable(response.invoices()).orElse(List.of()).stream()
					.map(Row::toClientInvoice)
					.toList();
		}
		catch (GhlUnavailableException refused) {
			throw missingScopeHint(refused);
		}
	}

	/**
	 * Re-throws a refusal with the likely cause named, when it looks like the missing grant.
	 *
	 * <p><strong>Because a bare "GHL refused the request with HTTP 401" sends the reader to the
	 * wrong place.</strong> Every other GHL-backed screen in EvalOS works on the current token,
	 * so the first assumption on seeing a 401 here will be that the token is broken — and it is
	 * not, it is missing one scope. That is a wasted afternoon this line prevents, and it is the
	 * same reasoning {@code GhlHttp}'s constructor logs the location id for.
	 *
	 * <p>Only 401 and 403 are decorated. A 404, a timeout or a 500 mean something else, and
	 * attaching a scope hint to them would send the next reader down this path wrongly.
	 */
	private static GhlUnavailableException missingScopeHint(GhlUnavailableException refused) {
		// Asked through the classification rather than by searching the message for "401" — see
		// GhlCalendarClient.missingScopeHint for why that string match had to go.
		if (refused.failure() != GhlFailure.UNAUTHORIZED) {
			return refused;
		}
		return new GhlUnavailableException(refused.getMessage() + " — the invoice API needs the "
				+ REQUIRED_SCOPE + " scope, which the rest of EvalOS does not use. Check the token's "
				+ "grant before suspecting the token.", refused, GhlFailure.UNAUTHORIZED,
				refused.status());
	}

	// --- wire shapes -----------------------------------------------------------------

	record InvoiceListResponse(List<Row> invoices, Integer total) {
	}

	/**
	 * What GHL sends. Bound separately from {@link ClientInvoice} so the fields deliberately not
	 * carried — line items, internal ids, the business's own details — are visibly absent here
	 * rather than dropped somewhere later.
	 */
	record Row(String invoiceNumber, String status, BigDecimal total, BigDecimal amountPaid,
			BigDecimal amountDue, String currency, String issueDate, String dueDate) {

		ClientInvoice toClientInvoice() {
			return new ClientInvoice(invoiceNumber, status, total, amountPaid, amountDue, currency,
					issueDate, dueDate);
		}
	}
}
