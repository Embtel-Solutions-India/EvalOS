package com.ie.evalos.chat.live;

import java.util.UUID;

import com.ie.evalos.chat.ChatIdentity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRealtimeTest {

	private final ChatRealtime off = new ChatRealtime(new AblySettings(""));

	@Test
	void withNoKeyRealtimeIsOffAndSaysSo() {
		assertThat(off.enabled()).isFalse();
		assertThatThrownBy(() -> off.token(ChatIdentity.client(UUID.randomUUID(), UUID.randomUUID())))
				.isInstanceOf(RealtimeUnavailableException.class);
	}

	@Test
	void publishingWithRealtimeOffIsANoOpNotAFailure() {
		assertThatCode(() -> off.publish("chat:user:STAFF:" + UUID.randomUUID(), "message.created", "{}"))
				.doesNotThrowAnyException();
		assertThat(off.isPresent("chat:user:STAFF:" + UUID.randomUUID())).isFalse();
	}

	@Test
	void aConfiguredKeyMintsATokenRequestForTheCallerAlone() {
		// A syntactically valid key; createTokenRequest signs locally and makes no network call.
		ChatRealtime on = new ChatRealtime(new AblySettings("appid.keyid:secretsecretsecret"));
		UUID account = UUID.randomUUID();

		AblyToken token = on.token(ChatIdentity.client(account, UUID.randomUUID()));

		assertThat(token.clientId()).isEqualTo("CLIENT:" + account);
		assertThat(token.capability()).contains("chat:user:CLIENT:" + account).doesNotContain("publish");
		assertThat(token.keyName()).isEqualTo("appid.keyid");
		assertThat(token.mac()).isNotBlank();
	}
}
