package com.ie.evalos.job;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One Postgres advisory lock per sweep, so two instances cannot run the same sweep at once.
 *
 * <p><strong>Session-scoped, and it must not be {@code pg_try_advisory_xact_lock}.</strong> That
 * variant releases when its transaction commits — and these sweeps deliberately run
 * <em>one transaction per item</em>, so a single bad case cannot abort the whole run. An
 * xact-scoped lock would therefore be dropped after the <em>first</em> item and leave the entire
 * remainder unprotected, which is precisely the rolling-deploy double-chase it exists to
 * prevent. So the lock is held on one connection for the sweep's lifetime, taken outside the
 * per-item transactions, and released in a {@code finally}.
 *
 * <p><strong>Why this is not speculative, given the single-instance NFR.</strong> That NFR is an
 * assumption, not an enforcement: nothing in the config or the platform prevents a second
 * instance, and <strong>every rolling deploy runs two for a few seconds</strong> while the old
 * process drains. If both tick in that window the sweeps do not conflict loudly — they send the
 * client a second chase and the staff a second alert, and nobody traces it back to the deploy.
 * One statement buys immunity to that, and to the day somebody scales out without reading this.
 *
 * <p>ShedLock is the named alternative and is refused: another dependency and another table for
 * what one Postgres builtin already does.
 *
 * <p><strong>The cost, stated:</strong> a hard JVM kill holds the lock until the connection is
 * reaped rather than releasing instantly. That is the right trade — the failure mode is "a sweep
 * is skipped for one tick" against "a client is messaged twice" — and a stuck lock is visible,
 * because the run ledger stops gaining rows for that job type.
 */
@Component
public class JobLock {

	private static final Logger log = LoggerFactory.getLogger(JobLock.class);

	private final DataSource dataSource;

	JobLock(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Runs {@code work} while holding the lock for {@code jobType}, or does nothing.
	 *
	 * <p>The connection is held open for the whole call on purpose — that is what session scope
	 * means, and closing it would release the lock. The work inside opens its own transactions
	 * through the normal Spring machinery; it does not use this connection.
	 *
	 * @return true if the lock was taken and the work ran, false if another instance holds it
	 */
	public boolean runExclusively(String jobType, Runnable work) {
		try (Connection connection = dataSource.getConnection()) {
			if (!tryLock(connection, jobType)) {
				// Not an error, and not worth a warning: this is the mechanism working. It
				// happens on every rolling deploy, which is the case it was built for.
				log.info("Sweep {} is already running elsewhere — skipping this tick", jobType);
				return false;
			}
			try {
				work.run();
			}
			finally {
				unlock(connection, jobType);
			}
			return true;
		}
		catch (SQLException failure) {
			// A lock that cannot be taken is a skipped tick, not a crashed scheduler. The next
			// tick tries again, which is the whole reason sweeps are idempotent from the data.
			log.error("Could not obtain the job lock for {} — skipping this tick", jobType, failure);
			return false;
		}
	}

	/**
	 * Whether the lock for {@code jobType} is currently free.
	 *
	 * <p>Advisory only, and racy by nature — it is for a message to a human ("already running"),
	 * never for a decision. The decision is {@link #runExclusively}'s own atomic claim.
	 */
	public boolean isFree(String jobType) {
		return probe(() -> {
			try (Connection connection = dataSource.getConnection()) {
				if (!tryLock(connection, jobType)) {
					return false;
				}
				unlock(connection, jobType);
				return true;
			}
			catch (SQLException failure) {
				log.warn("Could not probe the job lock for {}", jobType, failure);
				return true;
			}
		});
	}

	private static boolean probe(BooleanSupplier check) {
		return check.getAsBoolean();
	}

	private static boolean tryLock(Connection connection, String jobType) throws SQLException {
		// hashtext() rather than a hand-rolled hash so the key is stable across JVMs and
		// languages: two instances must compute the same bigint for the same job type, and
		// Java's String.hashCode() is not what Postgres would produce.
		try (PreparedStatement statement =
				connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
			statement.setString(1, jobType);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}

	private static void unlock(Connection connection, String jobType) {
		try (PreparedStatement statement =
				connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
			statement.setString(1, jobType);
			statement.execute();
		}
		catch (SQLException failure) {
			// Logged and swallowed: this runs in a finally, and throwing here would replace the
			// sweep's real failure with this one. Closing the connection releases it anyway.
			log.warn("Could not release the job lock for {} — the connection close will", jobType,
					failure);
		}
	}
}
