package com.ie.evalos.config;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing that seeds a database may sit under {@code db/migration}.
 *
 * <p>Flyway scans a location <em>and every sub-directory below it</em>. Production
 * lists plain {@code classpath:db/migration} (`application.yml`), so for a while the
 * local seed — which lived in {@code db/migration/local} and was believed unreachable
 * because only the local profile named that path — was in fact found and applied by a
 * prod boot. It inserted two brands and six logins sharing one committed BCrypt hash,
 * including the GM, the single cross-brand reader. The seed now lives in the sibling
 * {@code db/seed-local}.
 *
 * <p>Flyway has no exclude filter, so keeping the trees apart is the whole mechanism,
 * and a mechanism made of directory layout needs a test or the next person restores
 * the nesting for tidiness.
 */
class MigrationTreeTest {

	/** Above every real migration, which is what marks a file as a seed. */
	private static final int SEED_FLOOR = 900;

	@Test
	void productionMigrationTreeHoldsNoSeedsAndNoSubdirectories() throws Exception {
		Path migrations = resource("db/migration");

		try (Stream<Path> tree = Files.walk(migrations)) {
			List<Path> nested = tree.filter(Files::isRegularFile)
					.filter(path -> !path.getParent().equals(migrations))
					.toList();

			assertThat(nested)
					.describedAs("Flyway recurses, so anything below db/migration runs in production. "
							+ "Seeds and profile-specific scripts belong in a sibling directory.")
					.isEmpty();
		}

		try (Stream<Path> scripts = Files.list(migrations)) {
			assertThat(scripts.map(path -> path.getFileName().toString())
					.filter(name -> name.endsWith(".sql"))
					.filter(name -> version(name) >= SEED_FLOOR)
					.toList())
					.describedAs("V%d and above is the seed numbering — those scripts must not be "
							+ "reachable from the production Flyway location.", SEED_FLOOR)
					.isEmpty();
		}
	}

	/**
	 * The seed still has to be somewhere, or the profile that lists it silently boots an
	 * empty database. Both trees are checked: {@code db/seed-testprod} seeds a real
	 * environment, so the numbering rule that marks a file as a seed matters more there,
	 * not less.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "db/seed-local", "db/seed-testprod" })
	void seedsLiveInTheirOwnTreeBesideTheMigrations(String location) throws Exception {
		try (Stream<Path> seeds = Files.list(resource(location))) {
			assertThat(seeds.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".sql")))
					.isNotEmpty()
					.allSatisfy(name -> assertThat(version(name)).isGreaterThanOrEqualTo(SEED_FLOOR));
		}
	}

	/** {@code V901__seed_local_webhook_secrets.sql} to {@code 901}; anything unparseable sorts as a migration. */
	private static int version(String fileName) {
		String digits = fileName.replaceFirst("^V(\\d+)__.*$", "$1");
		return digits.equals(fileName) ? 0 : Integer.parseInt(digits);
	}

	private static Path resource(String location) throws Exception {
		URL url = MigrationTreeTest.class.getClassLoader().getResource(location);
		assertThat(url).describedAs("%s is missing from the classpath entirely", location).isNotNull();
		return Path.of(url.toURI());
	}
}
