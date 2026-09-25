package com.ie.evalos.integration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * The one door to the S3 document store (Unit 30). Replaces {@code GoogleDriveClient}.
 *
 * <p><strong>Two capabilities and no more: put an object, presign a read.</strong> There is no
 * delete, no list, no copy and no move. The capability is <em>absent from the codebase</em> rather
 * than present-and-unused, so a future unit that needs one has to add it and answer for it.
 *
 * <p><strong>{@code GhlHttp} used to hold the same position and no longer does</strong> — Unit 37
 * gave it {@code post}, {@code put} and {@code delete} so Sales and Marketing could work
 * opportunities from EvalOS. <strong>That is not a precedent for this class.</strong> The
 * argument there was that the desk had moved and every write would be audited; the argument here
 * is about evidence. These objects are a client's identity documents and an expert's signed
 * letter, and a system that can quietly delete evidence will eventually be asked whether it did.
 * Read the two as separate decisions, because they were.
 *
 * <p><strong>Bytes stream and are never buffered.</strong> {@link #put} takes an
 * {@link InputStream} with a known length and hands it straight to the SDK. EvalOS holds no byte
 * array, writes no temp file and has no blob column — invariant 14, and a test rather than a
 * convention.
 *
 * <p><strong>Reads are bounded capabilities.</strong> Nothing here returns object bytes to a
 * caller. {@link #presignedUrl} mints a URL that expires in minutes, and it is the caller's job to
 * have run the case's scope check <em>before</em> asking: a URL minted before the check is a URL
 * that leaked before the check.
 *
 * <p><strong>Unconfigured is a 502, not a failed boot.</strong> A laptop runs with no AWS
 * credential at all and every non-document route works — the property
 * {@code evalos.drive.required=false} used to protect, and the one most easily lost in a rewrite.
 */
@Component
public class DocumentStore {

	private static final Logger log = LoggerFactory.getLogger(DocumentStore.class);

	/**
	 * How long a presigned read is good for.
	 *
	 * <p>Long enough to click, too short to be worth forwarding. This is the whole security
	 * difference from the Drive link it replaces, which was a permanent capability sitting in a
	 * database column and travelling in email.
	 */
	static final Duration READ_WINDOW = Duration.ofMinutes(5);

	private final String bucket;
	private final boolean configured;
	private final S3Client s3;
	private final S3Presigner presigner;

	/**
	 * Where documents live on a laptop instead of in a bucket — <strong>local development only</strong>.
	 *
	 * <p><strong>This exists because the alternatives were both wrong.</strong> Blank S3 settings
	 * made every document route answer 502, so Unit 53 could not be exercised at all; naming the
	 * production bucket meant any developer whose shell held AWS credentials wrote test uploads
	 * into production's own {@code brandId/client/…} prefixes. A directory resolves it: there is
	 * no credential, no network call, and nothing that can reach a real bucket by accident.
	 *
	 * <p><strong>Blank everywhere but {@code application-local.yml}</strong>, and the constructor
	 * refuses to start if it is set outside the {@code local} profile. A deployment that set this
	 * would be writing client documents to a container filesystem that vanishes on the next deploy,
	 * and serving them from a route with no authentication in front of it.
	 */
	private final Path localDir;

	/**
	 * @param endpoint an S3-compatible endpoint to talk to instead of AWS, or blank for AWS itself.
	 *                 <strong>Local development only</strong> — `docker-compose.local.yml` runs
	 *                 MinIO and `application-local.yml` points here at it, which is what makes the
	 *                 document routes exercisable on a laptop without an AWS account. It is blank
	 *                 in `application.yml` and `application-prod.yml`, so a deployment reaches real
	 *                 S3 unless somebody sets this on purpose.
	 */
	DocumentStore(@Value("${evalos.s3.bucket:}") String bucket,
			@Value("${evalos.s3.region:}") String region,
			@Value("${evalos.s3.endpoint:}") String endpoint,
			@Value("${evalos.s3.local-dir:}") String localDir,
			org.springframework.core.env.Environment environment) {

		this.bucket = bucket;

		// **Local disk wins when it is set, and it is only allowed to be set locally.** The guard is
		// a startup failure rather than a warning because the failure it prevents is silent: client
		// documents written to a container filesystem, served from a route with no authentication,
		// and gone at the next deploy. A deployment that wants a real bucket already has one.
		// **`acceptsProfiles`, not `getActiveProfiles`.** `application.yml` sets
		// `spring.profiles.default: local`, so a run with nothing explicitly activated IS the local
		// profile and loads `application-local.yml` — while `getActiveProfiles()` returns an empty
		// array for it. Reading the active list therefore refused to start every integration test
		// in the suite, which is the same mistake in reverse: it would have called a deployment
		// local too if one ever ran with no profile set.
		if (!localDir.isBlank() && !environment.acceptsProfiles(
				org.springframework.core.env.Profiles.of("local"))) {
			throw new IllegalStateException("evalos.s3.local-dir is set to '" + localDir
					+ "' outside the 'local' profile. It writes documents to the local filesystem and "
					+ "serves them from an unauthenticated route; set EVALOS_S3_BUCKET and "
					+ "EVALOS_S3_REGION instead.");
		}
		this.localDir = localDir.isBlank() ? null : Path.of(localDir).toAbsolutePath().normalize();
		// **The credential is deliberately not a property.** The SDK's default provider chain reads
		// the environment, the shared profile file and the instance role, which is how every other
		// AWS-hosted service is configured. An `evalos.s3.access-key` property would invite a
		// credential into a committed yaml — the accident `ConfigSecretsTest` exists to catch.
		this.configured = this.localDir != null || (!bucket.isBlank() && !region.isBlank());

		if (this.localDir != null) {
			log.warn("Documents are being stored on the LOCAL FILESYSTEM at {} - development only. "
					+ "Nothing reaches S3 and reads are served unauthenticated by "
					+ "LocalDocumentController.", this.localDir);
			this.s3 = null;
			this.presigner = null;
		}
		else if (!configured) {
			log.warn("No S3 bucket or region configured - document routes will answer 502. "
					+ "Set EVALOS_S3_BUCKET and EVALOS_S3_REGION to enable them.");
			this.s3 = null;
			this.presigner = null;
		}
		else {
			// Both names are echoed for the reason the GHL client echoes its two: whoever reads
			// this is provisioning an environment and needs to know which variable resolved. A
			// bucket name is not a secret, and no credential appears here or in any message below.
			// Both names are echoed for the reason the GHL client echoes its two: whoever reads
			// this is provisioning an environment and needs to know which variable resolved. A
			// bucket name is not a secret, and no credential appears here or in any message below.
			// The endpoint is named too when it is set, because "my uploads went somewhere I did
			// not expect" is exactly the confusion an unlogged override causes.
			log.info("S3 document store configured: bucket={}, region={}, endpoint={}", bucket, region,
					endpoint.isBlank() ? "AWS" : endpoint);
			Region parsed = Region.of(region);

			// **Path-style addressing when an endpoint is overridden**, because MinIO and most
			// S3-compatible stores serve `host/bucket/key` while AWS serves `bucket.host/key`.
			// Virtual-host style against `localhost` would resolve `evalos-documents-local.localhost`
			// and fail as a DNS error, which reads like a network problem rather than a
			// configuration one.
			S3Configuration addressing = S3Configuration.builder()
					.pathStyleAccessEnabled(!endpoint.isBlank())
					.build();

			S3ClientBuilder client = S3Client.builder().region(parsed).serviceConfiguration(addressing);
			S3Presigner.Builder signer = S3Presigner.builder().region(parsed)
					.serviceConfiguration(addressing);
			if (!endpoint.isBlank()) {
				// **The presigner needs it too, and forgetting that is the subtle half.** A client
				// pointed at MinIO with a presigner still pointed at AWS uploads successfully and
				// then hands out URLs on `s3.amazonaws.com` that 404 — the upload looks fine and
				// only the read is broken, hours later.
				java.net.URI uri = java.net.URI.create(endpoint);
				client = client.endpointOverride(uri);
				signer = signer.endpointOverride(uri);
			}
			this.s3 = client.build();
			this.presigner = signer.build();
		}
	}

	@PreDestroy
	void close() {
		if (s3 != null) {
			s3.close();
		}
		if (presigner != null) {
			presigner.close();
		}
	}

	/** Whether a bucket and region are present. False means every call here answers 502. */
	public boolean isConfigured() {
		return configured;
	}

	/**
	 * Streams one object in.
	 *
	 * @param length the exact byte count, which S3 requires up front. That requirement is also why
	 *               this cannot quietly buffer: a caller who does not know the length has to say
	 *               so rather than have the store read the whole stream into memory to find out.
	 */
	public void put(String key, InputStream body, long length, String contentType) {
		requireConfigured();
		if (localDir != null) {
			putLocally(key, body);
			return;
		}
		try {
			s3.putObject(PutObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					// Set per request as well as on the bucket. The bucket default is the real
					// control; this is the half that survives somebody recreating the bucket
					// without it.
					.serverSideEncryption(ServerSideEncryption.AES256)
					.contentType(contentType == null ? "application/octet-stream" : contentType)
					.build(),
					RequestBody.fromInputStream(body, length));
		}
		catch (S3Exception | SdkClientException ex) {
			// The log names the key; the message to the caller does not name the bucket. The key is
			// ours and diagnostic, the bucket name is deployment configuration and does not belong
			// in an API response.
			log.error("S3 put failed for key {}", key, ex);
			throw new DocumentStoreUnavailableException(
					"The document store did not accept the upload. Nothing was saved - try again.", ex);
		}
	}

	/**
	 * A short-lived URL for reading one object.
	 *
	 * <p><strong>Never stored.</strong> A presigned URL in a database column is a credential in a
	 * database column. It is minted per request, handed to one caller, and expires.
	 *
	 * <p><strong>Always an attachment, and this is the half of gap G14 that closes the path rather
	 * than the file.</strong> EvalOS runs no virus scanner — scanning is the bucket's job — so the
	 * control that matters is that an uploaded file cannot *execute*: with
	 * {@code Content-Disposition: attachment} the browser downloads it instead of rendering it, so
	 * a malicious HTML page or SVG that got past the sniffer still has no origin to run in. Every
	 * read path in EvalOS goes through this method, which is why it is one line here rather than a
	 * rule each caller has to remember.
	 */
	public String presignedUrl(String key) {
		requireConfigured();
		if (localDir != null) {
			return localUrl(key);
		}
		try {
			return presigner.presignGetObject(GetObjectPresignRequest.builder()
					.signatureDuration(READ_WINDOW)
					.getObjectRequest(GetObjectRequest.builder()
							.bucket(bucket)
							.key(key)
							// The filename is deliberately not set: it would put client-supplied text
							// into a response header, and the browser's own default (the key's last
							// segment, a UUID) is safe and sufficient.
							.responseContentDisposition("attachment")
							.build())
					.build())
					.url()
					.toString();
		}
		catch (S3Exception | SdkClientException ex) {
			log.error("S3 presign failed for key {}", key, ex);
			throw new DocumentStoreUnavailableException("The document store is unavailable.", ex);
		}
	}

	/**
	 * Where a client's own uploads live.
	 *
	 * <p><strong>Brand first, and that is Unit 30's open question (b) answered.</strong> Every
	 * other store in EvalOS enforces brand at the row; a key prefix is S3's equivalent, and it is
	 * what lets a lifecycle rule, an access policy or a per-brand export ever be written. Adding it
	 * later is not a code change — it is a migration of the objects themselves.
	 *
	 * <p><strong>The client segment is the GHL contact id, and that is a business ruling of
	 * 2026-09-17: one id represents a contact everywhere in the system.</strong> It was
	 * {@code contact_snapshot.id} between 2026-09-14 and then — swapped in after IE replaced its
	 * GHL sub-account on 2026-09-11 with no contact migration, which left post-swap clients with no
	 * GHL id at all, so the key could not be built and {@code upload} threw.
	 *
	 * <p><strong>What makes the GHL id safe to key on now is D3d and D3c, not optimism.</strong>
	 * The contact is created at {@code setPassword}, at the next sign-in, or at the first request
	 * that needs one, and a document is uploaded with the request — which already ensures the
	 * id before it opens the opportunity. The id is therefore present at the moment a key is built,
	 * which was exactly what was untrue in September's failure.
	 *
	 * <p><strong>The residual exposure is stated rather than hidden:</strong> a contact whose GHL id
	 * is missing (a pre-swap row, or an outage that has not been repaired yet) cannot have a key
	 * built, and the caller refuses with a message naming the fix instead of writing to a guessed
	 * prefix. And a second sub-account swap would orphan these keys again — reads resolve through
	 * the stored {@code object_key}, so nothing breaks retroactively, but new writes would land in
	 * a new namespace beside the old one.
	 *
	 * <p><strong>Existing objects do not move, and do not need to.</strong> Reads resolve through
	 * the stored {@code case_document.object_key}, which is authoritative; only new writes take
	 * this shape. A bulk re-key would be an S3 object copy, not a migration, and is not required
	 * for correctness.
	 *
	 * <p><strong>No email appears in any key</strong>: an address in a key is PII in a log line, in
	 * a bucket listing, and in every access record that names it.
	 *
	 * <p><strong>The object name is the document's own id, not its filename.</strong> That closes
	 * path traversal, collisions and PII-in-the-key in one move. The real filename lives in
	 * {@code case_document.filename}, where it is data rather than a path.
	 *
	 * <pre>
	 * // ponytail: one prefix for every document a client sends, case or request. Unit 53's funnel
	 * // upload is a step earlier in the lifecycle and lands in the same place, which is what makes
	 * // Handoff A's carry-forward a row insert over the same object rather than an S3 copy.
	 * </pre>
	 *
	 * @param ghlContactId GHL's contact id — the one identifier that represents a contact
	 *                     everywhere in this system (2026-09-17), so a key resolves in both
	 *                     systems with no mapping table
	 */
	public static String clientKey(UUID brandId, String ghlContactId, UUID documentId) {
		return "%s/client/%s/%s".formatted(brandId, ghlContactId, documentId);
	}

	/**
	 * Where EvalOS's own artefacts live: the draft, and the expert's signed letter.
	 *
	 * <p>Separate from {@link #clientKey} because the two have different owners and different
	 * lifetimes. A client's document is evidence they supplied; a draft is work we produced.
	 */
	public static String caseKey(UUID brandId, UUID caseId, String folder, UUID documentId) {
		return "%s/case/%s/%s/%s".formatted(brandId, caseId, folder, documentId);
	}

	private void requireConfigured() {
		if (!configured) {
			throw new DocumentStoreUnavailableException(
					"The document store is not configured in this environment. "
							+ "Set EVALOS_S3_BUCKET and EVALOS_S3_REGION.");
		}
	}

	// --- local filesystem mode (development only) -------------------------------

	/**
	 * One handed-out local read, and when it stops working.
	 *
	 * <p>Held in memory on purpose: these are capability URLs with a five-minute life, and a
	 * restart invalidating them is correct rather than a limitation.
	 */
	private record LocalRead(String key, Instant expiresAt) {
	}

	private final Map<String, LocalRead> localReads = new ConcurrentHashMap<>();

	private void putLocally(String key, InputStream body) {
		Path target = resolveLocal(key);
		try {
			Files.createDirectories(target.getParent());
			Files.copy(body, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		catch (java.io.IOException ex) {
			log.error("Local document write failed for key {}", key, ex);
			throw new DocumentStoreUnavailableException(
					"The document store did not accept the upload. Nothing was saved - try again.", ex);
		}
	}

	/**
	 * A relative URL rather than an absolute one, which is the one real difference from a presign.
	 *
	 * <p>S3 hands back an absolute URL on its own host; this has no host to name that would be
	 * right for both the staff SPA and the two portals, each of which proxies {@code /api} to the
	 * backend from a different origin. A relative URL resolves against whichever origin the reader
	 * is already on and reaches the same route through the same proxy — and every caller opens it
	 * with {@code window.open}, which handles both.
	 */
	private String localUrl(String key) {
		String token = UUID.randomUUID().toString().replace("-", "");
		localReads.put(token, new LocalRead(key, Instant.now().plus(READ_WINDOW)));
		// Swept here rather than on a timer: the map is bounded by how often somebody clicks a
		// document on a laptop, and a scheduled job for that would be machinery with no user.
		localReads.values().removeIf((held) -> held.expiresAt().isBefore(Instant.now()));
		return "/api/local-documents/" + token;
	}

	/**
	 * The file one handed-out token names, or empty when it never existed or has expired.
	 *
	 * <p>Expiry is enforced here and not only at mint time, which is the whole point of the window:
	 * a URL that was forwarded rather than clicked has to stop working.
	 */
	public java.util.Optional<Path> resolveLocalRead(String token) {
		LocalRead held = localReads.get(token);
		if (held == null || held.expiresAt().isBefore(Instant.now())) {
			localReads.remove(token);
			return java.util.Optional.empty();
		}
		Path file = resolveLocal(held.key());
		return Files.isRegularFile(file) ? java.util.Optional.of(file) : java.util.Optional.empty();
	}

	/**
	 * A key resolved under the local root, <strong>and proved to be under it</strong>.
	 *
	 * <p>Every key here is built by EvalOS from ids, so a traversal would need a bug upstream rather
	 * than a hostile caller — which is exactly why the check is cheap to keep and expensive to
	 * omit. A filename never reaches a key (see {@link #clientKey}), and this is the second lock on
	 * the same door.
	 */
	private Path resolveLocal(String key) {
		Path file = localDir.resolve(key).toAbsolutePath().normalize();
		if (!file.startsWith(localDir)) {
			throw new DocumentStoreUnavailableException("That document key is not a valid one.");
		}
		return file;
	}
}
