package com.ie.evalos.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the opportunities standing in one GHL pipeline, in full, for the operational board.
 *
 * <p><strong>Separate from {@link GhlPipelineClient} because the projection is the difference.</strong>
 * That class reads the same endpoint for the three GM <em>funnel</em> screens and binds three
 * fields on purpose — stage, value, source — with a javadoc explaining that the narrowing is what
 * keeps marketing PII out of an EvalOS response. A board is the opposite question: a salesperson
 * needs to know <em>which deal, whose, and where it stands</em>. Widening that record would have
 * quietly widened the funnel screens too, so the two projections are two classes.
 *
 * <p><strong>The widening is a decision and it has a basis.</strong> {@code Role.seesCaseContent()}
 * is true for {@code SALES} and {@code MARKETING} (Unit 36 §3): a salesperson works with the
 * client directly and the client is the thing they work with. It is still a projection, not a
 * passthrough — GHL's search response also carries the contact's email, phone and tags, and none
 * of those are bound here because a board does not need them to draw a card.
 *
 * <p><strong>Read-only, and it stays that way.</strong> Unit 37 opened the write door; this class
 * does not use it. Units 39 and 40 are the first writers, and they audit.
 */
@Component
public class GhlOpportunityClient {

	private static final Logger log = LoggerFactory.getLogger(GhlOpportunityClient.class);

	/** GHL's page maximum. Fewer pages is fewer round trips against a 100-per-10s budget. */
	private static final int PAGE_SIZE = 100;

	/**
	 * Runaway guard on the cursor loop, not a business limit.
	 *
	 * <p>Sized on the same reasoning as {@code GhlPipelineClient.MAX_PAGES}: a cap below what a
	 * caller may legitimately ask for is not a guard, it is a wrong answer with a log line. One
	 * person's pipeline is far smaller than the year-wide funnel that number was set for, so this
	 * sits well above anything real.
	 */
	private static final int MAX_PAGES = 500;

	/**
	 * One opportunity, as a board draws it.
	 *
	 * <p>{@code contactId} is the canonical external client identity (invariant 7) and is what
	 * later units key notes and invoices on. {@code name} is GHL's opportunity title, which in
	 * practice is the client's name — that is the widening this class exists for.
	 */
	public record BoardOpportunity(
			String id,
			String name,
			String contactId,
			String pipelineId,
			String pipelineStageId,
			String status,
			BigDecimal monetaryValue,
			Instant updatedAt) {
	}

	private final GhlHttp http;

	GhlOpportunityClient(GhlHttp http) {
		this.http = http;
	}

	/**
	 * Every opportunity currently in one pipeline, across as many pages as GHL needs.
	 *
	 * <p><strong>No date window, unlike the funnel read.</strong> A funnel asks "what was created
	 * in this period"; a board asks "what is on my desk", and a deal created last quarter that is
	 * still open is still on the desk. Filtering by creation date here would hide exactly the
	 * stale work a board exists to surface.
	 *
	 * <p><strong>{@code location_id} and {@code pipeline_id} are snake_case</strong> on this
	 * endpoint while the cursor parameters are camelCase. That is GHL's inconsistency, verified
	 * against the live API and pinned in {@code GhlOpportunityClientHttpTest} — see
	 * {@code GhlPipelineClient} for the 422s that established it. Do not align them.
	 *
	 * <p><strong>The pipeline filter is applied by GHL, not by us.</strong> Another pipeline's
	 * rows never cross the wire. The EvalOS-side scope still exists on the cache, because a
	 * stored row is reachable by a query and a live call is not.
	 *
	 * @throws GhlUnavailableException if GHL is not configured here or refused the request
	 */
	public List<BoardOpportunity> inPipeline(String pipelineId) {
		List<BoardOpportunity> all = new ArrayList<>();
		Long startAfter = null;
		String startAfterId = null;

		for (int page = 0; page < MAX_PAGES; page++) {
			Long cursor = startAfter;
			String cursorId = startAfterId;
			SearchResponse response = http.get(SearchResponse.class, (uri) -> {
				uri.path("/opportunities/search")
						.queryParam("location_id", http.locationId())
						.queryParam("pipeline_id", pipelineId)
						.queryParam("limit", PAGE_SIZE);
				if (cursor != null && cursorId != null) {
					uri.queryParam("startAfter", cursor).queryParam("startAfterId", cursorId);
				}
				return uri.build();
			});

			List<Row> found = Optional.ofNullable(response.opportunities()).orElse(List.of());
			found.stream().map((row) -> row.toBoardOpportunity(pipelineId)).forEach(all::add);

			// A short page is the last page. GHL returns exactly `limit` rows whether or not more
			// exist, so a final full page still costs one more request. `meta.total` is
			// deliberately not the loop bound: a bound a few rows short silently drops the tail.
			if (found.size() < PAGE_SIZE || response.meta() == null
					|| response.meta().startAfter() == null || response.meta().startAfterId() == null) {
				return all;
			}
			startAfter = response.meta().startAfter();
			startAfterId = response.meta().startAfterId();
		}

		log.warn("Stopped reading GHL pipeline {} at the {}-page cap with {} opportunities",
				pipelineId, MAX_PAGES, all.size());
		return all;
	}

	// --- wire shapes -----------------------------------------------------------------

	/**
	 * What GHL sends. Bound separately from {@link BoardOpportunity} so the fields this class
	 * chooses not to carry — email, phone, tags — are visibly absent rather than dropped later.
	 */
	record Row(String id, String name, String contactId, String pipelineStageId, String status,
			BigDecimal monetaryValue, Instant updatedAt) {

		BoardOpportunity toBoardOpportunity(String pipelineId) {
			// The pipeline comes from the request, not the response: it is the scope the caller
			// asked for, and reading it back off the row would make a mislabelled row from GHL
			// into a mis-scoped row here.
			return new BoardOpportunity(id, name, contactId, pipelineId, pipelineStageId, status,
					monetaryValue, updatedAt);
		}
	}

	record SearchResponse(List<Row> opportunities, Meta meta) {

		record Meta(Long startAfter, String startAfterId, Integer total) {
		}
	}
}
