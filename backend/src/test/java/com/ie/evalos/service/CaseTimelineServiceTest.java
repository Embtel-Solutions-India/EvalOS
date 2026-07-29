package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.service.CaseLifecycleService.CaseSnapshot;
import com.ie.evalos.service.CaseTimelineService.TimelineEntry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The timeline read. Two properties matter beyond "it returns rows": the scoped load happens
 * before any audit row is touched, and a row whose snapshot no longer parses still appears.
 */
class CaseTimelineServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID ACTOR = UUID.randomUUID();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final AuditEventRepository auditEvents = mock(AuditEventRepository.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final CaseTimelineService timeline = new CaseTimelineService(
			lifecycle, auditEvents, teamMembers, objectMapper);

	private Case theCase() {
		Case subject = new Case(BRAND, "IE-2026-0001", Stage.DOC_COLLECTION);
		given(lifecycle.read(CASE_ID)).willReturn(subject);
		return subject;
	}

	private String snapshotJson(Stage stage, ExceptionState exception, String note) {
		try {
			return objectMapper.writeValueAsString(new CaseSnapshot(stage, exception, PoolStatus.ASSIGNED,
					null, null, null, null, null, PmApprovalStatus.PENDING, null, 1, SlaStatus.ON_TRACK, true, note));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private AuditEvent row(AuditAction action, UUID actorId, String afterJson) {
		AuditEvent event = mock(AuditEvent.class);
		given(event.getAction()).willReturn(action);
		given(event.getActorId()).willReturn(actorId);
		given(event.getAfterSnapshot()).willReturn(afterJson);
		given(event.getCreatedAt()).willReturn(Instant.parse("2026-07-30T09:00:00Z"));
		return event;
	}

	private void givenRows(AuditEvent... rows) {
		given(auditEvents.findByObjectTypeAndObjectIdOrderByCreatedAtAsc(anyString(), any()))
				.willReturn(List.of(rows));
	}

	@SuppressWarnings("unchecked")
	private void givenActors(TeamMember... members) {
		given(teamMembers.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
				.willReturn(List.of(members));
	}

	private static TeamMember named(String displayName) {
		TeamMember member = mock(TeamMember.class);
		given(member.getId()).willReturn(ACTOR);
		given(member.getDisplayName()).willReturn(displayName);
		return member;
	}

	@Test
	void anEntryCarriesWhoWhenWhatAndTheReasonTheActorTyped() {
		theCase();
		givenRows(row(AuditAction.UPDATED, ACTOR,
				snapshotJson(Stage.DOC_COLLECTION, ExceptionState.ON_HOLD_AWAITING_CLIENT, "waiting on transcripts")));
		// Built before the stubbing, not inside the willReturn argument: a mock created there
		// leaves the outer stubbing unfinished (the same trap CaseLifecycleServiceTest names).
		TeamMember actor = named("Priya Menon");
		givenActors(actor);

		List<TimelineEntry> entries = timeline.forCase(CASE_ID);

		assertThat(entries).singleElement().satisfies(entry -> {
			assertThat(entry.actorName()).isEqualTo("Priya Menon");
			assertThat(entry.action()).isEqualTo(AuditAction.UPDATED);
			assertThat(entry.stage()).isEqualTo(Stage.DOC_COLLECTION);
			assertThat(entry.exceptionState()).isEqualTo(ExceptionState.ON_HOLD_AWAITING_CLIENT);
			// The reason is the point of the entry — a hold with no reason on the trail is useless.
			assertThat(entry.note()).isEqualTo("waiting on transcripts");
		});
	}

	/**
	 * Actor names are resolved by the CASE's brand, not by the caller's tier.
	 *
	 * <p>This is why {@code ScopePredicate} is deliberately not used here: a Case Manager is
	 * {@code Tier.SELF}, so a tier-scoped lookup would match only their own row and every
	 * colleague on the timeline would read "System". The query must narrow by brand and still
	 * return other people.
	 */
	@Test
	void aReadOnlyCallerStillSeesTheirColleaguesNames() {
		theCase();
		UUID otherActor = UUID.randomUUID();
		givenRows(
				row(AuditAction.ASSIGNED, ACTOR, snapshotJson(Stage.DOC_COLLECTION, ExceptionState.NONE, null)),
				row(AuditAction.UPDATED, otherActor, snapshotJson(Stage.DOC_COLLECTION, ExceptionState.NONE, null)));

		TeamMember pm = named("Priya Menon");
		TeamMember coordinator = mock(TeamMember.class);
		given(coordinator.getId()).willReturn(otherActor);
		given(coordinator.getDisplayName()).willReturn("Priya Chandra");
		givenActors(pm, coordinator);

		List<TimelineEntry> entries = timeline.forCase(CASE_ID);

		assertThat(entries).extracting(TimelineEntry::actorName)
				.containsExactly("Priya Menon", "Priya Chandra");
		// A Specification, never the unscoped findAllById it replaced.
		verify(teamMembers).findAll(any(org.springframework.data.jpa.domain.Specification.class));
	}

	@Test
	void aRowWrittenByTheSystemIsAttributedToTheSystem() {
		theCase();
		// Handoff A creates the case with no authenticated actor.
		givenRows(row(AuditAction.CREATED, null, snapshotJson(Stage.DOC_COLLECTION, ExceptionState.NONE, null)));

		assertThat(timeline.forCase(CASE_ID)).singleElement()
				.satisfies(entry -> assertThat(entry.actorName()).isEqualTo("System"));
		// No actor ids means no roster query at all.
		verifyNoInteractions(teamMembers);
	}

	/**
	 * Audit rows are permanent while the snapshot shape moves — {@code assignedCoordinator} was
	 * added in Unit 08, and a strategy-notes edit stores a different record entirely. One
	 * unreadable snapshot must not take out the history it sits in.
	 */
	@Test
	void aRowWhoseSnapshotWillNotParseStillAppears() {
		theCase();
		givenRows(
				row(AuditAction.UPDATED, null, "{\"pmStrategyNotes\":\"lead with the publications\"}"),
				row(AuditAction.UPDATED, null, "not json at all"),
				row(AuditAction.STAGE_CHANGED, null, snapshotJson(Stage.EXPERT_ASSIGNMENT, ExceptionState.NONE, null)));

		List<TimelineEntry> entries = timeline.forCase(CASE_ID);

		assertThat(entries).hasSize(3);
		// The action, actor and timestamp live in real columns, so they survive either way.
		assertThat(entries).allSatisfy(entry -> {
			assertThat(entry.at()).isNotNull();
			assertThat(entry.action()).isNotNull();
		});
		// And nothing from the foreign snapshot leaks into the projected fields.
		assertThat(entries.get(0).stage()).isNull();
		assertThat(entries.get(0).note()).isNull();
		assertThat(entries.get(1).stage()).isNull();
		assertThat(entries.get(2).stage()).isEqualTo(Stage.EXPERT_ASSIGNMENT);
	}

	@Test
	void anOutOfScopeCaseIsRefusedBeforeAnyAuditRowIsRead() {
		given(lifecycle.read(CASE_ID)).willThrow(new com.ie.evalos.common.ForbiddenException("not yours"));

		assertThat(org.junit.jupiter.api.Assertions.assertThrows(
				com.ie.evalos.common.ForbiddenException.class, () -> timeline.forCase(CASE_ID))).isNotNull();

		// The history of a case the caller cannot open is not fetched, let alone filtered.
		verifyNoInteractions(auditEvents);
	}

	@Test
	void theTimelineNeverExposesTheStoredSnapshotItself() {
		theCase();
		givenRows(row(AuditAction.UPDATED, null, snapshotJson(Stage.DOC_COLLECTION, ExceptionState.NONE, "note")));

		timeline.forCase(CASE_ID);

		// TimelineEntry is a fixed projection: there is no component holding raw JSON, so a
		// field added to CaseSnapshot later cannot reach a client through here.
		assertThat(TimelineEntry.class.getRecordComponents())
				.extracting(component -> component.getName())
				.containsExactly("at", "actorName", "action", "stage", "exceptionState", "note");

		// And the scoped load is what every read goes through.
		verify(lifecycle).read(CASE_ID);
	}
}
