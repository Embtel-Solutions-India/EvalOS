package com.ie.evalos.config;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.ie.evalos.common.PaymentDetailConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The settings the Administrator saved in the app (D83, spec 85), read by the code that uses them.
 *
 * <p><strong>Only the override lives here.</strong> Each consumer keeps the value it was configured with at boot
 * (its environment variable) and asks {@link #app} per use; a saved value wins, no saved value means the
 * environment applies. That keeps every consumer's boot behaviour exactly as it was when nothing is saved.
 *
 * <p>Reads come from memory. The map is reloaded after every save on this instance; on every other one, the
 * save's {@code NOTIFY} on {@link #CHANNEL} reaches {@link AppSettingsListener}, which {@link #invalidate}s it so
 * the next read reloads. {@link #MAX_STALE_MS} stays as the floor for when the listener is down.
 *
 * <p>Deployment-wide, not brand-scoped: these configure the server, not a brand's rows.
 */
@Component
public class AppSettings {

	private static final Logger log = LoggerFactory.getLogger(AppSettings.class);

	static final long MAX_STALE_MS = 30_000;

	/** The Postgres channel a save or reset is announced on. Delivered on commit, dropped on rollback. */
	static final String CHANNEL = "evalos_app_setting";

	/** Where a setting's current value comes from, for the screen. */
	public enum Source { APP, ENVIRONMENT, UNSET }

	private final JdbcTemplate jdbc;
	private final PaymentDetailConverter cipher;
	private final Environment environment;

	private volatile Map<Setting, String> saved;
	private volatile long loadedAt;
	/** Bumped by {@link #invalidate}; the map is fresh only while it matches the count its read started at. */
	private final AtomicLong invalidations = new AtomicLong();
	private volatile long loadedAtInvalidation;

	AppSettings(JdbcTemplate jdbc, PaymentDetailConverter cipher, Environment environment) {
		this.jdbc = jdbc;
		this.cipher = cipher;
		this.environment = environment;
	}

	/**
	 * The saved value or {@code fallback}, trimmed — the one-line accessor every consumer uses. Null-safe on
	 * {@code settings} because hand-built instances in tests have none and keep the value they were given.
	 */
	public static String or(AppSettings settings, Setting setting, String fallback) {
		return settings == null ? fallback : settings.app(setting).map(String::trim).orElse(fallback);
	}

	/** {@link #or} for a whole number; a saved value was validated as one before it was stored. */
	public static int intOr(AppSettings settings, Setting setting, int fallback) {
		return settings == null ? fallback : settings.app(setting).map(String::trim).map(Integer::parseInt).orElse(fallback);
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
		announce();
		reloadAfterCommit();
	}

	/** Deletes the saved value, so the environment applies again. */
	public void clear(Setting setting) {
		jdbc.update("DELETE FROM app_setting WHERE key = ?", setting.name());
		announce();
		reloadAfterCommit();
	}

	/**
	 * Another instance saved: the next read reloads. Counted rather than flagged, so a notice that lands while a
	 * read is in flight is not swallowed by that read finishing — it may have read the table before the commit.
	 */
	void invalidate() {
		invalidations.incrementAndGet();
	}

	/** Tells the other instances. Postgres queues it with the transaction, so a rolled-back save announces nothing. */
	private void announce() {
		jdbc.execute("NOTIFY " + CHANNEL);
	}

	/**
	 * Inside a transaction the new values are not real until it commits — read them earlier and a save that then
	 * rolls back stays in force here for up to {@link #MAX_STALE_MS}. So: once, after commit; at once otherwise.
	 */
	private void reloadAfterCommit() {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			reload(true);
			return;
		}
		boolean scheduled = TransactionSynchronizationManager.getSynchronizations().stream()
				.anyMatch((registered) -> registered instanceof ReloadAfterCommit);
		if (!scheduled) {
			TransactionSynchronizationManager.registerSynchronization(new ReloadAfterCommit());
		}
	}

	/** One reload per transaction, however many settings it saved. */
	private final class ReloadAfterCommit implements TransactionSynchronization {
		@Override
		public void afterCommit() {
			reload(true);
		}
	}

	private Map<Setting, String> current() {
		Map<Setting, String> map = saved;
		if (map == null || stale()) {
			map = reload(false);
		}
		return map;
	}

	private boolean stale() {
		return System.currentTimeMillis() - loadedAt > MAX_STALE_MS || loadedAtInvalidation != invalidations.get();
	}

	/**
	 * {@code force} after a save; otherwise only if still stale once the lock is held, so the threads that queued
	 * behind the first one are answered by its read instead of each running another.
	 */
	private synchronized Map<Setting, String> reload(boolean force) {
		if (!force && saved != null && !stale()) {
			return saved;
		}
		long invalidation = invalidations.get();
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
			// A database blip must not turn every configured integration off: keep the last good values — and wait
			// out the window before asking again, or every lookup on every thread would queue here retrying it.
			log.warn("Could not read app settings; keeping the last values read for {} s", MAX_STALE_MS / 1000,
					unreadable);
			if (saved == null) {
				saved = Map.of();
			}
			loadedAt = System.currentTimeMillis();
			loadedAtInvalidation = invalidation;
			return saved;
		}
		saved = map;
		loadedAt = System.currentTimeMillis();
		loadedAtInvalidation = invalidation;
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
