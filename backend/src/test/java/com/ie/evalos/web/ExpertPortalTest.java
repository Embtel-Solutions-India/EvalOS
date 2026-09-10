package com.ie.evalos.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.VisaCategory;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.PortalSecurityConfig;
import com.ie.evalos.security.PortalTokenFilter;
import com.ie.evalos.service.ExpertPortalService;
import com.ie.evalos.service.PortalInvoiceService;
import com.ie.evalos.service.PortalAccessService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The expert's chain, and the two guards that live at the HTTP edge (Unit 15).
 *
 * <p><strong>One table, two audiences, no crossing.</strong> A {@code CLIENT} token on an expert
 * route and an {@code EXPERT} token on a client route are both refused by the same line —
 * {@code PortalPrincipal.current}. That property is what makes a second portal a deployment fact
 * rather than a new security surface, and it is asserted here in both directions.
 *
 * <p>The other guard is the upload's: <strong>PDF by content, not by name</strong>. A
 * {@code .pdf}-named JPEG does not become a signed letter, and the API refuses an upload with no
 * attestation whatever the UI does — it is the evidence.
 */
@WebMvcTest(controllers = { ExpertPortalController.class, ClientPortalController.class })
@Import({ PortalSecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.portal.rate-limit-per-minute=200",
})
class ExpertPortalTest {

	private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CASE_ID = UUID.randomUUID();

	private static final String EXPERT_TOKEN = "expert-token";
	private static final String CLIENT_TOKEN = "client-token";

	private static final byte[] PDF = "%PDF-1.7 signed".getBytes(StandardCharsets.UTF_8);
	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10 };
	private static final String ATTESTATION = "I, Dr Ada Lovelace, confirm this is my signature on this letter.";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	PortalAccessService portalAccess;

	// Unit 41 gave ClientPortalController an invoice route, so this slice needs the collaborator
	// behind it. Mocked rather than imported: nothing here exercises invoices — that is
	// ClientPortalInvoiceTest's job — and importing the real service would drag GhlHttp and a
	// GHL credential into a test about the two chains refusing each other's tokens.
	@MockitoBean
	PortalInvoiceService portalInvoices;

	@MockitoBean
	ExpertPortalService portal;

	@MockitoBean
	com.ie.evalos.service.PortalCaseService clientPortal;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	private void givenBothLinks() {
		given(portalAccess.resolve(EXPERT_TOKEN)).willReturn(Optional.of(
				new PortalPrincipal(UUID.randomUUID(), BRAND, CASE_ID, PortalAudience.EXPERT, UUID.randomUUID())));
		given(portalAccess.resolve(CLIENT_TOKEN)).willReturn(Optional.of(
				new PortalPrincipal(UUID.randomUUID(), BRAND, CASE_ID, PortalAudience.CLIENT, null)));
		given(portal.view(any())).willReturn(view());
	}

	private static ExpertPortalService.ExpertCaseView view() {
		return new ExpertPortalService.ExpertCaseView("IE-2026-0044", "Priya Menon", "Dr Ada Lovelace",
				ServiceType.EXPERT_OPINION_LETTER, VisaCategory.EB1A,
				"https://docs.google.com/document/d/draft/edit", List.of("Passport"),
				ExpertSignStatus.PENDING, SlaStatus.ON_TRACK, true, false, false, null, ATTESTATION);
	}

	@Test
	void anExpertTokenReadsItsOwnCase() throws Exception {
		givenBothLinks();

		mockMvc.perform(get("/api/portal/expert/case").header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.caseReference").value("IE-2026-0044"))
				.andExpect(jsonPath("$.data.applicantName").value("Priya Menon"));
	}

	/** The acceptance criterion, in the direction that would be a leak. */
	@Test
	void aClientTokenIsRefusedOnEveryExpertRoute() throws Exception {
		givenBothLinks();

		mockMvc.perform(get("/api/portal/expert/case").header(PortalTokenFilter.HEADER, CLIENT_TOKEN))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/portal/expert/accept").header(PortalTokenFilter.HEADER, CLIENT_TOKEN))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/portal/expert/letter").header(PortalTokenFilter.HEADER, CLIENT_TOKEN))
				.andExpect(status().isForbidden());

		verifyNoInteractions(portal);
	}

	/** And the other direction. One table, two audiences, no crossing. */
	@Test
	void anExpertTokenIsRefusedOnTheClientRoutes() throws Exception {
		givenBothLinks();

		mockMvc.perform(get("/api/portal/client/case").header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/portal/client/approve").header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isForbidden());

		verifyNoInteractions(clientPortal);
	}

	/** No token is a 401 that says nothing about why — the same one a bad token gets. */
	@Test
	void noTokenIsRefusedIdentically() throws Exception {
		mockMvc.perform(get("/api/portal/expert/case"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("PORTAL_LINK_INVALID"));
	}

	/**
	 * <strong>Sniffed, not trusted.</strong> A JPEG named {@code signed.pdf} and declared as
	 * {@code application/pdf} is still a JPEG, and the PM's final QC is the only other thing
	 * standing between it and a client.
	 */
	@Test
	void aPdfNamedJpegDoesNotBecomeASignedLetter() throws Exception {
		givenBothLinks();

		mockMvc.perform(multipart("/api/portal/expert/signed-letter")
				.file(new MockMultipartFile("file", "signed.pdf", MediaType.APPLICATION_PDF_VALUE, JPEG))
				.param("attestation", ATTESTATION)
				.header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(portal);
	}

	/** An empty part is not a letter, and never reaches the store. */
	@Test
	void anEmptyUploadIsRefused() throws Exception {
		givenBothLinks();

		mockMvc.perform(multipart("/api/portal/expert/signed-letter")
				.file(new MockMultipartFile("file", "signed.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[0]))
				.param("attestation", ATTESTATION)
				.header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(portal);
	}

	/** A real PDF with the attestation goes through, and the service is handed the stream. */
	@Test
	void aSignedPdfWithTheAttestationIsAccepted() throws Exception {
		givenBothLinks();
		given(portal.uploadSignedLetter(any(), anyString(), anyLong(), any(), anyString()))
				.willReturn(new ExpertPortalService.SignedLetterView(UUID.randomUUID(), "signed.pdf", 1,
						java.time.Instant.parse("2026-09-03T10:15:30Z"), "0".repeat(64)));

		mockMvc.perform(multipart("/api/portal/expert/signed-letter")
				.file(new MockMultipartFile("file", "signed.pdf", MediaType.APPLICATION_PDF_VALUE, PDF))
				.param("attestation", ATTESTATION)
				.header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.filename").value("signed.pdf"))
				.andExpect(jsonPath("$.data.contentSha256").isNotEmpty());
	}

	/** An evidence request with nothing named is refused before the service is reached. */
	@Test
	void requestingEvidenceWithNoDescriptionIsRefused() throws Exception {
		givenBothLinks();

		mockMvc.perform(post("/api/portal/expert/request-evidence")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"missing\":\"\"}")
				.header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(portal);
	}

	/** A decline with no reason tells the rematch nothing, so the API refuses it too. */
	@Test
	void decliningWithNoReasonIsRefused() throws Exception {
		givenBothLinks();

		mockMvc.perform(post("/api/portal/expert/decline")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"   \"}")
				.header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(portal);
	}

	/**
	 * The name is not on the wire at all, and that is the point (post-review).
	 *
	 * <p>An earlier version took `attestedName` as a parameter and checked the sentence against it,
	 * which any consistent pair satisfied — so the evidence row could name somebody who was never on
	 * the case. The name now comes off the case server-side, so a request carrying one is simply
	 * ignored rather than believed.
	 */
	@Test
	void anUploadCarryingItsOwnNameIsNotBelieved() throws Exception {
		givenBothLinks();
		given(portal.uploadSignedLetter(any(), anyString(), anyLong(), any(), anyString()))
				.willReturn(new ExpertPortalService.SignedLetterView(UUID.randomUUID(), "signed.pdf", 1,
						java.time.Instant.parse("2026-09-03T10:15:30Z"), "0".repeat(64)));

		mockMvc.perform(multipart("/api/portal/expert/signed-letter")
				.file(new MockMultipartFile("file", "signed.pdf", MediaType.APPLICATION_PDF_VALUE, PDF))
				.param("attestation", ATTESTATION)
				.param("attestedName", "Someone Else")
				.header(PortalTokenFilter.HEADER, EXPERT_TOKEN))
				.andExpect(status().isOk());

		// Five arguments, and none of them is a name: the controller does not read that parameter.
		verify(portal).uploadSignedLetter(any(), anyString(), anyLong(), any(), eq(ATTESTATION));
	}
}
