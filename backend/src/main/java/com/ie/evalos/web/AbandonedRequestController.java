package com.ie.evalos.web;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.AbandonedRequestService;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Requests a client started and never sent, oldest first (D54). Read-only; chasing is a person's job. */
@RestController
@RequestMapping("/api/requests/abandoned")
public class AbandonedRequestController {

	private final AbandonedRequestService abandoned;

	AbandonedRequestController(AbandonedRequestService abandoned) {
		this.abandoned = abandoned;
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('GM', 'SALES')")
	public ApiResponse<List<AbandonedRequestService.Row>> list() {
		return ApiResponse.ok(abandoned.forCaller());
	}
}
