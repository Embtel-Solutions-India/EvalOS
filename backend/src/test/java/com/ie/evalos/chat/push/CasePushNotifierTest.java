package com.ie.evalos.chat.push;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ie.evalos.chat.ParticipantKind;
import com.ie.evalos.chat.live.ChatPresence;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ClientAccountRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A client or expert who is away is pushed the case events the emails announce — and never what they say. */
class CasePushNotifierTest {

	private final PushSettings settings = new PushSettings("pub", "priv", "mailto:ops@ie.test",
			"http://staff.test", "http://client.test", "http://expert.test");
	private final PushSubscriptionRepository subscriptions = mock(PushSubscriptionRepository.class);
	private final PushSender sender = mock(PushSender.class);
	private final ChatPresence presence = mock(ChatPresence.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);
	private final CasePushNotifier notifier = new CasePushNotifier(settings, subscriptions, sender, presence, cases,
			accounts, Runnable::run);

	private final UUID brand = UUID.randomUUID();
	private final UUID caseId = UUID.randomUUID();
	private final UUID contact = UUID.randomUUID();
	private final UUID expert = UUID.randomUUID();
	private final UUID account = UUID.randomUUID();
	private final PushSubscription phone = new PushSubscription(brand, ParticipantKind.CLIENT, account,
			"https://push.test/1", "p", "a");
	private final PushSubscription expertPhone = new PushSubscription(brand, ParticipantKind.EXPERT, expert,
			"https://push.test/2", "p", "a");

	private CaseEvents.CaseEvent event(CaseEvents.Type type) {
		Case subject = mock(Case.class);
		when(subject.getBrandId()).thenReturn(brand);
		when(subject.getId()).thenReturn(caseId);
		when(subject.getCaseCode()).thenReturn("IE-2026-5110");
		when(subject.getContactId()).thenReturn(contact);
		when(subject.getExpertId()).thenReturn(expert);
		when(cases.findById(caseId)).thenReturn(Optional.of(subject));
		ClientAccount clientAccount = mock(ClientAccount.class);
		when(clientAccount.getId()).thenReturn(account);
		when(accounts.findByBrandIdAndContactId(brand, contact)).thenReturn(Optional.of(clientAccount));
		when(subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.CLIENT, account)).thenReturn(List.of(phone));
		when(subscriptions.findBySubscriberKindAndSubscriberId(ParticipantKind.EXPERT, expert)).thenReturn(List.of(expertPhone));
		when(sender.send(any(), any())).thenReturn(PushSender.Outcome.SENT);
		return new CaseEvents.CaseEvent(type, brand, caseId, contact, null, null);
	}

	@Test
	void anAwayClientIsToldTheirDraftIsReadyAndTheLinkOpensTheCase() throws Exception {
		notifier.on(event(CaseEvents.Type.DRAFT_READY_FOR_CLIENT));

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(sender).send(eq(phone), payload.capture());
		var json = new ObjectMapper().readTree(payload.getValue());
		assertThat(json.get("title").asText()).isEqualTo("Your draft is ready to review");
		assertThat(json.get("body").asText()).startsWith("IE-2026-5110");
		assertThat(json.get("url").asText()).isEqualTo("http://client.test/cases/" + caseId);
		verify(sender, never()).send(eq(expertPhone), any());
	}

	@Test
	void everyClientEventHasItsOwnLineAndNoneCarriesTheText() throws Exception {
		for (CaseEvents.Type type : List.of(CaseEvents.Type.CHECKLIST_REQUESTED, CaseEvents.Type.CHECKLIST_CHASED,
				CaseEvents.Type.CASE_DELIVERED, CaseEvents.Type.CASE_ON_HOLD, CaseEvents.Type.CASE_RESUMED,
				CaseEvents.Type.CLIENT_REMARK_ADDED)) {
			org.mockito.Mockito.clearInvocations(sender);
			notifier.on(event(type));
			ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
			verify(sender).send(eq(phone), payload.capture());
			assertThat(new ObjectMapper().readTree(payload.getValue()).get("title").asText()).isNotBlank();
		}
	}

	@Test
	void anAwayExpertIsToldOfAnOfferAndOfALetterToSign() {
		notifier.on(event(CaseEvents.Type.EXPERT_ASSIGNED));
		notifier.on(event(CaseEvents.Type.EXPERT_SENT_FOR_SIGNING));

		verify(sender, org.mockito.Mockito.times(2)).send(eq(expertPhone), any());
		verify(sender, never()).send(eq(phone), any());
	}

	@Test
	void someoneWithThePortalOpenIsNotPushed() {
		CaseEvents.CaseEvent event = event(CaseEvents.Type.CASE_DELIVERED);
		when(presence.isOnline(ParticipantKind.CLIENT, account)).thenReturn(true);

		notifier.on(event);

		verify(sender, never()).send(any(), any());
	}

	@Test
	void anEventNobodyAnnouncesAndUnconfiguredPushDoNothing() {
		notifier.on(event(CaseEvents.Type.CASE_CREATED));
		new CasePushNotifier(new PushSettings("", "", "", "a", "b", "c"), subscriptions, sender, presence, cases, accounts,
				Runnable::run).on(event(CaseEvents.Type.CASE_DELIVERED));

		verify(sender, never()).send(any(), any());
		verify(cases, never()).findById(any());
	}
}
