package com.ie.evalos.job;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sweep that exists but is not wired is a sweep that never runs, and nothing about that looks
 * broken — no error, no log line, just a queue of cases nobody chases.
 *
 * <p><strong>Spring will not catch it here, and that is why this is a test.</strong> An
 * unresolvable {@code ${evalos.jobs.…}} placeholder normally fails the boot — but only when
 * {@code @EnableScheduling} is active, and the one test that starts a full context deliberately
 * sets {@code evalos.jobs.enabled=false}. So a fifth sweep whose interval nobody added to
 * {@code application.yml} would pass the whole build and fail in production.
 *
 * <p>Three rules, each the shape of a real omission: implement {@link Sweep} (or the admin
 * panel cannot address it), carry a {@code @Scheduled} tick (or it has no clock), and name a
 * property that exists (or it has no interval).
 */
class SweepRegistrationTest {

	private static final Path SOURCE = Path.of("src/main/java/com/ie/evalos/job");
	private static final Path CONFIG = Path.of("src/main/resources/application.yml");

	/** The exact prefix a sweep's tick must open with. Plain text, so there is no regex to escape. */
	private static final String TICK = "@Scheduled(fixedDelayString = \"${evalos.jobs.intervals.";

	private record SweepSource(String name, String body) {
	}

	private static List<SweepSource> sweeps() throws IOException {
		try (Stream<Path> files = Files.list(SOURCE)) {
			List<SweepSource> found = new ArrayList<>();
			// Everything named *Sweep.java except the interface itself, which is the one file in
			// the package that ends that way and is not one.
			for (Path file : files
					.filter((f) -> f.getFileName().toString().endsWith("Sweep.java"))
					.filter((f) -> !f.getFileName().toString().equals("Sweep.java"))
					.toList()) {
				found.add(new SweepSource(file.getFileName().toString(),
						Files.readString(file, StandardCharsets.UTF_8)));
			}
			return found;
		}
	}

	@Test
	void everySweepIsAddressableAndScheduledAgainstAPropertyThatExists() throws IOException {
		String config = Files.readString(CONFIG, StandardCharsets.UTF_8);
		List<SweepSource> sweeps = sweeps();

		// Guards the guard: a rename that stopped this test finding anything would otherwise
		// leave it passing forever.
		assertThat(sweeps).as("sweeps found under %s", SOURCE).hasSizeGreaterThanOrEqualTo(4);

		for (SweepSource sweep : sweeps) {
			assertThat(sweep.body())
					.as("%s must implement Sweep, or the admin panel cannot run it by name", sweep.name())

					.contains("implements Sweep");
			int tick = sweep.body().indexOf(TICK);
			assertThat(tick)
					.as("%s must carry a @Scheduled tick reading an evalos.jobs.* property", sweep.name())
					.isNotNegative();
			// The interval is keyed BY the job type, so this also pins the two together: a
			// sweep scheduling on somebody else's interval would otherwise be invisible.
			String leaf = sweep.body().substring(tick + TICK.length(), sweep.body().indexOf('}', tick));
			assertThat(leaf)
					.as("%s schedules on evalos.jobs.intervals.%s but its JOB_TYPE is different",
							sweep.name(), leaf)
					.isEqualTo(jobTypeOf(sweep));
			assertThat(config)
					.as("%s schedules on evalos.jobs.intervals.%s, which application.yml does not define",
							sweep.name(), leaf)
					.contains("\n      " + leaf + ":");
		}
	}

	/**
	 * Two sweeps sharing a job type would share an advisory lock and take turns skipping each
	 * other — each one's ledger saying "already running elsewhere", neither doing any work.
	 */
	@Test
	void jobTypesAreDistinct() throws IOException {
		List<String> types = new ArrayList<>();
		for (SweepSource sweep : sweeps()) {
			types.add(jobTypeOf(sweep));
		}
		assertThat(types).doesNotHaveDuplicates();
	}

	private static String jobTypeOf(SweepSource sweep) {
		String declaration = "JOB_TYPE = \"";
		int at = sweep.body().indexOf(declaration);
		assertThat(at).as("%s declares no JOB_TYPE", sweep.name()).isNotNegative();
		int from = at + declaration.length();
		return sweep.body().substring(from, sweep.body().indexOf('"', from));
	}
}
