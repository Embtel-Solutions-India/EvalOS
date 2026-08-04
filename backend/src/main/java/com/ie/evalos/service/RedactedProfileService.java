package com.ie.evalos.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.IllegalTransitionException;
import com.ie.evalos.integration.GoogleDriveClient;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/**
 * The expert profile a client is shown before they approve the expert — anonymous by
 * default, identified once the case is paid (Unit 13).
 *
 * <p><strong>Why this exists at all.</strong> A client or attorney has to approve the
 * expert before the letter is drafted, and must be able to judge the credentials
 * <em>without being able to identify the person</em> — otherwise they contact the expert
 * directly and EvalOS is cut out of the work it sourced.
 *
 * <p><strong>Generated, never stored.</strong> The document is rendered from the roster row
 * on every request and held only in memory: streamed to the caller, or handed to Drive.
 * Nothing is written to Postgres and nothing touches disk (invariant 14). That is also the
 * only reason this unit can exist without object storage — EvalOS is not editing or hosting
 * a CV, it is generating a document from data it already owns.
 *
 * <p><strong>Redaction is a whitelist, not a blacklist.</strong> {@link #credentials} names
 * every field that may appear, and a field added to {@code Expert} in a later unit
 * therefore does not appear. A blacklist is how such a field leaks by default: the person
 * adding it has to remember a rule in a file they are not editing.
 *
 * <p>The scoped reads are the ones the rest of the system uses —
 * {@link CaseLifecycleService#read} for the case, {@link ExpertRepository#findScoped} for
 * the expert — so another brand's case, and another Case Manager's, is refused there.
 * No new scoping path.
 */
@Service
public class RedactedProfileService {

	private static final Logger log = LoggerFactory.getLogger(RedactedProfileService.class);

	private static final String OBJECT_TYPE = "CASE";

	private static final String TEMPLATE = "templates/redacted-profile.html";

	/**
	 * The two shapes a Drive folder URL takes: {@code /folders/<id>} and {@code ?id=<id>}.
	 *
	 * <p>{@code evalos_case.drive_link} is a URL, not a folder id, and the client needs the
	 * id. Anything else is a refusal rather than a fallback — see
	 * {@link #folderIdOf(String)}.
	 */
	private static final List<Pattern> FOLDER_ID_SHAPES = List.of(
			Pattern.compile("/folders/([A-Za-z0-9_-]{6,})"),
			Pattern.compile("[?&]id=([A-Za-z0-9_-]{6,})"));

	/** {@code {{name}}} in the template. */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

	/**
	 * One rendered profile.
	 *
	 * @param reference the anonymous label this document uses for the expert. Returned
	 *                  alongside the HTML on both profiles so a PM and a client are talking
	 *                  about the same expert whichever one they are looking at
	 */
	public record Profile(String html, String reference) {
	}

	/** What the Drive write answers, and what its audit row records. */
	public record DriveWrite(String fileId, String link, String reference) {
	}

	private final CaseLifecycleService cases;
	private final ExpertRepository experts;
	private final ExpertLoadService loads;
	private final AuditService audit;
	private final GoogleDriveClient drive;

	RedactedProfileService(CaseLifecycleService cases, ExpertRepository experts, ExpertLoadService loads,
			AuditService audit, GoogleDriveClient drive) {
		this.cases = cases;
		this.experts = experts;
		this.loads = loads;
		this.audit = audit;
		this.drive = drive;
	}

	// --- the two profiles ----------------------------------------------------

	/**
	 * The anonymous profile: credentials, fields, tier and experience, and none of the
	 * expert's name, institution or contact details.
	 *
	 * <p>Available whenever an expert is assigned — there is no payment gate on this one,
	 * because approving the expert is what the client does <em>before</em> the work, and
	 * since Unit 05a a case can be worked unpaid.
	 */
	@Transactional(readOnly = true)
	public Profile redacted(UUID caseId) {
		return redactedFor(cases.read(caseId));
	}

	/** The redacted profile for a case already loaded through the scoped read. */
	private Profile redactedFor(Case subject) {
		Expert expert = expertOn(subject);
		String reference = referenceFor(subject.getId(), expert.getId());

		return new Profile(render(reference,
				"Credentials of the proposed expert, prepared for client review.",
				// Empty, and this is the whole redaction: no identity block is assembled
				// on this path, so there is nothing for the template to interpolate.
				"",
				expert,
				"This profile is deliberately anonymous. The expert's identity is released "
						+ "on payment, once the engagement is confirmed.",
				reference), reference);
	}

	/**
	 * The identified profile, <strong>refused unless the case is paid</strong>.
	 *
	 * <p>409 rather than 403: the caller is permitted and the request is valid, the case is
	 * simply not in a state that releases this — the same shape {@code markDocsComplete}
	 * answers an unpaid case with, and the same exception, so both read identically to a
	 * client.
	 *
	 * <p>"Full" means the expert's identity and credentials. It is <strong>not</strong> the
	 * roster row: {@code payment_detail} is excluded always (invariant 4), and so are
	 * {@code notes}, {@code recruitment_source}, {@code quality_score},
	 * {@code performance_flags} and {@code avg_response_hours} — internal assessments and
	 * free text, and this document goes to a client.
	 */
	@Transactional(readOnly = true)
	public Profile full(UUID caseId) {
		Case subject = cases.read(caseId);
		if (!subject.isPaid()) {
			throw new IllegalTransitionException(
					"the case has not been paid, so the expert's full profile is not released yet");
		}

		Expert expert = expertOn(subject);
		String reference = referenceFor(subject.getId(), expert.getId());
		String heading = blankToDash(expert.getFullName());

		return new Profile(render(heading,
				"Full credentials of the assigned expert.",
				identityBlock(expert),
				expert,
				"Released because this case is paid. Previously issued to the client as "
						+ reference + ".",
				reference), reference);
	}

	// --- the Drive write -----------------------------------------------------

	/**
	 * Writes the redacted profile into the case's own Drive folder and answers the link.
	 *
	 * <p><strong>Deliberately not {@code @Transactional}.</strong> This makes an outbound
	 * HTTP request, and holding a database transaction open across it would tie a connection
	 * to a remote service's latency. Each step commits on its own instead — the same
	 * reasoning {@code WebhookGateway} is built on — and the ordering carries the guarantee:
	 * the audit row is written <em>after</em> a successful upload, so a failed write leaves
	 * no trail claiming a document exists. A successful upload whose audit row then fails is
	 * the residual risk, and it is the harmless direction: the document is regenerable and
	 * overwriting it is a fresh upload.
	 *
	 * @throws IllegalTransitionException  if the case's {@code drive_link} yields no folder
	 *                                    id, or there is no link at all. <strong>Nothing is
	 *                                    written</strong> — see {@link #folderIdOf(String)}
	 * @throws com.ie.evalos.integration.DriveUnavailableException if Drive refused or timed
	 *                                    out (502). Nothing in EvalOS changed
	 */
	public DriveWrite writeRedactedToDrive(UUID caseId) {
		Case subject = cases.read(caseId);
		Profile profile = redactedFor(subject);

		String folderId = folderIdOf(subject.getDriveLink())
				.orElseThrow(() -> new IllegalTransitionException(subject.getDriveLink() == null
						|| subject.getDriveLink().isBlank()
								? "this case has no Google Drive folder link, so there is nowhere to file the profile"
								: "this case's Google Drive link does not name a folder, so there is nowhere to "
										+ "file the profile: " + subject.getDriveLink()));

		String name = "Redacted expert profile — " + subject.getCaseCode();
		GoogleDriveClient.Uploaded uploaded = drive.uploadHtmlAsDoc(folderId, name, profile.html());

		log.info("Filed the redacted profile for case {} as Drive file {} in folder {}",
				subject.getCaseCode(), uploaded.fileId(), folderId);

		// The actor, the case and the Drive file id — the three things the acceptance
		// criterion names. EXPORTED rather than UPDATED: nothing about the case changed,
		// a document left the building. Open vocabulary, so no migration (as with CHASED).
		audit.recordEvent(OBJECT_TYPE, subject.getId(), AuditAction.EXPORTED,
				TenantContext.current().memberId(), null,
				Map.of("driveFileId", uploaded.fileId(),
						"driveFolderId", folderId,
						"expertReference", profile.reference(),
						"document", name));

		return new DriveWrite(uploaded.fileId(), uploaded.link(), profile.reference());
	}

	// --- redaction -----------------------------------------------------------

	/**
	 * <strong>The whitelist.</strong> Every value that may appear on a profile, and the only
	 * place either variant gets its content from.
	 *
	 * <p>Excluded, and each for a stated reason: {@code full_name}, {@code institution},
	 * {@code email} and {@code phone} identify the person, which is the entire point;
	 * {@code payment_detail} never appears anywhere in EvalOS (invariant 4);
	 * {@code quality_score}, {@code performance_flags} and {@code avg_response_hours} are
	 * internal assessments; and {@code notes} and {@code recruitment_source} are excluded
	 * <strong>because they are free text</strong> — any free-text field can contain the very
	 * name being redacted, so no free-text field enters this document whatever it is
	 * nominally for.
	 *
	 * <p>{@code title} is the one free-text field that does appear, because an academic rank
	 * is the credential. It is escaped like everything else, and the panel renders the
	 * result in a sandboxed iframe for exactly that reason.
	 */
	private Map<String, String> credentials(Expert expert) {
		return Map.of(
				"title", blankToDash(expert.getTitle()),
				"tier", expert.getTier() == null ? "—" : label(expert.getTier().name()),
				"primaryFields", tags(expert.getPrimaryFields()),
				"secondaryFields", tags(expert.getSecondaryFields()),
				"letterTypes", tags(expert.getLetterTypes()),
				// Derived, never expert.total_cases_completed: that column was created NOT
				// NULL DEFAULT 0 in V7 and nothing has ever written it, so reading it would
				// print "0 cases completed" on the profile of the brand's busiest expert.
				// ExpertLoadService is the one place that count is taken (Unit 11's reason).
				"casesCompleted", String.valueOf(loads.forExpert(expert.getId()).completed()),
				"years", experience(expert.getDateOnboarded()));
	}

	/**
	 * The identity the full profile adds. Assembled from escaped parts and interpolated as
	 * markup, which makes it the one placeholder the template does not escape — so nothing
	 * else may ever be passed through this route.
	 */
	private String identityBlock(Expert expert) {
		StringBuilder out = new StringBuilder("<div class=\"identity\">");
		appendIfPresent(out, "Institution", expert.getInstitution());
		appendIfPresent(out, "Email", expert.getEmail());
		appendIfPresent(out, "Phone", expert.getPhone());
		return out.append("</div>").toString();
	}

	private void appendIfPresent(StringBuilder out, String label, String value) {
		if (value != null && !value.isBlank()) {
			out.append("<p><strong>").append(escape(label)).append(":</strong> ")
					.append(escape(value)).append("</p>");
		}
	}

	// --- the reference label -------------------------------------------------

	/**
	 * The anonymous label, e.g. {@code Expert AK}.
	 *
	 * <p><strong>Stable per case, and carrying no ordering information about the roster.</strong>
	 * Stable because the PM and the client have a conversation about "Expert AK" across
	 * several messages and days, and a label that changed between two generations would make
	 * them think they were being shown two people. Carrying no ordering because a sequential
	 * "Expert 1" would tell the client they were the brand's first choice, or their fourth.
	 *
	 * <p>Derived from both ids, so the same expert proposed on two cases is a different
	 * label on each — a client who sees two of their own cases must not be able to match the
	 * expert across them.
	 *
	 * <p>ponytail: two letters is 676 labels, so two cases sharing an expert collide about
	 * 1 in 676 times. That is a cosmetic coincidence on documents that are never compared
	 * side by side (they go to different clients), not an identity leak. Widen to three
	 * characters if it ever matters.
	 */
	static String referenceFor(UUID caseId, UUID expertId) {
		byte[] digest = sha256(caseId + ":" + expertId);
		char first = (char) ('A' + Math.floorMod(digest[0], 26));
		char second = (char) ('A' + Math.floorMod(digest[1], 26));
		return "Expert " + first + second;
	}

	private static byte[] sha256(String input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandated by the platform; unreachable.
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	// --- the folder id -------------------------------------------------------

	/**
	 * The Drive folder id inside a {@code drive_link}, if the link names one.
	 *
	 * <p><strong>Empty is a refusal, never a fallback.</strong> The caller must not write to
	 * a default folder, to the Drive root, or to the service account's own space: the file
	 * would silently land somewhere nobody looks, or worse, somewhere another brand can see
	 * it. A cross-brand leak outside the database is one no {@code brand_id} predicate can
	 * close, so the only safe answer to an unusable link is to refuse and name it, and let
	 * somebody fix the case.
	 *
	 * <p>Package-private so it can be tested as what it is — a parser with two accepted
	 * shapes and an open-ended set of rejected ones.
	 */
	static Optional<String> folderIdOf(String driveLink) {
		if (driveLink == null || driveLink.isBlank()) {
			return Optional.empty();
		}
		for (Pattern shape : FOLDER_ID_SHAPES) {
			Matcher found = shape.matcher(driveLink);
			if (found.find()) {
				return Optional.of(found.group(1));
			}
		}
		return Optional.empty();
	}

	// --- rendering -----------------------------------------------------------

	/**
	 * Fills the template. <strong>Every value is HTML-escaped except {@code identity}</strong>,
	 * which is markup this class assembled from escaped parts.
	 *
	 * <p>An unfilled placeholder is left as-is rather than blanked, so a template edit that
	 * adds a field nobody supplies is visible in the output instead of silently rendering an
	 * empty row.
	 */
	private String render(String heading, String lede, String identity, Expert expert, String footer,
			String reference) {
		Map<String, String> values = new HashMap<>(credentials(expert));
		values.put("heading", heading);
		values.put("lede", lede);
		values.put("footer", footer);
		values.put("reference", reference);

		StringBuilder out = new StringBuilder();
		Matcher placeholders = PLACEHOLDER.matcher(template());
		while (placeholders.find()) {
			String key = placeholders.group(1);
			String replacement = "identity".equals(key)
					? identity
					: values.containsKey(key) ? escape(values.get(key)) : placeholders.group();
			placeholders.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		placeholders.appendTail(out);
		return out.toString();
	}

	/**
	 * Read per render rather than cached in a field.
	 *
	 * <p>ponytail: it is a classpath resource of a couple of kilobytes and this route is not
	 * hot — a PM generates a handful of these a day. Cache it in a field if a profile is
	 * ever generated in a loop.
	 */
	private String template() {
		try {
			return new ClassPathResource(TEMPLATE).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("The redacted-profile template is missing from the classpath", ex);
		}
	}

	// --- small shared bits ---------------------------------------------------

	/**
	 * The expert on the case, read through the scoped finder.
	 *
	 * <p>A case with no expert is a 409, not an empty profile: there is nothing to redact,
	 * and a document headed "Expert AK" with every field blank is worse than a refusal
	 * because it looks like it worked.
	 */
	private Expert expertOn(Case subject) {
		UUID expertId = subject.getExpertId();
		if (expertId == null) {
			throw new IllegalTransitionException(
					"no expert is assigned to this case yet, so there is no profile to generate");
		}
		return experts.findScoped(TenantContext.current(), expertId)
				.orElseThrow(() -> new IllegalTransitionException(
						"the expert on this case is not on this brand's roster"));
	}

	/** {@code "Years since date_onboarded"}, in the words a client reads. */
	private static String experience(LocalDate onboarded) {
		if (onboarded == null) {
			return "—";
		}
		long years = ChronoUnit.YEARS.between(onboarded, LocalDate.now());
		if (years < 1) {
			return "Engaged within the last year";
		}
		return years + (years == 1 ? " year" : " years") + " on the panel";
	}

	private static String tags(List<? extends Enum<?>> values) {
		return values.isEmpty()
				? "—"
				: values.stream().map(value -> label(value.name())).collect(Collectors.joining(", "));
	}

	/** {@code MECHANICAL_ENGINEERING} → {@code Mechanical Engineering}. */
	private static String label(String name) {
		StringBuilder out = new StringBuilder(name.length());
		for (String word : name.split("_")) {
			out.append(out.isEmpty() ? "" : " ")
					.append(word.charAt(0))
					.append(word.substring(1).toLowerCase());
		}
		return out.toString();
	}

	private static String blankToDash(String value) {
		return value == null || value.isBlank() ? "—" : value;
	}

	/**
	 * Spring's own escaper, from a dependency already on the classpath — no new one for
	 * this. It covers the five XML entities, which is what interpolating a roster field
	 * into markup needs.
	 */
	private static String escape(String value) {
		return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
	}
}
