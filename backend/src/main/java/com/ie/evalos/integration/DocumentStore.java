package com.ie.evalos.integration;

import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
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
 * than present-and-unused, so a future unit that needs one has to add it and answer for it. That
 * is the position {@code GhlHttp} holds about writing to GHL, and it is worth more here: these
 * objects are a client's identity documents and an expert's signed letter, and a system that can
 * quietly delete evidence will eventually be asked whether it did.
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

	DocumentStore(@Value("${evalos.s3.bucket:}") String bucket,
			@Value("${evalos.s3.region:}") String region) {

		this.bucket = bucket;
		// **The credential is deliberately not a property.** The SDK's default provider chain reads
		// the environment, the shared profile file and the instance role, which is how every other
		// AWS-hosted service is configured. An `evalos.s3.access-key` property would invite a
		// credential into a committed yaml — the accident `ConfigSecretsTest` exists to catch.
		this.configured = !bucket.isBlank() && !region.isBlank();

		if (!configured) {
			log.warn("No S3 bucket or region configured - document routes will answer 502. "
					+ "Set EVALOS_S3_BUCKET and EVALOS_S3_REGION to enable them.");
			this.s3 = null;
			this.presigner = null;
		}
		else {
			// Both names are echoed for the reason the GHL client echoes its two: whoever reads
			// this is provisioning an environment and needs to know which variable resolved. A
			// bucket name is not a secret, and no credential appears here or in any message below.
			log.info("S3 document store configured: bucket={}, region={}", bucket, region);
			Region parsed = Region.of(region);
			this.s3 = S3Client.builder().region(parsed).build();
			this.presigner = S3Presigner.builder().region(parsed).build();
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
	 */
	public String presignedUrl(String key) {
		requireConfigured();
		try {
			return presigner.presignGetObject(GetObjectPresignRequest.builder()
					.signatureDuration(READ_WINDOW)
					.getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
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
	 * <p><strong>The client segment is the GHL contact id</strong>, which the Client Portal knows
	 * too, so a key resolves in both systems with no mapping table between them (invariant 7).
	 * <strong>No email appears in any key</strong>: an address in a key is PII in a log line, in a
	 * bucket listing, and in every access record that names it.
	 *
	 * <p><strong>The object name is the document's own id, not its filename.</strong> That closes
	 * path traversal, collisions and PII-in-the-key in one move. The real filename lives in
	 * {@code case_document.filename}, where it is data rather than a path.
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
}
