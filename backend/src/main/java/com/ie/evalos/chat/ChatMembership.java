package com.ie.evalos.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who should be in each of a case's conversations (Unit 57 §1). Pure: a roster in, a list out.
 *
 * <p><strong>One person, one row.</strong> Somebody who is both the Case Manager and a pipeline
 * Sales holder is one member, labelled by the case role — the order below adds case roles first and
 * the first label for an id wins.
 */
public final class ChatMembership {

	private ChatMembership() {
	}

	public static List<ExpectedMember> expected(CaseRoster roster, ConversationType type) {
		Map<String, ExpectedMember> byIdentity = new LinkedHashMap<>();
		boolean sales = type == ConversationType.CLIENT || type == ConversationType.INTERNAL;
		boolean enms = type == ConversationType.INTERNAL || type == ConversationType.EXPERT;

		// The case team is in all three, and goes first so a case role outranks SALES or ENM.
		add(byIdentity, ParticipantKind.STAFF, roster.pm(), ChatRole.PM);
		add(byIdentity, ParticipantKind.STAFF, roster.coordinator(), ChatRole.COORDINATOR);
		add(byIdentity, ParticipantKind.STAFF, roster.caseManager(), ChatRole.CASE_MANAGER);
		if (sales) {
			roster.pipelineSales().forEach((id) -> add(byIdentity, ParticipantKind.STAFF, id, ChatRole.SALES));
		}
		if (enms) {
			roster.enms().forEach((id) -> add(byIdentity, ParticipantKind.STAFF, id, ChatRole.ENM));
		}
		if (type == ConversationType.CLIENT) {
			add(byIdentity, ParticipantKind.CLIENT, roster.clientAccountId(), ChatRole.CLIENT);
		}
		if (type == ConversationType.EXPERT) {
			add(byIdentity, ParticipantKind.EXPERT, roster.expertId(), ChatRole.EXPERT);
		}
		return new ArrayList<>(byIdentity.values());
	}

	private static void add(Map<String, ExpectedMember> into, ParticipantKind kind, UUID id, ChatRole role) {
		if (id != null) {
			into.putIfAbsent(kind + ":" + id, new ExpectedMember(kind, id, role));
		}
	}
}
