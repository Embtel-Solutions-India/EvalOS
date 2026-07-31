package com.ie.evalos.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ExpertLoadService.Load;
import com.ie.evalos.service.ExpertService.ExpertForm;
import com.ie.evalos.service.ExpertService.ExpertSnapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The roster's reads and writes below the endpoints: the filters narrow what the caller
 * could already read, the load shown is the derived one, every write leaves exactly one
 * audit row, and the audit trail never carries the encrypted payment detail.
 *
 * <p>{@link OwnershipGuard} is the real one rather than a mock — the create path's brand
 * argument is only safe because that guard refuses a caller who names somebody else's
 * brand, and a mocked guard would assert nothing about the thing most worth asserting.
 */
class ExpertServiceTest {

	private static final UUID BRAND_IE = UUID.randomUUID();

	private static final UUID BRAND_XP = UUID.randomUUID();

	private static final UUID EXPERT_ID = UUID.randomUUID();

	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final ExpertLoadService loads = mock(ExpertLoadService.class);
	private final AuditService audit = mock(AuditService.class);

	private final ExpertService service =
			new ExpertService(experts, brands, loads, new OwnershipGuard(), audit);

	@BeforeEach
	void anEnmWithARoster() {
		actAs(Role.EXPERT_NETWORK_MANAGER, BRAND_IE);
		given(experts.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(brands.findById(any())).willReturn(Optional.of(mock(Brand.class)));
		given(loads.forExperts(anyCollection())).willAnswer(invocation -> {
			Map<UUID, Load> byExpert = new java.util.HashMap<>();
			((java.util.Collection<?>) invocation.getArgument(0))
					.forEach(id -> byExpert.put((UUID) id, new Load(2, 7)));
			return byExpert;
		});
		given(loads.forExpert(any())).willReturn(new Load(2, 7));
	}

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	// --- reads ---------------------------------------------------------------

	@Test
	void theRosterFiltersNarrowWhatWasAlreadyScopedAndPagesTheRest() {
		given(experts.findScoped(any(TenantContext.class))).willReturn(List.of(
				expert("Zara Okonkwo", FieldTag.LAW, ExpertTier.TIER_1, Availability.AVAILABLE),
				expert("Alan Turing", FieldTag.COMPUTER_SCIENCE, ExpertTier.TIER_2, Availability.AVAILABLE),
				expert("Busy Person", FieldTag.LAW, ExpertTier.TIER_1, Availability.AT_CAPACITY)));

		// Unfiltered: name order, and the load is the derived count rather than the column.
		var all = service.roster(null, null, null, null, null, null, 0, 50);
		assertThat(all.entries()).extracting(entry -> entry.expert().getFullName())
				.containsExactly("Alan Turing", "Busy Person", "Zara Okonkwo");
		assertThat(all.total()).isEqualTo(3);
		assertThat(all.entries().get(0).load()).isEqualTo(new Load(2, 7));

		assertThat(service.roster(null, null, FieldTag.LAW, null, null, null, 0, 50).entries())
				.extracting(entry -> entry.expert().getFullName())
				.containsExactly("Busy Person", "Zara Okonkwo");
		assertThat(service.roster(null, null, null, null, Availability.AVAILABLE, null, 0, 50).entries())
				.hasSize(2);
		assertThat(service.roster(null, null, null, null, null, ExpertTier.TIER_2, 0, 50).entries())
				.hasSize(1);
		assertThat(service.roster(null, "turing", null, null, null, null, 0, 50).entries()).hasSize(1);
		// Institution as well as name: it is the other thing an ENM remembers.
		assertThat(service.roster(null, "rowan state", null, null, null, null, 0, 50).entries()).hasSize(3);
		assertThat(service.roster(null, null, null, LetterType.PERM_LETTER, null, null, 0, 50).entries())
				.isEmpty();

		// Paging is over the filtered set, and total is the filtered count, not the brand's.
		var second = service.roster(null, null, null, null, null, null, 1, 2);
		assertThat(second.entries()).hasSize(1);
		assertThat(second.total()).isEqualTo(3);
	}

	@Test
	void aGmsBrandFilterCanOnlyEverNarrow() {
		actAs(Role.GM, null);
		given(experts.findScoped(any(TenantContext.class))).willReturn(List.of(
				expert("IE Expert", FieldTag.LAW, ExpertTier.TIER_1, Availability.AVAILABLE),
				xpExpert("XP Expert")));

		assertThat(service.roster(BRAND_XP, null, null, null, null, null, 0, 50).entries())
				.extracting(entry -> entry.expert().getFullName())
				.containsExactly("XP Expert");
		// A brand nobody's scoped read returned yields an empty roster, not that brand's rows.
		assertThat(service.roster(UUID.randomUUID(), null, null, null, null, null, 0, 50).entries()).isEmpty();
	}

	@Test
	void theAvailabilityBoardKeepsEveryColumnAndFilesAnUnsetExpertAsInactive() {
		Expert unset = expert("No Availability", FieldTag.LAW, ExpertTier.TIER_1, null);
		given(experts.findScoped(any(TenantContext.class))).willReturn(List.of(
				expert("Free Person", FieldTag.LAW, ExpertTier.TIER_1, Availability.AVAILABLE), unset));

		var board = service.availabilityBoard(null);

		// Every availability is a column, even the empty ones, so the board does not reshape
		// itself as the roster changes.
		assertThat(board).extracting(ExpertService.AvailabilityGroup::availability)
				.containsExactly(Availability.values());
		assertThat(board.get(0).experts()).extracting(entry -> entry.expert().getFullName())
				.containsExactly("Free Person");
		// Not dropped: a roster row missing from every column is a row nobody will fix.
		assertThat(board).filteredOn(group -> group.availability() == Availability.INACTIVE)
				.singleElement()
				.satisfies(group -> assertThat(group.experts()).hasSize(1));
	}

	@Test
	void anExpertOutsideTheCallersScopeIsAbsentRatherThanForbidden() {
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID))).willReturn(Optional.empty());

		// The message cannot distinguish "another brand's expert" from "no such expert" —
		// either would turn the 403 into an existence oracle.
		assertThatThrownBy(() -> service.profile(EXPERT_ID)).isInstanceOf(ForbiddenException.class);
	}

	// --- writes --------------------------------------------------------------

	@Test
	void creatingAnExpertUsesTheCallersOwnBrandAndAuditsIt() {
		Expert created = service.create(null, form("Dr New Person"));

		assertThat(created.getBrandId()).isEqualTo(BRAND_IE);
		assertThat(created.getPrimaryFields()).containsExactly(FieldTag.MECHANICAL_ENGINEERING);

		ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
		verify(audit).recordEvent(eq("EXPERT"), any(), eq(AuditAction.CREATED), any(), eq(null), after.capture());
		assertThat(after.getValue()).isInstanceOf(ExpertSnapshot.class);
	}

	@Test
	void aBrandManagerCannotCreateAnExpertInAnotherBrand() {
		actAs(Role.BRAND_MANAGER, BRAND_IE);

		// The brand argument exists because a GM has no brand of their own — it is not a way
		// for a brand-locked caller to write next door.
		assertThatThrownBy(() -> service.create(BRAND_XP, form("Dr Elsewhere")))
				.isInstanceOf(ForbiddenException.class);
	}

	@Test
	void aGmMustSayWhichBrandAndThenMay() {
		actAs(Role.GM, null);

		assertThatThrownBy(() -> service.create(null, form("Dr Nobrand")))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("brand");

		assertThat(service.create(BRAND_XP, form("Dr Somewhere")).getBrandId()).isEqualTo(BRAND_XP);
	}

	@Test
	void aBrandThatDoesNotExistIsARequestErrorRatherThanAForeignKeyFailure() {
		given(brands.findById(any())).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(null, form("Dr Nowhere")))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void settingAvailabilityWritesExactlyOneAuditRowNamingTheChange() {
		Expert expert = expert("Free Person", FieldTag.LAW, ExpertTier.TIER_1, Availability.AVAILABLE);
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID))).willReturn(Optional.of(expert));

		service.setAvailability(EXPERT_ID, Availability.ON_LEAVE);

		assertThat(expert.getAvailability()).isEqualTo(Availability.ON_LEAVE);
		ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
		verify(audit, times(1)).recordEvent(eq("EXPERT"), any(), eq(AuditAction.UPDATED), any(), any(),
				after.capture());
		assertThat(((ExpertSnapshot) after.getValue()).note()).isEqualTo("Availability: AVAILABLE → ON_LEAVE");
	}

	@Test
	void theAuditTrailRecordsThatAPaymentDetailWasSetAndNeverWhatItIs() {
		Expert expert = expert("Free Person", FieldTag.LAW, ExpertTier.TIER_1, Availability.AVAILABLE);
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID))).willReturn(Optional.of(expert));

		service.setPaymentDetail(EXPERT_ID, "Wire to Bank of Nowhere, acct 12345678");

		ArgumentCaptor<Object> before = ArgumentCaptor.forClass(Object.class);
		ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
		verify(audit).recordEvent(eq("EXPERT"), any(), eq(AuditAction.UPDATED), any(), before.capture(),
				after.capture());

		// The snapshot is a record with no payment-detail component at all, so this holds by
		// construction — and the test is here so that stays true if somebody adds one.
		assertThat(ExpertSnapshot.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.doesNotContain("paymentDetail");
		assertThat(before.getValue().toString()).doesNotContain("12345678");
		assertThat(after.getValue().toString()).doesNotContain("12345678");
		// What a screen may know: that there is one now.
		assertThat(((ExpertSnapshot) after.getValue()).paymentDetailOnFile()).isTrue();
		assertThat(((ExpertSnapshot) before.getValue()).paymentDetailOnFile()).isFalse();
	}

	@Test
	void editingAnExpertKeepsTheStatusesLaterUnitsOwn() {
		Expert expert = expert("Free Person", FieldTag.LAW, ExpertTier.TIER_1, Availability.AVAILABLE);
		given(experts.findScoped(any(TenantContext.class), eq(EXPERT_ID))).willReturn(Optional.of(expert));

		service.update(EXPERT_ID, form("Dr Renamed"));

		assertThat(expert.getFullName()).isEqualTo("Dr Renamed");
		verify(audit).recordEvent(eq("EXPERT"), any(), eq(AuditAction.UPDATED), any(), any(), any());
		// The form has no member for either, so a roster edit cannot claim a signature nobody
		// gave or a payment nobody made — nor move a row to another brand.
		assertThat(ExpertForm.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.doesNotContain("agreementStatus", "paymentStatus", "paymentDetail", "brandId");
	}

	// --- fixtures ------------------------------------------------------------

	private static ExpertForm form(String name) {
		return new ExpertForm(name, "new.person@example.test", null, "Professor", "Rowan State University",
				List.of(FieldTag.MECHANICAL_ENGINEERING), List.of(), List.of(LetterType.EXPERT_OPINION_LETTER),
				ExpertTier.TIER_1, Availability.AVAILABLE, null, null, null, null, null);
	}

	private static Expert expert(String name, FieldTag tag, ExpertTier tier, Availability availability) {
		Expert expert = new Expert(BRAND_IE, name);
		expert.setInstitution("Rowan State University");
		expert.setPrimaryFields(List.of(tag));
		expert.setLetterTypes(List.of(LetterType.EXPERT_OPINION_LETTER));
		expert.setTier(tier);
		expert.setAvailability(availability);
		return expert;
	}

	private static Expert xpExpert(String name) {
		Expert expert = new Expert(BRAND_XP, name);
		expert.setAvailability(Availability.AVAILABLE);
		return expert;
	}

	private void actAs(Role role, UUID brandId) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "staff@evalos.local", "Staff", role,
				brandId, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}
}
