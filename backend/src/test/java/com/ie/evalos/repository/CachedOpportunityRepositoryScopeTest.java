package com.ie.evalos.repository;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opportunity cache has no {@code brand_id}, so it cannot be scoped by
 * {@code ScopePredicate}. <strong>Its scope is structural instead: every finder takes the
 * pipeline, and the pipeline comes from the caller's own principal.</strong>
 *
 * <p>That is stronger than a predicate rather than weaker — a predicate is something a query can
 * forget, and a required parameter is not. But it only holds while it is true of <em>every</em>
 * read declared here, which is what this test pins. Adding
 * {@code List<CachedOpportunity> findByStatus(String status)} would compile, look harmless, and
 * quietly return every desk's deals.
 *
 * <p>Reflection over the interface rather than a source scan, because the thing being checked is
 * a signature and Spring Data derives behaviour from exactly that.
 */
class CachedOpportunityRepositoryScopeTest {

	/**
	 * A parameter that names the scope. The pipeline is the access key (Unit 36); an opportunity
	 * id is narrower still, since it addresses exactly one row the caller already named.
	 */
	private static final List<String> SCOPING_PARAMETERS = List.of("ghlPipelineId", "pipelineId",
			"ghlPipelineIds", "opportunityId");

	@Test
	void everyDeclaredFinderIsScopedByPipelineOrOpportunity() {
		List<String> unscoped = Arrays.stream(CachedOpportunityRepository.class.getDeclaredMethods())
				.filter((method) -> Arrays.stream(method.getParameters())
						.noneMatch((parameter) -> SCOPING_PARAMETERS.contains(parameter.getName())))
				.map(Method::getName)
				.sorted()
				.toList();

		assertThat(unscoped)
				.describedAs("This cache has no brand_id and cannot go through ScopePredicate — the "
						+ "pipeline parameter IS the scope. A finder without one returns every "
						+ "desk's opportunities to whoever calls it. If a sweep genuinely needs to "
						+ "span pipelines (a TTL cleanup, say), give it a name that says so and add "
						+ "it to this test deliberately.")
				.isEmpty();
	}

	/**
	 * The scan has to be able to fail, or it is decoration — the same rule
	 * {@code SegmentIsNotAnAccessKeyTest} and {@code GhlHttpTest} apply to theirs.
	 *
	 * <p>Reflection needs {@code -parameters} to see real parameter names; without it they are
	 * {@code arg0}, every method looks unscoped, and the test above would pass only because it
	 * failed to see anything. This asserts the compiler flag is on, which is the one way that
	 * whole check could be silently worthless.
	 */
	@Test
	void parameterNamesAreCompiledIn() throws NoSuchMethodException {
		Method finder = CachedOpportunityRepository.class.getDeclaredMethod("findByGhlPipelineId", String.class);

		assertThat(finder.getParameters()[0].getName())
				.describedAs("compile with -parameters, or the scope check above sees only arg0 and "
						+ "passes by being blind")
				.isEqualTo("ghlPipelineId");
	}
}
