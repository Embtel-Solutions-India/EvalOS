package com.ie.evalos.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three client emails, and the mistakes that would reach a client's inbox.
 *
 * <p>{@link #noPlaceholderSurvivesRendering()} is the one that earns its keep. A mistyped
 * {@code {{link}}} does not fail a build, does not throw and does not look wrong in a diff — it
 * simply arrives in somebody's inbox as literal braces, on the one message they needed in order to
 * reach their account.
 */
class MailTemplatesTest {

	private final MailTemplates templates = new MailTemplates("https://portal.internationalevaluations.com/");

	private List<MailTemplates.Message> all() {
		return List.of(templates.setPassword("Ana Ruiz", "https://portal.test/set-password#tok"),
				templates.resetPassword("Ana Ruiz", "https://portal.test/set-password#tok"),
				templates.requestSubmitted("Ana Ruiz", "Academic Evaluation", 2));
	}

	@Test
	void noPlaceholderSurvivesRendering() {
		for (MailTemplates.Message message : all()) {
			assertThat(message.html())
					.describedAs("unreplaced placeholder in '%s'", message.subject())
					.doesNotContain("{{").doesNotContain("}}");
			assertThat(message.text()).doesNotContain("{{");
		}
	}

	/** Every message carries both parts, and neither is a stub of the other. */
	@Test
	void everyMessageHasATextPartAndAnHtmlPart() {
		for (MailTemplates.Message message : all()) {
			assertThat(message.subject()).isNotBlank();
			assertThat(message.text()).isNotBlank().doesNotContain("<");
			assertThat(message.html()).contains("<!doctype html>").contains("International Evaluations");
		}
	}

	/**
	 * <strong>The link is in the text part too.</strong>
	 *
	 * <p>The HTML button is unreachable for a text-only client, and on these two messages failing to
	 * reach the link means failing to reach the account. A text part that summarised instead of
	 * carrying it would strand exactly the readers least able to recover.
	 */
	@Test
	void bothCredentialMessagesCarryTheLinkInBothParts() {
		String link = "https://portal.test/set-password#tok";

		for (MailTemplates.Message message : List.of(templates.setPassword("Ana", link),
				templates.resetPassword("Ana", link))) {
			assertThat(message.text()).contains(link);
			assertThat(message.html()).contains(link);
		}
	}

	/**
	 * <strong>A client-supplied name cannot write HTML into their own inbox.</strong>
	 *
	 * <p>Sign-up takes a name and nothing sanitises it on the way in, so the escape has to be here.
	 * The link is the deliberate exception — EvalOS builds it — and it is listed by name in
	 * {@code MailTemplates.RAW} rather than inferred.
	 */
	@Test
	void aNameThatLooksLikeMarkupIsEscaped() {
		MailTemplates.Message message =
				templates.setPassword("<script>alert(1)</script>", "https://portal.test/x");

		assertThat(message.html()).doesNotContain("<script>").contains("&lt;script&gt;");
	}

	/** A blank name drops the greeting rather than writing "Welcome ," at somebody. */
	@Test
	void aBlankNameLeavesNoDanglingComma() {
		MailTemplates.Message message = templates.setPassword("  ", "https://portal.test/x");

		assertThat(message.text()).startsWith("Welcome.").doesNotContain("Welcome,");
	}

	/** The first name only: a greeting, not a database record read back at somebody. */
	@Test
	void onlyTheFirstNameIsUsed() {
		assertThat(templates.setPassword("Ana Maria Ruiz Delgado", "https://portal.test/x").text())
				.contains("Welcome, Ana.").doesNotContain("Delgado");
	}

	/**
	 * <strong>Zero documents reads as an invitation, never a warning.</strong>
	 *
	 * <p>Submit is deliberately not gated on documents ({@code 43} §5). A confirmation that scolded
	 * a client for sending none would undo that decision in the one message they actually read.
	 */
	@Test
	void zeroDocumentsIsAnInvitationAndTheCountIsOtherwiseStated() {
		// Not a blanket ban on the word "missing" — the body legitimately says "if anything is
		// missing or unclear, we will ask", which is the opposite of a warning. What is pinned is
		// that the zero case offers rather than demands.
		assertThat(templates.requestSubmitted("Ana", "Academic Evaluation", 0).html())
				.contains("not attached any documents yet")
				.contains("whenever you like")
				.doesNotContain("required").doesNotContain("must ");
		assertThat(templates.requestSubmitted("Ana", "Academic Evaluation", 1).html())
				.contains("1 document with your request");
		assertThat(templates.requestSubmitted("Ana", "Academic Evaluation", 3).html())
				.contains("3 documents with your request");
	}

	/**
	 * The confirmation promises nothing the flow can fail to keep.
	 *
	 * <p>No price, no turnaround, no date: EvalOS holds no price list and the work is quoted by a
	 * person. This pins the absence, because the pressure to add "within 48 hours" to a
	 * confirmation is exactly the kind of sentence that gets added without anyone owning it.
	 */
	@Test
	void theConfirmationPromisesNoPriceAndNoTurnaround() {
		String html = templates.requestSubmitted("Ana", "Academic Evaluation", 1).html().toLowerCase();

		assertThat(html).doesNotContain("$").doesNotContain("hours").doesNotContain("business day")
				.doesNotContain("within");
	}

	/**
	 * The logo is an absolute URL on the marketing site, at the mark's own aspect ratio.
	 *
	 * <p><strong>The apex host is asserted, not just the path.</strong> The {@code www} host 301s,
	 * and a redirect an image proxy declines to follow is a logo that silently stops rendering in
	 * Gmail — a failure nobody sees from inside this codebase.
	 *
	 * <p><strong>The dimensions are asserted because getting them wrong is invisible here too.</strong>
	 * The mark is 1230x290 (4.24:1); the previous stacked logo was 380x175 (2.17:1) and its 152x70
	 * attributes would squash this one. A test is the only thing that notices, because the template
	 * renders either way.
	 */
	@Test
	void theLogoIsTheMarketingSiteMarkAtItsOwnAspectRatio() {
		String html = templates.setPassword("Ana", "https://portal.test/x").html();

		assertThat(html)
				.contains("https://internationalevaluations.com/assets/logo-horizontal-main.png")
				.doesNotContain("www.internationalevaluations.com/assets")
				.contains("width=\"240\" height=\"57\"")
				.contains("width:240px; height:57px;");
	}

	/**
	 * The accent is the client portal's {@code --brand-crimson}, everywhere it appears.
	 *
	 * <p>Pins two things a recolour gets wrong. <strong>No navy survives</strong> — a palette swap
	 * that misses one button leaves a single blue control in an otherwise crimson email, which
	 * reads as a rendering fault rather than a design. And <strong>the neutrals are untouched</strong>:
	 * body text stays {@code #11212C}, because an email whose prose is red reads as a warning and
	 * every message here is routine.
	 */
	@Test
	void theAccentIsThePortalsCrimsonAndTheNeutralsAreNot() {
		for (String html : java.util.List.of(
				templates.setPassword("Ana", "https://portal.test/x").html(),
				templates.resetPassword("Ana", "https://portal.test/x").html(),
				templates.requestSubmitted("Ana", "Academic Evaluation", 1).html())) {

			assertThat(html.toUpperCase())
					.contains("#C8102E")
					.doesNotContain("#003152")
					.doesNotContain("#085A91")
					.contains("#11212C");
		}
	}
}
