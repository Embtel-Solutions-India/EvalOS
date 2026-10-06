package com.ie.evalos.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.ClientRemarkService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the client is told about a case (D74). The ENM is absent on purpose: like every case
 * document it is case content (D63), and the ENM reads none.
 */
@RestController
@RequestMapping("/api/cases/{id}/client-remarks")
public class ClientRemarkController {

	static final String MAY_WRITE = "hasAnyRole('GM', 'PROJECT_MANAGER', 'PROJECT_COORDINATOR', 'CASE_MANAGER')";

	public record RemarkRequest(@NotBlank @Size(max = 2000) String body) {
	}

	private final ClientRemarkService remarks;

	ClientRemarkController(ClientRemarkService remarks) {
		this.remarks = remarks;
	}

	@GetMapping
	@PreAuthorize(MAY_WRITE + " or hasRole('BRAND_MANAGER')")
	public ApiResponse<List<ClientRemarkService.Remark>> list(@PathVariable UUID id) {
		return ApiResponse.ok(remarks.list(id));
	}

	@PostMapping
	@PreAuthorize(MAY_WRITE)
	public ApiResponse<ClientRemarkService.Remark> add(@PathVariable UUID id, @Valid @RequestBody RemarkRequest request) {
		return ApiResponse.ok(remarks.add(id, request.body()));
	}
}
