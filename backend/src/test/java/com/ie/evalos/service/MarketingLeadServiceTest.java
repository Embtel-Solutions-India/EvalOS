package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The marketing desk's scope checks, which are the whole of its security.
 *
 * <p>Every method resolves the pipeline from the principal and refuses an opportunity outside
 * it. The tests worth reading twice are {@link #anotherDesksOpportunityIsRefused} and
 * {@link #aNoteCarriesTheCallersBrandAndPipelineNotTheRequests} — between them they say that
 * nothing a caller sends can widen what they reach or mislabel what they write.
 */
class MarketingLeadServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();
	private static final String MINE = "pipe_mine";
	private static final String OPPORTUNITY = "opp_1";

	private final GhlWriteClient ghl = mock(GhlWriteClient.class);
	private final OpportunityMirrorService deals = mock(OpportunityMirrorService.class);
	private final MarketingLeadService service =
			new MarketingLeadService(ghl, new PipelineScope(deals));

	private void authenticate(Role role, String pipelineId) {
		StaffPrincipal principal = new StaffPrincipal(MEMBER, "desk@ie.test", "Desk", role, BRAND, null,
				pipelineId, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private void givenTheOpportunityIsMine() {
		when(deals.isOnPipeline(OPPORTUNITY, MINE)).thenReturn(true);
	}

	// --- opening a lead --------------------------------------------------------

	@Test
	void opensALeadOnTheCallersOwnPipeline() {
		authenticate(Role.MARKETING, MINE);
		when(ghl.upsertContact(any(), any(), any(), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedContact("c1", "Ada Lovelace", "ada@example.test", null));
		when(ghl.upsertOpportunity(eq(MINE), eq("c1"), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedOpportunity("o1", "c1", MINE, "s1", "open",
						"Ada Lovelace", new BigDecimal("500"), true));

		MarketingLeadService.Lead lead = service.openLead("Ada", "Lovelace", "ada@example.test", null,
				null, new BigDecimal("500"));

		// The pipeline handed to GHL is the caller's, never anything they supplied.
		verify(ghl).upsertOpportunity(eq(MINE), eq("c1"), eq("Ada Lovelace"), eq(new BigDecimal("500")));
		assertThat(lead.contactId()).isEqualTo("c1");
		assertThat(lead.opportunityId()).isEqualTo("o1");
		assertThat(lead.created()).isTrue();
	}

	/**
	 * A second submission of the same lead reports {@code created = false} rather than making a
	 * second deal — the whole reason §3a chose upsert over create.
	 */
	@Test
	void aRepeatSubmissionIsNotASecondDeal() {
		authenticate(Role.MARKETING, MINE);
		when(ghl.upsertContact(any(), any(), any(), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedContact("c1", "Ada", "ada@example.test", null));
		when(ghl.upsertOpportunity(any(), any(), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedOpportunity("o1", "c1", MINE, "s1", "open", "Ada",
						null, false));

		assertThat(service.openLead("Ada", null, "ada@example.test", null, null, null).created()).isFalse();
	}

	/**
	 * <strong>A lead with neither email nor phone is refused before anything is written.</strong>
	 *
	 * <p>GHL dedupes a contact on email then phone. With neither there is nothing to match on, so
	 * the endpoint chosen precisely for its idempotency silently stops being idempotent and every
	 * save creates another contact.
	 */
	@Test
	void aLeadWithNoEmailAndNoPhoneIsRefused() {
		authenticate(Role.MARKETING, MINE);

		assertThatThrownBy(() -> service.openLead("Ada", "Lovelace", "  ", null, null, null))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("email or a phone");

		verify(ghl, never()).upsertContact(any(), any(), any(), any(), any());
	}

	/** Fail closed: no pipeline on the principal means no write, not a default. */
	@Test
	void aCallerWithNoPipelineCannotOpenALead() {
		authenticate(Role.MARKETING, null);

		assertThatThrownBy(() -> service.openLead("Ada", null, "ada@example.test", null, null, null))
				.isInstanceOf(ForbiddenException.class);

		verify(ghl, never()).upsertContact(any(), any(), any(), any(), any());
	}

	// --- the scope check in front of every write -------------------------------

	/**
	 * <strong>An opportunity outside the caller's pipeline is refused, and with 403.</strong>
	 *
	 * <p>Not 404: "no such opportunity" and "not yours" must answer identically, or the response
	 * becomes an oracle for which ids exist in the GHL location.
	 */
	@Test
	void anotherDesksOpportunityIsRefused() {
		authenticate(Role.MARKETING, MINE);
		when(deals.isOnPipeline("opp_theirs", MINE)).thenReturn(false);

		assertThatThrownBy(() -> service.value("opp_theirs", null, BigDecimal.TEN))
				.isInstanceOf(ForbiddenException.class);

		verify(ghl, never()).updateOpportunity(any(), any(), any(), any(), any());
	}

	@Test
	void theScopeCheckAsksAboutTheCallersOwnPipeline() {
		authenticate(Role.MARKETING, MINE);
		givenTheOpportunityIsMine();
		when(ghl.updateOpportunity(any(), any(), any(), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedOpportunity(OPPORTUNITY, "c1", MINE, "s1", "open",
						"Ada", BigDecimal.TEN, false));

		service.value(OPPORTUNITY, null, BigDecimal.TEN);

		verify(deals).isOnPipeline(OPPORTUNITY, MINE);
	}

	// --- valuation -------------------------------------------------------------

	/** The valuation goes to GHL's own field. There is no EvalOS column holding a second one. */
	@Test
	void theValuationIsWrittenToGhl() {
		authenticate(Role.MARKETING, MINE);
		givenTheOpportunityIsMine();
		when(ghl.updateOpportunity(any(), any(), any(), any(), any()))
				.thenReturn(new GhlWriteClient.UpsertedOpportunity(OPPORTUNITY, "c1", MINE, "s1", "open",
						"Ada", new BigDecimal("2500"), false));

		MarketingLeadService.Lead lead = service.value(OPPORTUNITY, "Ada — expedited",
				new BigDecimal("2500"));

		verify(ghl).updateOpportunity(OPPORTUNITY, MINE, "Ada — expedited", new BigDecimal("2500"), null);
		// What comes back is GHL's answer, not the request body echoed.
		assertThat(lead.monetaryValue()).isEqualByComparingTo("2500");
	}
}
