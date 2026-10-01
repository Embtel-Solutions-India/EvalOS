package com.ie.evalos.chat.push;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.chat.live.ChatPresence;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.notification.BellRaised;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D37: the bell pushes to a staff member who is not on the screen. */
class BellPushNotifierTest {

	private final PushSettings settings = new PushSettings("pub", "priv", "mailto:ops@ie.test",
			"http://staff.test", "http://client.test", "http://expert.test");
	private final PushSubscriptionRepository subscriptions = mock(PushSubscriptionRepository.class);
	private final PushSender sender = mock(PushSender.class);
	private final ChatPresence presence = mock(ChatPresence.class);
	private final BellPushNotifier notifier = new BellPushNotifier(settings, subscriptions, sender, presence,
			Runnable::run);

	private final UUID pm = UUID.randomUUID();
	private final UUID caseId = UUID.randomUUID();
	private final PushSubscription laptop = new PushSubscription(UUID.randomUUID(), ParticipantKind.STAFF, pm,
			"https://push.test/1", "p", "a");

	@Test
	void anOfflineRecipientIsPushedTheBellLineAndTheCase() throws Exception {
		when(subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.STAFF, pm)).thenReturn(List.of(laptop));
		when(sender.send(any(), any())).thenReturn(PushSender.Outcome.SENT);

		notifier.on(new BellRaised(pm, NotificationType.SLA_AT_RISK, caseId, "IE-1042 is at risk of missing its deadline"));

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(sender).send(eq(laptop), payload.capture());
		var json = new ObjectMapper().readTree(payload.getValue());
		assertThat(json.get("title").asText()).isEqualTo("Deadline at risk");
		assertThat(json.get("body").asText()).isEqualTo("IE-1042 is at risk of missing its deadline");
		assertThat(json.get("url").asText()).isEqualTo("http://staff.test/cases/" + caseId);
		assertThat(json.get("tag").asText()).isEqualTo("bell:SLA_AT_RISK:" + caseId);
		verify(subscriptions).save(laptop); // stamped as sent
	}

	@Test
	void someoneWithTheAppOpenIsNotPushed() {
		when(presence.isOnline(ParticipantKind.STAFF, pm)).thenReturn(true);

		notifier.on(new BellRaised(pm, NotificationType.CASE_ASSIGNED, caseId, "body"));

		verify(sender, never()).send(any(), any());
	}

	@Test
	void unconfiguredPushDoesNothing() {
		BellPushNotifier off = new BellPushNotifier(new PushSettings("", "", "", "a", "b", "c"), subscriptions, sender,
				presence, Runnable::run);

		off.on(new BellRaised(pm, NotificationType.CASE_ASSIGNED, caseId, "body"));

		verify(presence, never()).isOnline(any(), any());
		verify(sender, never()).send(any(), any());
	}

	@Test
	void aGoneBrowserIsForgotten() {
		when(subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.STAFF, pm)).thenReturn(List.of(laptop));
		when(sender.send(any(), any())).thenReturn(PushSender.Outcome.GONE);

		notifier.on(new BellRaised(pm, NotificationType.HIRING_PIPELINE_UPDATED, null, "New candidate"));

		verify(subscriptions).delete(laptop);
	}
}
