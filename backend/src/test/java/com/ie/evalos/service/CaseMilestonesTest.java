package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit 58 §3: the client's history is milestones in their words, never the internal trail. */
class CaseMilestonesTest {

	private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");
	private final ObjectMapper json = new ObjectMapper();

	private static AuditEvent move(String from, String to, int version, int minutes) {
		AuditEvent e = new AuditEvent(UUID.randomUUID(), "CASE", UUID.randomUUID(), AuditAction.STAGE_CHANGED, null,
				ActorType.STAFF, "{\"stage\":\"" + from + "\",\"draftVersionCount\":" + version + "}",
				"{\"stage\":\"" + to + "\",\"draftVersionCount\":" + version + "}");
		ReflectionTestUtils.setField(e, "createdAt", T0.plusSeconds(minutes * 60L));
		return e;
	}

	@Test
	void aFullWalkReadsAsSixMilestonesAndNothingInternal() {
		List<AuditEvent> trail = List.of(
				move("DOC_COLLECTION", "PM_REVIEW", 0, 10),
				move("PM_REVIEW", "DRAFT_IN_PROGRESS", 0, 20),      // assignment: internal
				move("DRAFT_IN_PROGRESS", "DRAFT_REVIEW", 1, 30),   // PM review: internal
				move("DRAFT_REVIEW", "DRAFT_IN_PROGRESS", 1, 35),   // PM return: internal
				move("DRAFT_REVIEW", "READY_TO_SEND", 2, 40),
				move("READY_TO_SEND", "CLIENT_REVIEW", 2, 50),
				move("CLIENT_REVIEW", "DRAFT_IN_PROGRESS", 2, 60),  // changes requested
				move("READY_TO_SEND", "CLIENT_REVIEW", 3, 70),
				move("CLIENT_REVIEW", "CLIENT_APPROVAL", 3, 80),
				move("CLIENT_APPROVAL", "EXPERT_SIGNING", 3, 90),
				move("EXPERT_SIGNING", "FINAL_QC", 3, 100),
				move("READY_TO_DELIVER", "DELIVERED", 3, 110));

		assertThat(CaseMilestones.derive(T0, trail, json))
				.extracting(CaseMilestones.Milestone::label)
				.containsExactly("Case opened", "Documents received", "Draft ready — v2", "Draft ready — v3",
						"Draft approved", "Letter signed", "Delivered");
	}

	@Test
	void aNewCaseHasOnlyItsOpening() {
		assertThat(CaseMilestones.derive(T0, List.of(), json)).singleElement()
				.satisfies(m -> assertThat(m.at()).isEqualTo(T0));
	}

	/** A row written before snapshots carried the stage, or by another object, is skipped, not a 500. */
	@Test
	void anUnreadableSnapshotIsSkipped() {
		AuditEvent odd = new AuditEvent(UUID.randomUUID(), "CASE", UUID.randomUUID(), AuditAction.UPDATED, null,
				ActorType.STAFF, null, "not json");
		ReflectionTestUtils.setField(odd, "createdAt", T0.plusSeconds(60));

		assertThat(CaseMilestones.derive(T0, List.of(odd), json)).hasSize(1);
	}
}
