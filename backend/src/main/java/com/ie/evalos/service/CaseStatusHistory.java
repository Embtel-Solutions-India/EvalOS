package com.ie.evalos.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseClientRemark;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.CaseClientRemarkRepository;
import com.ie.evalos.service.PortalStageProjection.ClientStatus;

import org.springframework.stereotype.Component;

/**
 * The client's dated status history (D74), derived at read time from the stage snapshots the
 * lifecycle already writes — no second record of the same facts. A row exists only where the
 * <em>status</em> changed; moves between stages of one status, PM review, returns and
 * reassignments never appear. Replaces Unit 58's milestones.
 *
 * <p><strong>A remark belongs to the status it was written under</strong>: each entry carries the
 * latest remark dated inside its own period. A hold's reason is a remark by definition (it is shown
 * to the client), read from the hold's audit note, so putting a case on hold needs no second write.
 */
@Component
public class CaseStatusHistory {

	public record Remark(String body, Instant at) {
	}

	public record Entry(String key, String label, String description, Instant at, Remark remark) {
	}

	private final AuditEventRepository trail;
	private final CaseClientRemarkRepository remarks;
	private final ObjectMapper json;

	CaseStatusHistory(AuditEventRepository trail, CaseClientRemarkRepository remarks, ObjectMapper json) {
		this.trail = trail;
		this.remarks = remarks;
		this.json = json;
	}

	/** Oldest first; the last entry is the case's current status. {@code subject} is already authorized. */
	public List<Entry> of(Case subject) {
		return derive(subject.getCreatedAt(), trail.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("CASE", subject.getId()),
				remarks.findByCaseIdOrderByCreatedAtAsc(subject.getId()), subject.getCurrentStage(),
				subject.getExceptionState(), subject.getStageEnteredAt(), json);
	}

	static List<Entry> derive(Instant opened, List<AuditEvent> events, List<CaseClientRemark> written, Stage stage,
			ExceptionState exception, Instant stageEnteredAt, ObjectMapper json) {
		List<ClientStatus> statuses = new ArrayList<>(List.of(ClientStatus.AWAITING_DOCUMENTS));
		List<Instant> starts = new ArrayList<>(List.of(opened));
		List<Remark> all = new ArrayList<>();
		written.forEach(r -> all.add(new Remark(r.getBody(), r.getCreatedAt())));

		for (AuditEvent event : events) {
			JsonNode after = read(json, event.getAfterSnapshot());
			Stage to = after == null ? null : enumOf(Stage.class, after.path("stage").asText(null));
			if (to == null) {
				continue;
			}
			ClientStatus status = ClientStatus.of(to, enumOf(ExceptionState.class, after.path("exceptionState").asText(null)));
			if (status == statuses.get(statuses.size() - 1)) {
				continue;
			}
			statuses.add(status);
			starts.add(event.getCreatedAt());
			String reason = after.path("note").asText("");
			if (status == ClientStatus.ON_HOLD && !reason.isBlank()) {
				all.add(new Remark(reason, event.getCreatedAt()));
			}
		}

		// A case whose trail predates snapshots must still show where it is now.
		ClientStatus now = ClientStatus.of(stage, exception);
		if (now != statuses.get(statuses.size() - 1)) {
			statuses.add(now);
			starts.add(stageEnteredAt == null ? opened : stageEnteredAt);
		}

		all.sort(Comparator.comparing(Remark::at));
		List<Entry> out = new ArrayList<>();
		for (int i = 0; i < statuses.size(); i++) {
			Instant from = starts.get(i);
			Instant until = i + 1 < starts.size() ? starts.get(i + 1) : null;
			Remark latest = all.stream()
					.filter(r -> !r.at().isBefore(from) && (until == null || r.at().isBefore(until)))
					.reduce((a, b) -> b).orElse(null);
			ClientStatus s = statuses.get(i);
			out.add(new Entry(s.name(), s.label(), s.description(), from, latest));
		}
		return out;
	}

	private static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
		if (name == null) {
			return null;
		}
		try {
			return Enum.valueOf(type, name);
		}
		catch (IllegalArgumentException unknown) {
			return null;
		}
	}

	private static JsonNode read(ObjectMapper json, String snapshot) {
		if (snapshot == null) {
			return null;
		}
		try {
			return json.readTree(snapshot);
		}
		catch (Exception unreadable) {
			return null;
		}
	}
}
