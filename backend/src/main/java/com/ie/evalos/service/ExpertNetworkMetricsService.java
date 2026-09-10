package com.ie.evalos.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Expert Network Manager's supply-side figures.
 *
 * <p><strong>Reads the roster and the offer ledger, and never a case.</strong> That is the axis
 * {@code architecture.md} draws and the Prerequisite fix now enforces at the field level: no
 * client name, no case content, no deal value appears anywhere in this service's output. An
 * expert's *load* arrives as a count from {@link ExpertLoadService}, which is a number about the
 * expert rather than a fact about anyone's case.
 */
@Service
public class ExpertNetworkMetricsService {

	/**
	 * A field with fewer available experts than this is a single point of failure worth showing
	 * before it bites. The business's number, not a derived one.
	 */
	private static final int COVERAGE_FLOOR = 5;

	/** Two declines is the point the business wants an expert looked at, not automatically judged. */
	private static final int DECLINE_ATTENTION = 2;

	/**
	 * Below this the ENM wants to look. The scale is 1–10 (`V7`), so this is the bottom third.
	 *
	 * <p>Stated here rather than in a query because it is a judgement about the roster, and the
	 * ENM is the person who owns it — if the bar moves, it moves in one place.
	 */
	private static final java.math.BigDecimal LOW_QUALITY_BELOW = new java.math.BigDecimal("6.0");

	private final ExpertRepository experts;
	private final ExpertCaseOfferRepository offers;
	private final ExpertLoadService loads;
	private final int monthlyTarget;

	ExpertNetworkMetricsService(ExpertRepository experts, ExpertCaseOfferRepository offers,
			ExpertLoadService loads, @Value("${evalos.roster.monthly-onboarding-target}") int monthlyTarget) {
		this.experts = experts;
		this.offers = offers;
		this.loads = loads;
		this.monthlyTarget = monthlyTarget;
	}

	public record RosterHealth(int available, int atCapacity, int onLeave, int inactive, int total) {
	}

	/**
	 * A field of expertise and how the bench there is made up.
	 *
	 * <p>The build spec asks for **available vs at-capacity vs inactive per field**, not just a
	 * headline count — because they mean different things to act on: at-capacity is work the
	 * network could take next week, inactive is a recruiting problem, and only `available` counts
	 * toward the gap rule.
	 *
	 * @param gap the business's rule: fewer than five **available**. At-capacity experts are
	 *            deliberately not counted toward it — a field whose whole bench is full cannot
	 *            staff the next case that arrives.
	 */
	public record FieldCoverage(FieldTag field, int available, int atCapacity, int inactive, int total,
			boolean gap) {
	}

	/**
	 * An expert whose quality score is below the bar the ENM works to.
	 *
	 * <p>Human-entered and unversioned, so this is the current reading and never a trend — see the
	 * accepted limitation on quality-score history.
	 */
	public record LowQualityExpert(UUID expertId, String name, java.math.BigDecimal qualityScore) {
	}

	public record Onboarding(int thisMonth, int target) {
	}

	/**
	 * @param ratePct   null rather than 0 when no offer has ever resolved — an expert nobody has
	 *                  asked is not an expert who refuses.
	 * @param resolved  the denominator, for the reason every rate here carries one.
	 */
	public record Acceptance(Integer ratePct, int resolved) {
	}

	public record DecliningExpert(UUID expertId, String name, int declines) {
	}

	/**
	 * How long the roster takes to answer an offer — <strong>gap G9, closed by derivation</strong>.
	 *
	 * <p>{@code expert.avg_response_hours} existed, was written by nothing, and read as
	 * permanently null. G9's instruction was not to revive it but to derive the figure from the
	 * offer ledger, where both timestamps have lived since {@code V19}.
	 *
	 * @param medianHours the <em>median</em>, not the mean: over a handful of offers a single
	 *                    expert who answered after a fortnight drags a mean somewhere nobody
	 *                    recognises. Null when nothing has been resolved yet — never zero, which
	 *                    would read as "answered instantly"
	 * @param resolved    how many offers the figure is over, so a median of one is visible as one
	 */
	public record Turnaround(Long medianHours, int resolved) {
	}

	public record ExpertNetworkMetrics(
			RosterHealth roster,
			List<FieldCoverage> coverage,
			Onboarding onboarding,
			Acceptance acceptance,
			Turnaround turnaround,
			List<DecliningExpert> declining,
			List<LowQualityExpert> lowQuality,
			int activeCases) {
	}

	@Transactional(readOnly = true)
	public ExpertNetworkMetrics forCaller() {
		TenantContext ctx = TenantContext.current();
		List<Expert> roster = experts.findScoped(ctx);

		Map<UUID, ExpertLoadService.Load> load = loads.forExperts(roster.stream().map(Expert::getId).toList());
		int activeCases = load.values().stream().mapToInt(ExpertLoadService.Load::active).sum();

		// One read of the offer ledger, two readers. `acceptance` and `declining` ask different
		// questions of exactly the same rows — same brand, same expert ids — so issuing the query
		// twice bought nothing and gave the two figures a chance to be taken a moment apart.
		List<Object[]> outcomes = roster.isEmpty() || ctx.brandId() == null
				? List.of()
				: offers.countOutcomesPerExpert(ctx.brandId(), roster.stream().map(Expert::getId).toList());

		List<Double> turnarounds = roster.isEmpty() || ctx.brandId() == null
				? List.of()
				: offers.resolvedTurnaroundSeconds(ctx.brandId(),
						roster.stream().map(Expert::getId).toList());

		return new ExpertNetworkMetrics(
				health(roster),
				coverage(roster),
				onboarding(roster),
				acceptance(outcomes),
				turnaround(turnarounds),
				declining(outcomes, roster),
				lowQuality(roster),
				activeCases);
	}

	private static RosterHealth health(List<Expert> roster) {
		Map<Availability, Integer> counts = new EnumMap<>(Availability.class);
		for (Expert expert : roster) {
			counts.merge(expert.availabilityOrInactive(), 1, Integer::sum);
		}
		return new RosterHealth(
				counts.getOrDefault(Availability.AVAILABLE, 0),
				counts.getOrDefault(Availability.AT_CAPACITY, 0),
				counts.getOrDefault(Availability.ON_LEAVE, 0),
				counts.getOrDefault(Availability.INACTIVE, 0),
				roster.size());
	}

	/**
	 * Availability per field of expertise.
	 *
	 * <p>Counted over **primary** fields only. A secondary tag says an expert could be asked at a
	 * stretch, and counting it would report cover this network does not really have — which is the
	 * opposite of what an alert with a floor of five is for.
	 *
	 * <p>Every field an expert claims is listed, including ones with full cover: a list showing
	 * only the gaps reads as the whole taxonomy and hides how narrow the roster is elsewhere.
	 */
	private static List<FieldCoverage> coverage(List<Expert> roster) {
		// [available, atCapacity, inactive, total] per field.
		Map<FieldTag, int[]> byField = new HashMap<>();
		for (Expert expert : roster) {
			for (FieldTag field : expert.getPrimaryFields()) {
				int[] counts = byField.computeIfAbsent(field, key -> new int[4]);
				counts[3]++;
				switch (expert.availabilityOrInactive()) {
					case AVAILABLE -> counts[0]++;
					case AT_CAPACITY -> counts[1]++;
					// ON_LEAVE sits with INACTIVE here on purpose: for staffing the next case they
					// are the same answer, and the roster screen is where the difference matters.
					case INACTIVE, ON_LEAVE -> counts[2]++;
				}
			}
		}
		return byField.entrySet().stream()
				.map(entry -> new FieldCoverage(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
						entry.getValue()[2], entry.getValue()[3],
						entry.getValue()[0] < COVERAGE_FLOOR))
				// Thinnest cover first: the list is an alert, so the thing to act on is at the top.
				.sorted(Comparator.comparingInt(FieldCoverage::available)
						.thenComparing(row -> row.field().name()))
				.toList();
	}

	/**
	 * Experts scoring below the bar, worst first.
	 *
	 * <p>An unscored expert is <em>not</em> low quality — they are unassessed, and treating a null
	 * as a zero would put every newly imported expert on a list the ENM reads as a problem.
	 */
	private static List<LowQualityExpert> lowQuality(List<Expert> roster) {
		return roster.stream()
				.filter(expert -> expert.getQualityScore() != null
						&& expert.getQualityScore().compareTo(LOW_QUALITY_BELOW) < 0)
				.map(expert -> new LowQualityExpert(expert.getId(), expert.getFullName(),
						expert.getQualityScore()))
				.sorted(Comparator.comparing(LowQualityExpert::qualityScore))
				.toList();
	}

	private Onboarding onboarding(List<Expert> roster) {
		LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
		int thisMonth = (int) roster.stream()
				.filter(expert -> expert.getDateOnboarded() != null
						&& !expert.getDateOnboarded().isBefore(monthStart))
				.count();
		return new Onboarding(thisMonth, monthlyTarget);
	}

	/**
	 * Fleet-wide acceptance rate.
	 *
	 * <p>Built from {@code countOutcomesPerExpert} and {@link OfferOutcome#countsTowardAcceptanceRate},
	 * **the same expressions {@code ExpertMatchService} scores with**. Two definitions of an
	 * acceptance rate is how this tile and a shortlist come to disagree about the same person.
	 */
	/**
	 * The median hours between an offer going out and being answered (G9).
	 *
	 * <p>Median rather than mean, and stated as a decision: these samples are few and heavily
	 * skewed — most experts answer within a day and one answers in three weeks — so a mean
	 * reports a number nobody in the room recognises.
	 *
	 * <p>Null on no data rather than zero. Zero would read as "answered instantly", which is the
	 * opposite of "we do not know yet", and this is exactly the kind of figure a dashboard is
	 * asked to justify.
	 */
	private static Turnaround turnaround(List<Double> seconds) {
		if (seconds.isEmpty()) {
			return new Turnaround(null, 0);
		}
		List<Double> sorted = seconds.stream().filter((value) -> value != null).sorted().toList();
		if (sorted.isEmpty()) {
			return new Turnaround(null, 0);
		}
		int middle = sorted.size() / 2;
		double median = sorted.size() % 2 == 1
				? sorted.get(middle)
				: (sorted.get(middle - 1) + sorted.get(middle)) / 2;
		return new Turnaround(Math.round(median / 3600), sorted.size());
	}

	private static Acceptance acceptance(List<Object[]> outcomes) {
		int accepted = 0;
		int resolved = 0;
		for (Object[] row : outcomes) {
			OfferOutcome outcome = (OfferOutcome) row[1];
			if (!outcome.countsTowardAcceptanceRate()) {
				continue;
			}
			int count = ((Number) row[2]).intValue();
			resolved += count;
			if (outcome == OfferOutcome.ACCEPTED) {
				accepted += count;
			}
		}
		return new Acceptance(resolved == 0 ? null : Math.round(accepted * 100f / resolved), resolved);
	}

	/**
	 * Experts who have turned down two or more cases.
	 *
	 * <p>Read from the offer ledger rather than from {@code expert.performance_flags} — the
	 * tracker's own guidance, and the honest source: a decline is a recorded event, whereas the
	 * flag column is a human judgement about one.
	 */
	private static List<DecliningExpert> declining(List<Object[]> outcomes, List<Expert> roster) {
		Map<UUID, String> names = new HashMap<>();
		roster.forEach(expert -> names.put(expert.getId(), expert.getFullName()));

		List<DecliningExpert> declining = new ArrayList<>();
		for (Object[] row : outcomes) {
			if (row[1] != OfferOutcome.DECLINED) {
				continue;
			}
			int count = ((Number) row[2]).intValue();
			if (count >= DECLINE_ATTENTION) {
				declining.add(new DecliningExpert((UUID) row[0], names.get((UUID) row[0]), count));
			}
		}
		declining.sort(Comparator.comparingInt(DecliningExpert::declines).reversed());
		return declining;
	}
}
