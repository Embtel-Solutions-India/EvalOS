package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.service.BrandQueryService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

	/** Only what a switcher needs. No webhook token, no signing secret — ever. */
	public record BrandOption(UUID id, String name, String slug) {

		static BrandOption of(Brand source) {
			return new BrandOption(source.getId(), source.getName(), source.getSlug());
		}
	}

	private final BrandQueryService brands;

	BrandController(BrandQueryService brands) {
		this.brands = brands;
	}

	@GetMapping
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<List<BrandOption>> list() {
		return ApiResponse.ok(brands.selectable().stream().map(BrandOption::of).toList());
	}
}
