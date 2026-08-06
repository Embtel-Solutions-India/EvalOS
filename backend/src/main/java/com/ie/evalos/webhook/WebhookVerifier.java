package com.ie.evalos.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ie.evalos.domain.Brand;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Step one of the gateway: prove the delivery came from the brand's GHL
 * sub-account, before the body is deserialized and before anything is written.
 *
 * <p>HMAC-SHA256 over the exact bytes received, compared with
 * {@link MessageDigest#isEqual} rather than {@code String.equals} — a
 * short-circuiting comparison on a signature leaks it one byte at a time.
 *
 * <p>"Exact bytes" is literal, and the reason this takes {@code byte[]}: a body that
 * has been through {@code String} has been decoded and must be re-encoded to hash,
 * and a decode/encode round trip is only lossless when both ends agree on the
 * charset. They do not — see {@code InboundWebhookController}. Never add a
 * {@code String} overload here; it would compile at every call site and fail only on
 * payloads carrying non-ASCII.
 *
 * <p>Fails closed in every direction: no secret on the brand, no signature header,
 * malformed hex and a wrong digest all produce the same rejection with the same
 * message, so a caller learns nothing about which one it was.
 */
@Component
public class WebhookVerifier {

	private static final String ALGORITHM = "HmacSHA256";

	/** Common convention, and harmless to accept either way. */
	private static final String PREFIX = "sha256=";

	private static final String MESSAGE = "Signature verification failed";

	public void verify(Brand brand, String signature, byte[] rawBody) {
		String secret = brand.getGhlWebhookSecret();
		if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
			throw rejected();
		}
		if (!MessageDigest.isEqual(hmac(secret, rawBody), presented(signature))) {
			throw rejected();
		}
	}

	private static byte[] hmac(String secret, byte[] rawBody) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
			return mac.doFinal(rawBody);
		}
		catch (java.security.GeneralSecurityException ex) {
			// HmacSHA256 is guaranteed present, so this only fires on an unusable key.
			throw rejected();
		}
	}

	private static byte[] presented(String signature) {
		String hex = signature.startsWith(PREFIX) ? signature.substring(PREFIX.length()) : signature;
		try {
			return HexFormat.of().parseHex(hex.trim());
		}
		catch (IllegalArgumentException ex) {
			throw rejected();
		}
	}

	private static WebhookRejected rejected() {
		return new WebhookRejected(HttpStatus.UNAUTHORIZED, "SIGNATURE_INVALID", MESSAGE);
	}
}
