package com.ie.evalos.webhook;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.WebhookSource;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One public endpoint per source, per brand. Unauthenticated by design — GHL holds
 * no EvalOS token — and gated instead by the endpoint token in the path plus the
 * HMAC over the body.
 *
 * <p>The body is taken as a {@code String}, not a DTO: verification has to run over
 * the exact bytes received, before anything is deserialized. Letting Spring bind a
 * DTO here would parse an unverified payload, which is the thing the gateway exists
 * to prevent.
 */
@RestController
@RequestMapping("/api/webhooks")
public class InboundWebhookController {

	private final WebhookGateway gateway;

	/**
	 * Which header carries the signature, read off the request rather than bound by
	 * annotation so the configured name has exactly one source. Configurable because
	 * GHL's actual header name is not yet confirmed — the one knob this unit needs to
	 * be re-pointed without a code change.
	 */
	private final String signatureHeader;

	InboundWebhookController(WebhookGateway gateway,
			@Value("${evalos.webhook.signature-header}") String signatureHeader) {
		this.gateway = gateway;
		this.signatureHeader = signatureHeader;
	}

	@PostMapping(path = "/ghl/{endpointToken}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<WebhookGateway.Ack> ghl(@PathVariable String endpointToken, @RequestBody String rawBody,
			HttpServletRequest request) {
		return ApiResponse.ok(gateway.accept(
				WebhookSource.GHL, endpointToken, request.getHeader(signatureHeader), rawBody));
	}
}
