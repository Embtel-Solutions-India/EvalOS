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
	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);
	private final GhlHttp http = mock(GhlHttp.class);
	private final Brand brand = mock(Brand.class);
	private final WebhookPayload payloads = new WebhookPayload(new ObjectMapper().findAndRegisterModules()
					.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
			Validation.buildDefaultValidatorFactory().getValidator());

	private GhlOpportunityHandler handler(SellingBrand selling) {
		given(brand.getId()).willReturn(BRAND);
		return new GhlOpportunityHandler(intake, payloads, ghl, http, selling);
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
}
