package com.ie.evalos.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Hears every instance's settings save ({@code LISTEN} on {@link AppSettings#CHANNEL}) and makes this one's next
 * read reload, so a value the Administrator saved is in force everywhere within a second rather than
 * {@link AppSettings#MAX_STALE_MS}.
 *
 * <p><strong>Its own connection, outside the pool.</strong> A listener holds its connection for the life of the
 * process; borrowed from Hikari it would be one slot short forever and trip leak detection.
 *
 * <p><strong>Best effort by design.</strong> Down, it changes nothing: the time-based staleness still bounds every
 * instance. So a failure is logged and retried with backoff, never thrown — and on every (re)connect the cache is
 * invalidated once, because a save made while it was deaf was never heard.
 */
@Component
class AppSettingsListener {

	private static final Logger log = LoggerFactory.getLogger(AppSettingsListener.class);

	private static final int POLL_MS = 10_000;
	private static final long FIRST_RETRY_MS = 1_000;
	private static final long LAST_RETRY_MS = 30_000;

	private final AppSettings settings;
	private final DataSourceProperties datasource;

	private volatile boolean running;
	private volatile Thread worker;
	private volatile Connection connection;
	private volatile boolean listening;

	AppSettingsListener(AppSettings settings, DataSourceProperties datasource) {
		this.settings = settings;
		this.datasource = datasource;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		running = true;
		Thread thread = new Thread(this::run, "app-settings-listener");
		thread.setDaemon(true);
		worker = thread;
		thread.start();
	}

	/** Whether this instance hears other instances' saves right now. */
	boolean listening() {
		return listening;
	}

	@PreDestroy
	void stop() {
		running = false;
		Thread thread = worker;
		if (thread != null) {
			thread.interrupt();
		}
		closeQuietly(connection);
	}

	private void run() {
		long backoff = FIRST_RETRY_MS;
		while (running) {
			try (Connection session = DriverManager.getConnection(datasource.determineUrl(),
					datasource.determineUsername(), datasource.determinePassword())) {
				connection = session;
				PGConnection pg = session.unwrap(PGConnection.class);
				try (Statement listen = session.createStatement()) {
					listen.execute("LISTEN " + AppSettings.CHANNEL);
				}
				listening = true;
				settings.invalidate();
				backoff = FIRST_RETRY_MS;
				while (running) {
					PGNotification[] heard = pg.getNotifications(POLL_MS);
					if (heard != null && heard.length > 0) {
						settings.invalidate();
					}
				}
			}
			catch (SQLException lost) {
				if (!running) {
					return;
				}
				log.warn("App settings listener lost its connection ({}); retrying in {} s, settings refresh every {} s "
						+ "meanwhile", lost.getMessage(), backoff / 1000, AppSettings.MAX_STALE_MS / 1000);
			}
			finally {
				listening = false;
				connection = null;
			}
			try {
				Thread.sleep(backoff);
			}
			catch (InterruptedException stopping) {
				return;
			}
			backoff = Math.min(backoff * 2, LAST_RETRY_MS);
		}
	}

	private static void closeQuietly(Connection open) {
		if (open == null) {
			return;
		}
		try {
			open.close();
		}
		catch (SQLException ignored) {
			// Shutting down; the server reaps the session either way.
		}
	}
}
