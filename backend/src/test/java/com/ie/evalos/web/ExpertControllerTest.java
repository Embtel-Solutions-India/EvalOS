package com.ie.evalos.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.Role;
import com.ie.evalos.security.EvalOsUserDetailsService;
import com.ie.evalos.security.JwtService;
import com.ie.evalos.security.SecurityConfig;
import com.ie.evalos.security.StaffPrincipal;
import com.ie.evalos.service.ExpertImportService;
import com.ie.evalos.service.ExpertImportService.ImportReport;
import com.ie.evalos.service.ExpertLoadService;
import com.ie.evalos.service.ExpertService;
import com.ie.evalos.service.ExpertService.AvailabilityGroup;
import com.ie.evalos.service.ExpertService.RosterEntry;
import com.ie.evalos.service.ExpertService.RosterPage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The expert database's endpoints, and the one property this unit is most able to break.
 *
 * <p>{@link #noRouteEverSerializesThePaymentDetail} is the unit's acceptance criterion
 * written as a test: it walks <em>every</em> route on this controller with a service that
 * returns an expert whose {@code payment_detail} is set, and greps each serialized
 * response for the secret. A masking bug, an added DTO member or a stray
 * {@code @JsonProperty} anywhere in the chain fails it — which a per-field assertion on
 * one endpoint would not.
 */
@WebMvcTest(controllers = ExpertController.class)
@Import({ SecurityConfig.class, JwtService.class, ApiErrors.class })
@TestPropertySource(properties = {
		"evalos.security.jwt.secret=test-signing-key-that-is-long-enough-for-hs256",
		"evalos.security.field-key=0123456789abcdef0123456789abcdef" })
class ExpertControllerTest {

	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID EXPERT_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

	/** What must never appear in a response body, on any route, for any role. */
	private static final String SECRET = "Wire to Bank of Nowhere, acct 12345678";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtService jwtService;

	@MockitoBean
	ExpertService experts;

	@MockitoBean
	ExpertImportService imports;

	@MockitoBean
	EvalOsUserDetailsService userDetailsService;

	@BeforeEach
	void aRosterOfOneExpertWhoIsPaidSomehow() {
		RosterEntry entry = new RosterEntry(anExpert(), new ExpertLoadService.Load(2, 7));

		given(experts.roster(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
				.willReturn(new RosterPage(List.of(entry), 0, 50, 1));
		given(experts.availabilityBoard(any()))
				.willReturn(List.of(new AvailabilityGroup(Availability.AVAILABLE, List.of(entry))));
		given(experts.profile(any())).willReturn(entry);
		given(experts.create(any(), any())).willReturn(entry.expert());
		given(experts.update(any(), any())).willReturn(entry.expert());
		given(experts.setAvailability(any(), any())).willReturn(entry.expert());
		given(imports.validate(any(), any(), any()))
				.willReturn(new ImportReport("roster.csv", 1, 1, 0, List.of(), false));
		given(imports.importSheet(any(), any(), any()))
				.willReturn(new ImportReport("roster.csv", 1, 1, 0, List.of(), true));
	}

	/** A full expert, including the one field that may never travel. */
	private static Expert anExpert() {
		Expert expert = new Expert(BRAND_IE, "Dr Miriam Osei");
		expert.setTitle("Professor of Mechanical Engineering");
		expert.setInstitution("Rowan State University");
		expert.setEmail("m.osei@rowanstate.test");
		expert.setPrimaryFields(List.of(FieldTag.MECHANICAL_ENGINEERING));
		expert.setSecondaryFields(List.of(FieldTag.PHYSICS));
		expert.setLetterTypes(List.of(LetterType.EXPERT_OPINION_LETTER));
		expert.setTier(ExpertTier.TIER_1);
		expert.setAvailability(Availability.AVAILABLE);
		expert.setQualityScore(new BigDecimal("9.2"));
		expert.setStandardFee(new BigDecimal("350.00"));
		expert.setPaymentDetail(SECRET);
		return expert;
	}

	/** Every route this controller declares, as a request an ENM may make. */
	private static List<MockHttpServletRequestBuilder> everyRoute() {
		return List.of(
				get("/api/experts/roster"),
				get("/api/experts/availability-board"),
				get("/api/experts/" + EXPERT_ID),
				post("/api/experts").contentType(MediaType.APPLICATION_JSON).content(formJson()),
				patch("/api/experts/" + EXPERT_ID).contentType(MediaType.APPLICATION_JSON).content(formJson()),
				patch("/api/experts/" + EXPERT_ID + "/availability")
						.contentType(MediaType.APPLICATION_JSON).content("{\"availability\":\"ON_LEAVE\"}"),
				put("/api/experts/" + EXPERT_ID + "/payment-detail")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"paymentDetail\":\"" + SECRET + "\"}"),
				sheetUpload("/api/experts/import/validate"),
				sheetUpload("/api/experts/import"));
	}

	@ParameterizedTest
	@MethodSource("everyRoute")
	void noRouteEverSerializesThePaymentDetail(MockHttpServletRequestBuilder route) throws Exception {
		String body = mockMvc.perform(route.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		// Not "is masked" and not "is null" — the field is not a member of any DTO, so the
		// value is absent and so is the property. Quoted, because `paymentDetailOnFile` is
		// a legitimate member and contains this name as a prefix: the boolean is the whole
		// point, and asserting on the bare substring would forbid it.
		assertThat(body).doesNotContain(SECRET).doesNotContain("12345678").doesNotContain("\"paymentDetail\"");
	}

	@Test
	void theRosterSaysWhetherAPaymentDetailIsOnFileAndNothingMore() throws Exception {
		mockMvc.perform(get("/api/experts/roster").header(HttpHeaders.AUTHORIZATION, bearer(Role.GM)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.rows[0].paymentDetailOnFile").value(true))
				.andExpect(jsonPath("$.data.rows[0].fullName").value("Dr Miriam Osei"))
				// The load is the derived count, not expert.current_active_count (always 0).
				.andExpect(jsonPath("$.data.rows[0].activeLoad").value(2))
				.andExpect(jsonPath("$.data.rows[0].completedCases").value(7))
				.andExpect(jsonPath("$.data.total").value(1));
	}

	@Test
	void anUnknownFieldTagIsRejectedByTheApi() throws Exception {
		mockMvc.perform(post("/api/experts")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"Dr Nobody","primaryFields":["MECHANICAL ENGG"]}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
				// The value it did not recognise, so whoever typed it can see what to fix.
				.andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("MECHANICAL ENGG")));
	}

	@Test
	void anExpertWithNoNameIsRejected() throws Exception {
		mockMvc.perform(post("/api/experts")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"  \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void aProjectManagerReadsTheRosterAndDoesNotEditIt() throws Exception {
		// They pick experts, so they need to see the roster they are picking from.
		mockMvc.perform(get("/api/experts/roster").header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER)))
				.andExpect(status().isOk());

		// Maintaining the roster is a supply decision, not a case one.
		mockMvc.perform(patch("/api/experts/" + EXPERT_ID + "/availability")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_MANAGER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"availability\":\"ON_LEAVE\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void aCaseManagerCannotOpenTheRosterAtAll() throws Exception {
		mockMvc.perform(get("/api/experts/roster").header(HttpHeaders.AUTHORIZATION, bearer(Role.CASE_MANAGER)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/experts/" + EXPERT_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.PROJECT_COORDINATOR)))
				.andExpect(status().isForbidden());
	}

	@Test
	void noExpertRouteIsReachableWithoutASession() throws Exception {
		for (MockHttpServletRequestBuilder route : everyRoute()) {
			mockMvc.perform(route).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void theImportReportIsTheAnswerEvenWhenTheSheetIsRejected() throws Exception {
		given(imports.importSheet(any(), any(), any())).willReturn(new ImportReport("roster.csv", 3, 0, 0,
				List.of(new ExpertImportService.RowProblem(4, "Fields", "'MECHANICAL ENGG' is not a recognised field tag")),
				false));

		mockMvc.perform(sheetUpload("/api/experts/import")
				.header(HttpHeaders.AUTHORIZATION, bearer(Role.EXPERT_NETWORK_MANAGER)))
				// 200 with a report that says nothing was written: the envelope's error carries
				// one message, and a rejection has one reason per bad row.
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.imported").value(false))
				.andExpect(jsonPath("$.data.problems[0].row").value(4))
				.andExpect(jsonPath("$.data.problems[0].column").value("Fields"));
	}

	private static MockHttpServletRequestBuilder sheetUpload(String path) {
		return multipart(path)
				.file(new MockMultipartFile("file", "roster.csv", "text/csv", "Name\nDr Nobody\n".getBytes()))
				.file(new MockMultipartFile("mapping", "mapping.json", MediaType.APPLICATION_JSON_VALUE,
						"{\"columns\":{\"Name\":\"fullName\"}}".getBytes()));
	}

	private static String formJson() {
		return """
				{"fullName":"Dr Miriam Osei","email":"m.osei@rowanstate.test",
				 "primaryFields":["MECHANICAL_ENGINEERING"],"letterTypes":["EXPERT_OPINION_LETTER"],
				 "tier":"TIER_1","availability":"AVAILABLE","qualityScore":9.2,"standardFee":350.00}
				""";
	}

	private String bearer(Role role) {
		StaffPrincipal principal = new StaffPrincipal(UUID.randomUUID(), role + "@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND_IE, null, null, true);
		return "Bearer " + jwtService.issue(principal);
	}
}
