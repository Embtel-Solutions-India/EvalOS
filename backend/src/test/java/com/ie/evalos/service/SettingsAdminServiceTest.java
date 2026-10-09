package com.ie.evalos.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.config.AppSettings;
import com.ie.evalos.config.Setting;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Role;
import com.ie.evalos.integration.GhlFailure;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.integration.MailTransport;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** D83, spec 85: what the Settings screen may change, and what it must never say about a secret. */
class SettingsAdminServiceTest {

	private static final UUID ADMIN_ID = UUID.randomUUID();
	private static final String TOKEN = "pit-0123456789abcdef-super-secret";

	private final AppSettings settings = mock(AppSettings.class);
	private final AuditService audit = mock(AuditService.class);
	private final BrandRepository brands = mock(BrandRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final MailTransport mail = mock(MailTransport.class);
	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);
	private final SettingsAdminService service = new SettingsAdminService(settings, audit, brands, members, mail, ghl);

	@BeforeEach
	void asTheAdministrator() {
		StaffPrincipal admin = new StaffPrincipal(ADMIN_ID, "admin@ie.test", "Admin", Role.ADMIN, null, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
		given(settings.app(any())).willReturn(Optional.empty());
		given(settings.enabled(any())).willReturn(true);
		given(settings.source(any())).willReturn(AppSettings.Source.UNSET);
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theScreenNeverReceivesASecretsValueOnlyWhetherItIsSet() {
		given(settings.effective(Setting.GHL_TOKEN)).willReturn(TOKEN);
		given(settings.environment(Setting.GHL_TOKEN)).willReturn(TOKEN);
		given(settings.source(Setting.GHL_TOKEN)).willReturn(AppSettings.Source.APP);

		SettingsAdminService.View token = service.list().stream()
				.filter((view) -> view.key() == Setting.GHL_TOKEN).findFirst().orElseThrow();

		assertThat(token.secret()).isTrue();
		assertThat(token.set()).isTrue();
		assertThat(token.value()).isNull();
		assertThat(token.environmentValue()).isNull();
		assertThat(service.list().toString()).doesNotContain(TOKEN);
	}

	@Test
	void savingASecretAuditsThatItWasSetAndNeverItsValue() {
		service.update(Map.of("GHL_TOKEN", TOKEN));

		verify(settings).save(Setting.GHL_TOKEN, TOKEN, ADMIN_ID);
		ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
		verify(audit).recordEvent(eq("SETTING"), eq(SettingsAdminService.idOf(Setting.GHL_TOKEN)), eq(AuditAction.UPDATED),
				eq(ADMIN_ID), any(), after.capture());
		assertThat(after.getValue().toString()).contains("set").doesNotContain(TOKEN);
	}

	@Test
	void aNullResetsToTheEnvironmentAndIsAuditedAsCleared() {
		Map<String, String> changes = new HashMap<>();
		changes.put("MAIL_PASSWORD", null);

		service.update(changes);

		verify(settings).clear(Setting.MAIL_PASSWORD);
		ArgumentCaptor<Object> after = ArgumentCaptor.forClass(Object.class);
		verify(audit).recordEvent(eq("SETTING"), any(), eq(AuditAction.UPDATED), eq(ADMIN_ID), any(), after.capture());
		assertThat(after.getValue().toString()).contains("cleared");
	}

	/** A batch is all or nothing: one bad value saves none of the others. */
	@Test
	void everyValueIsValidatedBeforeAnyIsSaved() {
		for (Map<String, String> bad : List.of(
				Map.of("MAIL_HOST", "smtp.ok.com", "MAIL_PORT", "70000"),
				Map.of("MAIL_FROM", "not-an-address"),
				Map.of("MAIL_HOST", "smtp example.com"),
				Map.of("MAIL_ENABLED", "yes"),
				Map.of("CASES_PER_CM", "0"),
				Map.of("WON_LOOKBACK_DAYS", "many"),
				Map.of("GHL_LOCATION_ID", "   "),
				Map.of("NOT_A_SETTING", "x"))) {
			assertThatThrownBy(() -> service.update(bad)).as(bad.toString()).isInstanceOf(InvalidRequestException.class);
		}
		verify(settings, never()).save(any(), any(), any());
		verifyNoInteractions(audit);
	}

	@Test
	void theSellingBrandMustNameABrandThatExists() {
		given(brands.findBySlug("nobody")).willReturn(Optional.empty());
		given(brands.findBySlug("international-evaluations")).willReturn(Optional.of(mock(Brand.class)));

		assertThatThrownBy(() -> service.update(Map.of("SALES_BRAND", "nobody"))).isInstanceOf(InvalidRequestException.class);
		service.update(Map.of("SALES_BRAND", "international-evaluations"));

		verify(settings).save(Setting.SALES_BRAND, "international-evaluations", ADMIN_ID);
	}

	@Test
	void theTestEmailGoesOnlyToTheAdministratorsOwnAddress() {
		com.ie.evalos.domain.TeamMember me = mock(com.ie.evalos.domain.TeamMember.class);
		given(me.getEmail()).willReturn("admin@ie.test");
		given(members.findById(ADMIN_ID)).willReturn(Optional.of(me));
		given(mail.isConfigured()).willReturn(true);
		given(mail.send(any(), any(), any(), any())).willReturn(true);

		assertThat(service.testMail().ok()).isTrue();

		ArgumentCaptor<MailTransport.Recipient> to = ArgumentCaptor.forClass(MailTransport.Recipient.class);
		verify(mail).send(to.capture(), any(), any(), any());
		assertThat(to.getValue().email()).isEqualTo("admin@ie.test");
	}

	@Test
	void noTestEmailLeavesWhileOutboundEmailIsSwitchedOff() {
		given(settings.enabled(Setting.MAIL_ENABLED)).willReturn(false);

		assertThat(service.testMail().ok()).isFalse();
		verify(mail, never()).send(any(), any(), any(), any());
	}

	@Test
	void theGhlTestSaysWhatToFixWithoutRepeatingTheCredential() {
		given(ghl.pipelines()).willThrow(new GhlUnavailableException("refused", null, GhlFailure.UNAUTHORIZED, 401));

		SettingsAdminService.TestResult result = service.testGhl();

		assertThat(result.ok()).isFalse();
		assertThat(result.message()).contains("401").contains("token");
	}

	@Test
	void aBrandsDetailsAreValidatedAndAudited() {
		UUID id = UUID.randomUUID();
		Brand brand = mock(Brand.class);
		given(brands.findById(id)).willReturn(Optional.of(brand));
		given(brands.save(brand)).willReturn(brand);
		given(brand.getId()).willReturn(id);

		assertThatThrownBy(() -> service.updateBrand(id, new SettingsAdminService.BrandChange("IE", "DOLLARS", 7)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.updateBrand(id, new SettingsAdminService.BrandChange("IE", "USD", 400)))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.updateBrand(id, new SettingsAdminService.BrandChange(" ", "USD", 7)))
				.isInstanceOf(InvalidRequestException.class);

		service.updateBrand(id, new SettingsAdminService.BrandChange(" Intl Evaluations ", "usd", 14));

		verify(brand).update("Intl Evaluations", "USD", 14);
		verify(audit).recordEvent(eq("BRAND"), eq(id), eq(AuditAction.UPDATED), eq(ADMIN_ID), any(), any());
	}
}
