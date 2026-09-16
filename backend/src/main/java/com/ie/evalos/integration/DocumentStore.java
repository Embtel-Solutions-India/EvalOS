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
	 * that needs one, and a document is uploaded at questionnaire submit — which already ensures the
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
}
