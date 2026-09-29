package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The fee on a case's current offer (Unit 65): read it for the case page, change it while the
 * offer is open. The case is loaded scoped, so a PC sees and edits only cases assigned to them.
 */
@Service
public class OfferFeeService {

	/** Who may change an open offer's fee (spec 65 rule 2). The CM is deliberately absent. */
	public static final Set<Role> MAY_SET_FEE = Set.of(Role.GM, Role.PROJECT_MANAGER, Role.PROJECT_COORDINATOR,
			Role.EXPERT_NETWORK_MANAGER);

	public record OfferView(UUID offerId, UUID expertId, BigDecimal fee, String currency, OfferOutcome outcome,
			String feeSetByName, Instant feeSetAt, List<OfferLog.Entry> log) {
	}

	private final CaseRepository cases;
	private final ExpertCaseOfferRepository offers;
	private final PayoutLedgerRepository payouts;
	private final BrandRepository brands;
	private final TeamMemberRepository members;
	private final AuditService audit;
	private final OfferLog log;

	OfferFeeService(CaseRepository cases, ExpertCaseOfferRepository offers, PayoutLedgerRepository payouts,
			BrandRepository brands, TeamMemberRepository members, AuditService audit, OfferLog log) {
		this.cases = cases;
		this.offers = offers;
		this.payouts = payouts;
		this.brands = brands;
		this.members = members;
		this.audit = audit;
		this.log = log;
	}

	/** The case's latest offer, or empty when nobody has been offered it yet. */
	@Transactional(readOnly = true)
	public Optional<OfferView> current(UUID caseId) {
		Case subject = load(caseId);
		return latest(subject).map(offer -> view(subject, offer));
	}

	@Transactional
	public OfferView editFee(UUID caseId, BigDecimal fee) {
		TenantContext ctx = TenantContext.current();
		if (!MAY_SET_FEE.contains(ctx.role())) {
			throw new ForbiddenException("Only a GM, PM, PC or ENM sets a case's fee.");
		}
		if (fee == null || fee.signum() < 0) {
			throw new InvalidRequestException("A fee cannot be empty or negative");
		}
		Case subject = load(caseId);
		ExpertCaseOffer offer = latest(subject)
				.orElseThrow(() -> new NotFoundException("This case has no expert offer"));
		BigDecimal before = offer.getFee();
		offer.setFee(fee, ctx.memberId()); // 409 unless the offer is still OFFERED
		offers.save(offer);
		// HashMap: a pre-V79 offer's fee is null, which Map.of refuses.
		Map<String, Object> beforeSnapshot = new HashMap<>();
		beforeSnapshot.put("fee", before);
		audit.recordEvent("OFFER", offer.getId(), AuditAction.UPDATED, ctx.memberId(), beforeSnapshot,
				Map.of("fee", fee));
		return view(subject, offer);
	}

	private Case load(UUID caseId) {
		return cases.findScoped(TenantContext.current(), caseId)
				.orElseThrow(() -> new NotFoundException("No such case: " + caseId));
	}

	/** The case id came off a scoped load — the only way the unscoped finder may be called. */
	private Optional<ExpertCaseOffer> latest(Case subject) {
		return offers.findByCaseIdOrderByOfferedAtDesc(subject.getId()).stream().findFirst();
	}

	private OfferView view(Case subject, ExpertCaseOffer offer) {
		String currency = brands.findById(subject.getBrandId()).map(Brand::getCurrency).orElse(null);
		String setBy = offer.getFeeSetBy() == null ? null
				: members.findById(offer.getFeeSetBy()).map(TeamMember::getDisplayName).orElse(null);
		PayoutLedger payout = offer.getOutcome() != OfferOutcome.ACCEPTED ? null
				: payouts.findByBrandIdAndExpertIdOrderByCreatedAtDesc(subject.getBrandId(), offer.getExpertId())
						.stream()
						.filter(p -> p.getCaseId().equals(subject.getId()) && p.getStatus() != PayoutStatus.VOIDED)
						.findFirst().orElse(null);
		return new OfferView(offer.getId(), offer.getExpertId(), offer.getFee(), currency, offer.getOutcome(), setBy,
				offer.getFeeSetAt(), log.forOffer(offer, payout));
	}
}
