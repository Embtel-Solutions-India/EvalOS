package com.ie.evalos.service;

import java.util.List;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.security.PortalPrincipal;

import org.springframework.stereotype.Service;

/**
 * The client's own meetings, for the Client Portal.
 *
 * <p><strong>This closes the last half of "the portal shows what the client is owed".</strong>
 * Invoices landed with Unit 41; a meeting is the other thing a client has with the business
 * before a case exists, and until now nothing showed it. Sales books it from the desk
 * ({@code SalesMeetingService}), GHL sends the invitation, and this is where the client sees it
 * afterwards — without an inbox search.
 *
 * <p><strong>What the portal deliberately does NOT show, decided 2026-09-11.</strong> Invoices
 * and meetings, and nothing else of the opportunity: no pipeline stage, no deal value, no sales
 * notes. The stage names are written for staff — "Warm", "Cold", "Hot" — and showing a prospect
 * that they are currently "Cold" is the kind of leak no amount of relabelling makes safe. Deal
 * value is invariant 4's neighbourhood, and {@code opportunity_note} is explicitly the
 * Sales-for-Sales stream. If a client-facing status is ever wanted it needs a projection with a
 * decision per stage, the way {@code PortalStageProjection} does for cases.
 *
 * <p><strong>The contact id comes off the credential and can come from nowhere else</strong> —
 * word for word the rule {@link PortalInvoiceService} follows. There is no route parameter for a
 * contact and there must never be one.
 *
 * <p><strong>Nothing is stored.</strong> No table, no migration, no cache. One client's
 * appointments are one small response and {@code GhlHttp}'s limiter already paces it, so
 * {@code 00b} §1.3's "GHL is truth" holds in its strongest form here too.
 */
@Service
public class PortalMeetingService {

	private final GhlCalendarClient calendars;

	PortalMeetingService(GhlCalendarClient calendars) {
		this.calendars = calendars;
	}

	/**
	 * Every meeting for the party this credential names.
	 *
	 * <p><strong>A case-scoped credential is refused</strong>, for the identical reason invoices
	 * are: a meeting belongs to the <em>client</em>, not to one engagement, and GHL offers no
	 * filter that would narrow it to a case. Answering with everything would hand a narrow
	 * credential a wide reply; answering with nothing would be a silent lie. The refusal says
	 * which link they are holding.
	 */
	public List<GhlCalendarClient.ClientMeeting> forCaller(PortalPrincipal principal) {
		if (!principal.isPartyScoped()) {
			throw new ForbiddenException("This link opens one case rather than your account");
		}
		return calendars.forContact(principal.ghlContactId());
	}
}
