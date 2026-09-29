package com.ie.evalos.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit 58 §6: the two answers the draft routes add. */
class ApiExceptionHandlerDraftTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void aVersionNotInReviewIsAConflictWithItsOwnCode() {
		var response = handler.onDraftNotCurrent(new DraftNotCurrentException("v2 is not the version in review"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().error().code()).isEqualTo("DRAFT_NOT_CURRENT");
	}

	@Test
	void somethingNotThereYetIsNotFound() {
		var response = handler.onNotFound(new NotFoundException("Nothing has been delivered yet"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody().error().code()).isEqualTo("NOT_FOUND");
	}
}
