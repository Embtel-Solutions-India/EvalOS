package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.service.BrandQueryService;
import com.ie.evalos.service.SettingsAdminService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The brand list behind the GM's brand switcher, and nothing else.
 *
 * <p><strong>GM only, and it is the one endpoint that is cross-brand by design.</strong>
 * Every other role's brand is fixed and arrives on {@code /api/me}, so no other role has
 * a reason to ask what brands exist — knowing the shape of the business is itself
 * cross-brand information (invariant 1).
 */
@RestController
@RequestMapping("/api/brands")
public class BrandController {

	/**
	 * What the switcher and the Brands screen (Unit 68) need. No webhook token, no signing secret —
	 * ever. A brand is created by migration; its name, currency and payout term are the Administrator's (D83).
	 */
	public record BrandOption(UUID id, String name, String slug, boolean active, String currency, int payoutTermDays) {

		static BrandOption of(Brand source) {
			return new BrandOption(source.getId(), source.getName(), source.getSlug(), source.isActive(),
					source.getCurrency(), source.getPayoutTermDays());
		}
	}

	/** What the Administrator may change about a brand. */
	public record BrandEdit(@NotBlank String name, String currency, @NotNull Integer payoutTermDays) {
	}

	private final BrandQueryService brands;
	private final SettingsAdminService admin;

	BrandController(BrandQueryService brands, SettingsAdminService admin) {
		this.brands = brands;
		this.admin = admin;
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ApiResponse<BrandOption> update(@PathVariable UUID id, @Valid @RequestBody BrandEdit edit) {
		return ApiResponse.ok(BrandOption.of(admin.updateBrand(id,
				new SettingsAdminService.BrandChange(edit.name(), edit.currency(), edit.payoutTermDays()))));
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('GM', 'ADMIN')")
	public ApiResponse<List<BrandOption>> list() {
		return ApiResponse.ok(brands.selectable().stream().map(BrandOption::of).toList());
	}
}
