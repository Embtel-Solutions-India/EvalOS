package com.ie.evalos.config;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The newest WARN and ERROR lines, kept in memory so the System health page can show what its
 * "Errors logged" count is counting (D82).
 *
 * <p><strong>In memory, on purpose.</strong> The container log is the record; this is a window onto
 * it. A restart empties it, and lines written before this bean was created (early startup) are not in
 * it — the count, from Micrometer, still includes them. Admin-only, through {@code /api/system}.
 */
@Component
public class RecentLogProblems {

	public record Line(Instant at, String level, String logger, String thread, String message, String exception) {
	}

	static final int CAPACITY = 50;
	private static final int MAX_TEXT = 2000;

	private final Deque<Line> lines = new ArrayDeque<>(CAPACITY);

	RecentLogProblems() {
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
			return;
		}
		AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
			@Override
			protected void append(ILoggingEvent event) {
				if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
					record(event);
				}
			}
		};
		appender.setContext(context);
		appender.setName("recent-problems");
		appender.start();
		context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
	}

	/** Newest first. */
	public synchronized List<Line> newestFirst() {
		return new ArrayList<>(lines);
	}

	synchronized void record(ILoggingEvent event) {
		if (lines.size() == CAPACITY) {
			lines.removeLast();
		}
		IThrowableProxy thrown = event.getThrowableProxy();
		lines.addFirst(new Line(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(),
				event.getLoggerName(), event.getThreadName(), clip(event.getFormattedMessage()),
				thrown == null ? null : clip(thrown.getClassName() + ": " + thrown.getMessage())));
	}

	private static String clip(String text) {
		return text == null || text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…";
	}
}
