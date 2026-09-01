package com.ie.evalos.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that actually calls GHL, closing Unit 24's standing open item.
 *
 * <p><strong>Why this exists.</strong> Every other test of this client stubs the transport:
 * {@code GhlPipelineClientTest} mocks the collaborator and {@code GhlPipelineClientHttpTest} runs a
 * local {@code HttpServer} replaying canned bodies. Both pin what EvalOS <em>sends</em> against what
 * we believe GHL wants — which is exactly the thing a canned test cannot check. The progress tracker
 * carried "never exercised against live GHL from the running app" as an open item for Units 24 and
 * 26 precisely because the pipeline's shape had been verified by hand in GHL, while this class had
 * never made the call itself.
 *
 * <p><strong>What only a live call can settle, and therefore what this asserts.</strong> GHL's two
 * endpoints disagree with each other about parameter casing, and getting it wrong is not a clean
 * failure: {@code /opportunities/pipelines} wants {@code locationId} (camelCase) while
 * {@code /opportunities/search} wants {@code location_id} and {@code pipeline_stage_id}
 * (snake_case). The wrong spelling returns a 422 whose message reads like a scope problem, and
 * GHL's own {@code nextPageUrl} spells the search params camelCase, which made the wrong guess look
 * well-evidenced. The mixed casing in this client is not a typo — it is the live answer, and this is
 * the test that keeps it honest. The same goes for the {@code Version} header, the {@code MM-dd-yyyy}
 * date format, and {@code meta.total} being present on a {@code limit=1} search, which is the whole
 * basis of {@code countIn}.
 *
 * <p><strong>Opt-in, and it must stay opt-in.</strong> Skipped unless {@code GHL_LIVE_TEST=true},
 * so {@code mvnw test} and CI never reach the network and never need a credential. Gating on the
 * presence of {@code GHL_API_TOKEN} alone would be worse: anyone who exports a token to run the app
 * would silently start hitting a live third-party account from their test runs.
 *
 * <h2>Running it</h2>
 *
 * <pre>
 * $env:GHL_LIVE_TEST = "true"
 * .\mvnw.cmd test -Dtest=GhlPipelineClientLiveTest
 * </pre>
 *
 * <p><strong>One flag, and no credential on the command line.</strong> The token is read from
 * {@code backend/config/application-local.yml} — the same gitignored file Spring Boot itself reads
 * it from, and the place {@code .gitignore} documents as where a real token belongs on a laptop. So
 * this test is configured exactly the way the running app is, and a live secret never passes through
 * a shell command, a process listing or a CI variable. {@code GHL_API_TOKEN} in the environment
 * still wins if it is set, matching Spring's own precedence.
 *
 * <p><strong>Read-only, by grant and by assertion.</strong> Everything below is a GET. This client
 * has no write method, the token is scoped {@code opportunities.readonly}, and invariant 2 forbids
 * EvalOS running marketing — so a live run cannot alter the business's funnel. That is what makes
 * pointing a test at production data acceptable here; it would not be if anything wrote.
 */
@EnabledIfEnvironmentVariable(named = "GHL_LIVE_TEST", matches = "(?i)true",
		disabledReason = "Live GHL test: set GHL_LIVE_TEST=true to run it deliberately.")
class GhlPipelineClientLiveTest {

	/** Where a real token lives on a laptop, per {@code .gitignore}. Relative to {@code backend/}. */
	private static final Path LOCAL_OVERRIDE = Path.of("config", "application-local.yml");

	private static final String TOKEN = setting("GHL_API_TOKEN", "token", null);

	/**
	 * International Evaluations' sub-account — the same default the committed local profile carries,
	 * so this test and a local app run point at the same place.
	 *
	 * <p>Hard-coding it is not a leak: a location id appears in every GHL URL and grants nothing
	 * without the token, which is why {@code application-local.yml} defaults it too. The token is
	 * the credential, and that one has no default anywhere.
	 */
	private static final String LOCATION = setting("GHL_LOCATION_ID", "location-id", "kBumF0uUOmMBB5bneYjx");

	/** The names the live location uses, matching the profile defaults. */
	private static final String ADS_PIPELINE = setting("GHL_ADS_PIPELINE_NAME", "ads-pipeline-name",
			"Google ADS Pipeline");

	private static final String EMAIL_PIPELINE = setting("GHL_EMAIL_PIPELINE_NAME", "email-pipeline-name",
			"Shivangi's Email Marketing");

	/**
	 * The sales funnel, and <strong>the reason this constant is worth a comment</strong>: GHL stores
	 * this name with TWO spaces in the middle, and the default here has one.
	 *
	 * <p>That is not a typo to fix. The single-space spelling is what a human writes into an
	 * environment variable, and {@code GhlPipelineClient} collapses whitespace before matching so
	 * that spelling resolves. Which makes the live run below the assertion that actually matters:
	 * the unit test proves the normalisation works against a fixture <em>we</em> wrote, and only a
	 * real call proves the fixture matches what GHL really returns.
	 */
	private static final String SALES_PIPELINE = setting("GHL_SALES_PIPELINE_NAME", "sales-pipeline-name",
			"Aditya's pipeline");

	/**
	 * Environment first, then the gitignored local override, then the default.
	 *
	 * <p>That order is Spring Boot's own precedence, deliberately — a test configured differently
	 * from the app it is vouching for is a test that can pass against something nobody runs. The
	 * file read is a regex rather than a YAML parser because one flat key is being looked up, and
	 * pulling in a parser to read one line is more moving parts than the thing it reads.
	 *
	 * <p><strong>The value is never printed.</strong> Only its length and prefix are, which is what
	 * makes a wrong token diagnosable without copying a credential into a build log.
	 */
	private static String setting(String envName, String yamlKey, String fallback) {
		String fromEnv = System.getenv(envName);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return fromEnv;
		}
		if (Files.isReadable(LOCAL_OVERRIDE)) {
			try {
				Matcher found = Pattern.compile("(?m)^\\s*" + yamlKey + ":\\s*(.+?)\\s*$")
						.matcher(Files.readString(LOCAL_OVERRIDE, StandardCharsets.UTF_8));
				if (found.find()) {
					String value = found.group(1).replaceAll("^[\"']|[\"']$", "");
					// A placeholder left as `${GHL_API_TOKEN:}` is not a value.
					if (!value.isBlank() && !value.startsWith("${")) {
						return value;
					}
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException("Cannot read " + LOCAL_OVERRIDE, ex);
			}
		}
		return fallback;
	}

	/**
	 * A real timeout, not a generous one. If the live call cannot answer in the same bound the
	 * request path uses, that is the finding — not something to wait out.
	 */
	private GhlPipelineClient client() {
		assertThat(TOKEN).describedAs("GHL_API_TOKEN must be set to run the live test").isNotBlank();
		assertThat(TOKEN).describedAs("GHL_API_TOKEN must be the FULL token including the `pit-` prefix. "
				+ "A bare 36-char UUID produces a 401 that reads like a scope problem.").startsWith("pit-");
		assertThat(LOCATION).describedAs("GHL_LOCATION_ID must be set to run the live test").isNotBlank();

		return new GhlPipelineClient(new GhlHttp("https://services.leadconnectorhq.com", "2021-07-28", TOKEN,
				LOCATION, Duration.ofSeconds(10)));
	}

	/**
	 * All three configured pipelines resolve, by name, to a real pipeline with ordered stages.
	 *
	 * <p>This is the assertion that the {@code Version} header, the token, the
	 * {@code locationId} <em>camelCase</em> parameter and the name matching all work together. A
	 * rename in GHL fails here, which is the intended direction — the alternative is an empty funnel
	 * on screen that reads as a bad month.
	 *
	 * <p><strong>{@code SALES_PIPELINE} is the interesting one, and it is why this loop is the first
	 * thing to run after a config change.</strong> Its configured name differs from GHL's stored
	 * name by an invisible second space; this is the only test in the suite that proves the
	 * whitespace normalisation matches <em>GHL's real answer</em> rather than a fixture written from
	 * the same assumption it is meant to check.
	 */
	@Test
	void resolvesEveryConfiguredPipelineWithItsStages() {
		GhlPipelineClient ghl = client();

		for (String name : List.of(ADS_PIPELINE, EMAIL_PIPELINE, SALES_PIPELINE)) {
			GhlPipelineClient.Pipeline pipeline = ghl.pipelineNamed(name);

			assertThat(pipeline.id()).describedAs("%s must have an id", name).isNotBlank();
			// `stages` is the one field that used to be dereferenced unguarded. Asserting it is
			// really populated live is what says the guard is a guard and not the normal path.
			assertThat(pipeline.stages()).describedAs("%s must have stages", name).isNotEmpty();
			assertThat(pipeline.stages()).allSatisfy((stage) -> {
				assertThat(stage.id()).isNotBlank();
				assertThat(stage.name()).isNotBlank();
				assertThat(stage.position()).isGreaterThanOrEqualTo(0);
			});

			System.out.printf("[live] %s -> id=%s, %d stages: %s%n", name, pipeline.id(), pipeline.stages().size(),
					pipeline.stages().stream().map(GhlPipelineClient.Pipeline.Stage::name).toList());
		}
	}

	/**
	 * {@code countIn} really does come back from GHL's own {@code meta.total}.
	 *
	 * <p><strong>The load-bearing assertion of the whole marketing unit.</strong> Counting by
	 * pagination cost 115 sequential requests on the email funnel and blew the frontend's 15s
	 * timeout; the fix was to trust {@code meta.total} on a {@code limit=1} search. That only works
	 * if GHL returns the total with {@code pipeline_stage_id} (snake_case) applied — so this proves
	 * both the casing and the premise. A year is used because the email pipeline's newest deal is
	 * months old.
	 */
	@Test
	void countsStagesFromGhlsOwnTotalWithoutReadingRows() {
		GhlPipelineClient ghl = client();
		LocalDate to = LocalDate.now();
		LocalDate from = to.minusDays(364);

		GhlPipelineClient.Pipeline pipeline = ghl.pipelineNamed(EMAIL_PIPELINE);
		int total = 0;
		for (GhlPipelineClient.Pipeline.Stage stage : pipeline.stages()) {
			int count = ghl.countIn(pipeline.id(), stage.id(), from, to);
			// Never negative, and never null-collapsed-to-something-odd: a window with no matches
			// returns a null total, which countIn maps to 0.
			assertThat(count).describedAs("count for stage %s", stage.name()).isGreaterThanOrEqualTo(0);
			total += count;
			System.out.printf("[live] %s / %s -> %d deals%n", EMAIL_PIPELINE, stage.name(), count);
		}

		System.out.printf("[live] %s total over %s..%s -> %d deals%n", EMAIL_PIPELINE, from, to, total);
		// The email funnel is the five-figure one; if this is zero the window or the id is wrong,
		// not the pipeline.
		assertThat(total).describedAs("the email funnel should hold deals over a year").isPositive();
	}

	/**
	 * A row read terminates and returns the three fields the screen uses.
	 *
	 * <p>Deliberately a <strong>narrow</strong> window: this exercises the cursor pagination and the
	 * row shape, not the five-figure read. The big read is what the background totaller is for, and
	 * pulling 115 pages inside a test would be a rate-limit problem rather than a check.
	 */
	@Test
	void readsRealOpportunityRowsOnANarrowWindow() {
		GhlPipelineClient ghl = client();
		LocalDate to = LocalDate.now();

		GhlPipelineClient.Pipeline pipeline = ghl.pipelineNamed(ADS_PIPELINE);
		List<GhlPipelineClient.Opportunity> rows = ghl.opportunitiesIn(pipeline.id(), to.minusDays(29), to);

		System.out.printf("[live] %s -> %d rows over the last 30 days%n", ADS_PIPELINE, rows.size());

		// An empty month is a legitimate answer, so the shape is asserted only on what came back.
		assertThat(rows).allSatisfy((row) -> {
			assertThat(row.pipelineStageId()).describedAs("every row must name its stage").isNotBlank();
			// `monetaryValue` and `source` are both genuinely nullable in GHL — a deal with no
			// amount and a lead with no source both exist. Asserting they are non-null would fail
			// on real data, which is the sort of thing only a live run tells you.
			if (row.monetaryValue() != null) {
				assertThat(row.monetaryValue().signum()).isGreaterThanOrEqualTo(0);
			}
		});

		// Every row must belong to a stage this pipeline actually has — the check that the
		// pipeline_id filter is really being applied rather than silently ignored.
		List<String> stageIds = pipeline.stages().stream().map(GhlPipelineClient.Pipeline.Stage::id).toList();
		assertThat(rows).allSatisfy((row) -> assertThat(stageIds).contains(row.pipelineStageId()));
	}

	/**
	 * A name GHL does not have fails as a stated {@link GhlUnavailableException}, live.
	 *
	 * <p>The failure direction the whole client is built around: a misconfiguration must arrive as a
	 * 502 naming what to fix, not as an empty funnel that reads as a quiet month. Asserted against
	 * the real API because a stub proves only that our own code throws.
	 */
	@Test
	void aPipelineNameThatDoesNotExistFailsLoudly() {
		GhlPipelineClient ghl = client();

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> ghl.pipelineNamed("No Such Pipeline " + LOCATION))
				.isInstanceOf(GhlUnavailableException.class)
				// The configured name is echoed because it is the thing to correct; the other
				// pipelines in the location are deliberately NOT listed.
				.hasMessageContaining("No Such Pipeline");
	}
}
