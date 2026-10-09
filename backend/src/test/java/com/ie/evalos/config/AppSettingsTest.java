package com.ie.evalos.config;

import com.ie.evalos.common.PaymentDetailConverter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** How {@link AppSettings} behaves when the database misbehaves, and when a save is inside a transaction. */
class AppSettingsTest {

	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final AppSettings settings = new AppSettings(jdbc, mock(PaymentDetailConverter.class), mock(Environment.class));

	@AfterEach
	void endSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	/**
	 * A failed read is not retried by every following lookup: the last values stand for the window. Without that, a
	 * database blip queued every request thread behind the settings lock, each retrying the read.
	 */
	@Test
	void anUnreadableTableIsNotRetriedOnEveryLookup() {
		willThrow(new DataAccessResourceFailureException("connection refused"))
				.given(jdbc).query(anyString(), any(RowCallbackHandler.class));

		for (int i = 0; i < 50; i++) {
			assertThat(settings.app(Setting.GHL_TOKEN)).isEmpty();
		}

		verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class));
	}

	/** Inside a transaction, saved values are read back only once it commits — once, however many were saved. */
	@Test
	void savesInsideATransactionReloadOnceAfterCommitAndNotBefore() {
		TransactionSynchronizationManager.initSynchronization();

		settings.save(Setting.MAIL_HOST, "smtp.example.com", null);
		settings.save(Setting.MAIL_PORT, "2525", null);
		settings.clear(Setting.MAIL_FROM);
		verify(jdbc, times(0)).query(anyString(), any(RowCallbackHandler.class));

		for (TransactionSynchronization registered : TransactionSynchronizationManager.getSynchronizations()) {
			registered.afterCommit();
		}

		verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class));
	}

	/** Outside a transaction a save is read back at once, so the next lookup sees it. */
	@Test
	void aSaveOutsideATransactionReloadsAtOnce() {
		settings.save(Setting.MAIL_HOST, "smtp.example.com", null);

		verify(jdbc, times(1)).query(anyString(), any(RowCallbackHandler.class));
	}
}
