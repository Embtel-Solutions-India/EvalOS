package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ClientApprovalStatus;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Project Coordinator's figures: what the client is holding up, and what has gone out.
 *
 * <p>Same scope story as {@link PmMetricsService} — every figure derives from
 * {@link CaseLifecycleService#list}, the caller's already-scoped read, so this cannot see further
 * than the checklist board beside it.
 */
@Service
public class CoordinatorMetricsService {

	/** Past this, a client sitting on a draft is worth chasing. Business hours, like every clock here. */
	private static final Duration CLIENT_REVIEW_ATTENTION = Duration.ofHours(48);

	private final CaseLifecycleService lifecycle;
	private final SlaCalculator sla;
	private final BusinessCalendar calendar;

	CoordinatorMetricsService(CaseLifecycleService lifecycle, SlaCalculator sla, BusinessCalendar calendar) {
		this.lifecycle = lifecycle;
		this.sla = sla;
		this.calendar = calendar;
	}

	/**
	 * @param outstanding every case still collecting documents
	 * @param aging       those of them past {@code SlaCalculator}'s DOC_COLLECTION budget or
	 *                    approaching it. **Decision 6:** this reuses the stage SLA rather than a
	 *                    second 48-hour threshold, so this tile and the board's rail cannot
	 *                    disagree about one case. Note the consequence — 24 business hours is
	 *                    roughly three working days, so this is looser than a wall-clock 48h.
	 * @param medianWaitHours median business hours the *open* cases have been waiting. Not the
	 *                    time completed collections took: that needs paired audit rows, and the
	 *                    question a Coordinator acts on is who is waiting now.
	 */
	public record DocumentCollection(int outstanding, int aging, long medianWaitHours) {
	}

	/**
	 * @param unopened drafts the client has never opened — `client_portal_read_at` is stamped once,
	 *                 on first read, so this is evidence the link arrived rather than a guess
	 * @param stale    sent more than 48 business hours ago and still unanswered
	 */
	public record ClientReview(int awaiting, int unopened, int stale) {
	}

	public record Delivered(int today, int thisWeek) {
	}

	public record CoordinatorMetrics(DocumentCollection documents, ClientReview clientReview,
			Delivered delivered, int readyToDeliver) {
	}

	@Transactional(readOnly = true)
	public CoordinatorMetrics forCaller(UUID brandId) {
		List<Case> scoped = lifecycle.list(null, null, null).stream()
				.filter(subject -> brandId == null || brandId.equals(subject.getBrandId()))
				.toList();
		Instant now = Instant.now();

		return new CoordinatorMetrics(
				documents(scoped, now),
				clientReview(scoped, now),
				delivered(scoped, now),
				(int) scoped.stream().filter(c -> c.getCurrentStage() == Stage.FINAL_DELIVERY).count());
	}

	private DocumentCollection documents(List<Case> scoped, Instant now) {
		List<Case> collecting = scoped.stream()
				.filter(subject -> subject.getCurrentStage() == Stage.DOC_COLLECTION)
				.toList();

		int aging = 0;
		List<Long> waits = new ArrayList<>();
		for (Case subject : collecting) {
			SlaStatus status = sla.statusOf(subject, now);
			if (status == SlaStatus.AT_RISK || status == SlaStatus.OVERDUE) {
				aging++;
			}
			if (subject.getStageEnteredAt() != null) {
				waits.add(calendar.elapsedBusinessTime(subject.getStageEnteredAt(), now).toHours());
			}
		}
		return new DocumentCollection(collecting.size(), aging, median(waits));
	}

	private ClientReview clientReview(List<Case> scoped, Instant now) {
		int awaiting = 0;
		int unopened = 0;
		int stale = 0;
		for (Case subject : scoped) {
			if (subject.getCurrentStage() != Stage.DRAFT_GENERATION
					|| subject.getClientApprovalStatus() != ClientApprovalStatus.PENDING) {
				continue;
			}
			awaiting++;
			if (subject.getClientPortalReadAt() == null) {
				unopened++;
			}
			if (subject.getStageEnteredAt() != null
					&& calendar.elapsedBusinessTime(subject.getStageEnteredAt(), now)
							.compareTo(CLIENT_REVIEW_ATTENTION) >= 0) {
				stale++;
			}
		}
		return new ClientReview(awaiting, unopened, stale);
	}

	/**
	 * Delivered counts, on wall-clock days rather than business hours.
	 *
	 * <p>"Delivered today" is a calendar question, and is implemented as one: from midnight in
	 * {@link BusinessCalendar#ZONE}, not a rolling 24 hours. "This week" stays a rolling seven
	 * days, which is what "in the last week" means and needs no week-start convention.
	 */
	private static Delivered delivered(List<Case> scoped, Instant now) {
		// Midnight in the *business's* zone, not a rolling 24 hours. This read
		// `now.minus(1, DAYS)` and its own javadoc claimed calendar semantics, which meant that at
		// 09:00 on Tuesday "delivered today" silently included most of Monday.
		//
		// BusinessCalendar.ZONE rather than the server's default: a Coordinator asking what went
		// out today means their working day, and the server may be anywhere.
		Instant startOfToday = LocalDate.now(BusinessCalendar.ZONE)
				.atStartOfDay(BusinessCalendar.ZONE).toInstant();
		Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
		int today = 0;
		int week = 0;
		for (Case subject : scoped) {
			Instant date = subject.getDeliveryDate();
			if (date == null) {
				continue;
			}
			if (date.isAfter(weekAgo)) {
				week++;
			}
			if (!date.isBefore(startOfToday)) {
				today++;
			}
		}
		return new Delivered(today, week);
	}

	private static long median(List<Long> values) {
		List<Long> sorted = values.stream().sorted().toList();
		int size = sorted.size();
		if (size == 0) {
			return 0;
		}
		return size % 2 == 1
				? sorted.get(size / 2)
				: (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
	}
}
