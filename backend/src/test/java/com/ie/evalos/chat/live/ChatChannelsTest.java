package com.ie.evalos.chat.live;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ChatIdentity;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.domain.Role;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatChannelsTest {

	private static JsonNode capability(ChatIdentity who) throws Exception {
		return new ObjectMapper().readTree(ChatChannels.capability(who));
	}

	@Test
	void aMemberMayOnlySubscribeToTheirOwnChannelAndNeverPublish() throws Exception {
		UUID pm = UUID.randomUUID();
		JsonNode caps = capability(new ChatIdentity(ParticipantKind.STAFF, pm, UUID.randomUUID(), Role.PROJECT_MANAGER));

		assertThat(caps.size()).isEqualTo(1);
		JsonNode own = caps.get("chat:user:STAFF:" + pm);
		assertThat(own).isNotNull();
		assertThat(own.toString()).contains("subscribe").contains("presence").doesNotContain("publish");
	}

	@Test
	void aClientGetsTheirOwnChannelOnly() throws Exception {
		UUID account = UUID.randomUUID();
		JsonNode caps = capability(ChatIdentity.client(account, UUID.randomUUID()));

		assertThat(caps.fieldNames()).toIterable().containsExactly("chat:user:CLIENT:" + account);
	}

	@Test
	void aBrandManagerMayWatchTheirBrandsConversationsOnly() throws Exception {
		UUID brand = UUID.randomUUID();
		JsonNode caps = capability(new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), brand, Role.BRAND_MANAGER));

		assertThat(caps.get("chat:view:" + brand + ":*").toString()).isEqualTo("[\"subscribe\"]");
		assertThat(caps.has("chat:view:*")).isFalse();
	}

	@Test
	void theGmMayWatchEveryConversation() throws Exception {
		JsonNode caps = capability(new ChatIdentity(ParticipantKind.STAFF, UUID.randomUUID(), null, Role.GM));

		assertThat(caps.get("chat:view:*").toString()).isEqualTo("[\"subscribe\"]");
	}

	@Test
	void theClientIdIsKindAndId() {
		UUID id = UUID.randomUUID();
		assertThat(ChatChannels.clientId(ChatIdentity.expert(id, UUID.randomUUID()))).isEqualTo("EXPERT:" + id);
		assertThat(ChatChannels.personal(ParticipantKind.EXPERT, id)).isEqualTo("chat:user:EXPERT:" + id);
	}
}
