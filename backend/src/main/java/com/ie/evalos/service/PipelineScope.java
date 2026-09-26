package com.ie.evalos.service;

import java.util.List;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
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

	/**
	 * The mirrored opportunities (Unit 44d).
	 *
	 * <p><strong>{@code 00d} §6.5 recorded that access control here had a TTL.</strong> This asked
	 * a cache whose rows were destroyed and recreated on every board refresh, so "a newly opened
	 * lead is <em>immediately</em> unauthorised until the next refill". A mirror that is upserted
	 * rather than replaced closes that half by construction: a row written on create stays written.
	 *
	 * <p>The other half — a deal moved to another rep's pipeline in GHL staying authorised here
	 * until something refreshes — is a staleness bound rather than a hole, and Unit 45's delta sweep
	 * is its fix. {@code 39} §7 already considered and rejected the alternative of asking GHL on
	 * every write: "a second round trip on every write against a 100-per-10-seconds budget, to close
	 * a gap the board read has already closed for anything the caller can actually see."
	 */
	private final OpportunityMirrorService deals;

	PipelineScope(OpportunityMirrorService deals) {
		this.deals = deals;
	}

	/**
	 * The one pipeline this caller owns.
	 *
	 * <p>Fail closed on a principal carrying none — a token minted before {@code V39}, or a row a
	 * GM has not finished setting up. The same rule {@code ScopePredicate}'s PIPELINE arm applies,
	 * and the same safe direction: an empty desk is a support call, somebody else's is a breach.
	 */
	/**
	 * Every pipeline this caller may work — Unit 44b turned this from one id into a set.
	 *
	 * <p>{@code 00d} §6.7: the target pipeline set is nine, and Case Delivery has no single owner,
	 * so "exactly one" was never going to survive. An empty set is still a refusal rather than an
	 * empty answer: a desk with no pipeline cannot do anything here, and saying so is more use than
	 * a screen that silently shows nothing.
	 */
	public List<String> mine() {
		List<String> pipelineIds = TenantContext.current().ghlPipelineIds();
		if (pipelineIds.isEmpty()) {
			throw new ForbiddenException("You have no GHL pipeline assigned. A GM assigns one.");
		}
		return pipelineIds;
	}

	/**
	 * The one pipeline a <em>write</em> lands on, when the caller has exactly one.
	 *
	 * <p><strong>A create has to name a pipeline and the caller must not choose it</strong> — that
	 * is Unit 40's rule and the reason no desk route takes a pipeline parameter. With a set, "the
	 * caller's pipeline" stops being a single answer, so a member on several must say which, and
	 * this refuses rather than guessing. Picking the first would file a deal on whichever pipeline
	 * sorted earliest, which is a silent wrong answer.
	 *
	 * <p>Nobody is on two pipelines yet, so this is the same behaviour as before for every caller
	 * that exists. It becomes reachable the day Case Delivery is assigned, and the fix then is a
	 * pipeline argument on the create route rather than a guess here.
	 */
	public String mineForWrite() {
		List<String> pipelineIds = mine();
		if (pipelineIds.size() > 1) {
			throw new InvalidRequestException(
					"You work " + pipelineIds.size() + " pipelines, so this action has to say which. "
							+ "Open the deal from the board you want it on.");
		}
		return pipelineIds.getFirst();
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
	/**
	 * The caller's pipeline this opportunity is on, or a refusal.
	 *
	 * <p>Returns <em>which</em> one rather than a boolean, because every caller then passes it to
	 * GHL — and with a set, "the caller's pipeline" is no longer a single answer they could have
	 * worked out themselves.
	 */
	/**
	 * A deal the caller may <strong>read</strong>, or a refusal — the three widths, on a deal.
	 *
	 * <p><strong>This exists because {@link #mine()} answers the wrong question for two of the
	 * three roles.</strong> It reads {@code ghlPipelineIds()} off the principal, and D19e says a
	 * GM holds no assignment rows <em>and must not</em>. So a GM's board listed every mirrored deal
	 * and then 403'd on opening any of them: the contact, the notes and the meetings all refused,
	 * on a screen the same GM was looking at a card for. A Brand Manager was in the same position.
	 * {@code 00d} row 73 recorded it as P0 and named the cause exactly — "an implementation
	 * consequence hardened into policy".
	 *
	 * <p><strong>Reading is widened; writing is not.</strong> The rule is that a GM sees
	 * everything, a Brand Manager sees their brand, and a desk sees its own pipelines — and
	 * <em>sees</em> is the operative word. {@link #requireMine} still guards every edit, close,
	 * booking and note, so a GM can now open any deal and still cannot move one. Widening the one
	 * method both paths shared would have handed out write access nobody asked for, which is why
	 * this is a second method rather than a looser first one.
	 *
	 * <p><strong>403 for a deal that does not exist, exactly as for one that is not yours.</strong>
	 * Unchanged from {@link #requireMine} and for its reason: distinguishing them turns the
	 * response into an oracle for which ids the location holds.
	 *
	 * @return the deal, because every caller then needs its brand — and for a GM the caller's own
	 *         brand is null, so reading it off the principal is exactly the bug this fixes
	 */
	public com.ie.evalos.domain.Opportunity requireVisible(String opportunityId) {
		TenantContext caller = TenantContext.current();
		com.ie.evalos.domain.Opportunity deal = deals.byGhlId(opportunityId)
				.orElseThrow(PipelineScope::notYours);

		return switch (caller.role().tier()) {
			case ALL -> deal;
			case BRAND -> {
				if (caller.brandId() != null && caller.brandId().equals(deal.getBrandId())) {
					yield deal;
				}
				throw notYours();
			}
			case PIPELINE -> {
				// Delegated rather than reimplemented: a desk's reading and writing scope are the
				// same set, and two copies of that check is one place for them to drift apart.
				requireMine(opportunityId);
				yield deal;
			}
			// SELF and SUPPLY work cases, not the CRM. They reach the portal request and its
			// documents through their own routes, which deliberately apply no pipeline scope
			// (see ApplicationReviewController) — but a deal's notes and meetings are not theirs.
			default -> throw notYours();
		};
	}

	private static ForbiddenException notYours() {
		return new ForbiddenException("That opportunity is not on a pipeline you can read.");
	}

	public String requireMine(String opportunityId) {
		for (String pipelineId : mine()) {
			if (deals.isOnPipeline(opportunityId, pipelineId)) {
				return pipelineId;
			}
		}
		throw new ForbiddenException("That opportunity is not in your pipeline");
	}
}
