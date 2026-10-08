package com.ie.evalos.service;

import java.util.Map;

import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.ServiceType;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** A service added without a checklist or a letter type fails here, not as an empty checklist or shortlist in production. */
class ChecklistTemplatesTest {

	@Test
	void everyServiceHasItsOwnChecklist() {
		for (ServiceType service : ServiceType.values()) {
			assertThat(ChecklistTemplates.forService(service)).as(service.name()).isNotEmpty();
		}
	}

	@Test
	void everyServiceResolvesToALetterType() {
		@SuppressWarnings("unchecked")
		Map<ServiceType, LetterType> letters = (Map<ServiceType, LetterType>) ReflectionTestUtils
				.getField(ExpertMatchService.class, "LETTER_FOR_SERVICE");
		assertThat(letters).containsKeys(ServiceType.values());
	}

	@Test
	void theBusinessSheetListsAreSeededAsWritten() {
		assertThat(ChecklistTemplates.forService(ServiceType.ACADEMIC_EQUIVALENCY_HIGH_SCHOOL)).hasSize(2);
		assertThat(ChecklistTemplates.forService(ServiceType.ACADEMIC_EQUIVALENCY)).hasSize(4);
		assertThat(ChecklistTemplates.forService(ServiceType.L1A_VISA)).hasSize(7);
		assertThat(ChecklistTemplates.forService(ServiceType.SPECIALTY_OCCUPATION_LETTER)).hasSize(11);
		// Marked "not mandatory" or "in case of RFE" on the sheet, so left off rather than blocking the case.
		assertThat(ChecklistTemplates.forService(ServiceType.EXPERT_OPINION_LETTER))
				.noneMatch((label) -> label.contains("RFE") || label.contains("support letter"));
	}
}
