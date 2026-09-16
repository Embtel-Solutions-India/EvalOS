package com.ie.evalos.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Brevo's transactional API — {@code POST /v3/smtp/email}.
 *
 * <p><strong>It replaced the GHL transport, and the swap is the point of the seam.</strong> This is
 * one new class and one changed value of {@code evalos.mail.transport}; nothing that knows what a
 * set-password mail <em>says</em> moved. That was the whole justification for
 * {@link MailTransport} existing over two messages, and it held.
 *
 * <p><strong>It addresses an email, not a contact, and that quietly gave back a decision.</strong>
 * GHL's send required a {@code contactId}, which is the only reason the GHL contact had to be
 * created at sign-up (D3d) rather than when the mailbox was proved (D3a). Brevo needs nothing but
 * the address, so that constraint is gone — see the note on {@code ClientAccountService.signUp}.
 *
 * <p><strong>{@code textContent} only, no {@code htmlContent}.</strong> The two messages are four
 * lines and a link. HTML would need a template, a template needs an owner, and the one thing that
 * must never happen to a set-password mail is a rendering bug swallowing the link. Brevo delivers
 * a text-only body fine.
 *
 * <p><strong>Its own {@link RestClient}, not {@code GhlHttp}.</strong> That door carries GHL's
 * bearer token, its {@code Version} header and — importantly — the 110ms pacer that exists to
 * protect GHL's per-location budget. Brevo has its own quota and no relationship to that one;
 * sharing the pacer would make a mail send wait on a pipeline read.
 */
@Component
public class BrevoMailTransport implements MailTransport {

	private static final Logger log = LoggerFactory.getLogger(BrevoMailTransport.class);

	/** Loosely bound: only {@code messageId} is read, as proof Brevo accepted the message. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Accepted(String messageId) {
	}

	private final RestClient http;

	private final String senderEmail;

	private final String senderName;

	private final boolean configured;

	BrevoMailTransport(@Value("${evalos.mail.brevo.base-url}") String baseUrl,
			@Value("${evalos.mail.brevo.api-key:}") String apiKey,
			@Value("${evalos.mail.brevo.sender-email:}") String senderEmail,
			@Value("${evalos.mail.brevo.sender-name:}") String senderName,
			@Value("${evalos.mail.brevo.timeout}") Duration timeout) {
		this.senderEmail = senderEmail == null ? "" : senderEmail.trim();
		this.senderName = senderName == null ? "" : senderName.trim();
		// Both, because either alone cannot send: a key with no verified sender is refused by
		// Brevo, and a sender with no key never reaches it.
		this.configured = apiKey != null && !apiKey.isBlank() && !this.senderEmail.isBlank();

		if (!configured) {
			log.warn("Brevo is not configured — client password mail is disabled on this transport. "
					+ "Set EVALOS_MAIL_BREVO_API_KEY and EVALOS_MAIL_BREVO_SENDER_EMAIL to enable it.");
		}
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		// Bounded for the reason the GHL door gives: this call sits on a request path, and an
		// unbounded outbound wait is a thread nobody gets back.
		//
		// (Naming that class here as `GhlHttp` followed by a word would trip `writeCallersAudit`,
		// whose scan cannot tell a field declaration from prose — it is a deliberate over-match on
		// a rule worth over-matching, so this comment works around it rather than loosening it.)
		factory.setConnectTimeout(timeout);
		factory.setReadTimeout(timeout);
		this.http = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(factory)
				// `api-key`, not `Authorization: Bearer` — Brevo's own header, and getting this
				// wrong answers 401 in a way that reads like a bad key rather than a bad scheme.
				.defaultHeader("api-key", apiKey == null ? "" : apiKey.trim())
				.defaultHeader("Accept", "application/json")
				.build();
	}

	@Override
	public String name() {
		return "brevo";
	}

	@Override
	public boolean isConfigured() {
		return configured;
	}

	@Override
	public boolean send(Recipient to, String subject, String body) {
		Map<String, Object> sender = senderName.isBlank()
				? Map.of("email", senderEmail)
				: Map.of("email", senderEmail, "name", senderName);
		Map<String, Object> payload = Map.of(
				"sender", sender,
				"to", List.of(Map.of("email", to.email())),
				"subject", subject,
				"textContent", body);
		try {
			Accepted accepted = http.post()
					.uri((uri) -> uri.path("/v3/smtp/email").build())
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.body(payload)
					.retrieve()
					.body(Accepted.class);
			if (accepted == null || accepted.messageId() == null) {
				// A 2xx carrying no id means Brevo queued nothing we can name. Reported as a
				// failure rather than assumed a success: the cost of being wrong is a client
				// waiting for a mail that never left, with the cooldown suppressing their retry.
				log.error("Brevo accepted no message for '{}' to {}", subject, to.email());
				return false;
			}
			return true;
		}
		catch (RuntimeException failed) {
			// Swallowed and logged, like every transport. An outage must not reach the client as a
			// 500, and on forgot-password a 500 for a known address beside a 204 for an unknown one
			// is the enumeration oracle that route exists to avoid.
			log.error("Brevo send failed — '{}' to {} was not delivered: {}", subject, to.email(),
					failed.getMessage());
			return false;
		}
	}
}
