package com.ie.evalos.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Segment} records which kind of client a sales or marketing employee handles, and
 * <strong>nothing in production code may branch on it.</strong>
 *
 * <p><strong>Why this is a build-failing test and not a comment.</strong> The three kinds carry
 * identical permissions, which is the entire reason they are a column instead of six roles. The
 * moment one {@code if (segment == ATTORNEY)} appears, the column has quietly become an access
 * key — an org chart inside the security model — and every later reader will reasonably copy it.
 * That drift is invisible in review because each individual branch looks harmless; it is only
 * obvious in aggregate, which is what a structural test can see and a reviewer cannot.
 *
 * <p>The day a segment genuinely needs a different permission, it is a new {@link Role}, argued
 * in {@code context/specs/00b-ghl-operational-programme.md} first — and this test is what forces
 * that conversation to happen instead of being skipped.
 *
 * <p>Deliberately a source scan rather than bytecode analysis: the thing being forbidden is a
 * <em>shape of code</em>, and the failure message wants to name a file a developer can open.
 */
class SegmentIsNotAnAccessKeyTest {

	private static final Path MAIN = Path.of("src", "main", "java");

	/**
	 * Comparing against a named constant, switching on the value, or asking "is it this one".
	 *
	 * <p>Carrying the value — a field, a getter, a DTO component, a JPA mapping — is fine and is
	 * what the column is for. What is forbidden is <em>deciding</em> anything with it.
	 */
	private static final List<Pattern> BRANCHING = List.of(
			Pattern.compile("Segment\\s*\\.\\s*(ATTORNEY|EMPLOYER_FIRM|INDIVIDUAL)"),
			Pattern.compile("(==|!=)\\s*Segment\\b"),
			Pattern.compile("\\bSegment\\s*\\.\\s*valueOf\\b"),
			Pattern.compile("getSegment\\(\\)\\s*(==|!=|\\.equals)"),
			Pattern.compile("switch\\s*\\([^)]*[Ss]egment"));

	@Test
	void noProductionCodeBranchesOnASegment() throws IOException {
		assertThat(Files.isDirectory(MAIN))
				.as("the main source tree must be where this test can read it: %s", MAIN.toAbsolutePath())
				.isTrue();

		try (Stream<Path> sources = Files.walk(MAIN)) {
			List<String> offenders = sources
					.filter((path) -> path.toString().endsWith(".java"))
					// Segment's own declaration names its constants in javadoc and in the enum
					// body, which is not a branch. Nothing else is exempt.
					.filter((path) -> !path.getFileName().toString().equals("Segment.java"))
					.filter(SegmentIsNotAnAccessKeyTest::branchesOnSegment)
					.map(Path::toString)
					.toList();

			assertThat(offenders)
					.as("Segment is not an access key: the three kinds have identical permissions, "
							+ "which is why they are a column and not six roles. If one now needs a "
							+ "different permission, add a Role and argue it in "
							+ "context/specs/00b-ghl-operational-programme.md first.")
					.isEmpty();
		}
	}

	private static boolean branchesOnSegment(Path source) {
		String text;
		try {
			text = Files.readString(source, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read " + source, ex);
		}
		return BRANCHING.stream().anyMatch((pattern) -> pattern.matcher(text).find());
	}

	/** The scan has to be able to fail, or it is decoration. */
	@Test
	void theScanCatchesABranch() {
		String offending = "if (member.getSegment() == Segment.ATTORNEY) { return true; }";

		assertThat(BRANCHING.stream().anyMatch((pattern) -> pattern.matcher(offending).find())).isTrue();
	}

	/** And it has to allow the thing the column is actually for: carrying the value. */
	@Test
	void theScanAllowsCarryingTheValue() {
		String legitimate = "public record Row(UUID id, Segment segment) { } "
				+ "private Segment segment; public Segment getSegment() { return segment; }";

		assertThat(BRANCHING.stream().anyMatch((pattern) -> pattern.matcher(legitimate).find())).isFalse();
	}
}
