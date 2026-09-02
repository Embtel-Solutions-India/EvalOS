package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.DeadlineRisk;
import com.ie.evalos.domain.PmApprovalStatus;
import com.ie.evalos.domain.PoolStatus;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The counts the navigation rail puts beside a screen's name.
 *
 * <p><strong>A badge means "this many things here need you", never "this many things exist".</strong>
 * A number that only ever grows is decoration; these are all queues whose healthy value is low or
 * zero, so a rising badge is information and a missing one means nothing is waiting.
 *
 * <p><strong>Named counts, not a map keyed by route.</strong> Routes live in the frontend's
 * `navigation.ts`, which is also the router's allow-list; keying this response by path would put
 * half of that table on the server, where it could drift from the half that stayed. The client
 * maps a name to its own path.
 *
 * <p>One scoped read per call, on {@link CaseLifecycleService#list} like every other figure in
 * Unit 22 — so a badge can never count something the caller could not open.
 */
@Service
public class NavBadgeService {

	private final CaseLifecycleService lifecycle;
	private final DeadlineRiskCalculator deadlines;
	private final SlaCalculator sla;

	NavBadgeService(CaseLifecycleService lifecycle, DeadlineRiskCalculator deadlines, SlaCalculator sla) {
		this.lifecycle = lifecycle;
		this.deadlines = deadlines;
		this.sla = sla;
	}

	/**
	 * @param unassigned          cases arrived from sales with no Case Manager. Desired value zero
	 * @param draftsAwaitingReview drafts sitting with a Project Manager
	 * @param readyToDeliver      QC passed, waiting for the Coordinator to send
	 * @param docsAging           document collections at or past their stage budget
	 * @param myCasesCritical     the caller's own cases in the red deadline band
	 */
	public record NavBadges(
			int unassigned,
			int draftsAwaitingReview,
			int readyToDeliver,
			int docsAging,
			int myCasesCritical) {
	}

	@Transactional(readOnly = true)
	public NavBadges forCaller() {
		UUID me = TenantContext.current().memberId();
		List<Case> scoped = lifecycle.list(null, null, null);
		Instant now = Instant.now();

		int unassigned = 0;
		int drafts = 0;
		int ready = 0;
		int docsAging = 0;
		int critical = 0;

		for (Case subject : scoped) {
			if (subject.getPoolStatus() == PoolStatus.IN_POOL) {
				unassigned++;
			}
			// Unit 31: one stage, no sub-status join.
			if (subject.getCurrentStage() == Stage.DRAFT_REVIEW) {
				drafts++;
			}
			if (subject.getCurrentStage() == Stage.READY_TO_DELIVER) {
				ready++;
			}
			if (subject.getCurrentStage() == Stage.DOC_COLLECTION) {
				SlaStatus status = sla.statusOf(subject, now);
				if (status == SlaStatus.AT_RISK || status == SlaStatus.OVERDUE) {
					docsAging++;
				}
			}
			// Keyed on the caller, not on the scope: a GM reading this endpoint has no docket of
			// their own, and counting the brand's red cases as "my cases" would be a badge on a
			// screen they cannot even reach.
			if (me != null && me.equals(subject.getAssignedCm())
					&& deadlines.riskOf(subject, now) == DeadlineRisk.OVERDUE) {
				critical++;
			}
		}

		return new NavBadges(unassigned, drafts, ready, docsAging, critical);
	}
}
