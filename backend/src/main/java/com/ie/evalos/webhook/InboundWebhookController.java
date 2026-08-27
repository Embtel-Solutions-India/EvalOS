package com.ie.evalos.webhook;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.WebhookSource;

/**
 * One public endpoint per source, per brand. Unauthenticated by design — GHL holds
 * no EvalOS token — and gated by the per-brand endpoint token in the path. That token
 * is the whole credential: it resolves the brand, the brand must be active, and an
 * unknown or inactive one is a 404.
 *
 * <p>No signature is read or required. GHL's Custom Webhook action posts a plain JSON
 * body and cannot compute an HMAC, so demanding one meant Handoff A could not be wired
 * up from GHL's own UI. The endpoint token is therefore a secret: it is unguessable, it
 * never appears in a DTO, and rotating it revokes the endpoint.
 *
 * <p>The body is still taken as {@code byte[]} rather than a DTO: the gateway decodes it
 * as UTF-8 (JSON's charset, RFC 8259) and archives the exact text it parsed, so what the
 * archive holds is what was routed. Letting Spring bind a DTO here would parse the payload
 * twice, once before the gateway ever sees it.
 */
@RestController
@RequestMapping("/api/webhooks")
public class InboundWebhookController {

	private final WebhookGateway gateway;

	InboundWebhookController(WebhookGateway gateway) {
		this.gateway = gateway;
	}

	@PostMapping(path = "/ghl/{endpointToken}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<WebhookGateway.Ack> ghl(@PathVariable String endpointToken, @RequestBody byte[] rawBody) {
		return ApiResponse.ok(gateway.accept(WebhookSource.GHL, endpointToken, rawBody));
	}
}
