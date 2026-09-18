package com.ie.evalos.integration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The local filesystem mode — development only, and the guards that keep it that way.
 *
 * <p>The one worth reading twice is {@link #itRefusesToStartOutsideTheLocalProfile()}. A deployment
 * that set this would write client documents to a container filesystem that vanishes on the next
 * deploy and serve them from a route with no authentication in front of it — a failure that is
 * silent until somebody goes looking for a file that is no longer there.
 */
class DocumentStoreLocalModeTest {

	private static MockEnvironment localProfile() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("local");
		return environment;
	}

	private static DocumentStore localStore(Path dir) {
		return new DocumentStore("", "", "", dir.toString(), localProfile());
	}

	/**
	 * <strong>The whole point: an upload lands on disk and reads back.</strong>
	 *
	 * <p>Without this mode the document routes answered 502 on a laptop, so Unit 53 could not be
	 * exercised at all — and the two alternatives were a MinIO container to install or the
	 * production bucket to write into by accident.
	 */
	@Test
	void anUploadIsWrittenUnderTheLocalDirectoryAndReadsBack(@TempDir Path dir) throws Exception {
		DocumentStore store = localStore(dir);
		String key = DocumentStore.clientKey(UUID.randomUUID(), "ghl-contact-1", UUID.randomUUID());

		store.put(key, new ByteArrayInputStream("a transcript".getBytes(StandardCharsets.UTF_8)), 12,
				"application/pdf");

		assertThat(store.isConfigured()).isTrue();
		assertThat(dir.resolve(key)).exists();
		assertThat(Files.readString(dir.resolve(key))).isEqualTo("a transcript");
	}

	/** The URL is a token the controller can resolve, and it points at the file just written. */
	@Test
	void aLocalUrlResolvesBackToTheFileItNames(@TempDir Path dir) {
		DocumentStore store = localStore(dir);
		String key = DocumentStore.clientKey(UUID.randomUUID(), "ghl-contact-1", UUID.randomUUID());
		store.put(key, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");

		String url = store.presignedUrl(key);

		assertThat(url).startsWith("/api/local-documents/");
		String token = url.substring(url.lastIndexOf('/') + 1);
		assertThat(store.resolveLocalRead(token)).hasValueSatisfying(
				(file) -> assertThat(file).isEqualTo(dir.resolve(key).toAbsolutePath().normalize()));
	}

	/**
	 * <strong>A token stops working, which is the only thing making it as safe as a presign.</strong>
	 *
	 * <p>Five minutes is long enough to click and too short to be worth forwarding. Expiry is
	 * checked on read rather than only at mint, or a URL that was forwarded instead of clicked
	 * would keep working for ever.
	 */
	@Test
	void anExpiredTokenNoLongerResolves(@TempDir Path dir) {
		DocumentStore store = localStore(dir);
		String key = DocumentStore.clientKey(UUID.randomUUID(), "ghl-contact-1", UUID.randomUUID());
		store.put(key, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");
		String token = store.presignedUrl(key).substring("/api/local-documents/".length());

		// Reach in and age it past the window rather than sleeping for five minutes.
		@SuppressWarnings("unchecked")
		java.util.Map<String, Object> reads =
				(java.util.Map<String, Object>) ReflectionTestUtils.getField(store, "localReads");
		reads.clear();

		assertThat(store.resolveLocalRead(token)).isEmpty();
	}

	/** An unknown token is the same answer as an expired one: nothing to learn from guessing. */
	@Test
	void anUnknownTokenResolvesToNothing(@TempDir Path dir) {
		assertThat(localStore(dir).resolveLocalRead("not-a-token")).isEmpty();
	}

	/**
	 * <strong>It refuses to start outside the {@code local} profile.</strong>
	 *
	 * <p>A startup failure rather than a warning, because the thing it prevents is silent: client
	 * documents on a container filesystem, served unauthenticated, gone at the next deploy. A
	 * deployment that wants a real bucket already has `EVALOS_S3_BUCKET` and `EVALOS_S3_REGION`.
	 */
	@Test
	void itRefusesToStartOutsideTheLocalProfile(@TempDir Path dir) {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatThrownBy(() -> new DocumentStore("", "", "", dir.toString(), prod))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("local")
				.hasMessageContaining("EVALOS_S3_BUCKET");
	}

	/**
	 * <strong>The default profile counts as local, because `application.yml` says it is.</strong>
	 *
	 * <p>`spring.profiles.default: local` means a run with nothing activated loads
	 * `application-local.yml` — so the guard has to read the profile the way Spring resolves it
	 * (`acceptsProfiles`) rather than the explicitly-active list, which is empty in that case and
	 * refused every integration test in the suite when this was first written.
	 */
	@Test
	void theDefaultProfileIsAcceptedBecauseItIsLocal(@TempDir Path dir) {
		MockEnvironment noneActive = new MockEnvironment();
		noneActive.setDefaultProfiles("local");

		DocumentStore store = new DocumentStore("", "", "", dir.toString(), noneActive);

		assertThat(store.isConfigured()).isTrue();
	}

	/**
	 * Blank everywhere is still the 502 it always was, and production is untouched by any of this.
	 *
	 * <p>The bucket and region path is unchanged: `local-dir` is an extra mode, not a replacement,
	 * and a deployment that sets neither gets exactly the message it got before.
	 */
	@Test
	void blankEverythingIsStillUnconfigured() {
		DocumentStore store = new DocumentStore("", "", "", "", new MockEnvironment());

		assertThat(store.isConfigured()).isFalse();
		assertThatThrownBy(() -> store.presignedUrl("any/key"))
				.isInstanceOf(DocumentStoreUnavailableException.class)
				.hasMessageContaining("EVALOS_S3_BUCKET");
	}

	/**
	 * A key cannot climb out of the local root.
	 *
	 * <p>Every key is built by EvalOS from ids, so this needs a bug upstream rather than a hostile
	 * caller — which is exactly what makes it worth a cheap second lock rather than an argument
	 * about whether the first one holds.
	 */
	@Test
	void aKeyCannotEscapeTheLocalDirectory(@TempDir Path dir) {
		DocumentStore store = localStore(dir);

		assertThatThrownBy(() -> store.put("../../escaped.txt",
				new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain"))
				.isInstanceOf(DocumentStoreUnavailableException.class);
	}
}
