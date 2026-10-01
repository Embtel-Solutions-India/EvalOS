package com.ie.evalos.chat.live;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Unit 70 §6: one publish per case per transaction, none on rollback, the right channels. */
class CaseLiveTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID CONTACT = UUID.randomUUID();
	private static final UUID EXPERT = UUID.randomUUID();
	private static final UUID OFFERED_EXPERT = UUID.randomUUID();
	private static final UUID ACCOUNT = UUID.randomUUID();

	private final ChatRealtime realtime = mock(ChatRealtime.class);
	private final CaseRepository cases = mock(CaseRepository.class);
	private final ClientAccountRepository clients = mock(ClientAccountRepository.class);
	private final ExpertCaseOfferRepository offers = mock(ExpertCaseOfferRepository.class);
	private final CaseLive live = new CaseLive(realtime, cases, mock(CaseDocumentRepository.class), clients, offers,
			Runnable::run);

	@BeforeEach
	void stubs() {
		given(realtime.enabled()).willReturn(true);
		Case subject = mock(Case.class);
		given(subject.getBrandId()).willReturn(BRAND);
		given(subject.getContactId()).willReturn(CONTACT);
		given(subject.getExpertId()).willReturn(EXPERT);
		given(cases.findById(CASE_ID)).willReturn(Optional.of(subject));
		ClientAccount account = mock(ClientAccount.class);
		given(account.getId()).willReturn(ACCOUNT);
		given(clients.findByBrandIdAndContactId(BRAND, CONTACT)).willReturn(Optional.of(account));
		ExpertCaseOffer offer = mock(ExpertCaseOffer.class);
		given(offer.getBrandId()).willReturn(BRAND);
		given(offer.getExpertId()).willReturn(OFFERED_EXPERT);
		given(offers.findByCaseIdAndOutcome(CASE_ID, OfferOutcome.OFFERED)).willReturn(List.of(offer));
	}

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.unbindResourceIfPossible(CaseLive.class);
	}

	@Test
	void aCaseSignalReachesItsBrandItsClientAndEveryExpertOnIt() {
		live.touched(BRAND, CASE_ID);

		verify(realtime).publish(eq("live:brand:" + BRAND), eq("case.changed"), any());
		verify(realtime).publish(eq("chat:user:CLIENT:" + ACCOUNT), eq("case.changed"), any());
		verify(realtime).publish(eq("chat:user:EXPERT:" + EXPERT), eq("case.changed"), any());
		verify(realtime).publish(eq("chat:user:EXPERT:" + OFFERED_EXPERT), eq("case.changed"), any());
	}

	@Test
	void twoWritesInOneTransactionPublishOnceAndOnlyAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();
		live.touched(BRAND, CASE_ID);
		live.touched(BRAND, CASE_ID);
		verify(realtime, never()).publish(anyString(), anyString(), any());

		TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

		verify(realtime, times(1)).publish(eq("live:brand:" + BRAND), eq("case.changed"), any());
	}

	@Test
	void aRolledBackWritePublishesNothing() {
		TransactionSynchronizationManager.initSynchronization();
		live.touched(BRAND, CASE_ID);
		live.notificationsChanged(UUID.randomUUID());

		TransactionSynchronizationManager.getSynchronizations()
				.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		verify(realtime, never()).publish(anyString(), anyString(), any());
	}

	/** A stray id never reaches another brand's client or expert: the case must be in the brand named. */
	@Test
	void aCaseOfAnotherBrandSignalsOnlyThatBrandsStaffChannel() {
		UUID other = UUID.randomUUID();
		live.touched(other, CASE_ID);

		verify(realtime).publish(eq("live:brand:" + other), eq("case.changed"), any());
		verify(realtime, never()).publish(eq("chat:user:CLIENT:" + ACCOUNT), anyString(), any());
	}

	@Test
	void aBellSignalGoesToItsOwnersStaffChannel() {
		UUID pm = UUID.randomUUID();
		live.notificationsChanged(pm);

		verify(realtime).publish(eq("chat:user:STAFF:" + pm), eq("notifications.changed"), any());
	}

	@Test
	void nothingIsPublishedWithoutAbly() {
		given(realtime.enabled()).willReturn(false);
		live.touched(BRAND, CASE_ID);
		live.notificationsChanged(UUID.randomUUID());

		verify(realtime, never()).publish(anyString(), anyString(), any());
	}
}
