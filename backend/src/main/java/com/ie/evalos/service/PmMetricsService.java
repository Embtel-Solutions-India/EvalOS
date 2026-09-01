package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.TeamMember;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Project Manager's production figures.
 *
 * <p><strong>Every figure derives from {@link CaseLifecycleService#list}</strong>, the same
 * already-scoped read the board is built on. That is deliberate and is the whole scope story: a
 * dashboard cannot see further than the board does, and there is no second query here whose
 * predicate could drift from the first. {@code CaseBoardService} takes the same approach for the
 * same reason.
 *
 * <p><strong>Computed live, nothing stored</strong>, per {@code 17-dashboards.md}'s storage
 * decision. At the NFR scale — 50–100 cases per brand per month — these are aggregates over a
 * few thousand rows, and a cached figure beside a live board is the staleness bug this project
 * has already logged three times.
 *
 * <p>ponytail: the aggregation runs in Java over the scoped list rather than as SQL
 * {@code GROUP BY}, because the scope predicate lives in the repository and re-expressing it in
 * a projection query is exactly the duplication above. Push these into SQL if the case count per
 * brand ever reaches six figures; the definitions here stay the contract either way.
 */
@Service
public class PmMetricsService {

	private final CaseLifecycleService lifecycle;
	private final TeamMemberQueryService members;
	private final DeadlineRiskCalculator deadlines;
	private final BusinessCalendar calendar;
	private final int casesPerCm;

	PmMetricsService(CaseLifecycleService lifecycle, TeamMemberQueryService members,
			DeadlineRiskCalculator deadlines, BusinessCalendar calendar,
			@Value("${evalos.workload.cases-per-cm}") int casesPerCm) {
		this.lifecycle = lifecycle;
		this.members = members;
		this.deadlines = deadlines;
		this.calendar = calendar;
		this.casesPerCm = casesPerCm;
	}

	/**
	 * A rate and the population it was taken over.
	 *
	 * <p>{@code ratePct} is null rather than 0 when {@code delivered} is 0: no cases delivered is
	 * not the same as none delivered on time, and a tile reading "0%" over an empty month accuses
	 * a team of something that did not happen.
	 *
	 * <p>{@code delivered} travels with it because {@code 17-dashboards.md} requires the
	 * denominator on screen — "100%" over two cases must not read like "100%" over two hundred.
	 */
	public record OnTimeDelivery(int delivered, int onTime, Integer ratePct, Integer deltaPoints) {
	}

	public record ProductCompletion(ServiceType serviceType, int delivered, long medianBusinessHours) {
	}

	public record CmRevisionRate(UUID cmId, String name, int cases, int revised, Integer ratePct) {
	}

	public record CmWorkload(UUID cmId, String name, int active, int capacity) {
	}

	/**
	 * @param unassigned cases that arrived from sales and name no Case Manager. The desired
	 *                   value is zero, which is why it is a count and not a rate.
	 * @param atRiskNow  **not bounded by the window.** "Right now" means now, and applying the
	 *                   header's date filter to it would answer a different question than the
	 *                   tile asks.
	 */
	public record PmMetrics(
			OnTimeDelivery onTime,
			int atRiskNow,
			int unassigned,
			List<ProductCompletion> completionByService,
			List<CmRevisionRate> revisionRateByCm,
			List<CmWorkload> workload) {
	}

	@Transactional(readOnly = true)
	public PmMetrics forCaller(Instant from, Instant to, UUID brandId) {
		List<Case> scoped = lifecycle.list(null, null, null).stream()
				.filter(subject -> brandId == null || brandId.equals(subject.getBrandId()))
				.toList();

		Instant now = Instant.now();
		Map<UUID, String> names = cmNames();

		return new PmMetrics(
				onTime(scoped, from, to),
				atRiskNow(scoped, now),
				(int) scoped.stream().filter(c -> c.getPoolStatus() == PoolStatus.IN_POOL).count(),
				completionByService(scoped, from, to),
				revisionRateByCm(scoped, names),
				workload(scoped, names));
	}

	/**
	 * Delivered on or before the promised date, over everything delivered in the window.
	 *
	 * <p>The comparison period is the window shifted back by its own length, so "vs previous"
	 * always compares like with like whatever range the header is set to.
	 */
	private OnTimeDelivery onTime(List<Case> scoped, Instant from, Instant to) {
		Tally current = tally(scoped, from, to);
		Duration span = Duration.between(from, to);
		Tally previous = tally(scoped, from.minus(span), from);

		Integer delta = current.pct() == null || previous.pct() == null
				? null
				: current.pct() - previous.pct();
		return new OnTimeDelivery(current.delivered, current.onTime, current.pct(), delta);
	}

	private record Tally(int delivered, int onTime) {
		Integer pct() {
			return delivered == 0 ? null : Math.round(onTime * 100f / delivered);
		}
	}

	private static Tally tally(List<Case> scoped, Instant from, Instant to) {
		int delivered = 0;
		int onTime = 0;
		for (Case subject : scoped) {
			Instant date = subject.getDeliveryDate();
			// **Half-open `[from, to)`, matching what `DateWindow.endInstant()` hands us.** It used
			// to be `isAfter(to)` — inclusive — which was harmless while `to` was `Instant.now()`
			// and never a round number. It stopped being harmless when the window became whole
			// calendar days: `to` is now exactly midnight, so an inclusive end puts the boundary
			// instant in this window AND in the next, and puts `from` in both this period and the
			// previous-period comparison below.
			if (date == null || date.isBefore(from) || !date.isBefore(to)) {
				continue;
			}
			delivered++;
			// A delivered case with no deadline was never promised a date, so it cannot be late.
			if (subject.getDeadline() == null || !date.isAfter(subject.getDeadline())) {
				onTime++;
			}
		}
		return new Tally(delivered, onTime);
	}

	/**
	 * Cases whose promised date is in danger, excluding those already at the delivery step.
	 *
	 * <p>Reads {@link DeadlineRiskCalculator} and never {@code SlaStatus}: the board's rail
	 * answers "is this stage slow", this answers "will we miss the date", and the two disagree
	 * often enough that using one for the other would quietly mis-state the tile.
	 */
	private int atRiskNow(List<Case> scoped, Instant now) {
		return (int) scoped.stream()
				// Past QC the letter is finished and only needs sending, so it is not "at risk"
				// in the sense this figure means. Unit 31 split that one stage into three, and all
				// three are past the point where a deadline can still be missed by production.
				.filter(subject -> subject.getCurrentStage() != Stage.READY_TO_DELIVER
						&& subject.getCurrentStage() != Stage.DELIVERED
						&& subject.getCurrentStage() != Stage.CLOSED)
				.map(subject -> deadlines.riskOf(subject, now))
				.filter(risk -> risk == DeadlineRisk.OVERDUE || risk == DeadlineRisk.AT_RISK)
				.count();
	}

	/**
	 * How long a delivered case took end to end, per service type.
	 *
	 * <p><strong>Median, not mean</strong> — one case parked in an exception state for three
	 * months drags a mean into meaninglessness, which is exactly when somebody stops trusting the
	 * tile. On the business calendar, so a case that sat over a holiday weekend is not reported
	 * as slow.
	 */
	private List<ProductCompletion> completionByService(List<Case> scoped, Instant from, Instant to) {
		Map<ServiceType, List<Long>> hours = new LinkedHashMap<>();
		for (Case subject : scoped) {
			Instant date = subject.getDeliveryDate();
			if (date == null || subject.getServiceType() == null
					// Half-open, for the reason `tally` above states at length.
					|| date.isBefore(from) || !date.isBefore(to) || subject.getCreatedAt() == null) {
				continue;
			}
			hours.computeIfAbsent(subject.getServiceType(), key -> new ArrayList<>())
					.add(calendar.elapsedBusinessTime(subject.getCreatedAt(), date).toHours());
		}
		return hours.entrySet().stream()
				.map(entry -> new ProductCompletion(entry.getKey(), entry.getValue().size(),
						median(entry.getValue())))
				.sorted(Comparator.comparing(row -> row.serviceType().name()))
				.toList();
	}

	private static long median(List<Long> values) {
		List<Long> sorted = values.stream().sorted().toList();
		int size = sorted.size();
		if (size == 0) {
			return 0;
		}
		return size % 2 == 1
				? sorted.get(size / 2)
				: (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
	}

	/**
	 * How often a Case Manager's drafts come back for another version.
	 *
	 * <p>Read from {@code draft_version_count > 1} rather than from returned-draft audit rows.
	 * {@code 17-dashboards.md} proposed the audit route and a {@code DRAFT_RETURNED} action that
	 * does not exist in {@code AuditAction}; adding one would record a fact the counter already
	 * carries. One home per fact, and the counter is the home.
	 *
	 * <p>Every assigned Case Manager appears, including those with a clean record — a list that
	 * silently omits them reads as "these are the ones with a problem" and invites the wrong
	 * conclusion about a short list.
	 */
	private List<CmRevisionRate> revisionRateByCm(List<Case> scoped, Map<UUID, String> names) {
		Map<UUID, int[]> byCm = new LinkedHashMap<>();
		for (Case subject : scoped) {
			UUID cm = subject.getAssignedCm();
			if (cm == null) {
				continue;
			}
			int[] counts = byCm.computeIfAbsent(cm, key -> new int[2]);
			counts[0]++;
			if (subject.getDraftVersionCount() > 1) {
				counts[1]++;
			}
		}
		return byCm.entrySet().stream()
				.map(entry -> {
					int cases = entry.getValue()[0];
					int revised = entry.getValue()[1];
					return new CmRevisionRate(entry.getKey(), name(names, entry.getKey()), cases, revised,
							cases == 0 ? null : Math.round(revised * 100f / cases));
				})
				.sorted(Comparator.comparing(CmRevisionRate::name))
				.toList();
	}

	/**
	 * Open cases per Case Manager against the configured capacity.
	 *
	 * <p>Every Case Manager the caller can assign to is listed, not only those already holding
	 * work: a redistribution screen whose empty column is missing cannot show you where to move
	 * a case to.
	 */
	private List<CmWorkload> workload(List<Case> scoped, Map<UUID, String> names) {
		Map<UUID, Integer> active = new LinkedHashMap<>();
		names.keySet().forEach(id -> active.put(id, 0));
		for (Case subject : scoped) {
			UUID cm = subject.getAssignedCm();
			if (cm == null || subject.getCurrentStage() == Stage.CLOSED) {
				continue;
			}
			active.merge(cm, 1, Integer::sum);
		}
		return active.entrySet().stream()
				.map(entry -> new CmWorkload(entry.getKey(), name(names, entry.getKey()),
						entry.getValue(), casesPerCm))
				.sorted(Comparator.comparing(CmWorkload::name))
				.toList();
	}

	private Map<UUID, String> cmNames() {
		Map<UUID, String> names = new LinkedHashMap<>();
		for (TeamMember member : members.assignable(Role.CASE_MANAGER)) {
			names.put(member.getId(), member.getDisplayName());
		}
		return names;
	}

	/**
	 * A case can name a Case Manager who has since been deactivated, and dropping that row would
	 * lose the case's work from the totals. Named for what it is instead.
	 */
	private static String name(Map<UUID, String> names, UUID id) {
		return names.getOrDefault(id, "Former team member");
	}
}
