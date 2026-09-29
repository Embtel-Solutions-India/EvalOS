package com.ie.evalos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.ie.evalos.domain.Role;
import com.ie.evalos.service.OfferFeeService;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** Unit 65: the fee-edit gate names exactly {@link OfferFeeService#MAY_SET_FEE}, read by reflection. */
class OfferFeeControllerTest {

	@Test
	void theFeeGateNamesExactlyTheFeeSetters() throws Exception {
		PreAuthorize gate = OfferFeeController.class
				.getMethod("editFee", UUID.class, OfferFeeController.FeeRequest.class)
				.getAnnotation(PreAuthorize.class);
		assertThat(gate).as("the fee edit must be gated").isNotNull();
		for (Role role : Role.values()) {
			assertThat(gate.value().contains("'" + role.name() + "'")).as(role.name())
					.isEqualTo(OfferFeeService.MAY_SET_FEE.contains(role));
		}
	}
}
