package com.ie.evalos.chat.push;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;

import org.apache.http.client.methods.HttpPost;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * web-push 5.1.2 with the BouncyCastle and jose4j versions this build pins (1.86, 0.9.6). Builds a
 * real encrypted, VAPID-signed push request offline — the part a unit test with a mocked sender
 * never touches — so a version bump that breaks the crypto fails here, not at the first live send.
 */
class PushLibraryCompatibilityTest {

	private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

	private static KeyPair p256() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		return generator.generateKeyPair();
	}

	/** The uncompressed point, 0x04 || X || Y, as browsers and VAPID encode public keys. */
	private static byte[] uncompressed(ECPublicKey key) {
		byte[] out = new byte[65];
		out[0] = 4;
		copy32(key.getW().getAffineX(), out, 1);
		copy32(key.getW().getAffineY(), out, 33);
		return out;
	}

	private static byte[] raw32(BigInteger value) {
		byte[] out = new byte[32];
		copy32(value, out, 0);
		return out;
	}

	private static void copy32(BigInteger value, byte[] into, int at) {
		byte[] bytes = value.toByteArray();
		int start = bytes.length > 32 ? bytes.length - 32 : 0;
		int length = Math.min(32, bytes.length);
		System.arraycopy(bytes, start, into, at + 32 - length, length);
	}

	@Test
	void anEncryptedVapidSignedRequestIsBuiltWithThePinnedCryptoVersions() throws Exception {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
		KeyPair vapid = p256();
		KeyPair browser = p256();
		byte[] auth = new byte[16];
		new SecureRandom().nextBytes(auth);

		PushService service = new PushService(URL.encodeToString(uncompressed((ECPublicKey) vapid.getPublic())),
				URL.encodeToString(raw32(((ECPrivateKey) vapid.getPrivate()).getS())), "mailto:ops@evalos.test");
		Subscription subscription = new Subscription("https://fcm.googleapis.com/fcm/send/test",
				new Subscription.Keys(URL.encodeToString(uncompressed((ECPublicKey) browser.getPublic())),
						URL.encodeToString(auth)));

		HttpPost post = service.preparePost(new Notification(subscription, "{\"title\":\"New message\"}"),
				Encoding.AES128GCM);

		// web-push rewrites FCM's legacy /fcm/send/ path to /wp/ for VAPID sends; the host is what matters.
		assertThat(post.getURI().getHost()).isEqualTo("fcm.googleapis.com");
		assertThat(post.getFirstHeader("Authorization").getValue()).startsWith("vapid t=");
		assertThat(post.getFirstHeader("Content-Encoding").getValue()).isEqualTo("aes128gcm");
		assertThat(post.getEntity().getContentLength()).isGreaterThan(0);
	}
}
