package com.ie.evalos.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The only encrypted field in the system, so the properties that matter are
 * asserted directly rather than trusted: the column never holds the plaintext,
 * two writes of the same value do not look alike, and an edited column fails
 * loudly instead of decrypting to something plausible.
 */
class PaymentDetailConverterTest {

	private static final String KEY = base64("unit-test-field-key-32-bytes!!!!");

	private static final String DETAIL = "Wire to Bank of Nowhere, acct 12345678, ref DR-KHAN";

	private final PaymentDetailConverter converter = new PaymentDetailConverter(KEY);

	@Test
	void roundTripsThroughTheDatabaseColumn() {
		String stored = converter.convertToDatabaseColumn(DETAIL);

		assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(DETAIL);
	}

	@Test
	void storedColumnNeverHoldsThePlaintextAndNeverRepeats() {
		String first = converter.convertToDatabaseColumn(DETAIL);
		String second = converter.convertToDatabaseColumn(DETAIL);

		assertThat(first).doesNotContain("12345678").doesNotContain("Bank of Nowhere");
		// A fresh IV per write: identical input must not produce identical ciphertext,
		// or the column leaks which experts share payment details.
		assertThat(first).isNotEqualTo(second);
		assertThat(converter.convertToEntityAttribute(second)).isEqualTo(DETAIL);
	}

	@Test
	void editedCiphertextIsRefusedRatherThanDecrypted() {
		byte[] payload = Base64.getDecoder().decode(converter.convertToDatabaseColumn(DETAIL));
		payload[payload.length - 1] ^= 0x01;
		String tampered = Base64.getEncoder().encodeToString(payload);

		assertThatIllegalStateException().isThrownBy(() -> converter.convertToEntityAttribute(tampered));
	}

	@Test
	void nullStaysNullInBothDirections() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}

	@Test
	void aKeyThatIsNotThirtyTwoBytesStopsStartup() {
		assertThatIllegalStateException()
				.isThrownBy(() -> new PaymentDetailConverter(base64("too-short")));
	}

	private static String base64(String raw) {
		return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}
}
