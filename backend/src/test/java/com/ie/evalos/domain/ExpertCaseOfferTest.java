package com.ie.evalos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Unit 65: the fee lives on the offer and is final once the offer is answered. */
class ExpertCaseOfferTest {

	private final UUID actor = UUID.randomUUID();

	@Test
	void theFeeCanBeSetWhileTheOfferIsOpen() {
		ExpertCaseOffer offer = new ExpertCaseOffer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		offer.setFee(new BigDecimal("350.00"), actor);
		assertThat(offer.getFee()).isEqualByComparingTo("350.00");
		assertThat(offer.getFeeSetBy()).isEqualTo(actor);
		assertThat(offer.getFeeSetAt()).isNotNull();
	}

	@Test
	void theFeeIsFrozenOnceTheOfferIsAnswered() {
		for (OfferOutcome outcome : new OfferOutcome[] { OfferOutcome.ACCEPTED, OfferOutcome.DECLINED,
				OfferOutcome.TIMED_OUT, OfferOutcome.SUPERSEDED }) {
			ExpertCaseOffer offer = new ExpertCaseOffer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
			offer.resolve(outcome, null);
			assertThatThrownBy(() -> offer.setFee(BigDecimal.TEN, actor))
					.as(outcome.name())
					.isInstanceOf(IllegalTransitionException.class);
		}
	}
}
