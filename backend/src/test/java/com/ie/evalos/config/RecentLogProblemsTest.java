package com.ie.evalos.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RecentLogProblemsTest {

	private static final Logger log = LoggerFactory.getLogger(RecentLogProblemsTest.class);

	/** WARN and ERROR are kept newest first with their exception; INFO is not; the window is capped. */
	@Test
	void keepsTheNewestWarningsAndErrorsOnly() {
		RecentLogProblems recent = new RecentLogProblems();

		log.info("routine");
		log.warn("first {}", "warning");
		log.error("it broke", new IllegalStateException("no row"));

		assertThat(recent.newestFirst()).extracting(RecentLogProblems.Line::message)
				.containsExactly("it broke", "first warning");
		assertThat(recent.newestFirst().get(0).exception()).isEqualTo("java.lang.IllegalStateException: no row");

		for (int i = 0; i < RecentLogProblems.CAPACITY + 5; i++) {
			log.warn("flood {}", i);
		}
		assertThat(recent.newestFirst()).hasSize(RecentLogProblems.CAPACITY);
		assertThat(recent.newestFirst().get(0).message()).isEqualTo("flood " + (RecentLogProblems.CAPACITY + 4));
	}
}
