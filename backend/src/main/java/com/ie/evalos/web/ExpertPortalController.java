package com.ie.evalos.web;

import java.io.IOException;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.UploadedFileType;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.service.ExpertPortalService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The expert's six routes, behind the portal filter chain (Unit 14, Unit 15).
 *
 * <p><strong>No route takes a case id</strong>, exactly like {@code ClientPortalController}: the
 * token names the case, so there is nothing to enumerate and no ownership check to write here.
 * {@code PortalPrincipal.current(EXPERT)} is the whole authorization — and it is the same line
 * that refuses a client's token on these paths and an expert's token on the client's.
 *
 * <p>No {@code @PreAuthorize}: this chain has no roles in it, and a staff JWT is never read on
 * these paths (see {@code SecurityConfig}).
 *
 * <p><strong>{@code EXPERT_TIMED_OUT} is deliberately absent from this controller.</strong> A
 * timeout is a staff act about an expert who did nothing; it lives on
 * {@code CaseController.expertTimedOut} behind GM · Brand Manager · PM · CM. No portal route
 * reaches it, which is what stops an expert closing their own offer.
 */
@RestController
@RequestMapping("/api/portal/expert")
public class ExpertPortalController {

	/** Evidence requests with no description are useless to the client, so it is required. */
	public record EvidenceRequest(@NotBlank String missing) {
	}

	/** A decline with no reason tells the rematch nothing, so it is required. */
	public record DeclineRequest(@NotBlank String reason) {
	}

	/** @param url the letter to sign. A link today — see {@code ExpertPortalService.letterLink}. */
	public record LetterLink(String url) {
	}

	private final ExpertPortalService portal;

	ExpertPortalController(ExpertPortalService portal) {
		this.portal = portal;
	}

	private static PortalPrincipal expert() {
		return PortalPrincipal.current(PortalAudience.EXPERT);
	}

	/** The whitelisted view, and the first read stamps the receipt. */
	@GetMapping("/case")
	public ApiResponse<ExpertPortalService.ExpertCaseView> read() {
		return ApiResponse.ok(portal.view(expert()));
	}

	/** "I will sign this." Idempotent — a second click answers 200 with the state as it stands. */
	@PostMapping("/accept")
	public ApiResponse<ExpertPortalService.ExpertCaseView> accept() {
		return ApiResponse.ok(portal.accept(expert()));
	}

	/** "Not until the client sends this." Holds the case and opens a required checklist item. */
	@PostMapping("/request-evidence")
	public ApiResponse<ExpertPortalService.ExpertCaseView> requestEvidence(
			@Valid @RequestBody EvidenceRequest request) {
		return ApiResponse.ok(portal.requestEvidence(expert(), request.missing()));
	}

	/** "I will not take this." Sends the case to rematching with the expert's own reason. */
	@PostMapping("/decline")
	public ApiResponse<ExpertPortalService.ExpertCaseView> decline(@Valid @RequestBody DeclineRequest request) {
		return ApiResponse.ok(portal.decline(expert(), request.reason()));
	}

	/** Where the letter is, so the expert can sign it in whatever tool they already use. */
	@GetMapping("/letter")
	public ApiResponse<LetterLink> letter() {
		return ApiResponse.ok(new LetterLink(portal.letterLink(expert())));
	}

	/**
	 * <strong>The signature.</strong> Multipart, PDF only, attestation required.
	 *
	 * <p>The trust boundary is enforced here because this is EvalOS's endpoint: the size cap is
	 * Spring's multipart limit, the filename is never used as a path (the key is the document's own
	 * id), and the type is decided by <strong>sniffing the first five bytes</strong> rather than by
	 * anything the caller can rename.
	 *
	 * <p>The stream is opened more than once on purpose — to sniff, then to hash, then to store. A
	 * {@code MultipartFile} hands out a fresh {@code InputStream} per call, so each read starts at
	 * byte zero and nothing is buffered to make that true.
	 */
	@PostMapping("/signed-letter")
	public ApiResponse<ExpertPortalService.SignedLetterView> signedLetter(
			@RequestParam("file") MultipartFile file,
			@RequestParam String attestation) throws IOException {

		if (file.isEmpty()) {
			throw new InvalidRequestException("an empty file is not a signed letter");
		}
		// **PDF by content, not by name.** One sniffer for both upload surfaces since Unit 35 (gap
		// G14) — this check used to live here alone, which is how the client's upload went without
		// it. A `.pdf`-named JPEG must not become a signed letter: the PM's final QC is the only
		// other thing between it and a client, and this is the half that is not a person's
		// attention.
		UploadedFileType.require(file, UploadedFileType.SIGNED_LETTER);

		// **The part itself, not one of its streams.** The service reads it twice — once to hash,
		// once to store — and a `MultipartFile` hands out a fresh stream per call, which is the
		// same property the sniff above relies on.
		//
		// **No `attestedName` parameter, deliberately.** The name on the attestation is the case's
		// own expert, read server-side: a caller-supplied name only ever proved the caller could
		// spell their own claim twice.
		return ApiResponse.ok(portal.uploadSignedLetter(expert(), file.getOriginalFilename(),
				file.getSize(), file, attestation));
	}

}
