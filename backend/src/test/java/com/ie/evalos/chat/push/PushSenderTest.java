package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.chat.ParticipantKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A push the push service could not take is owed, not lost (V92): queued as a row, retried on the
 * 30s / 120s / 600s backoff, and the row gone the moment the push settles either way.
 */
class PushSenderTest {

	private final PushOutboxRepository outbox = mock(PushOutboxRepository.class);
	private final PushSubscriptionRepository subscriptions = mock(PushSubscriptionRepository.class);
	private final PushSender sender = spy(new PushSender(new PushSettings("", "", "", "a", "b", "c"), outbox,
			subscriptions));

	private final UUID brand = UUID.randomUUID();
	private final PushSubscription laptop = new PushSubscription(brand, ParticipantKind.STAFF, UUID.randomUUID(),
			"https://push.test/1", "p", "a");
	private final UUID rowId = UUID.randomUUID();

	@BeforeEach
	void browserExists() {
		when(subscriptions.findById(any())).thenReturn(Optional.of(laptop));
	}

	private PushRetry owed(int retriesSpent) {
		PushRetry row = new PushRetry(laptop, "{\"title\":\"x\"}", Instant.now());
		for (int i = 0; i < retriesSpent; i++) {
			row.spendOne(Instant.now());
		}
		when(outbox.findById(rowId)).thenReturn(Optional.of(row));
		return row;
	}

	@Test
	void aMissIsQueuedForTheFirstStepOfTheBackoff() {
		doReturn(PushSender.Outcome.RETRY).when(sender).send(any(), any());
		Instant before = Instant.now();

		PushSender.deliver(sender, subscriptions, laptop, "{\"title\":\"x\"}");

		ArgumentCaptor<PushRetry> queued = ArgumentCaptor.forClass(PushRetry.class);
		verify(outbox).save(queued.capture());
		assertThat(queued.getValue().getBrandId()).isEqualTo(brand);
		assertThat(queued.getValue().getPayload()).isEqualTo("{\"title\":\"x\"}");
		assertThat(queued.getValue().getRetries()).isZero();
		assertThat(queued.getValue().getDueAt()).isBetween(before.plusSeconds(30), Instant.now().plusSeconds(30));
		verify(subscriptions, never()).save(any());
	}

	@Test
	void aRetryThatGetsThroughStampsTheBrowserAndDropsTheRow() {
		PushRetry row = owed(0);
		doReturn(PushSender.Outcome.SENT).when(sender).send(laptop, row.getPayload());

		assertThat(sender.retry(rowId)).isTrue();

		verify(outbox).delete(row);
		verify(subscriptions).save(laptop);
		assertThat(laptop.getLastSuccessAt()).isNotNull();
	}

	@Test
	void anotherMissWaitsForTheNextStep() {
		PushRetry row = owed(0);
		doReturn(PushSender.Outcome.RETRY).when(sender).send(any(), any());
		Instant before = Instant.now();

		assertThat(sender.retry(rowId)).isFalse();

		assertThat(row.getRetries()).isEqualTo(1);
		assertThat(row.getDueAt()).isBetween(before.plusSeconds(120), Instant.now().plusSeconds(120));
		verify(outbox).save(row);
		verify(outbox, never()).delete(any());
	}

	@Test
	void theLastStepIsTenMinutes() {
		PushRetry row = owed(1);
		doReturn(PushSender.Outcome.RETRY).when(sender).send(any(), any());
		Instant before = Instant.now();

		sender.retry(rowId);

		assertThat(row.getDueAt()).isBetween(before.plusSeconds(600), Instant.now().plusSeconds(600));
	}

	@Test
	void aMissOnTheLastRetryGivesUp() {
		PushRetry row = owed(2);
		doReturn(PushSender.Outcome.RETRY).when(sender).send(any(), any());

		assertThat(sender.retry(rowId)).isFalse();

		verify(outbox).delete(row);
		verify(outbox, never()).save(any());
		verify(subscriptions, never()).delete(any());
	}

	@Test
	void aGoneBrowserIsForgottenWithItsRow() {
		PushRetry row = owed(0);
		doReturn(PushSender.Outcome.GONE).when(sender).send(any(), any());

		sender.retry(rowId);

		verify(outbox).delete(row);
		verify(subscriptions).delete(laptop);
	}

	@Test
	void aRefusalIsNotRetried() {
		PushRetry row = owed(0);
		// Unconfigured push answers FAILED: the real send, no spy stub.

		assertThat(sender.retry(rowId)).isFalse();

		verify(outbox).delete(row);
		verify(subscriptions, never()).save(any());
		verify(subscriptions, never()).delete(any());
	}

	@Test
	void aRowWhoseBrowserWasRemovedIsDroppedUnsent() {
		PushRetry row = owed(0);
		when(subscriptions.findById(any())).thenReturn(Optional.empty());

		assertThat(sender.retry(rowId)).isFalse();

		verify(sender, never()).send(any(), any());
		verify(outbox).delete(row);
	}

	@Test
	void aRowAlreadyGoneIsNothingToDo() {
		when(outbox.findById(rowId)).thenReturn(Optional.empty());

		assertThat(sender.retry(rowId)).isFalse();

		verify(sender, never()).send(any(), any());
	}
}
