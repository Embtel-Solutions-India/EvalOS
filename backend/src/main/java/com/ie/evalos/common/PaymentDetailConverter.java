package com.ie.evalos.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Field-level encryption for the expert's payment detail — the one field EvalOS
 * encrypts, because payouts are recorded by hand and no card or bank rail exists
 * to hold the data instead.
 *
 * <p>AES-256-GCM with a fresh 12-byte IV per write, stored as
 * {@code base64(iv || ciphertext||tag)}. GCM is authenticated, so a tampered
 * column fails to decrypt rather than returning plausible plaintext. The
 * consequence of a random IV is that the column is not searchable or equality-
 * comparable — it is display data only, which is all a payout form needs.
 *
 * <p>A {@code @Component} so the key is injected from configuration; Hibernate
 * resolves converters through Spring's bean container. There is deliberately no
 * no-argument constructor, so a context that cannot supply the key fails at
 * startup instead of silently writing plaintext.
 */
@Component
@Converter
public class PaymentDetailConverter implements AttributeConverter<String, String> {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int KEY_BYTES = 32;

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	PaymentDetailConverter(@Value("${evalos.security.field-key}") String base64Key) {
		byte[] keyBytes;
		try {
			keyBytes = Base64.getDecoder().decode(base64Key.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("evalos.security.field-key must be base64-encoded", ex);
		}
		if (keyBytes.length != KEY_BYTES) {
			throw new IllegalStateException(
					"evalos.security.field-key must decode to " + KEY_BYTES + " bytes for AES-256, got "
							+ keyBytes.length);
		}
		this.key = new SecretKeySpec(keyBytes, "AES");
	}

	@Override
	public String convertToDatabaseColumn(String plaintext) {
		if (plaintext == null) {
			return null;
		}
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			byte[] payload = new byte[iv.length + sealed.length];
			System.arraycopy(iv, 0, payload, 0, iv.length);
			System.arraycopy(sealed, 0, payload, iv.length, sealed.length);
			return Base64.getEncoder().encodeToString(payload);
		}
		catch (GeneralSecurityException ex) {
			// Never include the value or the key in the message.
			throw new IllegalStateException("Could not encrypt payment detail", ex);
		}
	}

	@Override
	public String convertToEntityAttribute(String stored) {
		if (stored == null) {
			return null;
		}
		byte[] payload = Base64.getDecoder().decode(stored);
		if (payload.length <= IV_BYTES) {
			throw new IllegalStateException("Stored payment detail is too short to be valid ciphertext");
		}
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(TAG_BITS, Arrays.copyOf(payload, IV_BYTES)));
			byte[] plaintext = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
			return new String(plaintext, StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not decrypt payment detail", ex);
		}
	}
}
