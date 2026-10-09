package com.ie.evalos.config;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.PaymentDetailConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The settings the Administrator saved in the app (D83, spec 85), read by the code that uses them.
 *
 * <p><strong>Only the override lives here.</strong> Each consumer keeps the value it was configured with at boot
 * (its environment variable) and asks {@link #app} per use; a saved value wins, no saved value means the
 * environment applies. That keeps every consumer's boot behaviour exactly as it was when nothing is saved.
 *
 * <p>Reads come from memory. The map is reloaded after every save on this instance and at most
 * {@link #MAX_STALE_MS} later on any other.
 * ponytail: time-based staleness across instances; a cluster-wide invalidation is the upgrade if instances multiply.
 *
 * <p>Deployment-wide, not brand-scoped: these configure the server, not a brand's rows.
 */
@Component
public class AppSettings {

	private static final Logger log = LoggerFactory.getLogger(AppSettings.class);

	static final long MAX_STALE_MS = 30_000;

	/** Where a setting's current value comes from, for the screen. */
	public enum Source { APP, ENVIRONMENT, UNSET }

	private final JdbcTemplate jdbc;
	private final PaymentDetailConverter cipher;
	private final Environment environment;

	private volatile Map<Setting, String> saved;
	private volatile long loadedAt;

	AppSettings(JdbcTemplate jdbc, PaymentDetailConverter cipher, Environment environment) {
		this.jdbc = jdbc;
		this.cipher = cipher;
		this.environment = environment;
	}

	/** The value saved in the app, decrypted if it is a secret; empty when nothing is saved. */
	public Optional<String> app(Setting setting) {
		return Optional.ofNullable(current().get(setting));
	}

	/** A switch: on unless the Administrator turned it off. */
	public boolean enabled(Setting toggle) {
		return app(toggle).map(Boolean::parseBoolean).orElse(true);
	}

	/** The environment's value for a setting, or null when it sets none. Switches have none. */
	public String environment(Setting setting) {
		if (setting.property() == null) {
			return null;
		}
		String value = environment.getProperty(setting.property());
		return value == null || value.isBlank() ? null : value.trim();
	}

	public Source source(Setting setting) {
		if (app(setting).isPresent()) {
			return Source.APP;
		}
		return environment(setting) != null ? Source.ENVIRONMENT : Source.UNSET;
	}

	/** The value in force: the app's, else the environment's, else null. */
	public String effective(Setting setting) {
		return app(setting).orElseGet(() -> environment(setting));
	}

	/** Saves (or replaces) one value. A secret is encrypted before it reaches the database. */
	public void save(Setting setting, String value, UUID by) {
		String stored = setting.secret() ? cipher.convertToDatabaseColumn(value) : value;
		jdbc.update("INSERT INTO app_setting (key, value, updated_at, updated_by) VALUES (?, ?, now(), ?) "
				+ "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), "
				+ "updated_by = EXCLUDED.updated_by", setting.name(), stored, by);
		reload();
	}

	/** Deletes the saved value, so the environment applies again. */
	public void clear(Setting setting) {
		jdbc.update("DELETE FROM app_setting WHERE key = ?", setting.name());
		reload();
	}

	private Map<Setting, String> current() {
		Map<Setting, String> map = saved;
		if (map == null || System.currentTimeMillis() - loadedAt > MAX_STALE_MS) {
			map = reload();
		}
		return map;
	}

	private synchronized Map<Setting, String> reload() {
		Map<Setting, String> map = new EnumMap<>(Setting.class);
		try {
			jdbc.query("SELECT key, value FROM app_setting", (row) -> {
				Setting setting = known(row.getString("key"));
				if (setting == null) {
					return;
				}
				String value = row.getString("value");
				if (setting.secret()) {
					try {
						value = cipher.convertToEntityAttribute(value);
					}
					catch (IllegalStateException unreadable) {
						// The field key changed since it was saved. Ignored rather than fatal: the environment's value
						// applies, and the Settings screen shows the secret as not set so it can be entered again.
						log.warn("Saved setting {} cannot be decrypted with the current EVALOS_FIELD_KEY; re-enter it",
								setting);
						return;
					}
				}
				map.put(setting, value);
			});
		}
		catch (DataAccessException unreadable) {
			// A database blip must not turn every configured integration off: keep the last good values.
			log.warn("Could not read app settings; keeping the last values read", unreadable);
			return saved == null ? Map.of() : saved;
		}
		saved = map;
		loadedAt = System.currentTimeMillis();
		return map;
	}

	private static Setting known(String key) {
		try {
			return Setting.valueOf(key);
		}
		catch (IllegalArgumentException retired) {
			return null;
		}
	}
}
