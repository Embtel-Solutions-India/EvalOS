package com.ie.evalos.repository;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every finder on the opportunity mirror names its scope in its signature.
 *
 * <p><strong>This guard outlived the table it was written for, and the reason it is still needed
 * is narrow.</strong> It was {@code CachedOpportunityRepositoryScopeTest}, guarding a cache that
 * had <em>no {@code brand_id} at all</em> — so a parameter was the only scope it could have.
 * {@code opportunity} (Unit 44d) does have one, and {@code DomainInvariantsTest} now checks the
 * brand axis through {@code SCOPE}.
 *
 * <p>What is not yet covered is the <strong>pipeline</strong> axis. {@code OpportunityRepository.SCOPE}
 * is {@code brandOnly} on purpose: {@code ScopePredicate}'s pipeline arm compares a <em>GHL</em>
 * pipeline id from the caller's principal, and this entity holds EvalOS's {@code pipeline_id}.
 * Slice <strong>44b</strong> is where those line up. Until then a brand-scoped predicate would let
 * one desk read another's deals, so the structural rule still has to hold: every finder takes a
 * brand, a pipeline or an opportunity.
 *
 * <p>A required parameter is stronger than a predicate rather than weaker — a predicate is
 * something a query can forget, and a parameter is not. But it only holds while it is true of
 * <em>every</em> read declared here, which is what this pins. Adding
 * {@code List<Opportunity> findByStatus(String status)} would compile, look harmless, and quietly
 * return every desk's deals.
 *
 * <p><strong>Delete this test at 44b, not before</strong>, and only once {@code SCOPE} narrows by
 * pipeline — at that point {@code ScopePredicate} carries the same guarantee and this becomes a
 * second copy of it.
 */
class OpportunityRepositoryScopeTest {

	/**
	 * A parameter that names the scope.
	 *
	 * <p>{@code brandId} is the tenant, {@code pipelineId(s)} is the desk's access key (Unit 36),
	 * and an opportunity id is narrower still since it addresses exactly one row the caller already
	 * named.
	 */
	private static final List<String> SCOPING_PARAMETERS = List.of("brandId", "pipelineId",
			"pipelineIds", "ghlId", "opportunityId");

	@Test
	void everyDeclaredFinderIsScopedByBrandPipelineOrOpportunity() {
		List<String> unscoped = Arrays.stream(OpportunityRepository.class.getDeclaredMethods())
				.filter((method) -> Arrays.stream(method.getParameters())
						.noneMatch((parameter) -> SCOPING_PARAMETERS.contains(parameter.getName())))
				.map(Method::getName)
				.sorted()
				.toList();

		assertThat(unscoped)
				.describedAs("SCOPE is brandOnly until 44b, so the pipeline axis lives in these "
						+ "signatures. A finder without a scoping parameter returns every desk's "
						+ "opportunities to whoever calls it. If a sweep genuinely needs to span "
						+ "pipelines, give it a name that says so and add it here deliberately.")
				.isEmpty();
	}

	/**
	 * The scan has to be able to fail, or it is decoration — the same rule
	 * {@code SegmentIsNotAnAccessKeyTest} and {@code GhlHttpTest} apply to theirs.
	 *
	 * <p>Reflection needs {@code -parameters} to see real parameter names; without it they are
	 * {@code arg0}, every method looks unscoped, and the test above would pass only because it
	 * failed to see anything.
	 */
	@Test
	void parameterNamesAreCompiledIn() throws NoSuchMethodException {
		Method finder = OpportunityRepository.class.getDeclaredMethod("findByPipelineIdIn",
				java.util.Collection.class);

		assertThat(finder.getParameters()[0].getName())
				.describedAs("compile with -parameters, or the scope check above sees only arg0 and "
						+ "passes by being blind")
				.isEqualTo("pipelineIds");
	}
}
