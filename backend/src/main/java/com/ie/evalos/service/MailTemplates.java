package com.ie.evalos.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The client emails: a layout, three bodies, and {@code {{placeholder}}} substitution.
 *
 * <p><strong>No template engine, and that is a decision rather than an omission.</strong> Nine
 * messages with a handful of values each do not need Thymeleaf's dependency, its auto-configuration
 * or its second set of escaping rules — the whole substitution is ten lines below. If a fourth
 * message needs conditionals or loops, that is the moment to reconsider, not now.
 *
 * <p><strong>HTML lives in {@code resources/mail/}, not in Java text blocks.</strong> Email HTML is
 * table scaffolding and inline styles; buried in a string literal nobody can see what it renders
 * as, and the person most likely to want to change a sentence is not a Java developer.
 *
 * <p><strong>Escaping is on by default and skipped only by name.</strong> Every value a client
 * supplied — their name, the service they chose — is HTML-escaped, because a client called
 * {@code <script>} must not be able to write one into a mail their own inbox renders. The two
 * exceptions are the URLs EvalOS builds itself, and they are listed explicitly rather than
 * inferred.
 *
 * <p><strong>Every message carries a plain-text twin.</strong> Not a stripped-tags version of the
 * HTML — a written one, so it reads properly. See {@link MailTransport#send} for why the text part
 * is not optional.
 */
@Component
public class MailTemplates {

	/** Placeholders whose value is a URL this code built, so escaping them would break them. */
	private static final java.util.Set<String> RAW = java.util.Set.of("link", "logoUrl", "siteUrl",
			"portalUrl", "content");

	private static final String SITE_URL = "https://www.internationalevaluations.com";

	/**
	 * The brand mark, on the marketing site that already serves it.
	 *
	 * <p><strong>The apex host, and NOT derived from {@link #SITE_URL}.</strong> That constant is
	 * the {@code www} host, and {@code www.internationalevaluations.com/assets/…} answers
	 * <strong>301</strong>. A redirect is fine for a link a person clicks and is not fine for an
	 * image: Gmail, Outlook.com and Yahoo all fetch images through a caching proxy, and a proxy
	 * that declines to follow the hop serves nothing — the reader gets the alt text and the email
	 * looks broken. So this is written out in full rather than built from the other, and the two
	 * differing by a subdomain is the point rather than an oversight.
	 */
	/** An HTML comment, including a multi-line one. See {@link #load}. */
	private static final java.util.regex.Pattern COMMENT =
			java.util.regex.Pattern.compile("<!--.*?-->", java.util.regex.Pattern.DOTALL);

	private static final String LOGO_URL =
			"https://internationalevaluations.com/assets/logo-horizontal-main.png";

	/**
	 * The address the portal already shows a client in its dead-end states.
	 *
	 * <p>One constant here and one in {@code authService.ts}, deliberately not a shared config
	 * value: they are two separate builds and a property that had to be set in both to be correct
	 * is a property that will be set in one.
	 */
	private static final String SUPPORT_EMAIL = "support@internationalevaluations.com";

	private final String portalBaseUrl;

	private final Map<String, String> cache = new ConcurrentHashMap<>();

	MailTemplates(@Value("${evalos.portal.client-base-url}") String portalBaseUrl) {
		this.portalBaseUrl = portalBaseUrl.endsWith("/")
				? portalBaseUrl.substring(0, portalBaseUrl.length() - 1) : portalBaseUrl;
	}

	/** One rendered message: what the client sees in their inbox, both ways. */
	public record Message(String subject, String text, String html) {
	}

	/**
	 * <strong>Linked, never attached and never inlined.</strong>
	 *
	 * <p>A {@code cid:} attachment renders in Outlook and is stripped or shown as a download by
	 * several webmail clients; a {@code data:} URI is blocked outright by Gmail and Outlook.com. A
	 * plain URL is the one form every client loads.
	 *
	 * <p><strong>It moved off the portal origin on 2026-09-23</strong>, where it was
	 * {@code portalBaseUrl + "/brand/logo.png"} — the 380x175 stacked mark. The argument for that
	 * origin was that the email links there anyway so it added no host to trust; the footer links
	 * to {@link #SITE_URL} in the same breath, so the marketing site was never a new host either.
	 * What it does add is a mark that is already public, already cached and does not go dark when
	 * the portal is redeployed.
	 *
	 * <p>Not a method any more because there is nothing left to compute — see {@link #LOGO_URL}
	 * for why it is not built from {@code SITE_URL}.
	 */

	public Message setPassword(String fullName, String link) {
		Map<String, String> values = base(fullName);
		values.put("link", link);
		values.put("footerNote", "You are receiving this because an account was created for this "
				+ "address at International Evaluations.");
		return render("Set your password", "set-password",
				"Choose a password for your International Evaluations account.", values, """
						Welcome%s.

						Your International Evaluations account is ready. Use the link below to set a \
						password — it works once and expires in 30 minutes.

						%s

						If you did not expect this, you can ignore it: nothing changes until the link \
						is used.

						International Evaluations
						%s
						%s
						""".formatted(greeting(fullName), link, SITE_URL, SUPPORT_EMAIL));
	}

	public Message resetPassword(String fullName, String link) {
		Map<String, String> values = base(fullName);
		values.put("link", link);
		values.put("footerNote", "You are receiving this because a password reset was requested for "
				+ "this address.");
		return render("Reset your password", "reset-password",
				"Choose a new password for your International Evaluations account.", values, """
						Somebody asked for a password reset on your International Evaluations \
						account%s.

						Use the link below to choose a new one — it works once and expires in 30 \
						minutes.

						%s

						Did not ask for this? Ignore this message: your current password still works \
						and nothing has changed.

						International Evaluations
						%s
						%s
						""".formatted(greeting(fullName), link, SITE_URL, SUPPORT_EMAIL));
	}

	/**
	 * A client's first case has opened: set a password (Unit 64b, D65). Unprompted, so the link
	 * lives {@code ClientAccountService.CASE_LINK_TTL} — the copy says 7 days and must follow it.
	 */
	public Message caseStarted(String fullName, String link, String service, String caseCode) {
		Map<String, String> values = caseValues(fullName, service, caseCode);
		values.put("link", link);
		values.put("footerNote", "You are receiving this because a case was opened for you at "
				+ "International Evaluations.");
		return render("Your case has started — set your password", "case-started",
				"Your case " + caseCode + " is open. Set a password to follow it.", values, """
						Thank you%s — we have opened your case and our team is getting it ready.

						Service: %s
						Case reference: %s

						Set a password for your client portal, where you will see which documents we \
						need and upload them, follow your case, and review and download your report:

						%s

						This link works once and expires in 7 days. If it has expired, enter your \
						email on the sign-in page and we will send a new one.

						International Evaluations
						%s
						%s
						""".formatted(greeting(fullName), service, caseCode, link, SITE_URL, SUPPORT_EMAIL));
	}

	/** Another case for a client who already has a password: no credential, just sign in (D65). */
	public Message caseStartedSignIn(String fullName, String service, String caseCode) {
		Map<String, String> values = caseValues(fullName, service, caseCode);
		String signIn = portalBaseUrl + "/";
		values.put("link", signIn);
		values.put("footerNote", "You are receiving this because a case was opened for you at "
				+ "International Evaluations.");
		return render("Your new case has started", "case-signin",
				"Case " + caseCode + " is now in your client portal.", values, """
						Thank you%s — we have opened another case for you. It is already in your \
						client portal.

						Service: %s
						Case reference: %s

						Sign in to follow it: %s

						Forgot your password? Use "Forgot password" on the sign-in page.

						International Evaluations
						%s
						%s
						""".formatted(greeting(fullName), service, caseCode, signIn, SITE_URL, SUPPORT_EMAIL));
	}

	/**
	 * The case progress mails (Unit 64c, D58; the offer since Q17 → D67). Words only — they share
	 * {@code mail/case-update.html}. {@code intro} takes the greeting, then (checklist only) the
	 * document count.
	 */
	public enum CaseUpdate {
		OFFER(true, "A new case is offered to you", "A new case for you",
				"Hello%s. We would like to offer you this case. Sign in to read it and answer: accept "
						+ "it, ask the client for more evidence, or decline.",
				"Review the offer"),
		CHECKLIST(false, "Documents needed for your case", "Documents needed",
				"Thank you%s. To move your case forward we need %d document%s from you. Sign in to see "
						+ "the list and upload them.",
				"Upload my documents"),
		DRAFT_READY(false, "Your draft is ready to review", "Your draft is ready",
				"Good news%s — the draft for your case is ready. Sign in to read it, then approve it or "
						+ "tell us what to change.",
				"Review my draft"),
		SIGNING(true, "A letter is ready for your signature", "Ready for your signature",
				"Hello%s. The client has approved the letter for this case and it is ready for your "
						+ "signature. Please sign in and upload the signed letter within 24 hours.",
				"Open the case"),
		DELIVERED(false, "Your final report is ready", "Your report is ready",
				"Thank you%s — your case is complete and your final report is ready. Sign in to "
						+ "download it.",
				"Download my report");

		/** Whether the recipient is the case's expert rather than the client. */
		public final boolean expert;
		final String subject;
		final String heading;
		final String intro;
		final String button;

		CaseUpdate(boolean expert, String subject, String heading, String intro, String button) {
			this.expert = expert;
			this.subject = subject;
			this.heading = heading;
			this.intro = intro;
			this.button = button;
		}
	}

	/**
	 * One case progress mail. {@code documents} is read by {@link CaseUpdate#CHECKLIST} only.
	 *
	 * @param link a deep link into the recipient's portal; never a credential
	 */
	public Message caseUpdate(CaseUpdate kind, String fullName, String service, String caseCode, int documents,
			String link) {
		return caseUpdate(kind, fullName, service, caseCode, documents, null, link);
	}

	/**
	 * @param fee the offered fee as the expert should read it ("USD 250.00"); {@link CaseUpdate#OFFER}
	 *            only, and null leaves the sentence out rather than promising an amount
	 */
	public Message caseUpdate(CaseUpdate kind, String fullName, String service, String caseCode, int documents,
			String fee, String link) {
		String intro = kind == CaseUpdate.CHECKLIST
				? kind.intro.formatted(greeting(fullName), documents, documents == 1 ? "" : "s")
				: kind.intro.formatted(greeting(fullName));
		if (kind == CaseUpdate.OFFER && fee != null) {
			intro = intro + " The fee offered is " + fee + ".";
		}
		String note = kind.expert
				? "First time signing in? Enter your email on the sign-in page and choose \"Email me a link\"."
				: "First time signing in? Enter your email on the sign-in page and we will send you a "
						+ "link to set a password.";
		Map<String, String> values = caseValues(fullName, service, caseCode);
		values.put("link", link);
		values.put("heading", kind.heading);
		values.put("intro", intro);
		values.put("buttonLabel", kind.button);
		values.put("note", note);
		values.put("footerNote", "You are receiving this because of a case you have with "
				+ "International Evaluations.");
		return render(kind.subject, "case-update", kind.heading + " — case " + caseCode + ".", values, """
				%s

				%s

				Service: %s
				Case reference: %s

				%s: %s

				%s

				International Evaluations
				%s
				%s
				""".formatted(kind.heading, intro, service, caseCode, kind.button, link, note, SITE_URL,
				SUPPORT_EMAIL));
	}

	private Map<String, String> caseValues(String fullName, String service, String caseCode) {
		Map<String, String> values = base(fullName);
		values.put("service", service);
		values.put("caseCode", caseCode);
		return values;
	}

	private Map<String, String> base(String fullName) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("greetingName", greeting(fullName));
		values.put("logoUrl", LOGO_URL);
		values.put("siteUrl", SITE_URL);
		values.put("portalUrl", portalBaseUrl);
		values.put("supportEmail", SUPPORT_EMAIL);
		return values;
	}

	/**
	 * ", Ana" or nothing at all.
	 *
	 * <p>Rendered as a suffix rather than a whole greeting line so the sentence reads properly
	 * either way. A blank name is ordinary — a client can sign up with an address and nothing else —
	 * and "Welcome ," or "Welcome there" are both worse than "Welcome".
	 */
	private static String greeting(String fullName) {
		if (fullName == null || fullName.isBlank()) {
			return "";
		}
		// The first word only: "Welcome, Ana" is a greeting and "Welcome, Ana Maria Ruiz Delgado"
		// is a database record being read back at somebody.
		return ", " + fullName.strip().split("\\s+")[0];
	}

	private Message render(String subject, String body, String preheader, Map<String, String> values,
			String text) {
		values.put("subject", subject);
		values.put("preheader", preheader);
		String content = substitute(load(body), values);

		Map<String, String> outer = new LinkedHashMap<>(values);
		outer.put("content", content);
		return new Message(subject, text, substitute(load("layout"), outer));
	}

	private String substitute(String template, Map<String, String> values) {
		String out = template;
		for (Map.Entry<String, String> value : values.entrySet()) {
			String replacement = value.getValue() == null ? ""
					: RAW.contains(value.getKey()) ? value.getValue() : escape(value.getValue());
			out = out.replace("{{" + value.getKey() + "}}", replacement);
		}
		return out;
	}

	/**
	 * Enough escaping for a text node or a quoted attribute, which is all these templates have.
	 *
	 * <p>Quotes included because {@code {{link}}} sits inside {@code href="…"} — an unescaped one
	 * there closes the attribute, and every other placeholder is client-supplied text that must not
	 * be able to.
	 */
	private static String escape(String raw) {
		return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&#39;");
	}

	/** Read once and held: these are build artefacts, not something that changes at runtime. */
	/**
	 * The template, comments stripped, cached after the first read.
	 *
	 * <p><strong>The comments are for whoever edits these files and were being sent to
	 * clients.</strong> {@code layout.html} is 55% comment — why the markup looks like 2005, which
	 * hex replaced which, what the contrast measurements were — and all of it was travelling inside
	 * every set-password and every confirmation. Nothing broke, which is why it went unnoticed: it
	 * is invisible in a mail client and only shows in View Source. Stripping costs one regex at
	 * load and takes about 4KB off every message.
	 *
	 * <p><strong>Safe only because nothing here uses an Outlook conditional comment.</strong>
	 * {@code <!--[if mso]>} is a comment to every other client and a live branch to Word's renderer,
	 * so a template that grows one must strip conditionally or not at all. There are none today and
	 * {@code MailTemplatesTest} would not catch it — check by eye if you add one.
	 */
	private String load(String name) {
		return cache.computeIfAbsent(name, (key) -> {
			try {
				return COMMENT.matcher(new ClassPathResource("mail/" + key + ".html")
						.getContentAsString(StandardCharsets.UTF_8)).replaceAll("").strip();
			}
			catch (IOException missing) {
				// A template that is not on the classpath is a packaging failure, not a runtime
				// condition — it cannot be recovered from and must not be caught into a blank email.
				throw new UncheckedIOException("Mail template mail/" + key + ".html is missing",
						missing);
			}
		});
	}
}
