package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.CaseClientRemark;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Stage;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** D74: the client's history is status changes with the remark written under each, never the internal trail. */
class CaseStatusHistoryTest {

	private static final Instant T0 = Instant.parse("2026-09-01T09:00:00Z");
	private final ObjectMapper json = new ObjectMapper();

	private static AuditEvent move(String stage, String exception, String note, int minutes) {
		String after = "{\"stage\":\"" + stage + "\",\"exceptionState\":\"" + exception + "\""
				+ (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
		AuditEvent e = new AuditEvent(UUID.randomUUID(), "CASE", UUID.randomUUID(), AuditAction.STAGE_CHANGED, null,
				ActorType.STAFF, null, after);
		ReflectionTestUtils.setField(e, "createdAt", T0.plusSeconds(minutes * 60L));
		return e;
	}

	private static CaseClientRemark remark(String body, int minutes) {
		CaseClientRemark r = new CaseClientRemark(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), body);
		ReflectionTestUtils.setField(r, "createdAt", T0.plusSeconds(minutes * 60L));
		return r;
	}

	private List<CaseStatusHistory.Entry> derive(List<AuditEvent> trail, List<CaseClientRemark> remarks, Stage now,
			ExceptionState exception) {
		return CaseStatusHistory.derive(T0, trail, remarks, now, exception, T0, json);
	}

	@Test
	void onlyAStatusChangeIsARowAndTheInternalStagesBetweenAreNot() {
		List<AuditEvent> trail = List.of(
				move("PM_REVIEW", "NONE", null, 10),
				move("DRAFT_IN_PROGRESS", "NONE", null, 20),
				move("DRAFT_REVIEW", "NONE", null, 30),
				move("CLIENT_REVIEW", "NONE", null, 40),
				move("DRAFT_IN_PROGRESS", "NONE", null, 50),   // changes requested
				move("CLIENT_REVIEW", "NONE", null, 60),
				move("EXPERT_SIGNING", "NONE", null, 70),
				move("DELIVERED", "NONE", null, 80));

		assertThat(derive(trail, List.of(), Stage.DELIVERED, ExceptionState.NONE))
				.extracting(CaseStatusHistory.Entry::label)
				.containsExactly("Awaiting Documents", "In Preparation", "Awaiting Client Review", "In Preparation",
						"Awaiting Client Review", "Under Expert Review", "Delivered");
	}

	@Test
	void aRemarkIsShownUnderTheStatusItWasWrittenIn() {
		List<AuditEvent> trail = List.of(move("PM_REVIEW", "NONE", null, 10));
		List<CaseClientRemark> remarks = List.of(remark("Send the transcripts", 5), remark("Drafting started", 12),
				remark("Nearly done", 15));

		List<CaseStatusHistory.Entry> history = derive(trail, remarks, Stage.PM_REVIEW, ExceptionState.NONE);

		assertThat(history.get(0).remark().body()).isEqualTo("Send the transcripts");
		assertThat(history.get(1).remark().body()).isEqualTo("Nearly done");
		assertThat(history.get(1).remark().at()).isEqualTo(T0.plusSeconds(15 * 60L));
	}

	@Test
	void aHoldShowsItsReasonAndResumingReturnsToTheStageItStoppedAt() {
		List<AuditEvent> trail = List.of(
				move("PM_REVIEW", "NONE", null, 10),
				move("PM_REVIEW", "ON_HOLD_AWAITING_CLIENT", "Waiting on your ID", 20),
				move("PM_REVIEW", "NONE", null, 30));

		List<CaseStatusHistory.Entry> history = derive(trail, List.of(), Stage.PM_REVIEW, ExceptionState.NONE);

		assertThat(history).extracting(CaseStatusHistory.Entry::label)
				.containsExactly("Awaiting Documents", "In Preparation", "On Hold", "In Preparation");
		assertThat(history.get(2).remark().body()).isEqualTo("Waiting on your ID");
		assertThat(history.get(3).remark()).isNull();
	}

	@Test
	void aCaseWhoseTrailPredatesSnapshotsStillShowsWhereItIsNow() {
		AuditEvent odd = new AuditEvent(UUID.randomUUID(), "CASE", UUID.randomUUID(), AuditAction.UPDATED, null,
				ActorType.STAFF, null, "not json");

		List<CaseStatusHistory.Entry> history = derive(List.of(odd), List.of(), Stage.CLIENT_REVIEW, ExceptionState.NONE);

		assertThat(history).extracting(CaseStatusHistory.Entry::label)
				.containsExactly("Awaiting Documents", "Awaiting Client Review");
	}
}
