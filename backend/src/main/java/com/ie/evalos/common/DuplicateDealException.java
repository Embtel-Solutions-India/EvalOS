package com.ie.evalos.common;

/**
 * A salesperson is opening a deal for a contact who already has an open one on their pipeline.
 *
 * <p><strong>Not an error, which is why it has its own type.</strong> A repeat client buying a
 * second service is ordinary business, and Unit 39 §3a expected it — the whole reason the sales
 * create path uses {@code POST /opportunities/} rather than upsert is that upsert would silently
 * overwrite the first deal instead of opening the second. What was lost with upsert was its
 * accidental duplicate protection; this is what replaces it.
 *
 * <p>So the answer is a question, not a refusal: the caller is shown the deal that already exists
 * and may confirm a second. It maps to 409 with {@code DEAL_ALREADY_OPEN} so the form can draw a
 * confirmation step rather than an error state.
 *
 * <p>Lives in {@code common} beside {@link AmbiguousCaseException}, which is the same shape of
 * problem — "you have not made a mistake, but I need you to choose" — and for the same layering
 * reason: {@code ApiExceptionHandler} is in this package and must not import {@code service}.
 */
public class DuplicateDealException extends RuntimeException {

	private final String existingOpportunityId;

	public DuplicateDealException(String existingOpportunityId) {
		super("That contact already has an open deal on your pipeline");
		this.existingOpportunityId = existingOpportunityId;
	}

	public String existingOpportunityId() {
		return existingOpportunityId;
	}
}
