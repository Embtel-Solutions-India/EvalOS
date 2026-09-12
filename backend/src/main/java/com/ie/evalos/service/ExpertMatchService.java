package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.ToDoubleBiFunction;

import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PerformanceFlag;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ExpertLoadService.Load;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ranks a brand's roster for one case: a top-3 shortlist, with the arithmetic shown.
 *
 * <p><strong>Assist mode, and the word is load-bearing.</strong> This engine suggests and a
 * human confirms. There is no auto-assign anywhere in EvalOS, and the shortlist is never a
 * precondition — {@code CaseLifecycleService.assignCaseManager} neither knows nor cares whether
 * the expert it is given was on the list, so a PM who wants the fourth-ranked expert simply
 * picks them.
 *
 * <p><strong>The required field comes from the PM, not from the case.</strong> A case has
 * {@code service_type}, {@code service_subtype} and {@code visa_category}; it has no field tag,
 * and nothing in the system records that a case is a mechanical-engineering matter. The tag is
 * an argument here rather than a column because the PM has just read the documents and written
 * the strategy notes — they are the only person who knows what discipline the case needs, and
 * they know it at exactly this moment. A column would have to be filled at intake by a GHL
 * webhook that carries no such thing, and would then be a stale guess worked around. If a later
 * unit finds a second consumer, the column can be added then, with a real source. Recorded as a
 * deliberate omission, not an oversight.
 *
 * <p>Reads go through {@link ExpertRepository#findScoped} and the case through
 * {@link CaseLifecycleService#read} — no new scoped query and no second scoping path, per the
 * rule {@code CaseBoardService} and {@code ChecklistService} both follow.
 */
@Service
public class ExpertMatchService {

	/** How many experts a shortlist offers. Three is the build plan's number. */
	private static final int SHORTLIST_SIZE = 3;

	/**
	 * How many resolved offers an expert needs before their own acceptance rate is used
	 * instead of the roster's mean.
	 *
	 * <p>The cold-start trap: a brand-new expert has no record, a zero would put them
	 * permanently last, and being last is what stops them ever getting the case that would give
	 * them a record. Below the threshold they score the roster's mean, which is neither a
	 * reward nor a penalty for having no history.
	 */
	private static final int ACCEPTANCE_HISTORY_THRESHOLD = 3;

	/**
	 * Used when no expert on the roster has enough history for a mean to be taken from. It is a
	 * constant across the whole shortlist, so it cannot change anybody's ranking — the honest
	 * value is the one that says nothing.
	 */
	private static final double NEUTRAL_ACCEPTANCE = 0.5;

	/**
	 * {@code ServiceType → LetterType}, declared rather than derived.
	 *
	 * <p>Not a {@code valueOf} across the two enums: {@code TRANSLATION} and
	 * {@code TRANSLATION_CERTIFICATION} are the same matter under two names, so a name-based
	 * conversion would throw on exactly the pair that does not line up. {@code LetterType} is
	 * narrower than {@code ServiceType} on purpose — it is the expert's signing appetite, not
	 * the catalogue EvalOS sells — and this map is where the two meet.
	 */
	/**
	 * <strong>Every {@link ServiceType} has to appear here.</strong> An unmapped one resolves to
	 * a null letter type, which makes every expert on the roster ineligible — a silent empty
	 * shortlist rather than an error, so a service type added without its entry looks like a
	 * roster with nobody on it. Unit 33's two additions are mapped for that reason.
	 */
	private static final Map<ServiceType, LetterType> LETTER_FOR_SERVICE = new EnumMap<>(Map.of(
			ServiceType.CREDENTIAL_EVALUATION, LetterType.CREDENTIAL_EVALUATION,
			ServiceType.EXPERT_OPINION_LETTER, LetterType.EXPERT_OPINION_LETTER,
			ServiceType.PERM, LetterType.PERM_LETTER,
			ServiceType.RFE_RESPONSE, LetterType.RFE_RESPONSE,
			ServiceType.TRANSLATION, LetterType.TRANSLATION_CERTIFICATION,
			ServiceType.RECOMMENDATION_LETTER, LetterType.RECOMMENDATION_LETTER,
			ServiceType.WAGE_LEVEL_LETTER, LetterType.WAGE_LEVEL_LETTER));

	/** What the case needs, resolved once and handed to every factor. */
	record Requirement(FieldTag fieldTag, LetterType letterType) {
	}

	/**
	 * Everything a factor needs about one expert that is not on the expert row.
	 *
	 * @param ownAcceptanceRate whether {@code acceptanceRate} is this expert's own record or the
	 *                          roster mean stood in for it. Carried so the explanation can say
	 *                          which — the breakdown exists for a PM to disagree with, and
	 *                          reporting the roster's rate as the newcomer's own is the one row
	 *                          that would assert something the data does not say
	 */
	record Evidence(Load load, double acceptanceRate, boolean ownAcceptanceRate) {
	}

	/**
	 * One factor's contribution, as the PM is shown it.
	 *
	 * <p>{@code earned} and {@code weight} are both here so the card can draw the fraction, and
	 * {@code why} is here because an unexplained ranking gets ignored — a PM told only that an
	 * expert scored 71 has no way to disagree with it.
	 */
	public record Factor(String label, int weight, int earned, String why) {
	}

	/**
	 * One ranked expert.
	 *
	 * @param flags shown, never scored. Folding a {@code CLIENT_COMPLAINT} into a number hides
	 *              the one thing a human should see before assigning
	 */
	public record ScoredExpert(Expert expert, int score, List<Factor> factors, List<PerformanceFlag> flags,
			int activeLoad) {
	}

	/**
	 * The answer, empty or not.
	 *
	 * @param emptyReason names which factor emptied the list, and is null when it is not empty.
	 *                    "No matches" is not actionable; "no available expert carries the
	 *                    Mechanical Engineering tag" tells the ENM to recruit or to free
	 *                    somebody up. Same rule as Unit 08's empty dropdown
	 */
	public record Shortlist(List<ScoredExpert> experts, String emptyReason) {
	}

	/**
	 * The four factors and their weights, as one table.
	 *
	 * <p>Held as data rather than four branches for the reason {@code NotificationListeners} and
	 * {@code navigation.ts} give: a weight that lives in a literal table is a data diff when the
	 * business changes its mind about it, and the four cannot drift out of agreement about how
	 * many points are on the table.
	 *
	 * <p>Each row returns a fraction in {@code [0, 1]}; the points earned are
	 * {@code round(weight × fraction)}, and the score is the sum of those — so the breakdown the
	 * PM is shown adds up to the score they are shown, by construction rather than by
	 * coincidence.
	 */
	private record Weighted(String label, int weight, ToDoubleBiFunction<Expert, Scored> fraction,
			Explainer why) {
	}

	private interface Explainer {

		String describe(Expert expert, Scored context);
	}

	/** An expert plus the evidence about them, which is what the factor rows read. */
	private record Scored(Requirement requirement, Evidence evidence) {
	}

	private static final List<Weighted> FACTORS = List.of(
			new Weighted("Field match", 40,
					(expert, at) -> fieldMatch(expert, at.requirement().fieldTag()),
					(expert, at) -> describeFieldMatch(fieldMatch(expert, at.requirement().fieldTag()))),
			new Weighted("Letter-type experience", 25,
					(expert, at) -> signsLetterType(expert, at.requirement().letterType()) ? 1 : 0,
					(expert, at) -> at.requirement().letterType() == null
							? "The case names no service type"
							: signsLetterType(expert, at.requirement().letterType())
									? "Signs this letter type"
									: "Does not sign this letter type"),
			new Weighted("Acceptance rate", 20,
					(expert, at) -> at.evidence().acceptanceRate(),
					(expert, at) -> at.evidence().ownAcceptanceRate()
							? "%.0f%% of resolved offers accepted"
									.formatted(at.evidence().acceptanceRate() * 100)
							: "Too few resolved offers — scored at the roster average"),
			new Weighted("Current load", 15,
					(expert, at) -> loadFraction(at.evidence().load().active()),
					(expert, at) -> at.evidence().load().active() == 0
							? "No open cases"
							: at.evidence().load().active() + " open case(s)"));

	/**
	 * Orders equal scores: the better quality score first, then by name so the list is stable.
	 *
	 * <p><strong>{@code quality_score} is a tie-break, not a fifth factor.</strong> The build
	 * plan names four factors, and quality is a human judgement already reflected in the
	 * expert's tier and in whether the ENM keeps them {@code AVAILABLE} at all. Giving it its
	 * own weight would count the same opinion twice.
	 */
	private static final Comparator<ScoredExpert> RANKING =
			Comparator.comparingInt(ScoredExpert::score).reversed()
					.thenComparing(scored -> scored.expert().getQualityScore(),
							Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
					.thenComparing(scored -> scored.expert().getFullName(),
							Comparator.nullsLast(String::compareToIgnoreCase));

	private final CaseLifecycleService cases;
	private final ExpertRepository experts;
	private final ExpertLoadService loads;
	private final ExpertCaseOfferRepository offers;

	ExpertMatchService(CaseLifecycleService cases, ExpertRepository experts, ExpertLoadService loads,
			ExpertCaseOfferRepository offers) {
		this.cases = cases;
		this.experts = experts;
		this.loads = loads;
		this.offers = offers;
	}

	/**
	 * The shortlist for one case.
	 *
	 * <p>The case is loaded through {@link CaseLifecycleService#read}, so another brand's case —
	 * or, for a Case Manager, one that is not theirs — is refused there rather than here, and
	 * the roster is read through {@code findScoped} against the same caller. The brand filter on
	 * top of that is the case's own brand: a GM reading a case in one brand must not be offered
	 * a higher-scoring expert from the other, because the transition would refuse them.
	 */
	@Transactional(readOnly = true)
	public Shortlist shortlist(UUID caseId, FieldTag fieldTag) {
		Case subject = cases.read(caseId);
		Requirement requirement = new Requirement(fieldTag, letterTypeFor(subject.getServiceType()));

		List<Expert> roster = experts.findScoped(TenantContext.current()).stream()
				.filter(expert -> subject.getBrandId().equals(expert.getBrandId()))
				.toList();
		if (roster.isEmpty()) {
			return new Shortlist(List.of(), "this brand has no experts on its roster yet");
		}

		// Eligibility is a filter, not a low score: CaseLifecycleService.availableExpert refuses
		// anything other than AVAILABLE, so a shortlist offering one would be offering what the
		// write side rejects. The same rule Unit 08 applied to the picker — and the same reason
		// the expert already on the case is dropped: reassignExpert refuses "the expert who
		// declined", so ranking them first for a case in EXPERT_DECLINED_REMATCHING is proposing
		// a 409. On a case with no expert yet the second filter is a no-op.
		List<Expert> eligible = roster.stream()
				.filter(expert -> expert.getAvailability() == Availability.AVAILABLE)
				.filter(expert -> !expert.getId().equals(subject.getExpertId()))
				.toList();
		if (eligible.isEmpty()) {
			return new Shortlist(List.of(), "no expert in this brand is available for this case right now");
		}

		List<ScoredExpert> ranked = score(eligible, requirement, subject.getBrandId()).stream()
				// An expert who scores nothing at all on the 40-point field factor is not a
				// suggestion — proposing a physicist for a nursing matter is noise, and it is what
				// makes the empty state below able to name the tag. They are not *forbidden*: the
				// full picker sits under the shortlist and assigns anybody available.
				//
				// Asked of the field factor directly, not of factors().getFirst(): FACTORS is data
				// so its rows can be reordered in a data diff, and a positional read would then
				// quietly point this filter at whichever factor moved to index 0 — "Current load"
				// is never 0, so the gate would become a no-op with nothing failing to say so.
				.filter(scored -> fieldMatch(scored.expert(), requirement.fieldTag()) > 0)
				.sorted(RANKING)
				.limit(SHORTLIST_SIZE)
				.toList();

		return ranked.isEmpty()
				? new Shortlist(List.of(), "no available expert carries the " + label(fieldTag) + " tag")
				: new Shortlist(ranked, null);
	}

	/**
	 * The scoring pass, separated from the reads so it is testable as what it is: a pure
	 * function of the experts plus the evidence about them.
	 */
	private List<ScoredExpert> score(List<Expert> eligible, Requirement requirement, UUID brandId) {
		Map<UUID, Evidence> evidence = evidenceFor(eligible, brandId);
		return eligible.stream().map(expert -> score(expert, requirement, evidence.get(expert.getId()))).toList();
	}

	/** One expert's score and its four rows. Pure. */
	static ScoredExpert score(Expert expert, Requirement requirement, Evidence evidence) {
		Scored context = new Scored(requirement, evidence);
		List<Factor> factors = FACTORS.stream()
				.map(factor -> new Factor(factor.label(), factor.weight(),
						(int) Math.round(factor.weight() * factor.fraction().applyAsDouble(expert, context)),
						factor.why().describe(expert, context)))
				.toList();

		return new ScoredExpert(expert, factors.stream().mapToInt(Factor::earned).sum(), factors,
				performanceFlags(expert), evidence.load().active());
	}

	// --- the factors ---------------------------------------------------------

	/** Full for a primary field, half for a secondary, nothing otherwise. */
	private static double fieldMatch(Expert expert, FieldTag required) {
		if (expert.getPrimaryFields().contains(required)) {
			return 1;
		}
		return expert.getSecondaryFields().contains(required) ? 0.5 : 0;
	}

	private static String describeFieldMatch(double match) {
		if (match == 1) {
			return "Primary field";
		}
		return match > 0 ? "Secondary field" : "Does not carry the tag";
	}

	private static boolean signsLetterType(Expert expert, LetterType required) {
		return required != null && expert.getLetterTypes().contains(required);
	}

	/**
	 * Load as an inverse: no open cases scores full, and each additional case costs more than
	 * the one after it.
	 *
	 * <p>ponytail: 1/(1+n) is a naive inverse with no notion of capacity — an expert's third
	 * case scores the same fraction whoever they are. If brands ever set a per-expert cap, this
	 * becomes {@code max(0, 1 - n/cap)} and the cap is the knob; until somebody records one
	 * there is nothing to divide by.
	 */
	private static double loadFraction(int activeCases) {
		return 1.0 / (1 + activeCases);
	}

	// --- the evidence the factors need ---------------------------------------

	/**
	 * Load and acceptance rate for the whole shortlist, in two batched queries.
	 *
	 * <p>Load comes from {@link ExpertLoadService}, never from
	 * {@code expert.current_active_count}: that column was created {@code NOT NULL DEFAULT 0} in
	 * V7 and nothing has ever written it, so reading it would hand the scorer a constant and
	 * report every expert as free.
	 */
	private Map<UUID, Evidence> evidenceFor(List<Expert> eligible, UUID brandId) {
		List<UUID> ids = eligible.stream().map(Expert::getId).toList();
		Map<UUID, Load> load = loads.forExperts(ids);
		Map<UUID, double[]> tally = acceptanceTally(brandId, ids);

		// The mean is taken over the experts who actually have a record. Averaging in the
		// newcomers' placeholder would drag the mean toward the placeholder and make it drift as
		// the roster grows, which is a rate nobody has earned.
		OptionalDouble mean = tally.values().stream()
				.filter(counts -> counts[1] >= ACCEPTANCE_HISTORY_THRESHOLD)
				.mapToDouble(counts -> counts[0] / counts[1])
				.average();
		double coldStart = mean.orElse(NEUTRAL_ACCEPTANCE);

		Map<UUID, Evidence> evidence = new HashMap<>();
		for (UUID id : ids) {
			double[] counts = tally.getOrDefault(id, new double[] { 0, 0 });
			boolean own = counts[1] >= ACCEPTANCE_HISTORY_THRESHOLD;
			evidence.put(id, new Evidence(load.get(id), own ? counts[0] / counts[1] : coldStart, own));
		}
		return evidence;
	}

	/** Per expert, {@code [accepted, resolved]} — the numerator and denominator, counted once. */
	private Map<UUID, double[]> acceptanceTally(UUID brandId, List<UUID> expertIds) {
		Map<UUID, double[]> tally = new HashMap<>();
		for (Object[] row : offers.countOutcomesPerExpert(brandId, expertIds)) {
			OfferOutcome outcome = (OfferOutcome) row[1];
			if (!outcome.countsTowardAcceptanceRate()) {
				continue;
			}
			double count = ((Number) row[2]).doubleValue();
			double[] counts = tally.computeIfAbsent((UUID) row[0], key -> new double[] { 0, 0 });
			if (outcome == OfferOutcome.ACCEPTED) {
				counts[0] += count;
			}
			counts[1] += count;
		}
		return tally;
	}

	// --- small shared bits ---------------------------------------------------

	static LetterType letterTypeFor(ServiceType serviceType) {
		return serviceType == null ? null : LETTER_FOR_SERVICE.get(serviceType);
	}

	/**
	 * The flags a PM should weigh, minus {@code DECLINED_CASES}: that one is now a worse version
	 * of the acceptance rate two rows above it, which counts the declines instead of noting that
	 * some happened.
	 */
	private static List<PerformanceFlag> performanceFlags(Expert expert) {
		return expert.getPerformanceFlags().stream()
				.filter(flag -> flag != PerformanceFlag.DECLINED_CASES)
				.toList();
	}

	/** {@code MECHANICAL_ENGINEERING} → {@code Mechanical Engineering}, for the empty state. */
	private static String label(FieldTag tag) {
		StringBuilder out = new StringBuilder(tag.name().length());
		for (String word : tag.name().split("_")) {
			out.append(out.isEmpty() ? "" : " ")
					.append(word.charAt(0))
					.append(word.substring(1).toLowerCase());
		}
		return out.toString();
	}
}
