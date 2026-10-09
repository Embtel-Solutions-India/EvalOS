package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.StaffPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** The Case Manager dashboard's "Client feedback" log: client revision requests on the caller's own cases. */
class CaseManagerClientFeedbackTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID ME = UUID.randomUUID();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final AuditEventRepository audit = mock(AuditEventRepository.class);
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final CaseManagerMetricsService metrics = new CaseManagerMetricsService(lifecycle,
			mock(DeadlineRiskCalculator.class), mock(ContactSnapshotRepository.class), mock(ExpertRepository.class),
			audit, mapper);

	@BeforeEach
	void aCaseManager() {
		StaffPrincipal principal = new StaffPrincipal(ME, "cm@evalos.local", "CM", Role.CASE_MANAGER, BRAND, null, null,
				true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	private Case mine(Stage stage) {
		Case subject = new Case(BRAND, "C-" + UUID.randomUUID().toString().substring(0, 6), stage);
		ReflectionTestUtils.setField(subject, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(subject, "assignedCm", ME);
		return subject;
	}

	@Test
	void aClientRevisionRequestOnMyCaseShowsWithTheReasonTheClientGave() throws Exception {
		Case subject = mine(Stage.DRAFT_IN_PROGRESS);
		given(lifecycle.list(any(), any(), any())).willReturn(List.of(subject));
		AuditEvent event = mock(AuditEvent.class);
		given(event.getObjectId()).willReturn(subject.getId());
		given(event.getCreatedAt()).willReturn(Instant.parse("2026-10-01T10:00:00Z"));
		given(event.getAfterSnapshot()).willReturn(
				mapper.writeValueAsString(CaseLifecycleService.CaseSnapshot.of(subject, "Please fix the date of birth")));
		given(audit.findCaseActionScoped(any(String.class), any(AuditAction.class), anyCollection(), anyCollection()))
				.willReturn(List.of(event));

		CaseManagerMetricsService.CaseManagerMetrics result = metrics.forCaller();

		assertThat(result.clientFeedback()).singleElement().satisfies(entry -> {
			assertThat(entry.caseId()).isEqualTo(subject.getId());
			assertThat(entry.caseCode()).isEqualTo(subject.getCaseCode());
			assertThat(entry.note()).isEqualTo("Please fix the date of birth");
		});
	}

	@Test
	void aCaseWithNoCaseManagerRequestsNothingAndIsNotQueried() {
		Case someoneElses = mine(Stage.DRAFT_IN_PROGRESS);
		ReflectionTestUtils.setField(someoneElses, "assignedCm", UUID.randomUUID());
		given(lifecycle.list(any(), any(), any())).willReturn(List.of(someoneElses));

		assertThat(metrics.forCaller().clientFeedback()).isEmpty();
	}
}
