package com.ie.evalos.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.WebhookSource;

import jakarta.servlet.http.HttpServletRequest;

/**
 * One public endpoint per source, per brand. Unauthenticated by design — GHL holds
 * no EvalOS token — and gated instead by the endpoint token in the path plus the
 * HMAC over the body.
 *
 * <p>The body is taken as a {@code byte[]}, not a DTO and not a {@code String}:
 * verification has to run over the exact bytes received, before anything is
 * deserialized. Letting Spring bind a DTO here would parse an unverified payload,
 * which is the thing the gateway exists to prevent.
 *
 * <p>Not a {@code String} either, because binding one is already a decode, and the
 * verifier then has to re-encode to hash. That round trip is lossless only when both
 * ends agree on the charset. Boot configures {@code StringHttpMessageConverter} from
 * {@code server.servlet.encoding.charset}, so a plain UTF-8 delivery does survive it —
 * but a sender that declares any other charset does not: the body is decoded as
 * declared, re-encoded as UTF-8, and the digest is taken over bytes nobody signed.
 * A legitimate delivery is then rejected 401. Asserted both ways in
 * {@code InboundWebhookTest.aBodyCarryingNonAsciiVerifiesAgainstTheBytesAsSent}.
 */
@RestController
@RequestMapping("/api/webhooks")
public class InboundWebhookController {

	private final WebhookGateway gateway;

	/**
	 * Which header carries the signature, read off the request rather than bound by
	 * annotation so the configured name has exactly one source. Configurable because
	 * GHL's actual header name is not yet confirmed.
	 *
	 * <p><b>It is the only knob, not the only assumption.</b> The signature's
	 * <em>encoding</em> is hardcoded: {@code WebhookVerifier} parses hex, with an
	 * optional {@code sha256=} prefix. Plenty of providers send base64, and if GHL is
	 * one of them every delivery fails the hex parse and answers 401 — a config change
	 * will not save it. Same for the signed material: this verifies the bare body,
	 * while a scheme that signs {@code "<timestamp>.<body>"} (what Unit 18 does
	 * outbound) would need code. Confirm encoding and signed material against a real
	 * GHL sub-account before release; tracked as G17.
	 */
	private final String signatureHeader;

	InboundWebhookController(WebhookGateway gateway,
			@Value("${evalos.webhook.signature-header}") String signatureHeader) {
		this.gateway = gateway;
		this.signatureHeader = signatureHeader;
	}

	@PostMapping(path = "/ghl/{endpointToken}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<WebhookGateway.Ack> ghl(@PathVariable String endpointToken, @RequestBody byte[] rawBody,
			HttpServletRequest request) {
		return ApiResponse.ok(gateway.accept(
				WebhookSource.GHL, endpointToken, request.getHeader(signatureHeader), rawBody));
	}
}

