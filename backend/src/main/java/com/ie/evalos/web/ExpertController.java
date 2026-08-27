package com.ie.evalos.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.AgreementStatus;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertPaymentStatus;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.PerformanceFlag;
import com.ie.evalos.service.ExpertImportService;
import com.ie.evalos.service.ExpertImportService.ImportMapping;
import com.ie.evalos.service.ExpertImportService.ImportReport;
import com.ie.evalos.service.ExpertService;
import com.ie.evalos.service.ExpertService.ExpertForm;
import com.ie.evalos.service.ExpertService.RosterEntry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The expert database: the roster, the availability board, and the sheet upload that
 * replaces the Expert Network Manager's spreadsheet.
 *
 * <p>Beside {@link ExpertPickerController}, not instead of it. That endpoint stays the
 * narrow {@code {id, fullName}} {@code AVAILABLE}-only read the assignment dialog uses,
 * and its narrowness is the reason the encrypted field and the quality scores cannot
 * leak through it. A PM staffing a case needs a name; this controller is where somebody
 * maintains the roster that name comes from.
 *
 * <p><strong>No DTO here declares {@code paymentDetail}.</strong> Not blanked, not
 * masked, not null — <em>not a member</em>, so no future edit to a mapper can start
 * populating one (invariant 4). {@link #setPaymentDetail} is the only path that touches
 * the field and it is write-only: there is no endpoint that reads it back, including for
 * the ENM who wrote it. What every screen gets is
 * {@link RosterRow#paymentDetailOnFile()}.
 */
@RestController
@RequestMapping("/api/experts")
public class ExpertController {

	/**
	 * Who reads the roster: the roles that put an expert on a case, plus the ENM whose
	 * roster it is. The Project Manager is here because a PM who picks experts should be
	 * able to read the roster they are picking from — the picker only ever showed them a
	 * name. A Case Manager and a Coordinator are not: they work the case the expert was
	 * chosen for, and neither chooses.
	 */
	private static final String ROSTER_READ =
			"hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER', 'EXPERT_NETWORK_MANAGER')";

	/**
	 * Who maintains it. The ENM owns recruitment and the roster; the GM and Brand Manager
	 * are here because oversight is brand-wide everywhere else in EvalOS. The PM reads and
	 * does not write: choosing an expert for a case is a case decision, and editing the
	 * expert is a supply one.
	 */
	private static final String ROSTER_WRITE = "hasAnyRole('GM', 'BRAND_MANAGER', 'EXPERT_NETWORK_MANAGER')";

	/** How many roster rows one page may ask for, however big a number is sent. */
	private static final int MAX_PAGE_SIZE = 200;

	/**
	 * One roster row.
	 *
	 * @param activeLoad     cases this expert is carrying now — <strong>derived</strong>
	 *                       from {@code evalos_case}, never read from
	 *                       {@code current_active_count}, which nothing has ever written
	 *                       and which is therefore a permanent zero
	 * @param completedCases closed, un-refunded cases, derived the same way
	 * @param standardFee    what the expert usually charges, which Unit 16 prefills a
	 *                       payout with. Not a price EvalOS charges anyone
	 * @param pendingTotal   what this expert is currently owed — <strong>derived</strong>
	 *                       from the payout ledger (Unit 16), never read from
	 *                       {@code total_payments_pending}, which nothing has ever written
	 *                       and which is therefore a permanent zero
	 */
	public record RosterRow(
			UUID id,
			UUID brandId,
			String fullName,
			String title,
			String institution,
			String email,
			String phone,
			List<FieldTag> primaryFields,
			List<FieldTag> secondaryFields,
			List<LetterType> letterTypes,
			ExpertTier tier,
			Availability availability,
			BigDecimal qualityScore,
			BigDecimal standardFee,
			int activeLoad,
			int completedCases,
			boolean paymentDetailOnFile,
			BigDecimal pendingTotal) {

		static RosterRow of(RosterEntry entry) {
			Expert expert = entry.expert();
			return new RosterRow(expert.getId(), expert.getBrandId(), expert.getFullName(), expert.getTitle(),
					expert.getInstitution(), expert.getEmail(), expert.getPhone(), expert.getPrimaryFields(),
					expert.getSecondaryFields(), expert.getLetterTypes(), expert.getTier(),
					expert.getAvailability(), expert.getQualityScore(), expert.getStandardFee(),
					entry.load().active(), entry.load().completed(), expert.hasPaymentDetail(),
					entry.pendingTotal());
		}
	}

	public record RosterPageView(List<RosterRow> rows, int page, int size, int total) {
	}

	/**
	 * One profile.
	 *
	 * @param avgResponseHours     and the two statuses below are shown, not edited here:
	 *                             Unit 12 computes response behaviour, Unit 15 owns the
	 *                             signing agreement and Unit 16 the payment status. A
	 *                             roster edit that could flip "agreement signed" would be
	 *                             a way to claim a signature nobody gave
	 * @param totalPaymentsPending Unit 16's figure, derived from the payout ledger —
	 *                             never {@code expert.total_payments_pending}, which
	 *                             nothing has ever written and is a permanent zero
	 */
	public record ExpertProfileView(
			RosterRow expert,
			String notes,
			String recruitmentSource,
			LocalDate dateOnboarded,
			BigDecimal avgResponseHours,
			AgreementStatus agreementStatus,
			ExpertPaymentStatus paymentStatus,
			BigDecimal totalPaymentsPending,
			/**
			 * Standing performance concerns, and as of Unit 22 slice 4 they are writable — see
			 * {@code setPerformanceFlags}. Returned here because a flag the ENM can set and cannot
			 * read back is a write into a hole.
			 */
			List<PerformanceFlag> performanceFlags,
			Instant createdAt) {

		static ExpertProfileView of(RosterEntry entry) {
			Expert expert = entry.expert();
			return new ExpertProfileView(RosterRow.of(entry), expert.getNotes(), expert.getRecruitmentSource(),
					expert.getDateOnboarded(), expert.getAvgResponseHours(), expert.getAgreementStatus(),
					expert.getPaymentStatus(), entry.pendingTotal(), expert.getPerformanceFlags(),
					expert.getCreatedAt());
		}
	}

	/** One column of the availability board. Empty columns are sent, so the board is stable. */
	public record AvailabilityColumn(Availability availability, int count, List<RosterRow> experts) {
	}

	public record SetAvailabilityRequest(@NotNull Availability availability) {
	}

	/**
	 * @param flags  the complete set of standing concerns. Empty clears them, which is how a
	 *               resolved concern is retired.
	 * @param reason why, and required even when clearing — "who lifted this flag and on what
	 *               grounds" is exactly the question the trail has to answer later.
	 */
	public record PerformanceFlagsRequest(@NotNull List<PerformanceFlag> flags,
			@NotBlank @Size(max = 500) String reason) {
	}

	/**
	 * The write-only payment detail. Free text — how this expert is paid, in whatever form
	 * the brand actually uses — and the only encrypted column in EvalOS.
	 */
	public record PaymentDetailRequest(@NotBlank @Size(max = 500) String paymentDetail) {
	}

	private final ExpertService experts;
	private final ExpertImportService imports;

	ExpertController(ExpertService experts, ExpertImportService imports) {
		this.experts = experts;
		this.imports = imports;
	}

	/**
	 * @param brandId the GM's brand switcher. Narrowing only, applied after the scoped
	 *                read exactly as on the production board: naming a brand the caller
	 *                cannot read yields an empty roster, not that brand's experts.
	 */
	@GetMapping("/roster")
	@PreAuthorize(ROSTER_READ)
	public ApiResponse<RosterPageView> roster(
			@RequestParam(required = false) UUID brandId,
			@RequestParam(required = false) String search,
			@RequestParam(required = false) FieldTag fieldTag,
			@RequestParam(required = false) LetterType letterType,
			@RequestParam(required = false) Availability availability,
			@RequestParam(required = false) ExpertTier tier,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {

		ExpertService.RosterPage roster = experts.roster(brandId, search, fieldTag, letterType, availability, tier,
				Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
		return ApiResponse.ok(new RosterPageView(roster.entries().stream().map(RosterRow::of).toList(),
				roster.page(), roster.size(), roster.total()));
	}

	@GetMapping("/availability-board")
	@PreAuthorize(ROSTER_READ)
	public ApiResponse<List<AvailabilityColumn>> availabilityBoard(@RequestParam(required = false) UUID brandId) {
		return ApiResponse.ok(experts.availabilityBoard(brandId).stream()
				.map(group -> new AvailabilityColumn(group.availability(), group.experts().size(),
						group.experts().stream().map(RosterRow::of).toList()))
				.toList());
	}

	@GetMapping("/{id}")
	@PreAuthorize(ROSTER_READ)
	public ApiResponse<ExpertProfileView> profile(@PathVariable UUID id) {
		return ApiResponse.ok(ExpertProfileView.of(experts.profile(id)));
	}

	/**
	 * @param brandId which brand's roster this expert joins. Required of a GM, who has no
	 *                brand of their own; ignored for everybody else, whose own brand is
	 *                used and who is refused by {@code OwnershipGuard} if they name
	 *                another. One of the **three** endpoints that may name a brand — this
	 *                one and the two imports below, all creating rows and none widening a
	 *                read. {@code architecture.md} states the policy under Multi-Tenancy;
	 *                that is the list to audit, not this javadoc.
	 */
	@PostMapping
	@PreAuthorize(ROSTER_WRITE)
	public ApiResponse<ExpertProfileView> create(@RequestParam(required = false) UUID brandId,
			@Valid @RequestBody ExpertForm form) {
		UUID id = experts.create(brandId, form).getId();
		return ApiResponse.ok(ExpertProfileView.of(experts.profile(id)));
	}

	@PatchMapping("/{id}")
	@PreAuthorize(ROSTER_WRITE)
	public ApiResponse<ExpertProfileView> update(@PathVariable UUID id, @Valid @RequestBody ExpertForm form) {
		experts.update(id, form);
		return ApiResponse.ok(ExpertProfileView.of(experts.profile(id)));
	}

	/**
	 * Availability is its own endpoint as well as a form field, because it is the one
	 * change an ENM makes from a list without opening anybody's profile — and because it
	 * decides whether Unit 08's picker may offer this expert at all.
	 */
	@PatchMapping("/{id}/availability")
	@PreAuthorize(ROSTER_WRITE)
	public ApiResponse<ExpertProfileView> setAvailability(@PathVariable UUID id,
			@Valid @RequestBody SetAvailabilityRequest request) {
		experts.setAvailability(id, request.availability());
		return ApiResponse.ok(ExpertProfileView.of(experts.profile(id)));
	}

	/**
	 * Records the ENM's standing performance concerns about an expert, with the reason.
	 *
	 * <p>The first writer {@code performance_flags} has ever had — the column, its enum and its
	 * display all shipped in Unit 11 with nothing able to set them.
	 *
	 * <p><strong>The list replaces, it does not append</strong>, so clearing a resolved concern is
	 * sending a shorter list. The history is the audit trail, which keeps every previous set with
	 * its author and reason; the column holds only what is true now.
	 */
	@PatchMapping("/{id}/performance-flags")
	@PreAuthorize(ROSTER_WRITE)
	public ApiResponse<ExpertProfileView> setPerformanceFlags(@PathVariable UUID id,
			@Valid @RequestBody PerformanceFlagsRequest request) {
		experts.setPerformanceFlags(id, request.flags(), request.reason());
		return ApiResponse.ok(ExpertProfileView.of(experts.profile(id)));
	}

	/**
	 * Sets the encrypted payment detail. <strong>There is no GET counterpart</strong>, by
	 * design and not by omission: an ENM correcting an account number types the whole
	 * value again. The response is the refreshed profile, where the only trace of the
	 * write is {@code paymentDetailOnFile} turning true.
	 */
	@PutMapping("/{id}/payment-detail")
	@PreAuthorize(ROSTER_WRITE)
	public ApiResponse<ExpertProfileView> setPaymentDetail(@PathVariable UUID id,
			@Valid @RequestBody PaymentDetailRequest request) {
		experts.setPaymentDetail(id, request.paymentDetail());
		return ApiResponse.ok(ExpertProfileView.of(experts.profile(id)));
	}

	/**
	 * The dry run. Parses and checks every row, writes nothing, and answers the per-row
	 * report the upload screen is built around.
	 *
	 * <p>Multipart: the sheet as {@code file}, the column mapping as a JSON {@code mapping}
	 * part. The mapping travels with the file rather than being stored — it describes one
	 * upload of a sheet somebody keeps editing by hand.
	 */
	@PostMapping("/import/validate")
	@PreAuthorize(ROSTER_WRITE)
	public ApiResponse<ImportReport> validateImport(@RequestParam(required = false) UUID brandId,
			@RequestPart("file") MultipartFile file, @RequestPart("mapping") ImportMapping mapping) {
		return ApiResponse.ok(imports.validate(brandId, file, mapping));
	}

	/**
	 * The real import: every row or none.
	 *
	 * <p>Answers 200 with a report whose {@code imported} is false when the sheet was
	 * rejected. The envelope's error carries a code and one message, and a rejection has
	 * as many reasons as it has bad rows — so the report is the response either way, and
	 * the screen reads {@code imported} rather than the status code.
	 */
	@PostMapping("/import")
	@PreAuthorize(ROSTER_WRITE)
	public ApiResponse<ImportReport> runImport(@RequestParam(required = false) UUID brandId,
			@RequestPart("file") MultipartFile file, @RequestPart("mapping") ImportMapping mapping) {
		return ApiResponse.ok(imports.importSheet(brandId, file, mapping));
	}
}
