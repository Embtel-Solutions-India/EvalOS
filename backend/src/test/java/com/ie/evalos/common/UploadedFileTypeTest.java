package com.ie.evalos.common;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sniffer, and the property it exists for: <strong>a file is what its bytes say, not what its
 * name says</strong> (Unit 35, gap G14).
 *
 * <p>Both upload surfaces route through this, so a kind added here is a kind both accept — which is
 * the point of it having one home. The refusal message is asserted too: it names what was allowed
 * and never what the file turned out to be, because telling an uploader what EvalOS thinks their
 * file is helps somebody probing the check.
 */
class UploadedFileTypeTest {

	private static final byte[] PDF = "%PDF-1.7 hello".getBytes(StandardCharsets.UTF_8);
	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10 };
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0 };
	private static final byte[] DOCX = { 'P', 'K', 0x03, 0x04, 0x14, 0, 0, 0 };
	private static final byte[] DOC = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
			(byte) 0xE1 };
	/** A Windows executable, which is the thing an extension is most often lying about. */
	private static final byte[] EXE = { 'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0 };
	private static final byte[] HTML = "<html><script>".getBytes(StandardCharsets.UTF_8);

	private static MockMultipartFile named(String filename, byte[] body) {
		// The declared content type is deliberately the *wrong* one on the refusal cases: it is
		// attacker-controlled, and a check that consulted it would pass them.
		return new MockMultipartFile("file", filename, "application/pdf", body);
	}

	@Test
	void aClientDocumentMayBeAnyOfTheFiveKinds() {
		for (byte[] body : new byte[][] { PDF, JPEG, PNG, DOC, DOCX }) {
			assertThatCode(() -> UploadedFileType.require(named("evidence.pdf", body),
					UploadedFileType.CLIENT_DOCUMENT)).doesNotThrowAnyException();
		}
	}

	@Test
	void aSignedLetterMayOnlyBeAPdf() {
		assertThatCode(() -> UploadedFileType.require(named("signed.pdf", PDF), UploadedFileType.SIGNED_LETTER))
				.doesNotThrowAnyException();

		for (byte[] body : new byte[][] { JPEG, PNG, DOC, DOCX }) {
			assertThatThrownBy(() -> UploadedFileType.require(named("signed.pdf", body),
					UploadedFileType.SIGNED_LETTER)).isInstanceOf(InvalidRequestException.class);
		}
	}

	/** The case the check exists for, on both surfaces. */
	@Test
	void aRenamedExecutableOrWebPageIsRefusedEverywhere() {
		for (byte[] body : new byte[][] { EXE, HTML }) {
			assertThatThrownBy(() -> UploadedFileType.require(named("transcript.pdf", body),
					UploadedFileType.CLIENT_DOCUMENT)).isInstanceOf(InvalidRequestException.class);
			assertThatThrownBy(() -> UploadedFileType.require(named("letter.pdf", body),
					UploadedFileType.SIGNED_LETTER)).isInstanceOf(InvalidRequestException.class);
		}
	}

	/** A file shorter than the signature it claims is not that signature. */
	@Test
	void aTruncatedFileIsRefusedRatherThanGuessed() {
		assertThatThrownBy(() -> UploadedFileType.require(named("signed.pdf", new byte[] { '%', 'P' }),
				UploadedFileType.SIGNED_LETTER)).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theRefusalNamesWhatIsAllowedAndNotWhatTheFileIs() {
		assertThatThrownBy(() -> UploadedFileType.require(named("signed.pdf", JPEG),
				UploadedFileType.SIGNED_LETTER))
				.hasMessageContaining("PDF")
				.satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("JPEG"));
	}
}
