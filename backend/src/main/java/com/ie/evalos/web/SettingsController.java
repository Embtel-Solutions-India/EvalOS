package com.ie.evalos.web;

import java.util.List;
import java.util.Map;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.SettingsAdminService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Administrator's Settings (D83, spec 85). An Admin area ({@code AdminAllowlist}), and every handler carries
 * its own role gate. No response carries a secret's value.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	/** A value sets, {@code null} resets to the environment, a key left out is untouched. */
	public record Changes(@NotNull Map<String, String> changes) {
	}

	private final SettingsAdminService settings;

	SettingsController(SettingsAdminService settings) {
		this.settings = settings;
	}

	@GetMapping
	@PreAuthorize("hasRole('ADMIN')")
	public ApiResponse<List<SettingsAdminService.View>> list() {
		return ApiResponse.ok(settings.list());
	}

	@PutMapping
	@PreAuthorize("hasRole('ADMIN')")
	public ApiResponse<List<SettingsAdminService.View>> update(@Valid @RequestBody Changes body) {
		// Listed after update() has returned, i.e. after its transaction committed and the settings reloaded.
		settings.update(body.changes());
		return ApiResponse.ok(settings.list());
	}

	@PostMapping("/mail/test")
	@PreAuthorize("hasRole('ADMIN')")
	public ApiResponse<SettingsAdminService.TestResult> testMail() {
		return ApiResponse.ok(settings.testMail());
	}

	@PostMapping("/ghl/test")
	@PreAuthorize("hasRole('ADMIN')")
	public ApiResponse<SettingsAdminService.TestResult> testGhl() {
		return ApiResponse.ok(settings.testGhl());
	}
}
