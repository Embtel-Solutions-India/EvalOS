package com.ie.evalos.config;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Brand;
import com.ie.evalos.repository.BrandRepository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Resolving {@code evalos.ghl.sales-brand} — the one place that now does it.
 *
 * <p>Nine services used to parse this string themselves. The two rules worth keeping from that were
 * "blank is legal" and "a bad value fails the boot rather than the first board load"; the rule worth
 * adding is that a **slug** resolves, because a brand's id differs per database and a UUID in a
 * shared config file is right in exactly one environment.
 */
class SellingBrandTest {

	private final BrandRepository brands = mock(BrandRepository.class);

	@Test
	void aUuidIsTakenAsIsAndNeverTouchesTheDatabase() {
		UUID id = UUID.randomUUID();

		SellingBrand resolved = new SellingBrand(id.toString(), brands);

		assertThat(resolved.id()).isEqualTo(id);
		assertThat(resolved.isConfigured()).isTrue();
		verify(brands, never()).findBySlug(org.mockito.ArgumentMatchers.anyString());
	}

	/**
	 * <strong>The reason this class exists.</strong> International Evaluations is
	 * {@code 1111…} in the local seed and {@code 3333…} in testprod; the slug is the same in both.
	 */
	@Test
	void aSlugResolvesToThatBrandsId() {
		UUID id = UUID.randomUUID();
		// Brand has only a protected no-arg constructor (it is a JPA entity and rows come from
		// migrations, never from code), so the fixture is built the way the repository would hand
		// one back rather than through a constructor that does not exist.
		Brand ie = mock(Brand.class);
		given(ie.getId()).willReturn(id);
		given(brands.findBySlug("international-evaluations")).willReturn(Optional.of(ie));

		assertThat(new SellingBrand("international-evaluations", brands).id()).isEqualTo(id);
	}

	/** Blank is legal: no brand sells yet. Every mirror then no-ops, loudly, rather than guessing. */
	@Test
	void blankMeansUnconfiguredRatherThanBroken() {
		SellingBrand none = new SellingBrand("   ", brands);

		assertThat(none.id()).isNull();
		assertThat(none.isConfigured()).isFalse();
	}

	/**
	 * A typo fails the boot, not the first board load — the ruling {@code OpportunityBoardService}
	 * made for a malformed UUID, kept here and widened to cover a slug that names nothing.
	 */
	@Test
	void aValueThatIsNeitherAUuidNorASlugFailsTheBoot() {
		given(brands.findBySlug("internatonal-evaluations")).willReturn(Optional.empty());

		assertThatThrownBy(() -> new SellingBrand("internatonal-evaluations", brands))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("evalos.ghl.sales-brand")
				.hasMessageContaining("neither a UUID");
	}

	/** D83: the brand saved in Settings applies on the next call, by slug as well as by id. */
	@Test
	void aBrandSavedInSettingsReplacesTheEnvironmentsOnTheNextCall() {
		UUID env = UUID.randomUUID();
		UUID saved = UUID.randomUUID();
		Brand xp = mock(Brand.class);
		given(xp.getId()).willReturn(saved);
		given(brands.findBySlug("xpertsportal")).willReturn(Optional.of(xp));
		AppSettings settings = mock(AppSettings.class);
		given(settings.app(Setting.SALES_BRAND)).willReturn(Optional.empty());
		SellingBrand brand = new SellingBrand(env.toString(), brands);
		brand.useSettings(settings);
		assertThat(brand.id()).isEqualTo(env);

		given(settings.app(Setting.SALES_BRAND)).willReturn(Optional.of("xpertsportal"));

		assertThat(brand.id()).isEqualTo(saved);
		assertThat(brand.id()).isEqualTo(saved);
		verify(brands, org.mockito.Mockito.times(1)).findBySlug("xpertsportal");
	}

	/** A saved brand that has since disappeared switches the sync off rather than failing every request. */
	@Test
	void aSavedBrandThatNoLongerExistsTurnsTheSyncOff() {
		given(brands.findBySlug("gone")).willReturn(Optional.empty());
		AppSettings settings = mock(AppSettings.class);
		given(settings.app(Setting.SALES_BRAND)).willReturn(Optional.of("gone"));
		SellingBrand brand = new SellingBrand(UUID.randomUUID().toString(), brands);
		brand.useSettings(settings);

		assertThat(brand.id()).isNull();
		assertThat(brand.isConfigured()).isFalse();
	}

	/** One operation, one brand: a change saved while a request or sweep is running applies from the next one. */
	@Test
	void insideAPinTheBrandDoesNotChangeMidOperation() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		given(brands.existsById(first)).willReturn(true);
		given(brands.existsById(second)).willReturn(true);
		AppSettings settings = mock(AppSettings.class);
		given(settings.app(Setting.SALES_BRAND)).willReturn(Optional.of(first.toString()));
		SellingBrand brand = new SellingBrand(UUID.randomUUID().toString(), brands);
		brand.useSettings(settings);

		try (SellingBrand.Pin pin = SellingBrand.pin()) {
			assertThat(brand.id()).isEqualTo(first);
			given(settings.app(Setting.SALES_BRAND)).willReturn(Optional.of(second.toString()));
			try (SellingBrand.Pin nested = SellingBrand.pin()) {
				assertThat(brand.id()).isEqualTo(first);
			}
			// The nested pin closing does not end the outer operation.
			assertThat(brand.id()).isEqualTo(first);
		}

		assertThat(brand.id()).isEqualTo(second);
	}

	/** A saved id is checked against the brands, like a slug: a brand that is gone turns the sync off. */
	@Test
	void aSavedIdThatNamesNoBrandTurnsTheSyncOff() {
		UUID gone = UUID.randomUUID();
		given(brands.existsById(gone)).willReturn(false);
		AppSettings settings = mock(AppSettings.class);
		given(settings.app(Setting.SALES_BRAND)).willReturn(Optional.of(gone.toString()));
		SellingBrand brand = new SellingBrand(UUID.randomUUID().toString(), brands);
		brand.useSettings(settings);

		assertThat(brand.id()).isNull();
	}
}
