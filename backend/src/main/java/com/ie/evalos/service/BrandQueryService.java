package com.ie.evalos.service;

import java.util.List;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The brands a caller may choose between. One method, and it exists as a service
 * rather than a repository call in the controller because the GM check belongs
 * somewhere a later caller cannot skip — the same reason {@code RefundService} re-checks
 * the GM in the service and not only at its endpoint.
 *
 * <p>This is the only cross-brand read outside the GM's own scoped queries, so the
 * gate is worth stating twice: {@code @PreAuthorize} on the route, and here.
 */
@Service
public class BrandQueryService {

	private final BrandRepository brands;

	BrandQueryService(BrandRepository brands) {
		this.brands = brands;
	}

	@Transactional(readOnly = true)
	public List<Brand> selectable() {
		if (TenantContext.current().role() != Role.GM) {
			throw new ForbiddenException("Only the GM may list brands");
		}
		return brands.findByActiveTrueOrderByNameAsc();
	}
}
