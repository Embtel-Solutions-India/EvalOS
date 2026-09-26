package com.ie.evalos.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.repository.AuditEventRepository;

import org.springframework.stereotype.Component;

/**
 * The client's case history (Unit 58 §3), derived at read time from the stage snapshots the
 * lifecycle already writes — no second record of the same facts. Only entries into the stages a
 * client cares about become milestones; PM review, returns, reassignments and holds never appear.
 */
@Component
public class CaseMilestones {

	public record Milestone(String label, Instant at) {
	}

	private final AuditEventRepository trail;
	private final ObjectMapper json;

	CaseMilestones(AuditEventRepository trail, ObjectMapper json) {
		this.trail = trail;
		this.json = json;
	}

	/** {@code subject} is already authorized; its id is the only thing read by. */
	public List<Milestone> of(Case subject) {
		return derive(subject.getCreatedAt(), trail.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("CASE", subject.getId()),
				json);
	}

	static List<Milestone> derive(Instant opened, List<AuditEvent> events, ObjectMapper json) {
		List<Milestone> out = new ArrayList<>();
		out.add(new Milestone("Case opened", opened));
		boolean documentsReceived = false;
		for (AuditEvent event : events) {
			JsonNode after = read(json, event.getAfterSnapshot());
			JsonNode before = read(json, event.getBeforeSnapshot());
			String to = after == null ? null : after.path("stage").asText(null);
			String from = before == null ? null : before.path("stage").asText(null);
			if (to == null || to.equals(from)) {
				continue;
			}
			String label = switch (to) {
				case "PM_REVIEW" -> documentsReceived ? null : "Documents received";
				case "CLIENT_REVIEW" -> "Draft ready — v" + after.path("draftVersionCount").asInt();
				case "CLIENT_APPROVAL" -> "Draft approved";
				case "FINAL_QC" -> "EXPERT_SIGNING".equals(from) ? "Letter signed" : null;
				case "DELIVERED" -> "Delivered";
				default -> null;
			};
			if ("PM_REVIEW".equals(to)) {
				documentsReceived = true;
			}
			if (label != null) {
				out.add(new Milestone(label, event.getCreatedAt()));
			}
		}
		return out;
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
