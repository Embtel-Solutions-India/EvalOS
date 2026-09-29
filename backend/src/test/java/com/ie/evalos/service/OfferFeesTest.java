package com.ie.evalos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;

/** Unit 65 rules 1–2: what a new offer pays, and who may name a different amount. */
class OfferFeesTest {

	private static final BigDecimal STANDARD = new BigDecimal("350.00");

	@Test
	void blankMeansTheBaseFee() {
		assertThat(OfferFees.price(Role.PROJECT_MANAGER, null, STANDARD)).isEqualByComparingTo("350.00");
	}

	@Test
	void aFeeSetterMayNameAnyAmount() {
		assertThat(OfferFees.price(Role.EXPERT_NETWORK_MANAGER, new BigDecimal("500"), STANDARD))
				.isEqualByComparingTo("500");
		assertThat(OfferFees.price(Role.PROJECT_MANAGER, new BigDecimal("500"), null)).isEqualByComparingTo("500");
	}

	@Test
	void anOfferCannotBeMadeWithoutAFee() {
		assertThatThrownBy(() -> OfferFees.price(Role.PROJECT_MANAGER, null, null))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessage("Set a fee for this case — this expert has no standard fee.");
	}

	@Test
	void aCaseManagerOffersAtTheStandardFeeOnly() {
		assertThat(OfferFees.price(Role.CASE_MANAGER, null, STANDARD)).isEqualByComparingTo("350.00");
		assertThat(OfferFees.price(Role.CASE_MANAGER, new BigDecimal("350"), STANDARD)).isEqualByComparingTo("350");
		assertThatThrownBy(() -> OfferFees.price(Role.CASE_MANAGER, new BigDecimal("400"), STANDARD))
				.isInstanceOf(ForbiddenException.class)
				.hasMessage("A case manager offers at the expert's standard fee.");
		assertThatThrownBy(() -> OfferFees.price(Role.CASE_MANAGER, null, null))
				.isInstanceOf(IllegalTransitionException.class)
				.hasMessage("Ask a PM, PC or ENM to set the fee for this case.");
	}
}
