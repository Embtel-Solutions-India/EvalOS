package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.SystemHealthService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Admin's System health page (D82). Under {@code /api} rather than {@code /actuator} so it travels
 * through nginx like every other staff call; Actuator itself stays off the public host.
 */
@RestController
@RequestMapping("/api/system")
public class SystemHealthController {

	private final SystemHealthService systemHealth;

	SystemHealthController(SystemHealthService systemHealth) {
		this.systemHealth = systemHealth;
	}

	@GetMapping("/health")
	@PreAuthorize("hasRole('ADMIN')")
	public ApiResponse<SystemHealthService.Report> health() {
		return ApiResponse.ok(systemHealth.report());
	}
}
