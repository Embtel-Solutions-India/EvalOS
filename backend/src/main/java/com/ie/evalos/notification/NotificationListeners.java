package com.ie.evalos.notification;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.event.CaseEvents;
import com.ie.evalos.repository.CaseRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The one subscriber to {@link CaseEvents.CaseEvent} that raises staff alerts. Thin
 * on purpose (invariant 12): it resolves recipients and calls the service — no
 * business rule lives here, and no transition is decided here.
 *
 * <p>Synchronous, so it runs inside the transition's transaction. That is deliberate:
 * a rolled-back transition must not leave an alert claiming it happened.
 *
 * <p><strong>Silence is a decision, not an omission.</strong> An event absent from
 * {@link #ROUTES} raises nothing, and the two reasons are separated below so a reader
 * can tell "client-facing, deliberately no staff alert" from "no rule written yet".
 */
@Component
public class NotificationListeners {

	private static final Logger log = LoggerFactory.getLogger(NotificationListeners.class);

	/** Who hears about it, and under what heading. */
	private record Route(NotificationType type, BiFunction<Case, RecipientResolver, List<UUID>> recipients,
			String message) {
	}

	/**
	 * The spec's event → recipient table, verbatim except where Unit 05a moved the
	 * ground under it: the spec maps {@code case.created} to the pool alert, but since
	 * Handoff A fires on contact creation, {@code case.created} is a *lead* and
	 * {@code case.paid} is the pool arrival. Both are mapped, with the headings that
	 * distinguish them.
	 */
	private static final Map<CaseEvents.Type, Route> ROUTES = new EnumMap<>(Map.ofEntries(
			route(CaseEvents.Type.CASE_CREATED, NotificationType.NEW_LEAD,
					(c, r) -> r.gmAndBrandManagers(c.getBrandId()),
					"New lead %s. Not paid yet."),

			route(CaseEvents.Type.CASE_PAID, NotificationType.NEW_CASE_IN_POOL,
					(c, r) -> r.gmAndBrandManagers(c.getBrandId()),
					"Case %s is paid and needs a project manager."),

			route(CaseEvents.Type.DOCUMENTS_COMPLETED, NotificationType.STAGE_CHANGED,
					(c, r) -> r.assignedPm(c),
					"Documents are complete on %s — it needs an expert."),

			route(CaseEvents.Type.EXPERT_ASSIGNED, NotificationType.CASE_ASSIGNED,
					(c, r) -> r.assignedCm(c),
					"You are the case manager on %s."),

			route(CaseEvents.Type.DRAFT_SUBMITTED, NotificationType.STAGE_CHANGED,
					(c, r) -> r.assignedPm(c),
					"A draft on %s is waiting for your review."),

			route(CaseEvents.Type.DRAFT_PM_APPROVED, NotificationType.STAGE_CHANGED,
					(c, r) -> r.coordinators(c.getBrandId()),
					"The draft on %s is approved and ready to send to the client."),

			route(CaseEvents.Type.DRAFT_RETURNED, NotificationType.STAGE_CHANGED,
					(c, r) -> r.assignedCm(c),
					"The draft on %s came back from PM review."),

			route(CaseEvents.Type.DRAFT_CLIENT_APPROVED, NotificationType.STAGE_CHANGED,
					(c, r) -> r.assignedCm(c),
					"The client approved the draft on %s."),

			route(CaseEvents.Type.DRAFT_REVISION_REQUESTED, NotificationType.STAGE_CHANGED,
					(c, r) -> r.assignedCm(c),
					"The client asked for revisions on %s."),

			route(CaseEvents.Type.EXPERT_SIGNED, NotificationType.STAGE_CHANGED,
					(c, r) -> r.assignedPm(c),
					"The expert signed %s — it is ready for QC."),

			route(CaseEvents.Type.CASE_REFUND_REQUESTED, NotificationType.EXCEPTION_RAISED,
					(c, r) -> r.gm(),
					"A refund was requested on %s and needs a GM ruling.")));

	private static Map.Entry<CaseEvents.Type, Route> route(CaseEvents.Type event, NotificationType type,
			BiFunction<Case, RecipientResolver, List<UUID>> recipients, String message) {
		return Map.entry(event, new Route(type, recipients, message));
	}

	/**
	 * Published for GHL to deliver to the client (Unit 18), never turned into a staff
	 * alert and never emailed by EvalOS (invariant 14). Listed rather than left to fall
	 * through the default, so that "no staff notification" is visibly a decision.
	 */
	private static final Set<CaseEvents.Type> CLIENT_FACING = EnumSet.of(
			CaseEvents.Type.CHECKLIST_REQUESTED,
			CaseEvents.Type.DRAFT_READY_FOR_CLIENT,
			CaseEvents.Type.CASE_DELIVERED);

	private final CaseRepository cases;
	private final RecipientResolver recipients;
	private final NotificationService notifications;

	NotificationListeners(CaseRepository cases, RecipientResolver recipients, NotificationService notifications) {
		this.cases = cases;
		this.recipients = recipients;
		this.notifications = notifications;
	}

	@EventListener
	public void on(CaseEvents.CaseEvent event) {
		if (CLIENT_FACING.contains(event.type())) {
			return;
		}
		Route route = ROUTES.get(event.type());
		if (route == null) {
			return;
		}

		// The event carries ids, not assignments — it deliberately says nothing about who
		// holds the case (invariants 4/11 keep the payload thin). So the case is loaded
		// here, unscoped, because a listener has no caller whose scope could apply.
		Optional<Case> subject = cases.findById(event.caseId());
		if (subject.isEmpty()) {
			log.warn("No case {} for event {} — no notification raised", event.caseId(), event.type());
			return;
		}
		Case subjectCase = subject.get();

		// The pool arrival is announced once. `apply(...)` publishes one event per
		// transition including a mark-paid that only *corrects the amount*, and "needs a
		// project manager" is not worth saying twice.
		if (route.type() == NotificationType.NEW_CASE_IN_POOL
				&& notifications.alreadyRaised(event.caseId(), NotificationType.NEW_CASE_IN_POOL)) {
			return;
		}

		List<UUID> targets = route.recipients().apply(subjectCase, recipients);
		if (targets.isEmpty()) {
			log.info("Event {} on case {} has no resolved recipient — nothing raised",
					event.type(), subjectCase.getCaseCode());
			return;
		}
		// The event's case id rather than the loaded row's: it is the id we just looked up
		// by, so they are the same value, and this one cannot be null.
		notifications.create(subjectCase.getBrandId(), targets, route.type(), event.caseId(),
				route.message().formatted(subjectCase.getCaseCode()));
	}
}
