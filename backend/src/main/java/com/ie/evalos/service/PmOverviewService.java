package com.ie.evalos.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where the Project Manager's work is sitting, how long it has sat, and what is waiting on them.
 *
 * <p>Like {@link PmMetricsService}, every figure derives from its already-scoped case read — there
 * is no second scope predicate to drift. Computed live, nothing stored. Counts are <em>cases</em>.
 *
 * <p>ponytail: in-memory grouping over the scoped list, as {@code PmMetricsService} does; move to SQL
 * only when a brand reaches six-figure case counts.
 */
@Service
public class PmOverviewService {

	/** Queue rows returned per queue; the screen links to the full queue for the rest. */
	static final int QUEUE_CAP = 25;
	/** Daily buckets up to this many days, ISO-week buckets beyond. */
	static final int DAILY_MAX_DAYS = 31;

	private final PmMetricsService pm;
	private final TeamMemberQueryService members;
	private final DeadlineRiskCalculator deadlines;
	private final BusinessCalendar calendar;

	PmOverviewService(PmMetricsService pm, TeamMemberQueryService members,
			DeadlineRiskCalculator deadlines, BusinessCalendar calendar) {
		this.pm = pm;
		this.members = members;
		this.deadlines = deadlines;
		this.calendar = calendar;
	}

	/** {@code medianAgeBusinessHours} is null when no case in the stage has a stage-entry time; DELIVERED has none. */
	public record StageCount(Stage stage, int count, Long medianAgeBusinessHours) {
	}

	public record QueueRow(UUID caseId, String caseCode, ServiceType serviceType, String ownerName,
			Instant deadline, DeadlineRisk risk, Long waitingBusinessHours) {
	}

	public record Queues(List<QueueRow> draftReview, List<QueueRow> finalQc) {
	}

	public record Throughput(LocalDate bucket, int delivered) {
	}

	/**
	 * @param stages     the 11 funnel stages in pipeline order. Ten are live; {@code DELIVERED} counts
	 *                   deliveries inside the window.
	 * @param blocked    open cases with an exception state — see D79
	 * @param throughput zero-filled: a day with no deliveries is a real zero, unlike an unmeasured metric
	 */
	public record PmOverview(List<StageCount> stages, int active, int blocked, int awaitingReview,
			int awaitingQc, int readyToDeliver, Queues queues, List<Throughput> throughput) {
	}

	@Transactional(readOnly = true)
	public PmOverview forCaller(Instant from, Instant to, UUID brandId) {
		List<Case> scoped = pm.scoped(brandId);
		Instant now = Instant.now();

		Map<Stage, List<Case>> byStage = new EnumMap<>(Stage.class);
		for (Case subject : scoped) {
			byStage.computeIfAbsent(subject.getCurrentStage(), key -> new ArrayList<>()).add(subject);
		}

		List<StageCount> stages = new ArrayList<>();
		for (Stage stage : Stage.values()) {
			if (stage == Stage.CLOSED) {
				continue;
			}
			List<Case> inStage = byStage.getOrDefault(stage, List.of());
			if (stage == Stage.DELIVERED) {
				// Delivered in the window includes a case since CLOSED: it was delivered, and the throughput
				// chart beside this counts it, so the two must agree.
				int delivered = (int) Stream.concat(inStage.stream(), byStage.getOrDefault(Stage.CLOSED, List.of()).stream())
						.filter(c -> inWindow(c, from, to)).count();
				stages.add(new StageCount(stage, delivered, null));
			} else {
				stages.add(new StageCount(stage, inStage.size(), medianAge(inStage, now)));
			}
		}

		List<Case> open = scoped.stream()
				.filter(c -> c.getCurrentStage() != Stage.DELIVERED && c.getCurrentStage() != Stage.CLOSED)
				.toList();

		Map<UUID, String> cmNames = names(Role.CASE_MANAGER);
		Map<UUID, String> pmNames = names(Role.PROJECT_MANAGER);

		return new PmOverview(
				stages,
				open.size(),
				(int) open.stream().filter(c -> c.getExceptionState() != ExceptionState.NONE).count(),
				byStage.getOrDefault(Stage.DRAFT_REVIEW, List.of()).size(),
				byStage.getOrDefault(Stage.FINAL_QC, List.of()).size(),
				byStage.getOrDefault(Stage.READY_TO_DELIVER, List.of()).size(),
				new Queues(
						// Draft review waits on the PM but the draft is the CM's work — the owner shown is its author.
						queue(byStage.get(Stage.DRAFT_REVIEW), Case::getAssignedCm, cmNames, now),
						queue(byStage.get(Stage.FINAL_QC), Case::getAssignedPm, pmNames, now)),
				throughput(scoped, from, to));
	}

	private static boolean inWindow(Case subject, Instant from, Instant to) {
		Instant date = subject.getDeliveryDate();
		// Half-open [from, to), for the reason PmMetricsService.tally states at length.
		return date != null && !date.isBefore(from) && date.isBefore(to);
	}

	private Long medianAge(List<Case> inStage, Instant now) {
		List<Long> hours = inStage.stream()
				.filter(c -> c.getStageEnteredAt() != null)
				.map(c -> calendar.elapsedBusinessTime(c.getStageEnteredAt(), now).toHours())
				.sorted()
				.toList();
		if (hours.isEmpty()) {
			return null;
		}
		int size = hours.size();
		return size % 2 == 1 ? hours.get(size / 2) : (hours.get(size / 2 - 1) + hours.get(size / 2)) / 2;
	}

	private List<QueueRow> queue(List<Case> cases, Function<Case, UUID> owner,
			Map<UUID, String> names, Instant now) {
		if (cases == null) {
			return List.of();
		}
		return cases.stream()
				.map(c -> new QueueRow(c.getId(), c.getCaseCode(), c.getServiceType(),
						owner.apply(c) == null ? null : PmMetricsService.name(names, owner.apply(c)),
						c.getDeadline(), deadlines.riskOf(c, now),
						c.getStageEnteredAt() == null ? null
								: calendar.elapsedBusinessTime(c.getStageEnteredAt(), now).toHours()))
				// Longest-waiting first; a case with no entry time has no wait to rank, so it goes last.
				.sorted(Comparator.comparing(QueueRow::waitingBusinessHours,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(QUEUE_CAP)
				.toList();
	}

	private List<Throughput> throughput(List<Case> scoped, Instant from, Instant to) {
		ZoneId zone = BusinessCalendar.ZONE;
		LocalDate first = from.atZone(zone).toLocalDate();
		LocalDate last = to.minusNanos(1).atZone(zone).toLocalDate();
		boolean daily = ChronoUnit.DAYS.between(first, last) < DAILY_MAX_DAYS;

		Map<LocalDate, Integer> buckets = new LinkedHashMap<>();
		LocalDate cursor = daily ? first : first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		while (!cursor.isAfter(last)) {
			buckets.put(cursor, 0);
			cursor = cursor.plusDays(daily ? 1 : 7);
		}
		for (Case subject : scoped) {
			if (!inWindow(subject, from, to)) {
				continue;
			}
			LocalDate day = subject.getDeliveryDate().atZone(zone).toLocalDate();
			buckets.merge(daily ? day : day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
					1, Integer::sum);
		}
		return buckets.entrySet().stream().map(e -> new Throughput(e.getKey(), e.getValue())).toList();
	}

	private Map<UUID, String> names(Role role) {
		Map<UUID, String> names = new LinkedHashMap<>();
		for (TeamMember member : members.assignable(role)) {
			names.put(member.getId(), member.getDisplayName());
		}
		return names;
	}
}
