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
 * <p><strong>No template engine, and that is a decision rather than an omission.</strong> Three
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
	 * The confirmation a client gets when their request reaches Sales.
	 *
	 * <p><strong>It promises nothing the flow cannot keep.</strong> No price, no turnaround and no
	 * date: EvalOS holds no price list and the work is quoted by a person. "We will read it and come
	 * back to you" is exactly what the portal says on screen, and a mail that said more would be the
	 * first place the two disagreed.
	 */
	public Message requestSubmitted(String fullName, String serviceName, int documentCount) {
		Map<String, String> values = base(fullName);
		values.put("serviceName", serviceName);
		values.put("documentLine", documentLine(documentCount));
		values.put("footerNote", "You are receiving this because you submitted a request from your "
				+ "International Evaluations portal.");
		return render("We have your request", "request-submitted",
				"Your request has reached us — here is what happens next.", values, """
						Thank you%s — your request has reached us.

						What you asked for: %s
						%s

						What happens next: we read what you sent, price the work and contact you with \
						the details. If anything is missing or unclear we will ask; you do not need to \
						do anything in the meantime.

						Your portal: %s

						International Evaluations
						%s
						%s
						""".formatted(greeting(fullName), serviceName, documentLine(documentCount),
						portalBaseUrl, SITE_URL, SUPPORT_EMAIL));
	}

	/**
	 * Zero documents is an invitation, never a warning.
	 *
	 * <p>Submit is deliberately not gated on documents ({@code 43} §5) — a missing transcript is
	 * something Sales asks about on the call. A confirmation that read as a telling-off for sending
	 * none would undo that decision in the one message the client actually reads.
	 */
	private static String documentLine(int count) {
		return switch (count) {
			case 0 -> "You have not attached any documents yet. You can add them from your portal "
					+ "whenever you like — we will ask if we need something specific.";
			case 1 -> "We received 1 document with your request.";
			default -> "We received " + count + " documents with your request.";
		};
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
