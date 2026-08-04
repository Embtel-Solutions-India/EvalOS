package com.ie.evalos.integration;

import java.io.IOException;
import java.util.List;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one thing EvalOS does with Google Drive: put one generated document into one
 * folder that already exists.
 *
 * <p><strong>This is the first outbound Drive integration in EvalOS</strong> (Unit 13).
 * Until this unit {@code Case.driveLink} was a string EvalOS stored and never
 * dereferenced. Deliberately narrow, and to stay that way: no folder creation, no
 * permissions management, no reading documents back out, no listing. Each of those is a
 * different capability with a different blast radius, and none is in this unit's scope.
 *
 * <p><strong>The HTML is uploaded as a Google Doc, not as HTML.</strong> The target mime
 * type is {@code application/vnd.google-apps.document}, so Drive converts it on the way
 * in — which is why EvalOS needs no PDF library: Drive's own export produces a PDF from
 * the created Doc if one is ever wanted.
 *
 * <p>Timeouts are set on the transport in {@code GoogleDriveConfig}, because this runs in
 * a controller-triggered path and a bounded single request is the only reason invariant
 * 6's "controllers never run long-lived work" is satisfied here. If it turns out slow in
 * practice it moves to {@code job} (Unit 19), which is where that rule points.
 */
public class GoogleDriveClient {

	/** Drive's own mime type for a Doc. Uploading <em>to</em> it is what triggers the conversion. */
	private static final String GOOGLE_DOC = "application/vnd.google-apps.document";

	private static final Logger log = LoggerFactory.getLogger(GoogleDriveClient.class);

	/**
	 * @param fileId the Drive file id, recorded in the audit trail
	 * @param link   Drive's own {@code webViewLink} — the URL that opens the created Doc.
	 *               Taken from the response rather than assembled from the id, so it is
	 *               whatever Drive says it is
	 */
	public record Uploaded(String fileId, String link) {
	}

	/**
	 * Null when this environment has no Drive credentials. Only {@code local} is allowed
	 * to reach that state — {@code GoogleDriveConfig} refuses to start anywhere else — and
	 * it exists so a developer can run the whole app, generate profiles and exercise every
	 * other route without a service-account key. The single guard below is what turns it
	 * into a stated 502 rather than a null-pointer at the first upload.
	 */
	private final Drive drive;

	public GoogleDriveClient(Drive drive) {
		this.drive = drive;
	}

	/**
	 * Uploads {@code html} into {@code folderId} as a Google Doc named {@code name}.
	 *
	 * @throws DriveUnavailableException if Drive is not configured here, or the request
	 *                                  failed or timed out. The caller changes nothing on
	 *                                  the strength of a failure
	 */
	public Uploaded uploadHtmlAsDoc(String folderId, String name, String html) {
		if (drive == null) {
			throw new DriveUnavailableException(
					"Google Drive is not configured in this environment, so nothing was written");
		}

		File metadata = new File()
				.setName(name)
				.setMimeType(GOOGLE_DOC)
				// The one and only parent. A create with no parent lands in the service
				// account's own space, which is exactly the silent misfiling the spec's
				// "a link that does not yield a folder id is a refusal, not a fallback"
				// rule exists to prevent — so this list is never empty by the time we are
				// here: RedactedProfileService refuses before calling.
				.setParents(List.of(folderId));

		try {
			File created = drive.files()
					.create(metadata, ByteArrayContent.fromString("text/html", html))
					// Requested explicitly: the create response is partial by default and
					// carries neither of these unless asked.
					.setFields("id, webViewLink")
					// Required for a Shared Drive, which is one of the two access models
					// the spec names (the other being domain-wide delegation). Harmless on
					// a My Drive folder, and omitting it makes a Shared Drive parent a 404.
					.setSupportsAllDrives(true)
					.execute();

			return new Uploaded(created.getId(), created.getWebViewLink());
		}
		// IOException only, deliberately narrow. The Google client signals everything that
		// is genuinely Drive's fault this way — GoogleJsonResponseException for a refused
		// request, HttpResponseException and SocketTimeoutException for transport and for
		// the timeout set in GoogleDriveConfig — so this catch covers exactly the cases a
		// 502 describes. A RuntimeException from here is our bug, not Drive's, and it is
		// left to propagate: answering 502 would tell the PM to retry something no retry
		// can fix, and hide a defect behind an upstream-fault status.
		catch (IOException ex) {
			// The folder id is logged because it is the thing a human has to go and look
			// at; the case is logged by the caller, which knows it. Nothing about the
			// expert is logged — this document is a redaction and a log line is a copy.
			log.error("Drive upload failed into folder {}", folderId, ex);
			throw new DriveUnavailableException("Google Drive did not accept the document", ex);
		}
	}
}
