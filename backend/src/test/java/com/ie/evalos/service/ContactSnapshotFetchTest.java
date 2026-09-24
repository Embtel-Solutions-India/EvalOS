package com.ie.evalos.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.integration.GhlContactClient;
import com.ie.evalos.integration.GhlFailure;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.repository.ContactSnapshotRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code findOrFetch} — the read that fills the deal screen's contact card.
 *
 * <p>Four behaviours, each of which is one line away from being wrong in a way the screen would
 * not show. The mirror <strong>must win</strong> when it holds the row, or a busy pipeline spends
 * its rate limit re-reading contacts it already has and the screen stops working with the sync
 * off. GHL <strong>must be asked</strong> when it does not, or the blank card this exists to fix
 * comes straight back. What GHL returns <strong>must be kept</strong>, or the gap re-opens on
 * every page view. And a GHL failure <strong>must not throw</strong>, or an upstream blip takes
 * the notes, the questionnaire and the actions down with the contact card.
 */
class ContactSnapshotFetchTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final String GHL_CONTACT = "ghl-contact-1";

	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final GhlContactClient ghl = mock(GhlContactClient.class);
	private final ContactSnapshotService service = new ContactSnapshotService(contacts, ghl);

	@Test
	void aContactTheMirrorAlreadyHoldsIsReturnedWithoutTouchingGhl() {
		ContactSnapshot held = new ContactSnapshot(BRAND, GHL_CONTACT);
		given(contacts.findByBrandIdAndGhlContactId(BRAND, GHL_CONTACT)).willReturn(Optional.of(held));

		assertThat(service.findOrFetch(BRAND, GHL_CONTACT)).containsSame(held);

		// The property Unit 48 has to be able to claim: the screen works with the sync off.
		verifyNoInteractions(ghl);
	}

	@Test
	void aContactTheMirrorHasNeverSeenIsFetchedFromGhlAndKept() {
		given(contacts.findByBrandIdAndGhlContactId(BRAND, GHL_CONTACT)).willReturn(Optional.empty());
		given(contacts.findByBrandIdAndEmailIgnoreCase(any(), anyString())).willReturn(List.of());
		given(ghl.byId(GHL_CONTACT)).willReturn(new GhlContactClient.Contact(GHL_CONTACT,
				"Karim Nabil", "karim@example.com", "+15550000", "Nabil & Co"));
		given(contacts.save(any(ContactSnapshot.class))).willAnswer((call) -> call.getArgument(0));

		Optional<ContactSnapshot> found = service.findOrFetch(BRAND, GHL_CONTACT);

		assertThat(found).isPresent();
		assertThat(found.get().getFullName()).isEqualTo("Karim Nabil");
		assertThat(found.get().getEmail()).isEqualTo("karim@example.com");
		assertThat(found.get().getCompany()).isEqualTo("Nabil & Co");
		// Kept, so the second open of the same deal is a mirror read again rather than a
		// second GHL call. Without this the gap re-opens on every page view.
		verify(contacts).save(any(ContactSnapshot.class));
	}

	@Test
	void aGhlOutageLeavesTheCardEmptyRatherThanFailingTheScreen() {
		given(contacts.findByBrandIdAndGhlContactId(BRAND, GHL_CONTACT)).willReturn(Optional.empty());
		given(ghl.byId(GHL_CONTACT)).willThrow(new GhlUnavailableException("GHL said no", null,
				GhlFailure.UPSTREAM_ERROR, 503));

		assertThat(service.findOrFetch(BRAND, GHL_CONTACT)).isEmpty();
		verify(contacts, never()).save(any(ContactSnapshot.class));
	}

	@Test
	void aDealWithNoContactIdAsksGhlNothing() {
		assertThat(service.findOrFetch(BRAND, null)).isEmpty();
		assertThat(service.findOrFetch(BRAND, "  ")).isEmpty();

		// Not merely "returns empty": a blank id reaching GHL would be a request for
		// /contacts/, which is a different endpoint entirely.
		verifyNoInteractions(ghl);
		verifyNoInteractions(contacts);
	}
}
