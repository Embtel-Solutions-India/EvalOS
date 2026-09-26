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

		assertThatThrownBy(() -> api(true).subscribe(gm, request("https://fcm.googleapis.com/fcm/send/1")))
				.isInstanceOf(ForbiddenException.class);
		verify(subscriptions, never()).save(any());
	}

	@Test
	void subscribingStoresTheBrowserForTheCaller() {
		ChatIdentity client = ChatIdentity.client(UUID.randomUUID(), brand);
		when(subscriptions.findByEndpoint("https://fcm.googleapis.com/fcm/send/2")).thenReturn(Optional.empty());

		api(true).subscribe(client, request("https://fcm.googleapis.com/fcm/send/2"));

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
				"https://fcm.googleapis.com/fcm/send/3", "p", "a");
		when(subscriptions.findByEndpoint("https://fcm.googleapis.com/fcm/send/3")).thenReturn(Optional.of(previous));

		api(true).subscribe(pm, request("https://fcm.googleapis.com/fcm/send/3"));

		verify(subscriptions).delete(previous);
		verify(subscriptions).flush();
		verify(subscriptions).save(any(PushSubscription.class));
	}

	@Test
	void unsubscribingRemovesOnlyTheCallersOwnBrowser() {
		ChatIdentity pm = new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.PROJECT_MANAGER);
		PushSubscription someoneElses = new PushSubscription(brand, ParticipantKind.CLIENT, UUID.randomUUID(),
				"https://fcm.googleapis.com/fcm/send/4", "p", "a");
		when(subscriptions.findByEndpoint("https://fcm.googleapis.com/fcm/send/4")).thenReturn(Optional.of(someoneElses));

		api(true).unsubscribe(pm, new ChatApi.UnsubscribeRequest("https://fcm.googleapis.com/fcm/send/4"));

		verify(subscriptions, never()).delete(any());
	}

	/**
	 * Review I5: the endpoint is a URL the server will POST to, so it is a trust boundary. Only https to a
	 * known browser push service is accepted — never an internal address.
	 */
	@Test
	void onlyAnHttpsEndpointOfAKnownPushServiceIsAccepted() {
		ChatIdentity client = ChatIdentity.client(UUID.randomUUID(), brand);
		when(subscriptions.findByEndpoint(any())).thenReturn(Optional.empty());
		ChatApi api = api(true);

		for (String refused : new String[] { "http://fcm.googleapis.com/fcm/send/x", "https://10.0.0.5:8080/x",
				"https://169.254.169.254/latest/meta-data", "https://localhost/x", "https://evil.example/x",
				"https://fcm.googleapis.com.evil.example/x", "not a url" }) {
			assertThatThrownBy(() -> api.subscribe(client, request(refused)))
					.as(refused).isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
		}
		verify(subscriptions, never()).save(any());

		for (String accepted : new String[] { "https://fcm.googleapis.com/fcm/send/abc",
				"https://updates.push.services.mozilla.com/wpush/v2/abc", "https://web.push.apple.com/abc",
				"https://wns2-par02p.notify.windows.com/w/?token=abc" }) {
			api.subscribe(client, request(accepted));
		}
		verify(subscriptions, org.mockito.Mockito.times(4)).save(any());
	}
}
