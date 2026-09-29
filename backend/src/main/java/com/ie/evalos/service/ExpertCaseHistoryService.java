package com.ie.evalos.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.ExpertSignStatus;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every case one expert was offered, and what became of it (Unit 63).
 *
 * <p>Derived from {@code expert_case_offer} and the case, never stored: the offer says what the
 * expert answered, the case says how far the work got.
 */
@Service
public class ExpertCaseHistoryService {

	/** Where the expert's work on one case stands. */
	public enum WorkStatus {
		OFFERED, ACCEPTED, SUBMITTED, DELIVERED, REJECTED, REASSIGNED
	}

	public record Row(UUID caseId, String caseCode, Stage stage, Instant offeredAt, Instant answeredAt,
			OfferOutcome outcome, String declineReason, WorkStatus status, boolean retakeEligible) {
	}

	private final ExpertRepository experts;
	private final ExpertCaseOfferRepository offers;
	private final CaseRepository cases;

	ExpertCaseHistoryService(ExpertRepository experts, ExpertCaseOfferRepository offers, CaseRepository cases) {
		this.experts = experts;
		this.offers = offers;
		this.cases = cases;
	}

	@Transactional(readOnly = true)
	public List<Row> of(UUID expertId) {
		Expert expert = experts.findScoped(TenantContext.current(), expertId)
				.orElseThrow(() -> new InvalidRequestException("No such expert: " + expertId));
		List<ExpertCaseOffer> history = offers.findByBrandIdAndExpertIdOrderByOfferedAtDesc(expert.getBrandId(),
				expertId);
		Map<UUID, Case> byId = cases.findByBrandIdAndIdIn(expert.getBrandId(),
				history.stream().map(ExpertCaseOffer::getCaseId).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(Case::getId, Function.identity()));

		// Newest first, so the first row seen per case is this expert's latest answer on it — the
		// only one a retake can follow.
		Set<UUID> seen = new HashSet<>();
		return history.stream().map(offer -> {
			Case subject = byId.get(offer.getCaseId());
			boolean latest = seen.add(offer.getCaseId());
			return new Row(offer.getCaseId(), subject == null ? null : subject.getCaseCode(),
					subject == null ? null : subject.getCurrentStage(), offer.getOfferedAt(), offer.getOutcomeAt(),
					offer.getOutcome(), offer.getDeclineReason(), status(offer.getOutcome(), subject),
					latest && retakeEligible(offer, subject, expert));
		}).toList();
	}

	static WorkStatus status(OfferOutcome outcome, Case subject) {
		return switch (outcome) {
			case OFFERED -> WorkStatus.OFFERED;
			case DECLINED, TIMED_OUT -> WorkStatus.REJECTED;
			case SUPERSEDED -> WorkStatus.REASSIGNED;
			case ACCEPTED -> {
				if (subject == null) {
					yield WorkStatus.ACCEPTED;
				}
				Stage stage = subject.getCurrentStage();
				if (stage == Stage.DELIVERED || stage == Stage.CLOSED) {
					yield WorkStatus.DELIVERED;
				}
				yield subject.getExpertSignStatus() == ExpertSignStatus.SIGNED ? WorkStatus.SUBMITTED
						: WorkStatus.ACCEPTED;
			}
		};
	}

	/** D62: still waiting for a rematch, still this expert's, and the expert can take work. */
	static boolean retakeEligible(ExpertCaseOffer offer, Case subject, Expert expert) {
		return (offer.getOutcome() == OfferOutcome.DECLINED || offer.getOutcome() == OfferOutcome.TIMED_OUT)
				&& subject != null
				&& expert.getId().equals(subject.getExpertId())
				&& subject.getCurrentStage() == Stage.EXPERT_SIGNING
				&& subject.getExceptionState() == ExceptionState.EXPERT_DECLINED_REMATCHING
				&& expert.getAvailability() == Availability.AVAILABLE;
	}
}
