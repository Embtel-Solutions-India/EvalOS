package com.ie.evalos.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.web.multipart.MultipartFile;

/**
 * What an uploaded file actually is, decided by its first bytes (Unit 35, gap G14).
 *
 * <p><strong>Neither the filename nor the declared content type is evidence.</strong> An extension
 * is whatever the file was renamed to and {@code Content-Type} is whatever the browser was told to
 * send, so both are attacker-controlled on a surface that accepts files from a public link. Unit 30
 * recorded "a declared type is recorded, not trusted" as an owed item and Unit 15 sniffed the signed
 * letter; this is the same check with one home, so **both upload surfaces get it** rather than
 * whichever was reviewed most recently.
 *
 * <p><strong>What this is not.</strong> It is not a virus scanner and does not pretend to be:
 * scanning is the bucket's job (S3 malware protection, an infrastructure control the business
 * enables). What sniffing buys is that the *class* of file is what was claimed — a renamed
 * executable, script or HTML page is refused at the door — and the other half of G14 is that
 * nothing is ever served inline, so a file that gets in cannot execute in a browser origin. See
 * {@code DocumentStore.presignedUrl}.
 *
 * <p><strong>The known limit, stated rather than papered over:</strong> {@link Kind#DOCX} is a ZIP
 * container and {@link Kind#DOC} an OLE2 one, so sniffing proves the container and not the
 * document. A malicious archive renamed {@code .docx} passes this check. It is still worth having —
 * it stops the trivial cases — and the compensating controls are the attachment header and the
 * human review the file is uploaded *for*.
 */
public final class UploadedFileType {

	/** The file classes EvalOS accepts anywhere, each with the signature that proves it. */
	public enum Kind {

		PDF("%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
		JPEG(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }),
		PNG(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A }),
		/** OLE2 compound file — the pre-2007 Word container. */
		DOC(new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1 }),
		/** ZIP container, which is what a {@code .docx} is. See the class note on what that proves. */
		DOCX(new byte[] { 'P', 'K', 0x03, 0x04 });

		private final byte[] magic;

		Kind(byte[] magic) {
			this.magic = magic;
		}
	}

	/** What the client may send against a checklist item: documents and scans. */
	public static final Set<Kind> CLIENT_DOCUMENT = EnumSet.of(Kind.PDF, Kind.JPEG, Kind.PNG, Kind.DOC, Kind.DOCX);

	/**
	 * What a signed letter may be: a PDF, and nothing else.
	 *
	 * <p>Narrower than the client's on purpose — a JPEG is a fine supporting document and is not a
	 * fine deliverable.
	 */
	public static final Set<Kind> SIGNED_LETTER = EnumSet.of(Kind.PDF);

	/** The longest signature above, which is all that ever needs reading. */
	private static final int HEAD_BYTES = 8;

	private UploadedFileType() {
	}

	/**
	 * Refuses a part whose first bytes are not one of {@code allowed}.
	 *
	 * <p>Reads its own stream and closes it. {@link MultipartFile} hands out a fresh
	 * {@code InputStream} per call, so the caller's own read still starts at byte zero and nothing
	 * is buffered to make that true.
	 *
	 * @throws InvalidRequestException with a message naming the allowed kinds, never the sniffed
	 *                                 one — telling an uploader what EvalOS thinks their file is
	 *                                 is free information for somebody probing the check
	 */
	public static void require(MultipartFile file, Set<Kind> allowed) throws IOException {
		byte[] head = new byte[HEAD_BYTES];
		int read;
		try (InputStream in = file.getInputStream()) {
			read = in.readNBytes(head, 0, head.length);
		}
		for (Kind kind : allowed) {
			if (read >= kind.magic.length && Arrays.equals(head, 0, kind.magic.length, kind.magic, 0,
					kind.magic.length)) {
				return;
			}
		}
		throw new InvalidRequestException("that file is not one of: " + names(allowed)
				+ ". Its contents do not match its name.");
	}

	private static String names(Set<Kind> allowed) {
		return allowed.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(", "));
	}
}
