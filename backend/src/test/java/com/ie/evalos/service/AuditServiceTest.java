package com.ie.evalos.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The three audit writers, and the one property that ties them together: {@code actor_id} and
 * {@code actor_type} must never disagree.
 *
 * <p>Worth its own test because the trail is append-only — a row written with the wrong pair can
 * never be corrected (the {@code V10} trigger refuses every UPDATE), so the only place to get it
 * right is the moment of writing.
 */
class AuditServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OBJECT_ID = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();

	private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
	private final AuditService audit = new AuditService(auditEvents, new ObjectMapper());

	@BeforeEach
	void echoTheSavedRow() {
		given(auditEvents.save(any(AuditEvent.class))).willAnswer(call -> call.getArgument(0));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private void actAsStaff() {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new StaffPrincipal(MEMBER, "pm@evalos.local", "Priya Menon", Role.PROJECT_MANAGER, BRAND, null,
						null, true),
				null, List.of()));
	}

	private AuditEvent saved() {
		org.mockito.ArgumentCaptor<AuditEvent> row = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
		verify(auditEvents).save(row.capture());
		return row.getValue();
	}

	@Test
	void aStaffActionIsStaffAndCarriesTheCallersBrand() {
		actAsStaff();

		audit.recordEvent("CASE", OBJECT_ID, AuditAction.UPDATED, MEMBER, null, Map.of("note", "edited"));

		assertThat(saved().getActorType()).isEqualTo(ActorType.STAFF);
		assertThat(saved().getActorId()).isEqualTo(MEMBER);
		assertThat(saved().getBrandId()).as("brand comes off the caller, never an argument").isEqualTo(BRAND);
	}

	/**
	 * The one that was wrong: {@code recordEvent}'s own contract allows a null {@code actorId} "for a
	 * system action", and the type used to be hardcoded {@code STAFF} regardless. That would have
	 * written STAFF beside a null actor — contradicting the rule {@code V22} states and
	 * {@code CaseTimelineService} applies (a null {@code actor_id} reads as SYSTEM), permanently,
	 * because the row can never be updated. No caller passes null today, which is exactly why this
	 * needed pinning rather than leaving to chance.
	 */
	@Test
	void anActionWithNoActorIsSystemRatherThanStaff() {
		actAsStaff();

		audit.recordEvent("CASE", OBJECT_ID, AuditAction.UPDATED, null, null, null);

		assertThat(saved().getActorId()).isNull();
		assertThat(saved().getActorType())
				.as("a null actor_id must never be recorded as STAFF")
				.isEqualTo(ActorType.SYSTEM);
	}

	@Test
	void aWebhookIsSystemAndTakesItsBrandFromTheEndpointToken() {
		// No security context at all: an inbound webhook has no authenticated caller.
		audit.recordSystemEvent(BRAND, "CASE", OBJECT_ID, AuditAction.CREATED, null, null);

		assertThat(saved().getActorType()).isEqualTo(ActorType.SYSTEM);
		assertThat(saved().getActorId()).isNull();
		assertThat(saved().getBrandId()).isEqualTo(BRAND);
	}

	@Test
	void aPortalActionNamesTheAudienceAndTakesItsBrandFromTheToken() {
		audit.recordPortalEvent(BRAND, PortalAudience.CLIENT, "CASE", OBJECT_ID, AuditAction.STAGE_CHANGED,
				null, Map.of("note", "approved"));

		assertThat(saved().getActorType()).isEqualTo(ActorType.CLIENT);
		assertThat(saved().getActorId()).as("no team_member acted").isNull();
		assertThat(saved().getBrandId()).isEqualTo(BRAND);
	}

	@Test
	void anExpertPortalActionIsExpert() {
		audit.recordPortalEvent(BRAND, PortalAudience.EXPERT, "CASE", OBJECT_ID, AuditAction.STAGE_CHANGED,
				null, null);

		assertThat(saved().getActorType()).isEqualTo(ActorType.EXPERT);
	}
}
