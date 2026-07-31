package com.ie.evalos.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.ExpertImportService.ImportMapping;
import com.ie.evalos.service.ExpertImportService.ImportReport;
import com.ie.evalos.service.ExpertImportService.RowProblem;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The sheet upload's acceptance criteria: a sheet with bad rows imports nothing and says
 * what is wrong with each one, a re-upload updates rather than duplicates, and an
 * unrecognised field tag names the value it did not recognise.
 *
 * <p>The repository is mocked — the partial unique index that makes the upsert safe under
 * concurrency is proved against real SQL in {@code LocalPostgresIntegrationTest}, because
 * a mock has no unique index to violate.
 */
class ExpertImportServiceTest {

	private static final UUID BRAND = UUID.randomUUID();

	private static final String HEADER = "Name,Email,Fields,Letters,Tier,Availability,Score,Fee\n";

	/** The ENM's headers, mapped onto the form's fields. */
	private static final ImportMapping MAPPING = new ImportMapping(Map.of(
			"Name", "fullName",
			"Email", "email",
			"Fields", "primaryFields",
			"Letters", "letterTypes",
			"Tier", "tier",
			"Availability", "availability",
			"Score", "qualityScore",
			"Fee", "standardFee"));

	private final ExpertRepository experts = mock(ExpertRepository.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final ExpertLoadService loads = mock(ExpertLoadService.class);
	private final OwnershipGuard ownership = mock(OwnershipGuard.class);
	private final AuditService audit = mock(AuditService.class);
	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private final ExpertService expertService =
			new ExpertService(experts, brands, loads, ownership, audit);

	private final ExpertImportService imports =
			new ExpertImportService(experts, expertService, ownership, audit, validator);

	@BeforeEach
	void anEnmUploading() {
		actAs(Role.EXPERT_NETWORK_MANAGER);
		given(experts.findByBrandIdAndEmailIgnoreCase(any(), any())).willReturn(Optional.empty());
		given(experts.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void aCleanSheetImportsEveryRow() {
		ImportReport report = imports.importSheet(null, csv("""
				Dr Miriam Osei,m.osei@rowanstate.test,MECHANICAL_ENGINEERING;PHYSICS,\
				EXPERT_OPINION_LETTER,TIER_1,AVAILABLE,9.2,350
				Dr Alan Whitcombe,a.w@lakeside.test,COMPUTER_SCIENCE,PERM_LETTER,TIER_2,AT_CAPACITY,8,300
				"""), MAPPING);

		assertThat(report.problems()).isEmpty();
		assertThat(report.imported()).isTrue();
		assertThat(report.rows()).isEqualTo(2);
		assertThat(report.created()).isEqualTo(2);
		assertThat(report.updated()).isZero();

		ArgumentCaptor<Expert> saved = ArgumentCaptor.forClass(Expert.class);
		verify(experts, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
		Expert first = saved.getAllValues().get(0);
		assertThat(first.getFullName()).isEqualTo("Dr Miriam Osei");
		assertThat(first.getPrimaryFields())
				.containsExactly(FieldTag.MECHANICAL_ENGINEERING, FieldTag.PHYSICS);
		assertThat(first.getLetterTypes()).containsExactly(LetterType.EXPERT_OPINION_LETTER);
		assertThat(first.getTier()).isEqualTo(ExpertTier.TIER_1);
		assertThat(first.getAvailability()).isEqualTo(Availability.AVAILABLE);
		assertThat(first.getStandardFee()).isEqualByComparingTo("350");
		// The sheet never carries a payment detail, so no imported expert has one.
		assertThat(first.hasPaymentDetail()).isFalse();
	}

	/**
	 * The unit's headline criterion: three bad rows, nothing written, all three reported
	 * with a row number, a column and a reason.
	 */
	@Test
	void aSheetWithThreeBadRowsImportsNothingAndReportsAllThree() {
		ImportReport report = imports.importSheet(null, csv("""
				Dr Miriam Osei,m.osei@rowanstate.test,MECHANICAL_ENGINEERING,EXPERT_OPINION_LETTER,TIER_1,AVAILABLE,9.2,350
				Dr Bad Tag,b.tag@nowhere.test,MECHANICAL ENGG,EXPERT_OPINION_LETTER,TIER_1,AVAILABLE,9,350
				Dr No Email,,LAW,RFE_RESPONSE,TIER_2,AVAILABLE,8,300
				Dr Bad Score,b.score@nowhere.test,LAW,RFE_RESPONSE,TIER_2,AVAILABLE,44,300
				"""), MAPPING);

		assertThat(report.imported()).isFalse();
		assertThat(report.rows()).isEqualTo(4);
		verify(experts, never()).saveAndFlush(any());
		verify(audit, never()).recordEvent(any(), any(), any(), any(), any(), any());

		// Row numbers are the sheet's own, header included: row 2 is the first data row, so
		// these are the numbers the ENM sees in their spreadsheet.
		assertThat(report.problems()).extracting(RowProblem::row).containsExactly(3, 4, 5);
		assertThat(report.problems()).extracting(RowProblem::column).containsExactly("Fields", "Email", "Score");

		RowProblem badTag = report.problems().get(0);
		assertThat(badTag.reason())
				.contains("MECHANICAL ENGG")
				// "row 34 invalid" against a closed vocabulary is a dead end, so the closest
				// legal tags are named.
				.contains("MECHANICAL_ENGINEERING");
		assertThat(report.problems().get(1).reason()).contains("email is required");
		assertThat(report.problems().get(2).reason()).isNotBlank();
	}

	@Test
	void validateWritesNothingEvenWhenTheSheetIsPerfect() {
		ImportReport report = imports.validate(null, csv("""
				Dr Miriam Osei,m.osei@rowanstate.test,MECHANICAL_ENGINEERING,EXPERT_OPINION_LETTER,TIER_1,AVAILABLE,9.2,350
				"""), MAPPING);

		assertThat(report.problems()).isEmpty();
		// The dry run's whole point: it says what would happen and does none of it.
		assertThat(report.imported()).isFalse();
		assertThat(report.created()).isEqualTo(1);
		verify(experts, never()).saveAndFlush(any());
	}

	@Test
	void reUploadingTheSameSheetUpdatesTheExpertItAlreadyMatched() {
		Expert existing = new Expert(BRAND, "Dr Miriam Osei");
		existing.setEmail("m.osei@rowanstate.test");
		// Case-insensitively, because the index keys on lower(email).
		given(experts.findByBrandIdAndEmailIgnoreCase(eq(BRAND), eq("M.Osei@RowanState.test")))
				.willReturn(Optional.of(existing));

		ImportReport report = imports.importSheet(BRAND, csv("""
				Dr Miriam Osei-Boateng,M.Osei@RowanState.test,LAW,RFE_RESPONSE,TIER_1,AVAILABLE,9.4,375
				"""), MAPPING);

		assertThat(report.imported()).isTrue();
		assertThat(report.created()).isZero();
		assertThat(report.updated()).isEqualTo(1);

		ArgumentCaptor<Expert> saved = ArgumentCaptor.forClass(Expert.class);
		verify(experts).saveAndFlush(saved.capture());
		// The same row, edited — not a second expert for one person.
		assertThat(saved.getValue()).isSameAs(existing);
		assertThat(existing.getFullName()).isEqualTo("Dr Miriam Osei-Boateng");
		assertThat(existing.getPrimaryFields()).containsExactly(FieldTag.LAW);
	}

	@Test
	void aSheetListingOneAddressTwiceIsReportedRatherThanResolved() {
		ImportReport report = imports.importSheet(null, csv("""
				Dr Miriam Osei,m.osei@rowanstate.test,LAW,RFE_RESPONSE,TIER_1,AVAILABLE,9,350
				Miriam Osei,M.OSEI@rowanstate.test,LAW,RFE_RESPONSE,TIER_1,AVAILABLE,9,350
				"""), MAPPING);

		assertThat(report.imported()).isFalse();
		assertThat(report.problems()).singleElement().satisfies(problem -> {
			assertThat(problem.row()).isEqualTo(3);
			assertThat(problem.reason()).contains("already on row 2");
		});
	}

	@Test
	void aPaymentDetailColumnIsRefusedOutright() {
		// The exposure the encrypted column exists to end: a bank reference in a spreadsheet
		// that has been mailed around.
		assertThatThrownBy(() -> imports.validate(null,
				csv("Dr Miriam Osei,m.osei@rowanstate.test,LAW,RFE_RESPONSE,TIER_1,AVAILABLE,9,350\n"),
				new ImportMapping(Map.of("Name", "fullName", "Email", "paymentDetail"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("never imported");
	}

	@Test
	void aMappingNamingSomethingThatIsNotAFieldIsARequestError() {
		assertThatThrownBy(() -> imports.validate(null,
				csv("Dr Miriam Osei,m.osei@rowanstate.test,LAW,RFE_RESPONSE,TIER_1,AVAILABLE,9,350\n"),
				new ImportMapping(Map.of("Name", "fullName", "Email", "emailAddress"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("emailAddress");
	}

	@Test
	void aMappingNamingAColumnTheSheetDoesNotHaveIsARequestError() {
		assertThatThrownBy(() -> imports.validate(null,
				csv("Dr Miriam Osei,m.osei@rowanstate.test,LAW,RFE_RESPONSE,TIER_1,AVAILABLE,9,350\n"),
				new ImportMapping(Map.of("Name", "fullName", "Mobile", "phone"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("Mobile");
	}

	@Test
	void aFileThatIsNeitherCsvNorXlsxIsRefused() {
		MultipartFile pdf = new MockMultipartFile("file", "roster.pdf", "application/pdf",
				"not a sheet".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> imports.validate(null, pdf, MAPPING))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining(".csv");
	}

	/**
	 * XLSX, because that is what an ENM uploads straight out of Excel — and because POI
	 * hands back doubles: a fee typed as 350 arrives as 350.0 and a score as 9.2000001 if
	 * nothing converts it.
	 */
	@Test
	void anXlsxSheetParsesTheSameWayAndItsNumbersDoNotArriveAsDoubles() throws Exception {
		ImportReport report = imports.importSheet(null, xlsx(), MAPPING);

		assertThat(report.problems()).isEmpty();
		assertThat(report.imported()).isTrue();

		ArgumentCaptor<Expert> saved = ArgumentCaptor.forClass(Expert.class);
		verify(experts).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getFullName()).isEqualTo("Dr Petra Lindqvist");
		assertThat(saved.getValue().getStandardFee()).isEqualByComparingTo("320");
		assertThat(saved.getValue().getQualityScore()).isEqualByComparingTo("8.8");
	}

	@Test
	void anEmptySheetImportsNothing() {
		ImportReport report = imports.importSheet(null, csv(""), MAPPING);

		assertThat(report.rows()).isZero();
		assertThat(report.imported()).isFalse();
		verify(experts, never()).saveAndFlush(any());
	}

	@Test
	void oneAuditRowNamesTheFileTheRowCountAndTheActor() {
		imports.importSheet(null, csv("""
				Dr Miriam Osei,m.osei@rowanstate.test,LAW,RFE_RESPONSE,TIER_1,AVAILABLE,9,350
				"""), MAPPING);

		// Against the brand, because the object acted on is the brand's roster.
		verify(audit).recordEvent(eq("BRAND"), any(), eq(com.ie.evalos.domain.AuditAction.IMPORTED), any(),
				eq(null), eq(Map.of("file", "roster.csv", "rows", 1, "created", 1, "updated", 0)));
		// And the per-expert row, so the expert's own trail says where they came from.
		verify(audit).recordEvent(eq("EXPERT"), any(), eq(com.ie.evalos.domain.AuditAction.CREATED), any(),
				eq(null), any());
	}

	private static MultipartFile csv(String body) {
		return new MockMultipartFile("file", "roster.csv", "text/csv",
				(HEADER + body).getBytes(StandardCharsets.UTF_8));
	}

	private static MultipartFile xlsx() throws Exception {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
			var sheet = workbook.createSheet("Roster");
			var header = sheet.createRow(0);
			List<String> headers = List.of("Name", "Email", "Fields", "Letters", "Tier", "Availability",
					"Score", "Fee");
			for (int column = 0; column < headers.size(); column++) {
				header.createCell(column).setCellValue(headers.get(column));
			}
			var row = sheet.createRow(1);
			row.createCell(0).setCellValue("Dr Petra Lindqvist");
			row.createCell(1).setCellValue("p.lindqvist@northgate.test");
			row.createCell(2).setCellValue("CIVIL_ENGINEERING");
			row.createCell(3).setCellValue("EXPERT_OPINION_LETTER");
			row.createCell(4).setCellValue("TIER_1");
			row.createCell(5).setCellValue("AVAILABLE");
			row.createCell(6).setCellValue(8.8);
			row.createCell(7).setCellValue(320);
			workbook.write(bytes);
			return new MockMultipartFile("file", "roster.xlsx",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());
		}
	}

	private void actAs(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), "enm@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}
}
