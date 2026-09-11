package com.ie.evalos.service;

import java.util.UUID;

import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.security.PortalPrincipal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The half of {@link PortalMeetingService} no {@code @WebMvcTest} can reach, because
 * {@code ClientPortalMeetingTest} mocks this service out: what it does with the contact id on the
 * credential before it calls GHL.
 */
class PortalMeetingServiceTest {

	private final GhlCalendarClient calendars = mock(GhlCalendarClient.class);

	private final PortalMeetingService service = new PortalMeetingService(calendars);

	/**
	 * <strong>An account-scoped credential answers empty, not a GHL call with a null id</strong>
	 * (Unit 42). The contact id is a path segment here, so passing a null through would request
	 * {@code /contacts/null/appointments} — a call that can only fail, and only after the client
	 * has waited for it.
	 */
	@Test
	void anAccountScopedCredentialAnswersEmptyWithoutCallingGhl() {
		PortalPrincipal signedInWithNoContact = new PortalPrincipal(UUID.randomUUID(),
				UUID.randomUUID(), null, PortalAudience.CLIENT, null, null);

		assertThat(service.forCaller(signedInWithNoContact)).isEmpty();

		verify(calendars, never()).forContact(any());
	}
}
