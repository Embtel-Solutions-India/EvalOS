package com.ie.evalos.service;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.service.ClientAccountService.CaseAccountOutcome;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit 64 §2: a case opens its client's portal account, and never at the case's expense.
 *
 * <p>What each outcome does to the account is {@code ClientAccountServiceTest}'s; this pins the
 * listener's own rules — which cases it acts on, which outcomes a person is told about, and that a
 * failure stays inside it.
 */
class CasePortalAccountListenerTest {

	private static final UUID PORTAL_BRAND = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();
	private static final UUID CONTACT_ID = UUID.randomUUID();

	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final ClientAccountService accounts = mock(ClientAccountService.class);
	private final AuditService audit = mock(AuditService.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

	private final CasePortalAccountListener listener;

	CasePortalAccountListenerTest() {
		given(transactions.getTransaction(any())).willReturn(new SimpleTransactionStatus());
		listener = new CasePortalAccountListener(contacts, accounts, audit, transactions, PORTAL_BRAND);
	}

	private static CaseEvents.CaseEvent created(UUID brandId) {
		return new CaseEvents.CaseEvent(CaseEvents.Type.CASE_CREATED, brandId, CASE_ID, CONTACT_ID, null, null);
	}

	private ContactSnapshot givenContact(UUID brandId) {
		ContactSnapshot contact = new ContactSnapshot(brandId, "ghl-ana");
		given(contacts.findById(CONTACT_ID)).willReturn(Optional.of(contact));
		return contact;
	}

	@Test
	void aNewCaseOpensTheAccountAndFlagsNothing() {
		ContactSnapshot contact = givenContact(PORTAL_BRAND);
		given(accounts.openForCase(contact)).willReturn(CaseAccountOutcome.CREATED);

		listener.on(created(PORTAL_BRAND));

		verify(accounts).openForCase(contact);
		verifyNoInteractions(audit);
	}

	/** Row 1: the portal is single-brand, so a case in another brand has no portal to open. */
	@Test
	void aCaseInAnotherBrandIsLeftAlone() {
		listener.on(created(UUID.randomUUID()));

		verifyNoInteractions(contacts, accounts, audit);
	}

	@Test
	void otherCaseEventsAreIgnored() {
		listener.on(new CaseEvents.CaseEvent(CaseEvents.Type.PM_ASSIGNED, PORTAL_BRAND, CASE_ID, CONTACT_ID, null,
				null));

		verifyNoInteractions(contacts, accounts);
	}

	/** Rows 2, 3 and 8 are things a person must fix, so each becomes a flag on the case. */
	@Test
	void anOutcomeSomebodyMustFixIsFlaggedOnTheCase() {
		ContactSnapshot contact = givenContact(PORTAL_BRAND);
		given(accounts.openForCase(contact)).willReturn(CaseAccountOutcome.OTHER_CONTACT);

		listener.on(created(PORTAL_BRAND));

		verify(audit).recordSystemEvent(eq(PORTAL_BRAND), eq("CASE"), eq(CASE_ID), eq(AuditAction.FLAGGED), isNull(),
				contains("belongs to another contact"));
	}

	/** A contact row from another brand is not this case's to act on. */
	@Test
	void aContactFromAnotherBrandIsNotUsed() {
		givenContact(UUID.randomUUID());

		listener.on(created(PORTAL_BRAND));

		verify(accounts, never()).openForCase(any());
		verify(audit).recordSystemEvent(eq(PORTAL_BRAND), eq("CASE"), eq(CASE_ID), eq(AuditAction.FLAGGED), isNull(),
				contains("no GHL contact"));
	}

	/** Row 9: whatever goes wrong, the case stands — nothing propagates to the webhook. */
	@Test
	void aFailureIsFlaggedAndNeverThrown() {
		ContactSnapshot contact = givenContact(PORTAL_BRAND);
		given(accounts.openForCase(contact)).willThrow(new IllegalStateException("smtp down"));

		assertThatCode(() -> listener.on(created(PORTAL_BRAND))).doesNotThrowAnyException();
		verify(audit).recordSystemEvent(eq(PORTAL_BRAND), eq("CASE"), eq(CASE_ID), eq(AuditAction.FLAGGED), isNull(),
				contains("smtp down"));
	}
}
