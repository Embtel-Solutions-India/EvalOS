package com.ie.evalos.webhook;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.integration.GhlHttp;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.service.CaseIntakeService;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The endpoint token is the whole credential for {@code opportunity.won}, so a delivery is only a
 * trigger: GHL must show a won deal for the contact before a case, an account and an email follow.
 */
class GhlOpportunityHandlerTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final CaseIntakeService intake = mock(CaseIntakeService.class);
	private final com.ie.evalos.service.CaseServiceTypeService serviceTypes =
			mock(com.ie.evalos.service.CaseServiceTypeService.class);
	private final com.ie.evalos.integration.GhlContactClient contacts =
			mock(com.ie.evalos.integration.GhlContactClient.class);
	private final com.ie.evalos.repository.BrandRepository brands = mock(com.ie.evalos.repository.BrandRepository.class);
	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);
	private final GhlHttp http = mock(GhlHttp.class);
	private final Brand brand = mock(Brand.class);
	private final WebhookPayload payloads = new WebhookPayload(new ObjectMapper().findAndRegisterModules()
					.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
			Validation.buildDefaultValidatorFactory().getValidator());

	private GhlOpportunityHandler handler(SellingBrand selling) {
		given(brand.getId()).willReturn(BRAND);
		return new GhlOpportunityHandler(intake, payloads, ghl, http, selling, serviceTypes, contacts, brands);
	}

	private static String won(String opportunityId) {
		String extra = opportunityId == null ? "" : ",\"customData\":{\"opportunity_id\":\"" + opportunityId + "\"}";
		return "{\"event_type\":\"opportunity.won\",\"contact_id\":\"c-1\",\"full_name\":\"Ana Perez\"" + extra + "}";
	}

	private static GhlPipelineClient.Opportunity deal(String id, String status) {
		return new GhlPipelineClient.Opportunity(id, "A deal", "c-1", "p", "s", status, BigDecimal.TEN, null, null, null,
				null, null, null);
	}

	@Test
	void aWinGhlConfirmsCreatesTheCase() {
		given(http.isConfigured()).willReturn(true);
		given(ghl.forContact("c-1")).willReturn(List.of(deal("o-1", "won")));

		handler(new SellingBrand(BRAND)).handle(brand, won(null));

		verify(intake).intake(any(), any());
	}

	/** 2026-10-30: GHL's workflow sends no service, so the won deal's own Service Requested decides it. */
	@Test
	void theCaseTakesTheWonDealsServiceRequested() {
		given(http.isConfigured()).willReturn(true);
		GhlPipelineClient.Opportunity tn = new GhlPipelineClient.Opportunity("o-1", "A deal", "c-1", "p", "s", "won",
				BigDecimal.TEN, null, null, null, null, null, null,
				List.of(new GhlPipelineClient.CustomFieldValue("f-svc", "TN Visa Expert Opinion Letter")), null, null, null);
		given(ghl.forContact("c-1")).willReturn(List.of(tn));
		given(serviceTypes.requestedOn(org.mockito.ArgumentMatchers.eq(BRAND),
				org.mockito.ArgumentMatchers.eq(java.util.Map.of("f-svc", "TN Visa Expert Opinion Letter"))))
				.willReturn(java.util.Optional.of(com.ie.evalos.domain.ServiceType.TN_VISA));

		handler(new SellingBrand(BRAND)).handle(brand, won(null));

		verify(intake).intake(any(), org.mockito.ArgumentMatchers.argThat(
				(c) -> c.serviceType() == com.ie.evalos.domain.ServiceType.TN_VISA));
	}

	/** A deal naming no mapped service still opens the case, as Credential evaluation. */
	@Test
	void aDealWithNoMappedServiceFallsBackToCredentialEvaluation() {
		given(http.isConfigured()).willReturn(true);
		given(ghl.forContact("c-1")).willReturn(List.of(deal("o-1", "won")));

		handler(new SellingBrand(BRAND)).handle(brand, won(null));

		verify(intake).intake(any(), org.mockito.ArgumentMatchers.argThat(
				(c) -> c.serviceType() == com.ie.evalos.domain.ServiceType.CREDENTIAL_EVALUATION));
	}

	@Test
	void aDeliveryGhlDoesNotShowAsWonCreatesNothing() {
		given(http.isConfigured()).willReturn(true);
		given(ghl.forContact("c-1")).willReturn(List.of(deal("o-1", "open")));

		assertThatThrownBy(() -> handler(new SellingBrand(BRAND)).handle(brand, won(null)))
				.isInstanceOfSatisfying(WebhookRejected.class, e -> {
					assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(e.code()).isEqualTo("NOT_WON_IN_GHL");
				});
		verify(intake, never()).intake(any(), any());
	}

	@Test
	void whenThePayloadNamesADealThatExactDealMustBeWon() {
		given(http.isConfigured()).willReturn(true);
		given(ghl.forContact("c-1")).willReturn(List.of(deal("o-1", "won"), deal("o-2", "open")));

		assertThatThrownBy(() -> handler(new SellingBrand(BRAND)).handle(brand, won("o-2")))
				.isInstanceOf(WebhookRejected.class);
		verify(intake, never()).intake(any(), any());
	}

	@Test
	void aGhlOutageRefusesTheDeliveryRatherThanTrustingIt() {
		given(http.isConfigured()).willReturn(true);
		given(ghl.forContact("c-1")).willThrow(new IllegalStateException("GHL is down"));

		assertThatThrownBy(() -> handler(new SellingBrand(BRAND)).handle(brand, won(null)))
				.isInstanceOf(IllegalStateException.class);
		verify(intake, never()).intake(any(), any());
	}

	@Test
	void whereGhlCannotBeAskedTheDeliveryIsStillAccepted() {
		// No token at all, and a brand that does not own the GHL location: nothing to check against.
		given(http.isConfigured()).willReturn(false);
		handler(new SellingBrand(BRAND)).handle(brand, won(null));

		given(http.isConfigured()).willReturn(true);
		handler(new SellingBrand(UUID.randomUUID())).handle(brand, won(null));

		verify(intake, org.mockito.Mockito.times(2)).intake(any(), any());
		verify(ghl, never()).forContact(any());
	}

	// --- the PM's New case (2026-10-30) ---------------------------------------------------------

	private void asStaff(com.ie.evalos.domain.Role role, UUID brandId) {
		com.ie.evalos.security.StaffPrincipal principal = new com.ie.evalos.security.StaffPrincipal(UUID.randomUUID(),
				"pm@ie.test", "PM", role, brandId, null, null, true);
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
				new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null,
						principal.getAuthorities()));
	}

	@org.junit.jupiter.api.AfterEach
	void clearCaller() {
		org.springframework.security.core.context.SecurityContextHolder.clearContext();
	}

	private GhlOpportunityHandler manual() {
		given(http.isConfigured()).willReturn(true);
		given(brands.findById(BRAND)).willReturn(java.util.Optional.of(brand));
		given(contacts.byId("c-1")).willReturn(new com.ie.evalos.integration.GhlContactClient.Contact("c-1", "Ana Perez",
				"ana@x.test", "+1", null, null, List.of(), java.util.Map.of()));
		asStaff(com.ie.evalos.domain.Role.PROJECT_MANAGER, BRAND);
		return handler(new SellingBrand(BRAND));
	}

	@Test
	void aPmOpensACaseFromAWonOpportunityLikeTheWebhook() {
		GhlOpportunityHandler handler = manual();
		given(ghl.byId("o-1")).willReturn(deal("o-1", "won"));

		handler.openFromGhl("o-1", null);

		verify(intake).intake(org.mockito.ArgumentMatchers.eq(brand), org.mockito.ArgumentMatchers.argThat((c) ->
				"o-1".equals(c.ghlOpportunityId()) && "c-1".equals(c.contact().ghlContactId())
						&& "Ana Perez".equals(c.contact().fullName())
						&& c.serviceType() == com.ie.evalos.domain.ServiceType.CREDENTIAL_EVALUATION));
	}

	@Test
	void anOpportunityNotWonInGhlOpensNothing() {
		GhlOpportunityHandler handler = manual();
		given(ghl.byId("o-1")).willReturn(deal("o-1", "open"));

		assertThatThrownBy(() -> handler.openFromGhl("o-1", null))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class).hasMessageContaining("not won");
		verify(intake, never()).intake(any(), any());
	}

	@Test
	void aContactIdOpensTheirWonDeal() {
		GhlOpportunityHandler handler = manual();
		given(ghl.forContact("c-1")).willReturn(List.of(deal("o-open", "open"), deal("o-won", "won")));

		handler.openFromGhl(null, "c-1");

		verify(intake).intake(any(), org.mockito.ArgumentMatchers.argThat((c) -> "o-won".equals(c.ghlOpportunityId())));
	}

	/** A wrong id is a plain 400; a missing GHL scope is not a wrong id and stays GHL's failure. */
	@Test
	void onlyGhlsNoSuchIdReadsAsAWrongId() {
		GhlOpportunityHandler handler = manual();
		given(ghl.byId("o-404")).willThrow(new com.ie.evalos.integration.GhlUnavailableException("404", null,
				com.ie.evalos.integration.GhlFailure.REFUSED, 404));
		given(ghl.byId("o-401")).willThrow(new com.ie.evalos.integration.GhlUnavailableException("401", null,
				com.ie.evalos.integration.GhlFailure.UNAUTHORIZED, 401));

		assertThatThrownBy(() -> handler.openFromGhl("o-404", null))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class).hasMessageContaining("no opportunity");
		assertThatThrownBy(() -> handler.openFromGhl("o-401", null))
				.isInstanceOf(com.ie.evalos.integration.GhlUnavailableException.class);
	}

	@Test
	void neitherIdIsRefused() {
		assertThatThrownBy(() -> manual().openFromGhl(" ", null))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
	}

	@Test
	void anotherBrandsPmCannotOpenFromThisLocation() {
		GhlOpportunityHandler handler = manual();
		asStaff(com.ie.evalos.domain.Role.PROJECT_MANAGER, UUID.randomUUID());

		assertThatThrownBy(() -> handler.openFromGhl("o-1", null))
				.isInstanceOf(com.ie.evalos.common.ForbiddenException.class);
	}
}
