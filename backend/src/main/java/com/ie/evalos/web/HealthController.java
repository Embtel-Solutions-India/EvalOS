package com.ie.evalos.web;

import java.time.Instant;

import com.ie.evalos.common.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

	public record Health(String status, String service, Instant time) {
	}

	@GetMapping
	public ApiResponse<Health> health() {
		return ApiResponse.ok(new Health("UP", "evalos", Instant.now()));
	}
}
