package com.ie.evalos.job;

import java.time.Duration;
import java.time.Instant;

import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientCredentialTokenRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * What the portal's cleanup sweep deletes, and — more importantly — when.
 *
 * <p>The two cutoffs are the whole of this class's logic, and each is a subtraction that is easy to
 * get backwards: an inverted sign on either would delete the rows it is supposed to keep. That is
 * why the captors assert the instants rather than just counting calls.
 */
class PortalCleanupSweepTest {

	private static final Duration TTL = Duration.ofMinutes(30);

	private static final Duration ABANDONED_AFTER = Duration.ofDays(30);

	private final ClientCredentialTokenRepository credentials = mock(ClientCredentialTokenRepository.class);

	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);

	/** The real runner would take an advisory lock and write a ledger row; neither is under test. */
	private final SweepRunner runner = mock(SweepRunner.class);

	private final PortalCleanupSweep sweep =
			new PortalCleanupSweep(runner, credentials, accounts, TTL, ABANDONED_AFTER);

	@Test
	void bothCutoffsAreInThePastAndAreNotTheSameClock() {
		runEveryItem();
		Instant before = Instant.now();

		sweep.run();

		Instant after = Instant.now();

		ArgumentCaptor<Instant> tokenCutoff = ArgumentCaptor.forClass(Instant.class);
		verify(credentials).deleteByExpiresAtBefore(tokenCutoff.capture());
		ArgumentCaptor<Instant> accountCutoff = ArgumentCaptor.forClass(Instant.class);
		verify(accounts).deleteAbandonedSignUps(accountCutoff.capture());

		// A token is dead the moment it expires; one extra TTL is the grace, and nothing more.
		// Bracketed rather than compared to one instant: the sweep reads its own clock, which is
		// necessarily at or after `before`, so a one-sided assertion fails on a fast machine.
		assertThat(tokenCutoff.getValue()).isBetween(before.minus(TTL), after.minus(TTL));
		// An abandoned sign-up is a judgement about a person, on its own and much longer clock.
		assertThat(accountCutoff.getValue())
				.isBetween(before.minus(ABANDONED_AFTER), after.minus(ABANDONED_AFTER));
		assertThat(accountCutoff.getValue()).isBefore(tokenCutoff.getValue());
	}

	/**
	 * A quiet deployment must report zero acted, not two.
	 *
	 * <p>The admin panel's staleness warning is built on {@code seen} versus {@code acted}, so a
	 * sweep that always claims to have acted makes "is anything actually being cleaned up?"
	 * unanswerable from the one screen that exists to answer it.
	 */
	@Test
	void deletingNothingIsNotActing() {
		given(credentials.deleteByExpiresAtBefore(any())).willReturn(0L);
		given(accounts.deleteAbandonedSignUps(any())).willReturn(0);

		assertThat(act(PortalCleanupSweep.class)).isFalse();
	}

	@Test
	void deletingSomethingIsActing() {
		given(credentials.deleteByExpiresAtBefore(any())).willReturn(4L);

		assertThat(act(PortalCleanupSweep.class)).isTrue();
	}

	/** Runs the sweep body for every item, as the real runner would. */
	private void runEveryItem() {
		given(runner.sweep(any(), any(), any())).willAnswer((call) -> {
			java.util.function.Supplier<java.util.List<Object>> items = call.getArgument(1);
			SweepRunner.ItemAction<Object> action = call.getArgument(2);
			items.get().forEach(action::act);
			return true;
		});
	}

	/** True if any item reported that it deleted rows. */
	private boolean act(Class<?> unused) {
		boolean[] acted = { false };
		given(runner.sweep(any(), any(), any())).willAnswer((call) -> {
			java.util.function.Supplier<java.util.List<Object>> items = call.getArgument(1);
			SweepRunner.ItemAction<Object> action = call.getArgument(2);
			for (Object item : items.get()) {
				acted[0] |= action.act(item);
			}
			return true;
		});
		sweep.run();
		return acted[0];
	}
}
