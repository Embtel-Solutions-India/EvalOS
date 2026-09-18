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
	 * <strong>The logo is served by the portal, not attached and not inlined.</strong>
	 *
	 * <p>A {@code cid:} attachment renders in Outlook and is stripped or shown as a download by
	 * several webmail clients; a {@code data:} URI is blocked outright by Gmail and Outlook.com. A
	 * URL on the origin the links already point at is the one form every client loads — and it is
	 * the origin the reader is about to visit anyway, so it adds no new host to trust.
	 */
	private String logoUrl() {
		return portalBaseUrl + "/brand/logo.png";
	}

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
		values.put("logoUrl", logoUrl());
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
	private String load(String name) {
		return cache.computeIfAbsent(name, (key) -> {
			try {
				return new ClassPathResource("mail/" + key + ".html").getContentAsString(
						StandardCharsets.UTF_8);
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
