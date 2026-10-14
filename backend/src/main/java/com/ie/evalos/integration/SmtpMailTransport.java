package com.ie.evalos.integration;

import com.ie.evalos.config.AppSettings;
import com.ie.evalos.config.Setting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Spring Mail — and since 2026-09-18, the only transport there is.
 *
 * <p><strong>It outlasted two provider-specific transports, and that is the argument for it.</strong>
 * A GHL transport addressed a {@code contactId}; a Brevo transport spoke {@code POST /v3/smtp/email}
 * with an {@code api-key} header. Each was a class, a config block, a credential and a live test
 * that proved exactly one vendor — and each was replaced within months. Every transactional
 * provider worth using also speaks SMTP on 587, so this one class reaches all of them and
 * <strong>changing provider is four values and no build</strong> — environment variables, or (D83) the
 * Administrator's Settings screen with no restart.
 * The per-provider settings are tabulated over {@code spring.mail} in {@code application.yml}.
 *
 * <p><strong>What that trades away, stated plainly:</strong> an API transport can read a provider's
 * own response id, and Brevo's did. SMTP gives back a protocol-level accept and nothing more, so
 * "it left EvalOS" is the whole of what {@link #send} can promise — delivery, bounces and
 * suppression are read in the provider's dashboard or through its webhooks, neither of which is
 * built. For two messages whose failure is recoverable by a support conversation, that is the
 * cheaper side of the trade.
 *
 * <p>The body of this class is what {@code ClientMailer.send} was until the transport seam was
 * introduced; the wording moved up, the wire stayed here.
 */
@Component
public class SmtpMailTransport implements MailTransport {

	private static final Logger log = LoggerFactory.getLogger(SmtpMailTransport.class);

	private final JavaMailSender sender;

	private final String from;

	/**
	 * The relay, read only to answer {@link #isConfigured()}.
	 *
	 * <p><strong>Spring's own {@code JavaMailSender} will not tell us.</strong> It is auto-configured
	 * whether or not a host is set, so a deployment with {@code EVALOS_MAIL_FROM} and no
	 * {@code MAIL_HOST} used to report itself configured and then fail one send at a time — which
	 * is the state the interface's two-method split exists to keep apart. Unconfigured must be
	 * known before a client is told a link is coming.
	 */
	private final String host;

	/** Absent only in hand-built tests, which then use the environment values they passed in. */
	private AppSettings settings;
	private MailProperties mail;

	/** The sender built from Administrator-saved values (D83), and the values it was built from. */
	private volatile Built built;

	private record Built(String key, JavaMailSender sender) {
	}

	SmtpMailTransport(JavaMailSender sender, @Value("${evalos.mail.from:}") String from,
			@Value("${spring.mail.host:}") String host) {
		this.sender = sender;
		this.from = from == null ? "" : from.trim();
		this.host = host == null ? "" : host.trim();
	}

	@Autowired(required = false)
	void useSettings(AppSettings settings, MailProperties mail) {
		this.settings = settings;
		this.mail = mail;
	}

	private String from() {
		return AppSettings.or(settings, Setting.MAIL_FROM, from);
	}

	private String host() {
		return AppSettings.or(settings, Setting.MAIL_HOST, host);
	}

	/**
	 * Boot's sender while nothing about the relay is saved in the app; otherwise one built from the effective
	 * values, rebuilt only when they change. The timeouts and STARTTLS in {@code spring.mail.properties} carry
	 * over — the reason they are bounded is unchanged (application.yml).
	 */
	private JavaMailSender sender() {
		if (settings == null || mail == null || !relayOverridden()) {
			return sender;
		}
		String relayHost = host();
		int port = AppSettings.intOr(settings, Setting.MAIL_PORT, mail.getPort() == null ? 587 : mail.getPort());
		String username = settings.app(Setting.MAIL_USERNAME).orElse(mail.getUsername());
		String password = settings.app(Setting.MAIL_PASSWORD).orElse(mail.getPassword());
		// In memory only, and only to notice a change; never logged.
		String key = relayHost + '|' + port + '|' + username + '|' + password;
		Built current = built;
		if (current == null || !current.key().equals(key)) {
			JavaMailSenderImpl fresh = new JavaMailSenderImpl();
			fresh.setHost(relayHost);
			fresh.setPort(port);
			fresh.setUsername(username);
			fresh.setPassword(password);
			fresh.setDefaultEncoding("UTF-8");
			java.util.Properties properties = new java.util.Properties();
			properties.putAll(mail.getProperties());
			fresh.setJavaMailProperties(properties);
			current = new Built(key, fresh);
			built = current;
		}
		return current.sender();
	}

	private boolean relayOverridden() {
		return settings.app(Setting.MAIL_HOST).isPresent() || settings.app(Setting.MAIL_PORT).isPresent()
				|| settings.app(Setting.MAIL_USERNAME).isPresent() || settings.app(Setting.MAIL_PASSWORD).isPresent();
	}

	@Override
	public String name() {
		return "smtp";
	}

	/** Both, because either alone cannot send: a sender with no relay never leaves, and a relay
	 * with no sender is refused by every provider. */
	@Override
	public boolean isConfigured() {
		// Outbound email switched off by the Administrator (D83) reads exactly like unconfigured: the
		// MAIL_UNAVAILABLE path every caller already handles, with no mail leaving.
		if (settings != null && !settings.enabled(Setting.MAIL_ENABLED)) {
			return false;
		}
		return !from().isBlank() && !host().isBlank();
	}

	@Override
	public boolean send(Recipient to, String subject, String text, String html) {
		try {
			JavaMailSender sender = sender();
			jakarta.mail.internet.MimeMessage message = sender.createMimeMessage();
			// `true` for multipart: the two parts travel as multipart/alternative and the client
			// picks. UTF-8 named explicitly because a client's name is the first thing to arrive
			// with an accent in it, and the platform default on a Windows laptop is not UTF-8.
			org.springframework.mail.javamail.MimeMessageHelper helper =
					new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(from());
			helper.setTo(to.email());
			helper.setSubject(subject);
			// **Text first, HTML second, and the order is the API's not a preference.** Spring
			// writes them in the order `multipart/alternative` requires — least-preferred first —
			// so a client that understands both shows the HTML. Passing them the other way round
			// shows plain text to everyone.
			helper.setText(text, html);
			sender.send(message);
			return true;
		}
		catch (jakarta.mail.MessagingException malformed) {
			// A message this code built and could not assemble is our bug, not the relay's. Logged
			// at the same level and swallowed the same way: the caller's contract is a boolean, and
			// `forgot-password` must not answer differently for a known address.
			log.error("Could not assemble '{}' for {}", subject, com.ie.evalos.common.LogSafe.email(to.email()), malformed);
			return false;
		}
		catch (MailException failed) {
			// **Logged with the masked address (D82) and swallowed.** An outage here must not reach the client
			// as a 500, and on `forgot-password` it must not reach them as a 500 for a known
			// address and a 204 for an unknown one. The operator needs the detail; the client
			// needs the two paths to stay indistinguishable.
			log.error("SMTP send failed — '{}' to {} was not delivered", subject, com.ie.evalos.common.LogSafe.email(to.email()), failed);
			return false;
		}
	}
}
