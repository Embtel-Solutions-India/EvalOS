package com.ie.evalos.web;

import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.service.CaseServiceTypeService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The PM corrects a case's service type (2026-10-30): sent, uploaded and reviewed documents stay, untouched
 * ones the new type does not need go, its missing ones are added. Scoped by the case's own load.
 */
@RestController
@RequestMapping("/api/cases/{id}/service-type")
public class CaseServiceTypeController {

	public record ServiceTypeRequest(ServiceType serviceType) {
	}

	private final CaseServiceTypeService serviceTypes;

	CaseServiceTypeController(CaseServiceTypeService serviceTypes) {
		this.serviceTypes = serviceTypes;
	}

	@PutMapping
	@PreAuthorize("hasAnyRole('GM', 'BRAND_MANAGER', 'PROJECT_MANAGER')")
	public ApiResponse<CaseServiceTypeService.Change> change(@PathVariable UUID id,
			@RequestBody ServiceTypeRequest request) {
		return ApiResponse.ok(serviceTypes.change(id, request.serviceType()));
	}
}
