package com.ie.evalos.service;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Component;

/**
 * "Is this opportunity mine?" — asked identically by every desk, so asked in one place.
 *
 * <p><strong>Extracted in Unit 40 because there were about to be three copies.</strong> The
 * marketing desk, the shared note stream and the sales desk all need the same two answers: which
 * pipeline the caller owns, and whether a given opportunity is in it. Three copies of a scope
 * check is three places for one of them to drift permissive, and the drift would be invisible —
 * each copy looks correct on its own.
 *
 * <p><strong>The pipeline always comes from the principal, never from the request.</strong> That
 * is the whole access model for these roles: a request that could name a pipeline would make it
 * advisory.
 */
@Component
public class PipelineScope {

	private final OpportunityCache cache;

	PipelineScope(OpportunityCache cache) {
		this.cache = cache;
	}

	/**
	 * The one pipeline this caller owns.
	 *
	 * <p>Fail closed on a principal carrying none — a token minted before {@code V39}, or a row a
	 * GM has not finished setting up. The same rule {@code ScopePredicate}'s PIPELINE arm applies,
	 * and the same safe direction: an empty desk is a support call, somebody else's is a breach.
	 */
	public String mine() {
		String pipelineId = TenantContext.current().ghlPipelineId();
		if (pipelineId == null) {
			throw new ForbiddenException("You have no GHL pipeline assigned. A GM assigns one.");
		}
		return pipelineId;
	}

	/**
	 * Refuses an opportunity outside the caller's pipeline, and returns the pipeline it checked.
	 *
	 * <p><strong>Checked against the cache, and the failure mode is deliberate.</strong> The cache
	 * is droppable, so an opportunity that is real but not yet fetched is refused — a false
	 * negative. That is the correct direction: a refusal is visible and recoverable (the board
	 * refreshes and the action succeeds), whereas trusting an unverified id would let a caller
	 * reach another desk's deal by guessing one.
	 *
	 * <p>Asking GHL instead would be a second round trip on every action against a
	 * 100-per-10-seconds budget, to close a gap the board read has already closed for anything
	 * the caller can actually see on screen.
	 *
	 * <p><strong>403, never 404.</strong> "No such opportunity" and "not yours" must answer
	 * identically, or the response becomes an oracle for which ids exist in the location.
	 */
	public String requireMine(String opportunityId) {
		String pipelineId = mine();
		if (!cache.isInPipeline(opportunityId, pipelineId)) {
			throw new ForbiddenException("That opportunity is not in your pipeline");
		}
		return pipelineId;
	}
}
