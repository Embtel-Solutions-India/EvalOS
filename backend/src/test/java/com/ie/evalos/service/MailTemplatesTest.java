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

	/** The logo is served by the portal, on the origin the links already point at. */
	@Test
	void theLogoIsAbsoluteAndOnThePortalOrigin() {
		assertThat(templates.setPassword("Ana", "https://portal.test/x").html())
				.contains("https://portal.internationalevaluations.com/brand/logo.png");
	}
}
