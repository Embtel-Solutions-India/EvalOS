package com.ie.evalos.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Unit 65 review I2: two people changing one offer at once is a conflict, not a 500. */
class ApiExceptionHandlerConcurrencyTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void aConcurrentChangeIsAConflictThatSaysToReload() {
		var response = handler.onConcurrentChange(
				new ObjectOptimisticLockingFailureException("ExpertCaseOffer", "id"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().error().code()).isEqualTo("CHANGED_MEANWHILE");
		assertThat(response.getBody().error().message()).contains("Reload");
	}
}
