package com.ie.evalos.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.ie.evalos.integration.GoogleDriveClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Google service-account credentials, and the one {@link GoogleDriveClient} built from
 * them (Unit 13).
 *
 * <p><strong>An environment that forgets the key must fail to start.</strong> That is the
 * rule {@code EVALOS_FIELD_KEY} and {@code JWT_SECRET} already set, and it matters more
 * here than it looks: the alternative is an app that starts, serves every other route, and
 * then fails at the first upload a PM attempts — in front of a client waiting for the
 * profile. {@code evalos.drive.required} is {@code true} in {@code application.yml} and
 * {@code false} only in {@code application-local.yml}, so a developer can run the whole
 * app without a service-account key while no deployed profile can.
 *
 * <p>The key is read <strong>at startup</strong>, not on first use. An unreadable path or a
 * malformed key is then a boot failure too, which is the same argument one step further on.
 *
 * <p>Two sources, because the spec names both: {@code GOOGLE_DRIVE_KEY_JSON} carrying the
 * JSON inline (the shape a container secret takes) and {@code GOOGLE_APPLICATION_CREDENTIALS}
 * carrying a path to it (the shape a mounted volume takes). Inline wins if both are set —
 * an explicitly-set variable beats the ambient one Google's own tooling also reads.
 *
 * <p><strong>Per-brand access is not enforceable here, and must not be assumed.</strong>
 * One service account with blanket access to both brands' Drives is a cross-brand hole
 * outside the database that no {@code brand_id} predicate can close, so the grant has to be
 * per brand-folder-tree on the Google side. EvalOS writes only into the folder the case's
 * own {@code drive_link} names, which is the half of the guarantee this codebase can hold.
 */
@Configuration
public class GoogleDriveConfig {

	private static final Logger log = LoggerFactory.getLogger(GoogleDriveConfig.class);

	private final String keyJson;
	private final String keyPath;
	private final boolean required;
	private final String scope;
	private final Duration timeout;

	GoogleDriveConfig(
			@Value("${evalos.drive.key-json:}") String keyJson,
			@Value("${evalos.drive.credentials-path:}") String keyPath,
			@Value("${evalos.drive.required:true}") boolean required,
			@Value("${evalos.drive.scope}") String scope,
			@Value("${evalos.drive.timeout}") Duration timeout) {
		this.keyJson = keyJson;
		this.keyPath = keyPath;
		this.required = required;
		this.scope = scope;
		this.timeout = timeout;

		if (required && !configured()) {
			// Thrown from the constructor of a @Configuration class, so the context does
			// not come up. Names both variables: whoever sees this is provisioning an
			// environment and needs to know which one to set, and neither name is a secret.
			throw new IllegalStateException(
					"Google Drive credentials are required outside the local profile. Set GOOGLE_DRIVE_KEY_JSON "
							+ "(the service-account JSON) or GOOGLE_APPLICATION_CREDENTIALS (a path to it).");
		}
	}

	/**
	 * The client, with a live {@link Drive} when credentials exist and without one when they
	 * do not — which by the constructor's check can only happen on {@code local}.
	 *
	 * @see GoogleDriveClient for what a credential-less client does when asked to upload
	 */
	@Bean
	GoogleDriveClient googleDriveClient() {
		if (!configured()) {
			log.warn("No Google Drive credentials configured — the Drive write will answer 502. "
					+ "Expected on the local profile only.");
			return new GoogleDriveClient(null);
		}
		return new GoogleDriveClient(drive());
	}

	private boolean configured() {
		return !keyJson.isBlank() || !keyPath.isBlank();
	}

	private Drive drive() {
		try (InputStream key = openKey()) {
			GoogleCredentials credentials = GoogleCredentials.fromStream(key)
					.createScoped(List.of(scope));

			return new Drive.Builder(
					GoogleNetHttpTransport.newTrustedTransport(),
					GsonFactory.getDefaultInstance(),
					bounded(new HttpCredentialsAdapter(credentials)))
					.setApplicationName("EvalOS")
					.build();
		}
		catch (IOException | java.security.GeneralSecurityException ex) {
			throw new IllegalStateException("Google Drive credentials could not be read", ex);
		}
	}

	private InputStream openKey() throws IOException {
		if (!keyJson.isBlank()) {
			return new ByteArrayInputStream(keyJson.getBytes(StandardCharsets.UTF_8));
		}
		return Files.newInputStream(Path.of(keyPath));
	}

	/**
	 * Connect and read timeouts on every Drive request.
	 *
	 * <p>Not optional: this call is made from a controller-triggered path, and an outbound
	 * request with the library's own generous defaults is how a "bounded single request"
	 * quietly becomes long-lived work (invariant 6). The credentials adapter still runs —
	 * it is what signs the request — this only wraps it to set the two limits afterwards.
	 */
	private HttpRequestInitializer bounded(HttpCredentialsAdapter credentials) {
		int millis = (int) timeout.toMillis();
		return request -> {
			credentials.initialize(request);
			request.setConnectTimeout(millis);
			request.setReadTimeout(millis);
		};
	}
}
