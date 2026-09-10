package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.PortalAccessRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which portal links exist, whether anyone opened them, and whether a clock is running against
 * one nobody has — gap <strong>G16</strong>.
 *
 * <p><strong>This is a compensating control for a channel that does not exist, not a reporting
 * nicety.</strong> EvalOS sends no mail (invariant 14), so a portal link reaches its recipient
 * because a staff member copied it out of {@code PortalLinkPanel} and sent it by hand. Nothing
 * records that a minted link was actually sent, and until this tile nothing noticed that it was
 * not.
 *
 * <p>That is survivable for a client, who waits. It is <strong>gap G15</strong> for an expert:
 * the 20h/24h signing clock runs whether or not they ever received their link, so
 * <strong>the likeliest way EvalOS breaches that SLA is a link nobody sent</strong> — and no
 * screen showed it.
 *
 * <p><strong>No migration and no new column</strong>, deliberately. In particular there is no
 * {@code sent_at}: EvalOS cannot observe a staff member pasting a URL into someone else's mail
 * client, and a column recording a fact the system cannot witness is worse than an absent one,
 * because a dashboard would then report it. {@code last_seen_at} is the honest proxy — it is
 * evidence the link <em>arrived</em>, which is the thing actually worth knowing.
 *
 * <p><strong>No issuer column either.</strong> The mint audits against the <em>case</em>, with
 * the audience in a free-text note, so matching an issuer to one token would mean parsing that
 * string. The row links to the case timeline instead, which answers it precisely. Spec 17 §5
 * carries the two upgrade paths if that is ever wanted.
 */
@Service
public class PortalLinkLedgerService {

	/** A live link this close to expiry, on a case still running, is worth re-minting. */
	private static final Duration EXPIRING_SOON = Duration.ofHours(48);

	/** How urgent this pair is, on `ui-context.md`'s single RAG vocabulary. */
	public enum LinkState {
		/** A clock is running and nobody has opened the link — or there is no link at all. */
		RED,
		/** Nobody has opened it and the stage is at risk; or it is opened but nearly expired. */
		AMBER,
		/** Opened and in date, or this audience is not needed at this stage. */
		GREEN
	}

	/**
	 * One row per <strong>(case, audience)</strong> pair, not per token.
	 *
	 * <p>A case with six superseded client links is one line with a re-mint count, rather than
	 * six rows of noise. The newest token decides the row's state.
	 *
	 * @param openedAt  {@code last_seen_at} of the newest live token, or null for never
	 * @param expiresAt when the newest live token dies, or null when there is no live one
	 * @param reMints   tokens for this pair beyond the first; zero reads as blank on screen
	 * @param needed    whether this stage actually wants this audience — an absent link is
	 *                  correct almost everywhere, and a tile that shouted about it would be
	 *                  ignored within a week
	 */
	public record LedgerRow(UUID caseId, String caseCode, Stage stage, PortalAudience audience,
			LinkState state, boolean live, Instant openedAt, Instant expiresAt, int reMints,
			boolean needed) {
	}

	private final CaseLifecycleService lifecycle;
	private final PortalAccessRepository links;
	private final SlaCalculator sla;

	PortalLinkLedgerService(CaseLifecycleService lifecycle, PortalAccessRepository links, SlaCalculator sla) {
		this.lifecycle = lifecycle;
		this.links = links;
		this.sla = sla;
	}

	/**
	 * The ledger for every open case the caller can already see.
	 *
	 * <p><strong>Scoped by reusing {@code CaseLifecycleService.list}</strong>, exactly as the
	 * other metrics services are: this cannot see further than the board beside it, and there is
	 * no second scoping path to keep in step.
	 *
	 * <p>Sorted worst-first. A ledger nobody scrolls is a ledger that missed the one red row.
	 */
	@Transactional(readOnly = true)
	public List<LedgerRow> forCaller() {
		Instant now = Instant.now();
		List<Case> open = lifecycle.list(null, null, null).stream()
				// A delivered or closed case cannot have a clock running against it, and its old
				// links are history rather than a problem.
				.filter((subject) -> subject.getCurrentStage() != Stage.DELIVERED
						&& subject.getCurrentStage() != Stage.CLOSED)
				.toList();

		List<LedgerRow> rows = new ArrayList<>();
		for (Case subject : open) {
			for (PortalAudience audience : PortalAudience.values()) {
				rows.add(rowFor(subject, audience, now));
			}
		}

		return rows.stream()
				// Worst first, then the nearest expiry, then a stable order by case.
				.sorted(Comparator.comparing(LedgerRow::state)
						.thenComparing((row) -> row.expiresAt() == null ? Instant.MAX : row.expiresAt())
						.thenComparing(LedgerRow::caseCode, Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
	}

	private LedgerRow rowFor(Case subject, PortalAudience audience, Instant now) {
		List<PortalAccess> history = links.findByCaseIdAndAudienceOrderByCreatedAtDesc(subject.getId(),
				audience);
		PortalAccess newest = history.stream()
				.filter((link) -> link.getRevokedAt() == null && link.getExpiresAt().isAfter(now))
				.findFirst()
				.orElse(null);

		boolean needed = needs(subject, audience);
		int reMints = Math.max(0, history.size() - 1);
		Instant openedAt = newest == null ? null : newest.getLastSeenAt();
		Instant expiresAt = newest == null ? null : newest.getExpiresAt();

		return new LedgerRow(subject.getId(), subject.getCaseCode(), subject.getCurrentStage(), audience,
				state(subject, needed, newest, openedAt, expiresAt, now), newest != null, openedAt,
				expiresAt, reMints, needed);
	}

	/**
	 * The RAG band, derived rather than invented.
	 *
	 * <p>The rule is one question: <strong>is a clock running against a link nobody has
	 * opened?</strong> Everything else follows from it, and the stage SLA is reused rather than
	 * a second threshold invented — so this tile and the board's rail cannot disagree about one
	 * case.
	 */
	private LinkState state(Case subject, boolean needed, PortalAccess newest, Instant openedAt,
			Instant expiresAt, Instant now) {
		if (!needed) {
			// An absent link is correct almost everywhere. A tile that showed red for every case
			// not yet at signing would be ignored within a week, and then it would be ignored on
			// the day it was right.
			return LinkState.GREEN;
		}

		SlaStatus stageSla = sla.statusOf(subject);

		if (newest == null) {
			// Needed and there is no live link at all: nobody can act, and the clock is running.
			return LinkState.RED;
		}
		if (openedAt == null) {
			if (stageSla == SlaStatus.OVERDUE) {
				return LinkState.RED;
			}
			return stageSla == SlaStatus.AT_RISK ? LinkState.AMBER : LinkState.GREEN;
		}
		// Opened. The only remaining worry is it dying before the case does.
		return Duration.between(now, expiresAt).compareTo(EXPIRING_SOON) < 0 ? LinkState.AMBER
				: LinkState.GREEN;
	}

	/**
	 * Whether this stage wants this audience — <strong>derived from the stage, never stored</strong>.
	 *
	 * <p>A client link is needed once a draft has gone to them and they have not answered; an
	 * expert link is needed at {@code EXPERT_SIGNING}. Anywhere else an absent link is the
	 * correct state, which is what keeps this tile worth looking at.
	 */
	private static boolean needs(Case subject, PortalAudience audience) {
		return switch (audience) {
			case CLIENT -> subject.getCurrentStage() == Stage.CLIENT_REVIEW
					|| (subject.getCurrentStage() == Stage.CLIENT_APPROVAL
							&& subject.getClientApprovalStatus() == ClientApprovalStatus.PENDING);
			case EXPERT -> subject.getCurrentStage() == Stage.EXPERT_SIGNING;
		};
	}

	/**
	 * How many rows need attention, for the dashboard's summary line.
	 *
	 * <p>Returned beside the rows rather than counted on the client, so the number and the list
	 * cannot disagree — the same reason every other tile's totals come from the server.
	 */
	public record Summary(int red, int amber, List<LedgerRow> rows) {
	}

	@Transactional(readOnly = true)
	public Summary summaryForCaller() {
		List<LedgerRow> rows = forCaller();
		Map<LinkState, Integer> counts = new LinkedHashMap<>();
		rows.forEach((row) -> counts.merge(row.state(), 1, Integer::sum));
		return new Summary(counts.getOrDefault(LinkState.RED, 0),
				counts.getOrDefault(LinkState.AMBER, 0), rows);
	}
}
