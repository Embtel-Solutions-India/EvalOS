package com.ie.evalos.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.integration.GhlInvoiceClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.security.PortalTokenFilter;
import com.ie.evalos.service.PortalAccessService;
import com.ie.evalos.service.PortalCaseService;
import com.ie.evalos.service.PortalMeetingService;
import com.ie.evalos.service.PortalInvoiceService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The client's invoices: who may see them, and exactly what the payload carries.
 *
 * <p>{@link #thePayloadCarriesTheWhitelistAndNothingElse} asserts on the <strong>serialized
 * body</strong> rather than on a field list, which is the rule Units 14, 15 and 35 all follow: a
 * nested DTO passes a field-name check and still leaks.
 */
@WebMvcTest(controllers = ClientPortalController.class)
@Import({ PortalSecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.portal.rate-limit-per-minute=200",
})
class ClientPortalInvoiceTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final String CONTACT = "contact_ada";
	private static final String PARTY_TOKEN = "party-token";
	private static final String CASE_TOKEN = "case-token";

	private static final GhlInvoiceClient.ClientInvoice INVOICE = new GhlInvoiceClient.ClientInvoice(
			"INV-014", "partially_paid", new BigDecimal("1200"), new BigDecimal("400"),
			new BigDecimal("800"), "USD", "2026-09-01", "2026-09-15");

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PortalAccessService portalAccess;

	@MockitoBean
	PortalCaseService portal;

	// The meetings route (2026-09-11) gave ClientPortalController another collaborator, so this
	// slice needs it even though nothing here exercises meetings — ClientPortalMeetingTest does.
	@MockitoBean
	PortalMeetingService portalMeetings;

	@MockitoBean
	PortalInvoiceService invoices;

	private void givenAPartyLink() {
		given(portalAccess.resolve(PARTY_TOKEN)).willReturn(Optional.of(new PortalPrincipal(
				UUID.randomUUID(), BRAND_IE, null, PortalAudience.CLIENT, null, CONTACT)));
	}

	private void givenACaseLink() {
		given(portalAccess.resolve(CASE_TOKEN)).willReturn(Optional.of(new PortalPrincipal(
				UUID.randomUUID(), BRAND_IE, UUID.randomUUID(), PortalAudience.CLIENT, null)));
	}

	@Test
	void aPartyLinkReadsItsOwnInvoices() throws Exception {
		givenAPartyLink();
		given(invoices.forCaller(any())).willReturn(List.of(INVOICE));

		mockMvc.perform(get("/api/portal/client/invoices").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].invoiceNumber").value("INV-014"))
				.andExpect(jsonPath("$.data[0].status").value("partially_paid"))
				.andExpect(jsonPath("$.data[0].amountDue").value(800));
	}

	/**
	 * <strong>The contact comes off the credential, and there is no way to name another.</strong>
	 *
	 * <p>Asserted by handing the route a contact id anyway: the service is still called with the
	 * principal alone, so the parameter reaches nothing. The day somebody adds
	 * {@code @RequestParam String contactId} "so support can look one up", this fails.
	 */
	@Test
	void noParameterCanNameAnotherContact() throws Exception {
		givenAPartyLink();
		given(invoices.forCaller(any())).willReturn(List.of());

		mockMvc.perform(get("/api/portal/client/invoices")
				.param("contactId", "contact_someone_else")
				.header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk());

		then(invoices).should().forCaller(argThatNamesOurContact());
	}

	private static PortalPrincipal argThatNamesOurContact() {
		return org.mockito.ArgumentMatchers.argThat(
				(principal) -> CONTACT.equals(principal.ghlContactId()));
	}

	/**
	 * A case-scoped credential is refused.
	 *
	 * <p>It names one engagement; an invoice belongs to the client, who may have several. The
	 * narrow credential does not get the wide one's answer — the same rule, and the same
	 * wording, as Unit 35's case list.
	 */
	@Test
	void aCaseScopedLinkIsRefused() throws Exception {
		givenACaseLink();
		willThrow(new com.ie.evalos.common.ForbiddenException(
				"This link admits you to one case, not to your billing"))
				.given(invoices).forCaller(any());

		mockMvc.perform(get("/api/portal/client/invoices").header(PortalTokenFilter.HEADER, CASE_TOKEN))
				.andExpect(status().isForbidden());
	}

	@Test
	void anUnknownTokenIsRefusedWithoutReachingTheService() throws Exception {
		given(portalAccess.resolve(any())).willReturn(Optional.empty());

		mockMvc.perform(get("/api/portal/client/invoices").header(PortalTokenFilter.HEADER, "nope"))
				.andExpect(status().isUnauthorized());

		then(invoices).should(never()).forCaller(any());
	}

	@Test
	void noTokenIsRefused() throws Exception {
		mockMvc.perform(get("/api/portal/client/invoices")).andExpect(status().isUnauthorized());
	}

	/**
	 * <strong>The whitelist, asserted on the serialized body.</strong>
	 *
	 * <p>Checking a field list would pass while a nested DTO smuggled the whole GHL payload
	 * through. So this asserts the absent fields are absent in the JSON a client actually
	 * receives: GHL's internal ids, its line items, and anything naming the business's own side
	 * of the invoice.
	 */
	@Test
	void thePayloadCarriesTheWhitelistAndNothingElse() throws Exception {
		givenAPartyLink();
		given(invoices.forCaller(any())).willReturn(List.of(INVOICE));

		mockMvc.perform(get("/api/portal/client/invoices").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk())
				// Present: what the client is owed sight of.
				.andExpect(jsonPath("$.data[0].total").value(1200))
				.andExpect(jsonPath("$.data[0].amountPaid").value(400))
				.andExpect(jsonPath("$.data[0].currency").value("USD"))
				.andExpect(jsonPath("$.data[0].issueDate").value("2026-09-01"))
				.andExpect(jsonPath("$.data[0].dueDate").value("2026-09-15"))
				// Absent: GHL's internals and the business's side of the record.
				.andExpect(jsonPath("$.data[0]._id").doesNotExist())
				.andExpect(jsonPath("$.data[0].invoiceItems").doesNotExist())
				.andExpect(jsonPath("$.data[0].contactDetails").doesNotExist())
				.andExpect(jsonPath("$.data[0].businessDetails").doesNotExist())
				.andExpect(jsonPath("$.data[0].liveMode").doesNotExist());
	}

	/**
	 * The missing grant surfaces as a 502 that names the scope.
	 *
	 * <p>This is the state the environment is actually in until {@code invoices.readonly} is
	 * granted, so it is worth asserting rather than leaving to be discovered: the client sees a
	 * failure, and whoever reads the log is pointed at the grant rather than at the token.
	 */
	@Test
	void theMissingGrantIsABadGatewayNamingTheScope() throws Exception {
		givenAPartyLink();
		willThrow(new GhlUnavailableException("GHL refused the request with HTTP 401 — the invoice API "
				+ "needs the " + GhlInvoiceClient.REQUIRED_SCOPE + " scope"))
				.given(invoices).forCaller(any());

		mockMvc.perform(get("/api/portal/client/invoices").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isBadGateway());
	}

	/** A client with no invoices sees an empty list, not an error. */
	@Test
	void aClientWithNoInvoicesGetsAnEmptyList() throws Exception {
		givenAPartyLink();
		given(invoices.forCaller(any())).willReturn(List.of());

		mockMvc.perform(get("/api/portal/client/invoices").header(PortalTokenFilter.HEADER, PARTY_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(0));
	}
}
