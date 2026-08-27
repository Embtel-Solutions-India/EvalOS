package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.PayoutPayment;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.repository.PayoutPaymentRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an expert is owed, and what was actually sent.
 *
 * <p><b>EvalOS never moves money.</b> Nothing here talks to a bank, a card processor or
 * a disbursement rail, and no credential is stored. This is a ledger: a record that
 * money was owed and later paid, and every rule below follows from that.
 */
@Service
public class PayoutService {

	private final PayoutLedgerRepository payouts;

	private final PayoutPaymentRepository payments;

	private final ExpertRepository experts;

	private final BrandRepository brands;

	private final AuditService audit;

	PayoutService(PayoutLedgerRepository payouts, PayoutPaymentRepository payments, ExpertRepository experts,
			BrandRepository brands, AuditService audit) {
		this.payouts = payouts;
		this.payments = payments;
		this.experts = experts;
		this.brands = brands;
		this.audit = audit;
	}

	/**
	 * Open the row a delivered case owes its expert.
	 *
	 * <p>Called from inside {@code deliverToClient}'s transaction, deliberately:
	 * delivered and owed are one fact, and a delivery that committed without its payout
	 * row would be a case nobody gets paid for.
	 *
	 * @return the row, or empty when the case has no expert — which the caller reports
	 *         rather than swallows
	 */
	@Transactional
	public Optional<PayoutLedger> openForDelivery(Case delivered) {
		UUID expertId = delivered.getExpertId();
		if (expertId == null) {
			// A case can only reach FINAL_DELIVERY through EXPERT_SIGNING, so this should
			// be impossible — which is exactly why it is reported rather than swallowed.
			return Optional.empty();
		}

		Brand brand = brands.findById(delivered.getBrandId())
				.orElseThrow(() -> new IllegalStateException(
						"Case " + delivered.getId() + " names a brand that does not exist"));
		if (brand.getCurrency() == null) {
			// Guessing USD is the one guess in this unit that spends real money.
			throw new IllegalStateException(
					"Brand " + delivered.getBrandId() + " has no configured currency; no payout can be opened");
		}

		// Scoped to the case's brand, not the caller's: this runs inside deliverToClient,
		// which has no TenantContext axis that means "the brand this expert must belong
		// to" the way a GM's cross-brand context would not. An expert id from another
		// brand — should be impossible, since no expert is shared across brands — is
		// simply absent here, the same way an out-of-scope row is absent everywhere else.
		BigDecimal standardFee = experts.findByIdAndBrandId(expertId, delivered.getBrandId())
				.map(Expert::getStandardFee).orElse(null);
		Instant dueDate = delivered.getDeliveryDate().plus(brand.getPayoutTermDays(), ChronoUnit.DAYS);

		PayoutLedger row = payouts.save(new PayoutLedger(delivered.getBrandId(), delivered.getId(), expertId,
				standardFee, brand.getCurrency(), dueDate));

		audit.recordEvent("PAYOUT", row.getId(), AuditAction.CREATED, null,
				null, Map.of("caseId", delivered.getId(), "expertId", expertId, "status", "PENDING"));
		return Optional.of(row);
	}

	/**
	 * Who may record that money went out.
	 *
	 * <p><b>The ENM is here by decision, taken 2026-08-27.</b> Spec 16 restricted this to
	 * the GM and Brand Manager and said the widening was the business's call rather than
	 * a spec's. It was taken: the ENM sends the transfer, so the ENM records it.
	 *
	 * <p>Checked here as well as at the endpoint. {@code @PreAuthorize} guards one route;
	 * this guards the operation, so a later caller — a job, a webhook handler, another
	 * service — cannot reach it as anyone else. Same precedent as {@code RefundService}.
	 */
	static final Set<Role> MAY_RECORD = EnumSet.of(Role.GM, Role.BRAND_MANAGER, Role.EXPERT_NETWORK_MANAGER);

	/** One transfer, as the person who sent it describes it. */
	public record SettleForm(
			@NotNull UUID expertId,
			@NotEmpty List<UUID> payoutIds,
			@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
			@NotBlank @Size(max = 100) String method,
			@NotBlank @Size(max = 200) String reference,
			@NotNull Instant paidDate,
			@Size(max = 2000) String notes) {
	}

	/**
	 * Record one transfer that settles several delivered drafts.
	 *
	 * @return the new payment's id
	 */
	@Transactional
	public UUID settle(SettleForm form) {
		TenantContext ctx = TenantContext.current();
		requireMayRecord(ctx);

		List<UUID> ids = form.payoutIds().stream().distinct().toList();
		if (ids.size() != form.payoutIds().size()) {
			throw new InvalidRequestException("A draft was named twice in one payment");
		}

		// One scoped read per id rather than a new bulk finder: the scope is what makes an
		// id in a request body trustworthy, and `ids` is a week of one expert's drafts —
		// single digits. Adding a scoped-in finder would be new scoping code for no gain.
		List<PayoutLedger> rows = ids.stream()
				.map(id -> payouts.findScoped(ctx, id)
						.orElseThrow(() -> new InvalidRequestException("No such draft: " + id)))
				.toList();

		for (PayoutLedger row : rows) {
			if (!form.expertId().equals(row.getExpertId())) {
				throw new InvalidRequestException(
						"One transfer pays one expert; draft " + row.getId() + " is owed to somebody else");
			}
			if (row.getStatus() != PayoutStatus.PENDING) {
				throw new InvalidRequestException("Draft " + row.getId() + " is already " + row.getStatus());
			}
			if (row.getAmount() == null) {
				throw new InvalidRequestException(
						"Draft " + row.getId() + " has no amount yet; decide it before settling");
			}
		}

		// A GM is cross-brand, so "in the caller's scope" does not by itself mean "in one
		// brand". Same expert already implies same brand — an expert belongs to exactly one
		// — so this is belt and braces on a money path, which is where belt and braces belongs.
		UUID brandId = rows.get(0).getBrandId();
		String currency = rows.get(0).getCurrency();
		boolean mixed = rows.stream()
				.anyMatch(r -> !brandId.equals(r.getBrandId()) || !currency.equals(r.getCurrency()));
		if (mixed) {
			throw new InvalidRequestException("Every draft in one payment must share a brand and a currency");
		}

		BigDecimal owed = rows.stream().map(PayoutLedger::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
		// compareTo, not equals: BigDecimal.equals is scale-sensitive, so 700.0 and 700.00
		// would be "different" and refuse a settlement nobody could fix from the screen.
		if (owed.compareTo(form.amount()) != 0) {
			throw new InvalidRequestException("The payment is " + form.amount()
					+ " but the drafts it settles come to " + owed + ". Correct the draft amounts first.");
		}

		PayoutPayment payment = payments.save(new PayoutPayment(brandId, form.expertId(), form.amount(),
				currency, form.method().trim(), form.reference().trim(), form.paidDate(),
				blankToNull(form.notes()), ctx.memberId()));

		int attached = payouts.attachToPayment(payment.getId(), ids, brandId, ctx.memberId());
		if (attached != rows.size()) {
			// Rolls back the payment insert too, which is the point: a payment that settled
			// fewer drafts than it claims is exactly the silent disagreement rule 7 exists
			// to prevent.
			throw new IllegalTransitionException("Another settlement took " + (rows.size() - attached)
					+ " of these drafts; nothing was recorded");
		}

		audit.recordEvent("PAYOUT_PAYMENT", payment.getId(), AuditAction.PAYOUT_SETTLED, ctx.memberId(),
				null, Map.of("expertId", form.expertId(), "amount", form.amount(), "currency", currency,
						"method", payment.getMethod(), "reference", payment.getReference(),
						"draftCount", rows.size(), "payoutIds", ids));
		return payment.getId();
	}

	private static void requireMayRecord(TenantContext ctx) {
		if (!MAY_RECORD.contains(ctx.role())) {
			throw new ForbiddenException("Only the GM, a Brand Manager or the ENM may record a payout");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
