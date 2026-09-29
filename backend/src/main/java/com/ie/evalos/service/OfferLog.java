package com.ie.evalos.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.stereotype.Component;

/**
 * One offer's money history, read off {@code audit_event} (Unit 65 rule 8): the fee set and
 * changed, the answer, the payout opened, the transfer recorded, the expert's confirmation.
 *
 * <p>The ids come from already-scoped rows (the offer and its payout), which is the only way the
 * unscoped audit finder may be called. Nothing here writes: the log is append-only by trigger.
 */
@Component
public class OfferLog {

	/** One line: when, who, what, and the raw before / after snapshots the screen describes. */
	public record Entry(Instant at, String who, String what, String before, String after) {
	}

	private final AuditEventRepository events;
	private final TeamMemberRepository members;

	OfferLog(AuditEventRepository events, TeamMemberRepository members) {
		this.events = events;
		this.members = members;
	}

	/** Oldest first. {@code payout} is null until the case is delivered. */
	List<Entry> forOffer(ExpertCaseOffer offer, PayoutLedger payout) {
		List<Map.Entry<String, AuditEvent>> rows = new ArrayList<>();
		events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("OFFER", offer.getId())
				.forEach(e -> rows.add(Map.entry("OFFER", e)));
		if (payout != null) {
			events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("PAYOUT", payout.getId())
					.forEach(e -> rows.add(Map.entry("PAYOUT", e)));
			if (payout.getPaymentId() != null) {
				events.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("PAYOUT_PAYMENT", payout.getPaymentId())
						.forEach(e -> rows.add(Map.entry("PAYOUT_PAYMENT", e)));
			}
		}
		rows.sort(Comparator.comparing(r -> r.getValue().getCreatedAt()));

		List<UUID> actorIds = rows.stream().map(r -> r.getValue().getActorId()).filter(Objects::nonNull)
				.distinct().toList();
		Map<UUID, String> names = actorIds.isEmpty() ? Map.of()
				: members.findAllById(actorIds).stream()
						.collect(Collectors.toMap(TeamMember::getId, TeamMember::getDisplayName, (a, b) -> a));

		return rows.stream().map(r -> {
			AuditEvent e = r.getValue();
			return new Entry(e.getCreatedAt(), who(e, names), what(r.getKey(), e), e.getBeforeSnapshot(),
					e.getAfterSnapshot());
		}).toList();
	}

	private static String who(AuditEvent e, Map<UUID, String> names) {
		if (e.getActorType() == ActorType.EXPERT) {
			return "Expert";
		}
		if (e.getActorId() == null) {
			return "EvalOS";
		}
		return names.getOrDefault(e.getActorId(), "A former team member");
	}

	private static String what(String type, AuditEvent e) {
		String after = e.getAfterSnapshot() == null ? "" : e.getAfterSnapshot();
		return switch (type) {
			case "OFFER" -> e.getAction() == AuditAction.CREATED ? "Fee set"
					: after.contains("\"outcome\":\"ACCEPTED\"") ? "Offer accepted"
					: after.contains("\"outcome\":\"DECLINED\"") ? "Offer declined"
					: after.contains("\"outcome\":\"TIMED_OUT\"") ? "Offer timed out"
					: after.contains("\"outcome\":\"SUPERSEDED\"") ? "Offer superseded"
					: "Fee changed";
			case "PAYOUT" -> e.getAction() == AuditAction.CREATED ? "Payout opened" : "Payout amount set";
			default -> e.getAction() == AuditAction.PAYOUT_SETTLED ? "Transfer recorded"
					: after.contains("\"confirmed\":true") ? "Expert confirmed receipt" : "Transfer details edited";
		};
	}
}
