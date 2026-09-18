package com.ie.evalos.integration;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * GHL writes answered locally instead of sent — and the guard that keeps it off a deployment.
 *
 * <p>{@link #itRefusesToStartOutsideTheLocalProfile()} is the one that matters. Every other failure
 * in this system is loud: a refused write throws, a queued one retries, an outage shows a banner.
 * A deployment running this class would accept every desk edit, every portal request and every
 * close, report success, and send none of it — EvalOS and the CRM would diverge permanently with
 * nothing anywhere to notice. The startup refusal is the only thing standing between that and one
 * mistyped environment variable.
 */
class StubGhlWriteClientTest {

	private final GhlHttp http = mock(GhlHttp.class);
	private final com.ie.evalos.service.AuditService audit =
			mock(com.ie.evalos.service.AuditService.class);

	private StubGhlWriteClient local() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("local");
		return new StubGhlWriteClient(http, audit, environment);
	}

	/**
	 * <strong>Nothing reaches GHL, which is the entire point.</strong>
	 *
	 * <p>Asserted against the HTTP client rather than by reading the returned ids: a stub that
	 * answered locally <em>and</em> sent the request would pass any test that only looked at what
	 * came back.
	 */
	@Test
	void noWriteReachesTheHttpClient() {
		StubGhlWriteClient stub = local();

		stub.upsertContact("Ana", "Ruiz", "ana@example.test", null, "Client Portal");
		stub.upsertOpportunity("pipe-1", "c1", "Ana — Academic Evaluation", BigDecimal.TEN);
		stub.createOpportunity("pipe-1", "c1", "Ana", BigDecimal.TEN, null, null, Map.of());
		stub.updateOpportunity("opp-1", "pipe-1", "renamed", BigDecimal.ONE, "s2");
		stub.moveStage("opp-1", "pipe-1", "s3");
		stub.setStatus("opp-1", "pipe-1", "won");
		stub.setOpportunityFields("opp-1", Map.of("f1", "v1"));
		stub.createFollowUp("c1", "opp-1", "pipe-1", "Ring back", "2026-09-20", null, null);
		stub.completeFollowUp("c1", "task-1", "opp-1", "pipe-1");

		verifyNoInteractions(http);
	}

	/**
	 * The ids are unmistakable, so nobody spends an afternoon looking for one in GHL.
	 *
	 * <p>They are still unique, so the correlation key, the outbox and the mirror behave exactly as
	 * they would against real ids — a stub that returned a constant would hide every duplicate bug
	 * those mechanisms exist to prevent.
	 */
	@Test
	void theIdsAreObviouslyFakeAndStillUnique() {
		StubGhlWriteClient stub = local();

		String first = stub.createOpportunity("p", "c", "n", null, null, null, null).id();
		String second = stub.createOpportunity("p", "c", "n", null, null, null, null).id();

		assertThat(first).startsWith("stub-");
		assertThat(second).startsWith("stub-");
		assertThat(first).isNotEqualTo(second);
	}

	/** `moveStage` delegates to `updateOpportunity`, so the override catches it too. */
	@Test
	void aStageMoveIsStubbedThroughTheUpdateOverride() {
		assertThat(local().moveStage("opp-1", "pipe-1", "s3").stageId()).isEqualTo("s3");
		verifyNoInteractions(http);
	}

	/**
	 * <strong>It refuses to start outside the {@code local} profile.</strong>
	 *
	 * <p>A startup failure, because every other symptom of this being wrong is invisible: the app
	 * runs, the screens work, the writes vanish.
	 */
	@Test
	void itRefusesToStartOutsideTheLocalProfile() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatThrownBy(() -> new StubGhlWriteClient(http, audit, prod))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("local")
				.hasMessageContaining("live");
	}

	/** The default profile is local (`spring.profiles.default: local`), so it is accepted. */
	@Test
	void theDefaultProfileIsAcceptedBecauseItIsLocal() {
		MockEnvironment noneActive = new MockEnvironment();
		noneActive.setDefaultProfiles("local");

		assertThat(new StubGhlWriteClient(http, audit, noneActive)).isNotNull();
	}
}
