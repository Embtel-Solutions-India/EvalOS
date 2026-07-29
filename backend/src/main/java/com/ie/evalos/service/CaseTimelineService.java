package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.service.CaseLifecycleService.CaseSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The case timeline, read back out of the append-only audit trail.
 *
 * <p>Nothing here writes. The trail is the record of what happened and cannot be edited
 * (invariant 13); this is the first thing in EvalOS that reads it, and it stays a projection
 * — it never returns the stored snapshot JSON.
 *
 * <p><strong>That projection is what keeps restricted fields out.</strong> Each snapshot is
 * parsed into the typed {@link CaseSnapshot} and only three of its components are surfaced,
 * so a field the caller may not see cannot arrive by accident — including one added to the
 * snapshot later. {@code CaseSnapshot} has never carried {@code deal_value}, which is the
 * field invariant 3 restricts, and {@code DomainInvariantsTest} holds it that way.
 */
@Service
public class CaseTimelineService {

	private static final Logger log = LoggerFactory.getLogger(CaseTimelineService.class);

	private static final String OBJECT_TYPE = "CASE";

	/** Shown for a row the system wrote — an inbound webhook, or a scheduled job later. */
	private static final String SYSTEM_ACTOR = "System";

	/**
	 * One thing that happened to the case.
	 *
	 * @param at        when, from the same clock as every other row (see {@code AuditEvent})
	 * @param actorName who, or {@code System}
	 * @param stage     the stage the case was in afterwards, null if the row predates the
	 *                  current snapshot shape
	 * @param note      whatever the actor typed — a hold reason, a decline reason, revision
	 *                  notes, an invoice reference. Free text, and the point of the entry.
	 */
	public record TimelineEntry(
			Instant at,
			String actorName,
			AuditAction action,
			Stage stage,
			ExceptionState exceptionState,
			String note) {
	}

	private final CaseLifecycleService lifecycle;
	private final AuditEventRepository auditEvents;
	private final TeamMemberRepository teamMembers;
	private final ObjectMapper objectMapper;

	CaseTimelineService(CaseLifecycleService lifecycle, AuditEventRepository auditEvents,
			TeamMemberRepository teamMembers, ObjectMapper objectMapper) {
		this.lifecycle = lifecycle;
		this.auditEvents = auditEvents;
		this.teamMembers = teamMembers;
		this.objectMapper = objectMapper;
	}

	/**
	 * Oldest first, because a timeline is read forwards.
	 *
	 * <p>The scoped load comes first and is the whole authorization story: an out-of-scope
	 * case answers 403 before a single audit row is fetched, so this cannot become a way to
	 * read another brand's history by guessing a case id.
	 */
	@Transactional(readOnly = true)
	public List<TimelineEntry> forCase(UUID caseId) {
		Case subject = lifecycle.read(caseId);

		List<AuditEvent> rows = auditEvents.findByObjectTypeAndObjectIdOrderByCreatedAtAsc(
				OBJECT_TYPE, subject.getId());
		Map<UUID, String> actors = actorNames(rows, subject.getBrandId());

		return rows.stream().map(row -> entry(row, actors)).toList();
	}

	private TimelineEntry entry(AuditEvent row, Map<UUID, String> actors) {
		Optional<CaseSnapshot> after = parse(row.getAfterSnapshot());
		return new TimelineEntry(
				row.getCreatedAt(),
				row.getActorId() == null ? SYSTEM_ACTOR : actors.getOrDefault(row.getActorId(), SYSTEM_ACTOR),
				row.getAction(),
				after.map(CaseSnapshot::stage).orElse(null),
				after.map(CaseSnapshot::exceptionState).orElse(null),
				after.map(CaseSnapshot::note).orElse(null));
	}

	/**
	 * A row whose snapshot will not parse still becomes an entry.
	 *
	 * <p>Audit rows are permanent and the snapshot shape evolves — {@code assignedCoordinator}
	 * was added to it in Unit 08, and a strategy-notes edit records a different record
	 * entirely. Letting one unparseable row throw would take out the whole timeline, which is
	 * the opposite of what an append-only trail is for. The action, actor and timestamp live
	 * in real columns, so those survive regardless.
	 */
	private Optional<CaseSnapshot> parse(String json) {
		if (json == null || json.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(objectMapper.readValue(json, CaseSnapshot.class));
		}
		catch (Exception ex) {
			log.debug("Audit snapshot not readable as a case snapshot; showing the row without it", ex);
			return Optional.empty();
		}
	}

	/**
	 * One query for every actor on the timeline rather than one per row, narrowed to the brand
	 * that owns the case.
	 *
	 * <p>Brand-scoped at the query, not by the caller's tier. {@code ScopePredicate} would be the
	 * wrong tool here: it applies the *caller's* tier, and a Case Manager is {@code Tier.SELF} —
	 * so a CM reading their own case's history would resolve only their own name and see every
	 * colleague as "System". The case's brand is the correct axis, the same reasoning the
	 * notification centre records for not using {@code findScoped}.
	 *
	 * <p>A null {@code brand_id} is included deliberately: the GM is the one brand-less member
	 * (see {@code TeamMember}'s CHECK constraint) and a GM who acted on the case is a real actor
	 * whose name must resolve.
	 */
	private Map<UUID, String> actorNames(List<AuditEvent> rows, UUID brandId) {
		List<UUID> actorIds = rows.stream()
				.map(AuditEvent::getActorId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		if (actorIds.isEmpty()) {
			return Map.of();
		}
		Specification<TeamMember> inThisBrand = (root, query, cb) -> cb.and(
				root.get("id").in(actorIds),
				cb.or(cb.equal(root.get("brandId"), brandId), cb.isNull(root.get("brandId"))));
		return teamMembers.findAll(inThisBrand).stream()
				.collect(Collectors.toMap(TeamMember::getId, TeamMember::getDisplayName, (first, second) -> first));
	}
}
