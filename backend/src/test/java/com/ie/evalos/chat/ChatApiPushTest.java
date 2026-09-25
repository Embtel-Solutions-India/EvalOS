package com.ie.evalos.chat;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.chat.push.PushSettings;
import com.ie.evalos.chat.push.PushSubscription;
import com.ie.evalos.chat.push.PushSubscriptionRepository;
import com.ie.evalos.chat.push.PushUnavailableException;
import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Push subscriptions through the chat API (Unit 57 §6). */
class ChatApiPushTest {

	private final PushSubscriptionRepository subscriptions = mock(PushSubscriptionRepository.class);
	private final UUID brand = UUID.randomUUID();

	private ChatApi api(boolean pushOn) {
		PushSettings settings = new PushSettings(pushOn ? "pub" : "", pushOn ? "priv" : "", pushOn ? "mailto:x@y" : "",
				"http://staff", "http://client", "http://expert");
		return new ChatApi(null, null, null, null, null, null, null, settings, subscriptions);
	}

	private static ChatApi.SubscribeRequest request(String endpoint) {
		return new ChatApi.SubscribeRequest(endpoint, new ChatApi.SubscribeRequest.Keys("p256", "auth"));
	}

	@Test
	void thePublicKeyIsUnavailableWhenPushIsOff() {
		assertThatThrownBy(() -> api(false).publicKey()).isInstanceOf(PushUnavailableException.class);
		assertThat(api(true).publicKey()).containsEntry("publicKey", "pub");
	}

	/** The GM takes part in no conversation, so there is nothing to notify them about. */
	@Test
	void aGmCannotSubscribe() {
		ChatIdentity gm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM);

		assertThatThrownBy(() -> api(true).subscribe(gm, request("https://push/1")))
				.isInstanceOf(ForbiddenException.class);
		verify(subscriptions, never()).save(any());
	}

	@Test
	void subscribingStoresTheBrowserForTheCaller() {
		ChatIdentity client = ChatIdentity.client(UUID.randomUUID(), brand);
		when(subscriptions.findByEndpoint("https://push/2")).thenReturn(Optional.empty());

		api(true).subscribe(client, request("https://push/2"));

		ArgumentCaptor<PushSubscription> saved = ArgumentCaptor.forClass(PushSubscription.class);
		verify(subscriptions).save(saved.capture());
		assertThat(saved.getValue().belongsTo(ParticipantKind.CLIENT, client.id())).isTrue();
		assertThat(saved.getValue().getBrandId()).isEqualTo(brand);
	}

	/** A shared computer's browser follows whoever signed in last. */
	@Test
	void aBrowserAlreadySubscribedBySomeoneElseMovesToTheCaller() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		PushSubscription previous = new PushSubscription(brand, ParticipantKind.CLIENT, UUID.randomUUID(),
				"https://push/3", "p", "a");
		when(subscriptions.findByEndpoint("https://push/3")).thenReturn(Optional.of(previous));

		api(true).subscribe(pm, request("https://push/3"));

		verify(subscriptions).delete(previous);
		verify(subscriptions).flush();
		verify(subscriptions).save(any(PushSubscription.class));
	}

	@Test
	void unsubscribingRemovesOnlyTheCallersOwnBrowser() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		PushSubscription someoneElses = new PushSubscription(brand, ParticipantKind.CLIENT, UUID.randomUUID(),
				"https://push/4", "p", "a");
		when(subscriptions.findByEndpoint("https://push/4")).thenReturn(Optional.of(someoneElses));

		api(true).unsubscribe(pm, new ChatApi.UnsubscribeRequest("https://push/4"));

		verify(subscriptions, never()).delete(any());
	}
}
