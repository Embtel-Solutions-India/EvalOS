package com.ie.evalos.service;

import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.common.NotFoundException;
import com.ie.evalos.config.AppSettings;
import com.ie.evalos.config.Setting;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.integration.MailTransport;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Administrator's Settings screen (D83, spec 85): what is in force and where it comes from, changing it,
 * the two test buttons, and editing a brand's details.
 *
 * <p><strong>A secret's value never leaves this class</strong> — not in a view, not in an audit row, not in a log
 * line. A view says only whether it is set; an audit row says "set" or "cleared".
 */
@Service
public class SettingsAdminService {

	private static final Logger log = LoggerFactory.getLogger(SettingsAdminService.class);

	static final String OBJECT_TYPE = "SETTING";
	static final String BRAND_OBJECT_TYPE = "BRAND";

	private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
	private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

	/** One setting as the screen shows it. {@code value} is always null for a secret. */
	public record View(Setting key, Setting.Group group, Setting.Kind kind, boolean secret, String value,
			boolean set, AppSettings.Source source, String environmentValue) {
	}

	/** What a test button reports: whether it worked, in words a person can act on. Never a credential. */
	public record TestResult(boolean ok, String message) {
	}

	public record BrandChange(String name, String currency, int payoutTermDays) {
	}

	private final AppSettings settings;
	private final AuditService audit;
	private final BrandRepository brands;
	private final TeamMemberRepository members;
	private final MailTransport mail;
	private final GhlPipelineClient ghl;

	SettingsAdminService(AppSettings settings, AuditService audit, BrandRepository brands, TeamMemberRepository members,
			MailTransport mail, GhlPipelineClient ghl) {
		this.settings = settings;
		this.audit = audit;
		this.brands = brands;
		this.members = members;
		this.mail = mail;
		this.ghl = ghl;
	}

	public List<View> list() {
		return Arrays.stream(Setting.values()).map(this::view).toList();
	}

	private View view(Setting setting) {
		AppSettings.Source source = settings.source(setting);
		String effective = setting.kind() == Setting.Kind.BOOL ? String.valueOf(settings.enabled(setting))
				: settings.effective(setting);
		boolean set = setting.kind() == Setting.Kind.BOOL || effective != null;
		return new View(setting, setting.group(), setting.kind(), setting.secret(), setting.secret() ? null : effective,
				set, source, setting.secret() ? null : settings.environment(setting));
	}

	/**
	 * Applies a batch: a value sets, {@code null} resets to the environment, an absent key is untouched. Every value
	 * is validated before any is saved, so a bad port does not leave half a relay changed.
	 */
	@Transactional
	public List<View> update(Map<String, String> changes) {
		Map<Setting, String> valid = new LinkedHashMap<>();
		for (Map.Entry<String, String> change : changes.entrySet()) {
			Setting setting = known(change.getKey());
			valid.put(setting, change.getValue() == null ? null : validated(setting, change.getValue()));
		}
		UUID me = me();
		for (Map.Entry<Setting, String> change : valid.entrySet()) {
			Setting setting = change.getKey();
			Object before = snapshot(setting, settings.app(setting).orElse(null));
			if (change.getValue() == null) {
				settings.clear(setting);
			}
			else {
				settings.save(setting, change.getValue(), me);
			}
			audit.recordEvent(OBJECT_TYPE, idOf(setting), AuditAction.UPDATED, me, before,
					snapshot(setting, change.getValue()));
		}
		return list();
	}

	/** Sends one test mail to the signed-in Administrator's own address (invariant 14, amended for exactly this). */
	public TestResult testMail() {
		if (!settings.enabled(Setting.MAIL_ENABLED)) {
			return new TestResult(false, "Outbound email is switched off. Switch it on to send a test.");
		}
		if (!mail.isConfigured()) {
			return new TestResult(false, "Email is not configured: a relay host and a from address are both needed.");
		}
		String to = members.findById(me()).map((member) -> member.getEmail())
				.orElseThrow(() -> new InvalidRequestException("Your account has no email address to send to"));
		boolean sent = mail.send(new MailTransport.Recipient(null, to), "EvalOS test email",
				"This is a test email from EvalOS Settings. The relay accepted it, so client and expert email can "
						+ "be sent. No action is needed.",
				"<p>This is a test email from EvalOS Settings. The relay accepted it, so client and expert email can "
						+ "be sent.</p><p>No action is needed.</p>");
		log.info("Settings: test email {} for member {}", sent ? "accepted by the relay" : "refused", me());
		return sent ? new TestResult(true, "Sent to " + to + ". Check that inbox (and its spam folder).")
				: new TestResult(false, "The relay refused the message or did not answer. Check the host, port, "
						+ "username and password; the server log has the reason.");
	}

	/** One read of the location's pipelines — a call EvalOS already makes, so it needs no extra scope. */
	public TestResult testGhl() {
		try {
			int pipelines = ghl.pipelines().size();
			return new TestResult(true, "Connected. The location has " + pipelines + " pipeline"
					+ (pipelines == 1 ? "" : "s") + ".");
		}
		catch (GhlUnavailableException failed) {
			return new TestResult(false, switch (failed.failure()) {
			case NOT_CONFIGURED -> "GHL is not configured: a token and a location id are both needed.";
			case UNAUTHORIZED -> "GHL refused the token (HTTP " + failed.status() + "). Check the token, that it "
					+ "belongs to this location, and its scopes.";
			case NO_ANSWER -> "GHL did not answer. Try again in a moment.";
			case RATE_LIMITED -> "GHL is rate-limiting this location. Try again in a minute.";
			default -> failed.getMessage();
			});
		}
	}

	/** A brand's name, currency and payout term. The slug, webhook token and secret are not editable here. */
	@Transactional
	public Brand updateBrand(UUID id, BrandChange change) {
		Brand brand = brands.findById(id).orElseThrow(() -> new NotFoundException("No such brand"));
		String name = change.name() == null ? "" : change.name().trim();
		if (name.isEmpty() || name.length() > 120) {
			throw new InvalidRequestException("A brand name is required (at most 120 characters)");
		}
		String currency = change.currency() == null || change.currency().isBlank() ? null
				: change.currency().trim().toUpperCase();
		if (currency != null) {
			try {
				Currency.getInstance(currency);
			}
			catch (IllegalArgumentException unknown) {
				throw new InvalidRequestException(currency + " is not an ISO currency code (for example USD)");
			}
		}
		if (change.payoutTermDays() < 0 || change.payoutTermDays() > 365) {
			throw new InvalidRequestException("The payout term is between 0 and 365 days");
		}
		Map<String, Object> before = brandSnapshot(brand);
		brand.update(name, currency, change.payoutTermDays());
		Brand saved = brands.save(brand);
		audit.recordEvent(BRAND_OBJECT_TYPE, saved.getId(), AuditAction.UPDATED, me(), before, brandSnapshot(saved));
		return saved;
	}

	private static Map<String, Object> brandSnapshot(Brand brand) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", brand.getName());
		out.put("currency", brand.getCurrency());
		out.put("payoutTermDays", brand.getPayoutTermDays());
		return out;
	}

	private String validated(Setting setting, String raw) {
		String value = raw.trim();
		if (value.isEmpty()) {
			throw new InvalidRequestException(label(setting) + " cannot be empty — reset it to use the environment's value");
		}
		if (value.length() > 2000 || value.chars().anyMatch(Character::isISOControl)) {
			throw new InvalidRequestException(label(setting) + " is not a valid value");
		}
		switch (setting.kind()) {
		case HOST -> {
			if (!HOST.matcher(value).matches()) {
				throw new InvalidRequestException(label(setting) + " must be a host name such as smtp.example.com");
			}
		}
		case PORT -> {
			int port = parse(setting, value);
			if (port < 1 || port > 65535) {
				throw new InvalidRequestException(label(setting) + " must be between 1 and 65535");
			}
		}
		case EMAIL -> {
			if (!EMAIL.matcher(value).matches()) {
				throw new InvalidRequestException(label(setting) + " must be an email address");
			}
		}
		case BOOL -> {
			if (!value.equals("true") && !value.equals("false")) {
				throw new InvalidRequestException(label(setting) + " must be on or off");
			}
		}
		case COUNT -> {
			int floor = setting == Setting.ONBOARDING_TARGET ? 0 : 1;
			int count = parse(setting, value);
			if (count < floor || count > 100_000) {
				throw new InvalidRequestException(label(setting) + " must be between " + floor + " and 100000");
			}
		}
		case BRAND -> {
			boolean exists;
			try {
				exists = brands.existsById(UUID.fromString(value));
			}
			catch (IllegalArgumentException notAUuid) {
				exists = brands.findBySlug(value).isPresent();
			}
			if (!exists) {
				throw new InvalidRequestException("No brand is called " + value);
			}
		}
		case TEXT -> {
			// Free text: a token, a username, a location or field id. Checked for length and control characters above.
		}
		}
		return value;
	}

	private static int parse(Setting setting, String value) {
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException notANumber) {
			throw new InvalidRequestException(label(setting) + " must be a whole number");
		}
	}

	private static Setting known(String key) {
		try {
			return Setting.valueOf(key);
		}
		catch (IllegalArgumentException unknown) {
			throw new InvalidRequestException(key + " is not a setting that can be changed here");
		}
	}

	/** What the audit trail keeps: the value, except for a secret, which is only ever "set" or "cleared". */
	private static Object snapshot(Setting setting, String value) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("key", setting.name());
		out.put("value", setting.secret() ? (value == null ? "cleared" : "set") : value);
		return out;
	}

	/** A stable id per setting, so the audit trail for one setting reads as one object's history. */
	static UUID idOf(Setting setting) {
		return UUID.nameUUIDFromBytes(("setting:" + setting.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static String label(Setting setting) {
		String words = setting.name().toLowerCase().replace('_', ' ');
		return Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	private static UUID me() {
		return TenantContext.current().memberId();
	}
}
