package com.ie.evalos.service;

import java.math.BigDecimal;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.integration.GhlWriteClient;

import org.springframework.stereotype.Service;

/**
 * The marketing desk: create a lead, value it, and write notes on it — without opening GHL.
 *
 * <p><strong>Opening a lead is a marketing act, which is why this class is narrow.</strong>
 * Notes moved to {@link OpportunityNoteService} in Unit 40 — both desks write them — and the
 * scope check moved to {@link PipelineScope}, which all three desks now share. What is left here
 * is the two things only Marketing does: bring a lead into existence, and put a first number on
 * it.
 *
 * <p>{@link GhlWriteClient} takes a pipeline id and trusts it; the principal lives on this side,
 * so the check does too — via {@code PipelineScope}, never from anything the request carries.
 */
@Service
public class MarketingLeadService {

	/** What the desk gets back after opening a lead. */
	public record Lead(String contactId, String opportunityId, String name, BigDecimal monetaryValue,
			boolean created) {
	}

	private final GhlWriteClient ghl;
	private final PipelineScope scope;

	MarketingLeadService(GhlWriteClient ghl, PipelineScope scope) {
		this.ghl = ghl;
		this.scope = scope;
	}

	/**
	 * Opens a lead: upsert the contact, then upsert an opportunity on the caller's own pipeline.
	 *
	 * <p><strong>Two writes, and the first one succeeding while the second fails is a real
	 * outcome.</strong> There is no transaction across GHL, and there cannot be. What makes that
	 * survivable is that both halves are upserts: the marketer retries, the contact is matched
	 * rather than duplicated, and only the opportunity is created. That is the whole reason
	 * §3a chose upsert over create — not tidiness, recoverability.
	 */
	public Lead openLead(String firstName, String lastName, String email, String phone, String name,
			BigDecimal monetaryValue) {
		String pipelineId = scope.mine();
		if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
			// GHL dedupes a contact on email then phone. With neither, upsert has nothing to
			// match on and every submission creates another contact — so the endpoint that was
			// chosen for its idempotency would quietly stop being idempotent.
			throw new InvalidRequestException(
					"A lead needs an email or a phone number: GHL matches an existing contact on "
							+ "those, and without either every save creates a new one.");
		}

		GhlWriteClient.UpsertedContact contact = ghl.upsertContact(firstName, lastName, email, phone);
		GhlWriteClient.UpsertedOpportunity opportunity = ghl.upsertOpportunity(pipelineId, contact.id(),
				name == null || name.isBlank() ? contact.name() : name, monetaryValue);

		return new Lead(contact.id(), opportunity.id(), opportunity.name(), opportunity.monetaryValue(),
				opportunity.isNew());
	}

	/**
	 * Records the marketer's valuation, and any renaming, on an opportunity they own.
	 *
	 * <p>The valuation is <strong>GHL's {@code monetaryValue}</strong>, not an EvalOS column: GHL
	 * has the field, so a parallel EvalOS estimate would be two numbers that disagree.
	 */
	public Lead value(String opportunityId, String name, BigDecimal monetaryValue) {
		String pipelineId = scope.requireMine(opportunityId);

		GhlWriteClient.UpsertedOpportunity updated = ghl.updateOpportunity(opportunityId, pipelineId, name,
				monetaryValue, null);
		return new Lead(updated.contactId(), updated.id(), updated.name(), updated.monetaryValue(), false);
	}

}
