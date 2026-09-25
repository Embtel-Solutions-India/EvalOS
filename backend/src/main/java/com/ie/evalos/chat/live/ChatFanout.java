package com.ie.evalos.chat.live;

import java.util.List;

import com.ie.evalos.chat.ChatChanged;
import com.ie.evalos.chat.ChatViews;
import com.ie.evalos.chat.ConversationMember;
import com.ie.evalos.chat.ConversationMemberRepository;
import com.ie.evalos.chat.ExpectedMember;
import com.ie.evalos.chat.MembersChanged;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes committed chat changes into each current member's private Ably channel (Unit 57 §5).
 *
 * <p><strong>Recipients are read per event from current membership</strong>, so somebody reassigned
 * off a case receives nothing after they leave — their token never named the conversation.
 */
@Component
public class ChatFanout {

	private final ChatRealtime realtime;
	private final ConversationMemberRepository members;
	private final java.util.concurrent.Executor executor;

	ChatFanout(ChatRealtime realtime, ConversationMemberRepository members,
			@org.springframework.beans.factory.annotation.Qualifier("chatFanoutExecutor") java.util.concurrent.Executor executor) {
		this.realtime = realtime;
		this.members = members;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void on(ChatChanged change) {
		if (!realtime.enabled()) {
			return;
		}
		// Membership is read now, as committed; the Ably calls run off the request thread (review I6).
		List<ConversationMember> current =
				members.findByBrandIdAndConversationIdAndLeftAtIsNull(change.brandId(), change.conversationId());
		executor.execute(() -> publish(change, current));
	}

	private void publish(ChatChanged change, List<ConversationMember> current) {
		switch (change.kind()) {
			case MESSAGE_CREATED -> {
				ChatViews.MessageView message = (ChatViews.MessageView) change.payload();
				ChatEnvelope created = new ChatEnvelope("message.created", change.conversationId(), message);
				ChatEnvelope unread = new ChatEnvelope("unread.changed", change.conversationId(), null);
				for (ConversationMember member : current) {
					send(member, created);
					boolean author = member.getKind() == message.authorKind() && member.getMemberId().equals(message.authorId());
					if (!author) {
						send(member, unread);
					}
				}
				view(change, created);
			}
			case MESSAGE_EDITED -> everyone(current, change, "message.edited");
			case MESSAGE_DELETED -> everyone(current, change, "message.deleted");
			case REACTIONS_CHANGED -> everyone(current, change, "reactions.changed");
			case READ_MOVED -> everyone(current, change, "read.moved");
			case READ_ONLY -> everyone(current, change, "conversation.read_only");
			case MEMBERS_CHANGED -> {
				MembersChanged diff = (MembersChanged) change.payload();
				diff.added().forEach((m) -> sendTo(m, new ChatEnvelope("access.granted", change.conversationId(), null)));
				diff.removed().forEach((m) -> sendTo(m, new ChatEnvelope("access.revoked", change.conversationId(), null)));
				everyone(current, change, "members.changed");
			}
		}
	}

	private void everyone(List<ConversationMember> current, ChatChanged change, String type) {
		ChatEnvelope envelope = new ChatEnvelope(type, change.conversationId(), change.payload());
		current.forEach((member) -> send(member, envelope));
		view(change, envelope);
	}

	private void send(ConversationMember member, ChatEnvelope envelope) {
		realtime.publish(ChatChannels.personal(member.getKind(), member.getMemberId()), envelope.type(), envelope);
	}

	private void sendTo(ExpectedMember member, ChatEnvelope envelope) {
		realtime.publish(ChatChannels.personal(member.kind(), member.id()), envelope.type(), envelope);
	}

	private void view(ChatChanged change, ChatEnvelope envelope) {
		realtime.publish(ChatChannels.view(change.brandId(), change.conversationId()), envelope.type(), envelope);
	}
}
