package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PerformanceFlag;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ExpertLoadService.Load;
import com.ie.evalos.service.ExpertMatchService.Factor;
import com.ie.evalos.service.ExpertMatchService.ScoredExpert;
import com.ie.evalos.service.ExpertMatchService.Shortlist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The acceptance criteria of Unit 12 that live in the scorer: the ranking is right and it is
 * explained, the shortlist is brand-scoped and eligibility-filtered, the cold-start rule keeps a
 * new expert off the bottom, load comes from the derived count, and an empty answer names the
 * factor that emptied it.
 *
 * <p>Repositories are mocked, as in {@code CaseLifecycleServiceTest}: brand isolation here is the
 * two scoped reads this service delegates to, which are asserted where they live.
 */
class ExpertMatchServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID OTHER_BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final FieldTag NEEDED = FieldTag.MECHANICAL_ENGINEERING;

	private final CaseLifecycleService cases = mock(CaseLifecycleService.class);
	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final ExpertLoadService loads = mock(ExpertLoadService.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);

	private final ExpertMatchService matching = new ExpertMatchService(cases, experts, loads, offers);

	@BeforeEach
	void aCaseAwaitingAnExpert() {
		Case subject = new Case(BRAND, "IE-2026-0001", Stage.EXPERT_ASSIGNMENT);
		subject.setServiceType(ServiceType.EXPERT_OPINION_LETTER);
		given(cases.read(CASE_ID)).willReturn(subject);
		// No offer history and no load unless a test says otherwise.
		given(offers.countOutcomesPerExpert(any(UUID.class), anyCollection())).willReturn(List.of());
		noLoad();

		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "pm@evalos.local", "PM",
				Role.PROJECT_MANAGER, BRAND, UUID.randomUUID(), null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	// --- fixtures ------------------------------------------------------------

	private Expert expert(String name, UUID brandId, Availability availability, List<FieldTag> primary,
			List<FieldTag> secondary) {
		Expert value = mock(Expert.class);
		given(value.getId()).willReturn(UUID.randomUUID());
		given(value.getBrandId()).willReturn(brandId);
		given(value.getFullName()).willReturn(name);
		given(value.getAvailability()).willReturn(availability);
		given(value.getPrimaryFields()).willReturn(primary);
		given(value.getSecondaryFields()).willReturn(secondary);
		given(value.getLetterTypes()).willReturn(List.of(LetterType.EXPERT_OPINION_LETTER));
		given(value.getPerformanceFlags()).willReturn(List.of());
		return value;
	}

	/** The ordinary case: available, in this brand, primary tag, signs the letter type. */
	private Expert matching(String name) {
		return expert(name, BRAND, Availability.AVAILABLE, List.of(NEEDED), List.of());
	}

	private void roster(Expert... members) {
		given(experts.findScoped(any(TenantContext.class))).willReturn(List.of(members));
	}

	private void noLoad() {
		loadOf(Map.of());
	}

	/** Only the experts named carry cases; the rest come back zero, as the real service does. */
	private void loadOf(Map<Expert, Integer> active) {
		Map<UUID, Integer> byId = active.entrySet().stream()
				.collect(Collectors.toMap(entry -> entry.getKey().getId(), Map.Entry::getValue));
		given(loads.forExperts(anyCollection())).willAnswer(invocation -> {
			Collection<?> ids = invocation.getArgument(0);
			return ids.stream().collect(Collectors.toMap(
					id -> (UUID) id, id -> new Load(byId.getOrDefault(id, 0), 0)));
		});
	}

	/**
	 * The aggregate rows for one expert. The id is read into a local <em>before</em> the
	 * {@code given(...)}: a mock call inside a stubbing argument list leaves the outer stubbing
	 * unfinished, which is the same trap {@code CaseLifecycleServiceTest} notes.
	 */
	private void offerHistory(Expert expert, int accepted, int declined) {
		UUID id = expert.getId();
		given(offers.countOutcomesPerExpert(eq(BRAND), anyCollection())).willReturn(List.of(
				new Object[] { id, OfferOutcome.ACCEPTED, (long) accepted },
				new Object[] { id, OfferOutcome.DECLINED, (long) declined }));
	}

	private static int earned(ScoredExpert scored, String factor) {
		return scored.factors().stream()
				.filter(candidate -> candidate.label().equals(factor))
				.mapToInt(Factor::earned)
				.sum();
	}

	private static String why(ScoredExpert scored, String factor) {
		return scored.factors().stream()
				.filter(candidate -> candidate.label().equals(factor))
				.map(Factor::why)
				.findFirst().orElseThrow();
	}

	private static ScoredExpert byName(Shortlist shortlist, String name) {
		return shortlist.experts().stream()
				.filter(scored -> scored.expert().getFullName().equals(name))
				.findFirst().orElseThrow();
	}

	// --- the criteria --------------------------------------------------------

	@Test
	void theShortlistIsThreeAvailableExpertsFromTheCasesBrandAndNobodyElse() {
		Expert best = matching("A Available");
		roster(best,
				matching("B Available"),
				matching("C Available"),
				matching("D Available"),
				expert("At capacity", BRAND, Availability.AT_CAPACITY, List.of(NEEDED), List.of()),
				expert("On leave", BRAND, Availability.ON_LEAVE, List.of(NEEDED), List.of()),
				// A perfect match in the other brand: absent, not ranked low. The transition
				// would refuse them, so offering them would be offering what the write side rejects.
				expert("Other brand star", OTHER_BRAND, Availability.AVAILABLE, List.of(NEEDED), List.of()));

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertEquals(3, shortlist.experts().size());
		assertNull(shortlist.emptyReason());
		assertThat(shortlist.experts()).extracting(scored -> scored.expert().getFullName())
				.containsExactly("A Available", "B Available", "C Available")
				.doesNotContain("At capacity", "On leave", "Other brand star");
		assertEquals(best.getId(), shortlist.experts().getFirst().expert().getId());
	}

	@Test
	void aPrimaryFieldOutranksASecondaryOneAndTheBreakdownAddsUpToTheScoreShown() {
		Expert primary = matching("Primary");
		Expert secondary = expert("Secondary", BRAND, Availability.AVAILABLE, List.of(), List.of(NEEDED));
		roster(secondary, primary);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertThat(shortlist.experts()).extracting(scored -> scored.expert().getFullName())
				.containsExactly("Primary", "Secondary");
		assertEquals(40, earned(shortlist.experts().getFirst(), "Field match"));
		assertEquals(20, earned(shortlist.experts().getLast(), "Field match"), "half of the weight");

		// The number the PM is shown is the sum of the rows the PM is shown. A ranking whose
		// arithmetic does not add up gets distrusted, which is the same outcome as no ranking.
		for (ScoredExpert scored : shortlist.experts()) {
			assertEquals(scored.score(), scored.factors().stream().mapToInt(Factor::earned).sum());
			assertThat(scored.factors()).extracting(Factor::label)
					.containsExactly("Field match", "Letter-type experience", "Acceptance rate", "Current load");
			assertThat(scored.factors()).allSatisfy(factor ->
					assertThat(factor.why()).as("every row explains itself").isNotBlank());
		}
	}

	@Test
	void notSigningTheCasesLetterTypeCostsTheWholeFactorAndNothingElse() {
		Expert signs = matching("Signs");
		Expert doesNot = matching("Does not sign");
		given(doesNot.getLetterTypes()).willReturn(List.of(LetterType.CREDENTIAL_EVALUATION));
		roster(doesNot, signs);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertEquals(25, earned(shortlist.experts().getFirst(), "Letter-type experience"));
		assertEquals(0, earned(shortlist.experts().getLast(), "Letter-type experience"));
		// The map is declared, not derived: EXPERT_OPINION_LETTER lines up by name, TRANSLATION →
		// TRANSLATION_CERTIFICATION does not, and a valueOf would have thrown on that pair.
		assertEquals(LetterType.TRANSLATION_CERTIFICATION,
				ExpertMatchService.letterTypeFor(ServiceType.TRANSLATION));
		assertEquals(LetterType.PERM_LETTER, ExpertMatchService.letterTypeFor(ServiceType.PERM));
		assertNull(ExpertMatchService.letterTypeFor(null), "a case with no service type needs no letter");
	}

	/**
	 * The cold-start rule, asserted directly: an expert with no record must not be ranked last
	 * purely for having none, because being last is what stops them ever getting the case that
	 * would give them one.
	 */
	@Test
	void anExpertWithNoOfferHistoryIsNotRankedLastForThatAlone() {
		Expert seasoned = matching("Seasoned");
		Expert newcomer = matching("Newcomer");
		// Four resolved offers, half accepted — above the threshold, so their own rate is used.
		offerHistory(seasoned, 2, 2);
		roster(seasoned, newcomer);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);
		ScoredExpert seasonedScore = shortlist.experts().stream()
				.filter(scored -> scored.expert().getId().equals(seasoned.getId())).findFirst().orElseThrow();
		ScoredExpert newcomerScore = shortlist.experts().stream()
				.filter(scored -> scored.expert().getId().equals(newcomer.getId())).findFirst().orElseThrow();

		assertEquals(10, earned(seasonedScore, "Acceptance rate"), "half of 20");
		assertEquals(10, earned(newcomerScore, "Acceptance rate"),
				"the roster's mean, not zero — otherwise a new expert is permanently last");
		assertEquals(seasonedScore.score(), newcomerScore.score(),
				"all else equal, having no history is neither a reward nor a penalty");
	}

	/**
	 * The one row that could state a fact the data does not support. The newcomer is scored at the
	 * roster's mean, and the explanation has to say so — a PM told "50% of resolved offers
	 * accepted" about an expert with no resolved offers is being shown somebody else's record as
	 * theirs, and the breakdown exists precisely so they can disagree with it.
	 */
	@Test
	void theAcceptanceRowSaysWhenItIsTheRosterMeanAndNotTheExpertsOwnRecord() {
		Expert seasoned = matching("Seasoned");
		Expert newcomer = matching("Newcomer");
		offerHistory(seasoned, 2, 2);
		roster(seasoned, newcomer);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertEquals("50% of resolved offers accepted", why(byName(shortlist, "Seasoned"), "Acceptance rate"));
		assertEquals("Too few resolved offers — scored at the roster average",
				why(byName(shortlist, "Newcomer"), "Acceptance rate"));
	}

	@Test
	void aTinyOfferHistoryStillScoresTheMeanRatherThanItself() {
		Expert seasoned = matching("Seasoned");
		Expert barelyStarted = matching("Barely started");
		UUID seasonedId = seasoned.getId();
		UUID barelyStartedId = barelyStarted.getId();
		given(offers.countOutcomesPerExpert(eq(BRAND), anyCollection())).willReturn(List.of(
				new Object[] { seasonedId, OfferOutcome.ACCEPTED, 3L },
				// Two resolved offers is below the threshold of three, and both declined. Judging
				// somebody on two data points is what the threshold exists to refuse.
				new Object[] { barelyStartedId, OfferOutcome.DECLINED, 2L }));
		roster(seasoned, barelyStarted);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertThat(shortlist.experts()).allSatisfy(scored ->
				assertEquals(20, earned(scored, "Acceptance rate"),
						"the mean over the experts who have a record is 100%, and both are scored on it"));
	}

	/**
	 * Load comes from the derived count, and the mocks prove it: neither expert's
	 * {@code current_active_count} is ever stubbed, so a scorer reading the column would see the
	 * same zero for both and rank them equal.
	 */
	@Test
	void loadComesFromTheDerivedCountWhileBothRowsStillHoldZero() {
		Expert busy = matching("Busy");
		Expert free = matching("Free");
		loadOf(Map.of(busy, 2, free, 0));
		roster(busy, free);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertThat(shortlist.experts()).extracting(scored -> scored.expert().getFullName())
				.containsExactly("Free", "Busy");
		assertEquals(15, earned(shortlist.experts().getFirst(), "Current load"));
		assertEquals(5, earned(shortlist.experts().getLast(), "Current load"), "15 / (1 + 2)");
		assertEquals(2, shortlist.experts().getLast().activeLoad());
		assertThat(shortlist.experts().getLast().factors())
				.filteredOn(factor -> factor.label().equals("Current load"))
				.singleElement()
				.satisfies(factor -> assertThat(factor.why()).contains("2"));
	}

	@Test
	void qualityScoreBreaksATieAndIsNotAFifthFactor() {
		Expert good = matching("Good");
		Expert better = matching("Better");
		given(good.getQualityScore()).willReturn(new BigDecimal("7.5"));
		given(better.getQualityScore()).willReturn(new BigDecimal("9.0"));
		roster(good, better);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertThat(shortlist.experts()).extracting(scored -> scored.expert().getFullName())
				.containsExactly("Better", "Good");
		assertEquals(shortlist.experts().getFirst().score(), shortlist.experts().getLast().score(),
				"quality orders equal scores, it does not add points");
		assertThat(shortlist.experts().getFirst().factors()).hasSize(4);
	}

	@Test
	void thePerformanceFlagsAreShownAndNotScored() {
		Expert flagged = matching("Flagged");
		given(flagged.getPerformanceFlags()).willReturn(List.of(
				PerformanceFlag.CLIENT_COMPLAINT, PerformanceFlag.SLOW_RESPONSE, PerformanceFlag.DECLINED_CASES));
		Expert clean = matching("Clean");
		given(clean.getTier()).willReturn(ExpertTier.TIER_1);
		roster(flagged, clean);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);
		ScoredExpert flaggedScore = shortlist.experts().stream()
				.filter(scored -> scored.expert().getId().equals(flagged.getId())).findFirst().orElseThrow();
		ScoredExpert cleanScore = shortlist.experts().stream()
				.filter(scored -> scored.expert().getId().equals(clean.getId())).findFirst().orElseThrow();

		assertEquals(cleanScore.score(), flaggedScore.score(),
				"a complaint is for a human to weigh — folding it into a number hides it");
		assertThat(flaggedScore.flags())
				.containsExactly(PerformanceFlag.CLIENT_COMPLAINT, PerformanceFlag.SLOW_RESPONSE)
				// DECLINED_CASES is a worse version of the acceptance-rate factor two rows up.
				.doesNotContain(PerformanceFlag.DECLINED_CASES);
	}

	// --- the honest empty states --------------------------------------------

	@Test
	void anEmptyShortlistNamesTheFieldTagThatEmptiedIt() {
		roster(expert("Nurse", BRAND, Availability.AVAILABLE, List.of(FieldTag.NURSING), List.of()),
				expert("Lawyer", BRAND, Availability.AVAILABLE, List.of(FieldTag.LAW), List.of(FieldTag.ECONOMICS)));

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertThat(shortlist.experts()).isEmpty();
		// Actionable: the ENM recruits, or an existing expert's tags are wrong. "No matches" is not.
		assertEquals("no available expert carries the Mechanical Engineering tag", shortlist.emptyReason());
	}

	/**
	 * The rematch shortlist must not lead with the expert who just declined. They are still
	 * {@code AVAILABLE} and still carry the tag, so nothing else filters them — and
	 * {@code reassignExpert} refuses "the expert who declined", so ranking them first is proposing
	 * a 409 as the top suggestion.
	 */
	@Test
	void theRematchShortlistDropsTheExpertWhoDeclined() {
		Expert declined = matching("Declined");
		Expert fresh = matching("Fresh");
		Case rematching = new Case(BRAND, "IE-2026-0002", Stage.EXPERT_SIGNING);
		rematching.setServiceType(ServiceType.EXPERT_OPINION_LETTER);
		rematching.setExpertId(declined.getId());
		rematching.setExceptionState(ExceptionState.EXPERT_DECLINED_REMATCHING);
		given(cases.read(CASE_ID)).willReturn(rematching);
		roster(declined, fresh);

		Shortlist shortlist = matching.shortlist(CASE_ID, NEEDED);

		assertThat(shortlist.experts()).extracting(scored -> scored.expert().getFullName())
				.containsExactly("Fresh")
				.doesNotContain("Declined");
	}

	@Test
	void anEmptyShortlistDistinguishesNobodyAvailableFromNobodyOnTheRoster() {
		roster(expert("At capacity", BRAND, Availability.AT_CAPACITY, List.of(NEEDED), List.of()));
		assertEquals("no expert in this brand is available for this case right now",
				matching.shortlist(CASE_ID, NEEDED).emptyReason());

		roster();
		Shortlist bare = matching.shortlist(CASE_ID, NEEDED);
		assertEquals("this brand has no experts on its roster yet", bare.emptyReason());
		assertTrue(bare.experts().isEmpty());
	}
}
