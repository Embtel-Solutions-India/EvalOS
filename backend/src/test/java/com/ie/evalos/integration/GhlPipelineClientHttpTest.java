package com.ie.evalos.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against a real HTTP server serving <strong>GHL's actual response shapes</strong>.
 *
 * <p>This is the closest thing to the acceptance criterion the unit spec leaves open ("not yet
 * exercised against live GHL"). It cannot prove the credential works, but it proves everything
 * between here and there: the URL and query-parameter <em>names</em>, the two headers, Jackson
 * binding GHL's real payloads into the narrow records, the pipeline-by-name lookup picking the
 * right one out of several, and the pagination cursor actually terminating.
 *
 * <p><strong>A JDK {@code HttpServer} on an ephemeral port, not a mocking library.</strong>
 * {@code MockRestServiceServer} binds to a {@code RestClient.Builder}, and this client builds its
 * own on purpose (it owns its timeouts and default headers) — so a real socket is both simpler and
 * a stronger test: it exercises the actual request the app would send.
 *
 * <p><strong>The fixtures keep GHL's field-for-field shape and carry no real customer data.</strong>
 * The contact blocks below are deliberately complete — {@code relations}, {@code contact},
 * {@code attributions} and all — because their presence is what proves
 * {@link GhlPipelineClient.Opportunity} ignores them rather than merely not asking. The names,
 * emails and phones in them are invented. Real ones were available while this was written and are
 * **not** committed: a test fixture is source control, and marketing PII does not belong there.
 */
class GhlPipelineClientHttpTest {

	private static final String TOKEN = "pit-test-token-not-a-real-one";
	private static final String LOCATION = "kBumF0uUOmMBB5bneYjx";
	private static final String ADS_PIPELINE = "g6lo50r9Wn0qZvmp2bMP";

	/** A fixed window, so the mm-dd-yyyy formatting is asserted rather than whatever today is. */
	private static final LocalDate FROM = LocalDate.of(2026, 7, 27);

	private static final LocalDate TO = LocalDate.of(2026, 8, 26);

	/**
	 * Two pipelines, because the lookup has to pick one <em>by name</em> out of an account that
	 * really does hold seven. Stages are listed out of position order for the same reason the
	 * service test does it — GHL's array order is not the display order.
	 */
	private static final String PIPELINES_JSON = """
			{"pipelines":[
			  {"id":"OohZ2b8GS37F5PQVFtNg","name":"Master Pipeline","showInFunnel":true,
			   "showInPieChart":true,"useOpportunityProbability":false,
			   "dateAdded":"2024-08-12T23:45:20.379Z","dateUpdated":"2026-07-30T06:11:59.805Z",
			   "stages":[{"id":"m-1","name":"New Lead","showInFunnel":true,"position":0,
			              "stageWinProbability":20}],
			   "locationId":"kBumF0uUOmMBB5bneYjx"},
			  {"id":"g6lo50r9Wn0qZvmp2bMP","name":"Google ADS Pipeline","showInFunnel":true,
			   "showInPieChart":true,"useOpportunityProbability":false,
			   "dateAdded":"2025-10-07T19:54:06.684Z","dateUpdated":"2026-04-23T22:32:53.205Z",
			   "stages":[
			     {"id":"s-lost","name":"Lost","showInFunnel":true,"showInPieChart":true,
			      "position":5,"stageWinProbability":85.71},
			     {"id":"s-new","name":"New Lead","showInFunnel":true,"showInPieChart":true,
			      "position":0,"stageWinProbability":14.29},
			     {"id":"s-warm","name":"Warm","showInFunnel":true,"showInPieChart":true,
			      "position":1,"stageWinProbability":28.57},
			     {"id":"s-hot","name":"Hot","showInFunnel":true,"showInPieChart":true,
			      "position":2,"stageWinProbability":42.86},
			     {"id":"s-won","name":"Won","showInFunnel":true,"showInPieChart":true,
			      "position":3,"stageWinProbability":57.14},
			     {"id":"s-cold","name":"Cold","showInFunnel":true,"showInPieChart":true,
			      "position":4,"stageWinProbability":71.43}],
			   "locationId":"kBumF0uUOmMBB5bneYjx"},
			  {"id":"tj2agZ90S1LQgCpDAoKi","name":"Aditya's  pipeline","showInFunnel":true,
			   "showInPieChart":true,"useOpportunityProbability":false,
			   "dateAdded":"2025-02-27T21:04:41.498Z","dateUpdated":"2026-08-13T00:14:58.176Z",
			   "stages":[{"id":"a-new","name":"New Lead","showInFunnel":true,"position":1,
			              "stageWinProbability":22.22}],
			   "locationId":"kBumF0uUOmMBB5bneYjx"}]}
			""";

	private HttpServer server;

	/** Every request the client made, as "path?query", in order. */
	private final List<String> requestLines = new ArrayList<>();

	/** The Authorization and Version headers of the first request. */
	private final List<String> authHeaders = new ArrayList<>();
	private final List<String> versionHeaders = new ArrayList<>();

	/** Bodies handed out in order, one per request. */
	private final Deque<String> responses = new ArrayDeque<>();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::respond);
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private void respond(HttpExchange exchange) throws IOException {
		requestLines.add(exchange.getRequestURI().getPath()
				+ (exchange.getRequestURI().getQuery() == null ? "" : "?" + exchange.getRequestURI().getQuery()));
		authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
		versionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Version")));

		byte[] body = (responses.isEmpty() ? "{}" : responses.removeFirst()).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private GhlPipelineClient client() {
		return new GhlPipelineClient("http://127.0.0.1:" + server.getAddress().getPort(), "2021-07-28", TOKEN,
				LOCATION, Duration.ofSeconds(5));
	}

	/** One opportunity with GHL's full row shape — the contact block included, values invented. */
	private static String opportunity(String stageId, String monetaryValue, String source) {
		return """
				{"id":"opp-%s","name":"Test Person","monetaryValue":%s,
				 "pipelineId":"g6lo50r9Wn0qZvmp2bMP","pipelineStageId":"%s",
				 "pipelineStageUId":"%s","assignedTo":null,"status":"open","source":%s,
				 "lastStatusChangeAt":"2026-05-05T18:09:32.849Z",
				 "lastStageChangeAt":"2026-05-05T18:09:32.849Z",
				 "createdAt":"2026-05-05T18:09:32.849Z","updatedAt":"2026-05-05T18:09:32.849Z",
				 "forecastProbability":null,"effectiveProbability":14.29,
				 "contactId":"contact-1","locationId":"kBumF0uUOmMBB5bneYjx","customFields":[],
				 "lostReasonId":null,"followers":[],
				 "relations":[{"associationId":"OPPORTUNITIES_CONTACTS_ASSOCIATION",
				               "relationId":"opp-1","primary":true,"objectKey":"contact",
				               "recordId":"contact-1","fullName":"Test Person",
				               "contactName":"Test Person","companyName":null,
				               "email":"test.person@example.invalid","phone":"+15550000000",
				               "tags":["es_lead"],"attributed":null}],
				 "contact":{"id":"contact-1","name":"Test Person","companyName":null,
				            "email":"test.person@example.invalid","phone":"+15550000000",
				            "tags":["es_lead"],"score":[]},
				 "sort":[1778004572849,"contact-1"],
				 "attributions":[{"utmSessionSource":"CRM Workflows","medium":"Manual",
				                  "isFirst":true}]}"""
				.formatted(stageId, monetaryValue, stageId, stageId,
						source == null ? "null" : "\"" + source + "\"");
	}

	private static String searchPage(String opportunities, String meta) {
		return "{\"opportunities\":[" + opportunities + "],\"meta\":" + meta + ",\"traceId\":\"t-1\"}";
	}

	/** GHL's own last-page meta: it still carries a cursor, so the short page is what stops us. */
	private static final String LAST_PAGE_META = """
			{"total":2,"nextPageUrl":null,"startAfterId":"contact-1","startAfter":1778004572849,
			 "currentPage":1,"nextPage":null,"prevPage":null}""";

	@Test
	void findsThePipelineByNameAndOrdersItsStagesByPosition() {
		responses.add(PIPELINES_JSON);

		GhlPipelineClient.Pipeline pipeline = client().pipelineNamed("Google ADS Pipeline");

		assertThat(pipeline.id()).isEqualTo(ADS_PIPELINE);
		// Bound off the real shape: six stages, and `position` survived even though GHL listed
		// them out of order and wrapped them in fields this record does not have.
		assertThat(pipeline.stages()).hasSize(6);
		assertThat(pipeline.stages().stream()
				.sorted(java.util.Comparator.comparingInt(GhlPipelineClient.Pipeline.Stage::position))
				.map(GhlPipelineClient.Pipeline.Stage::name)
				.toList())
				.containsExactly("New Lead", "Warm", "Hot", "Won", "Cold", "Lost");
	}

	@Test
	void sendsTheTokenAndTheVersionHeaderGhlRequires() {
		responses.add(PIPELINES_JSON);

		client().pipelineNamed("Google ADS Pipeline");

		assertThat(authHeaders).containsExactly("Bearer " + TOKEN);
		// GHL versions its public API by header, not by path. Without this it answers 4xx.
		assertThat(versionHeaders).containsExactly("2021-07-28");
	}

	/**
	 * <strong>The two endpoints take different parameter conventions, and both are pinned here.</strong>
	 *
	 * <p>This looks like an inconsistency somebody should tidy up. It is not — GHL validates the
	 * two routes with different DTOs, and each rejects the other's spelling with a 422:
	 *
	 * <pre>
	 *   /opportunities/pipelines : camelCase  locationId
	 *                             snake_case -> 422 COMMON_LOCATION_ID_UNDEFINED
	 *   /opportunities/search    : snake_case location_id, pipeline_id
	 *                             camelCase  -> 422 "property locationId should not exist"
	 * </pre>
	 *
	 * <p>Confirmed against the live API, and it had to be: GHL's published operation registry
	 * lists the search parameters as <em>camelCase</em> and omits the location parameter entirely,
	 * and its own {@code nextPageUrl} spells them camelCase too. Every source of documentation
	 * pointed the wrong way, so only a real call settled it — which is why this assertion exists
	 * rather than a comment.
	 *
	 * <p>The cursor parameters stay camelCase ({@code startAfter}/{@code startAfterId}) because
	 * that is what the registry and {@code nextPageUrl} agree on and GHL's 422 did not object to
	 * them. <strong>That half is still unverified against the live API</strong>: it only matters
	 * above 100 opportunities and this account holds 93, so no second page has ever been fetched.
	 */
	@Test
	void addressesEachEndpointWithTheParameterConventionItActuallyAccepts() {
		responses.add(PIPELINES_JSON);
		responses.add(searchPage(opportunity("s-new", "1000", "Call Back Form-----ADS"), LAST_PAGE_META));

		client().pipelineNamed("Google ADS Pipeline");
		client().opportunitiesIn(ADS_PIPELINE, FROM, TO);

		assertThat(requestLines).hasSize(2);

		// Pipelines: camelCase, and NOT snake_case.
		assertThat(requestLines.get(0))
				.startsWith("/opportunities/pipelines?")
				.contains("locationId=" + LOCATION)
				.doesNotContain("location_id=");

		// Search: snake_case for location/pipeline, and NOT camelCase.
		assertThat(requestLines.get(1))
				.startsWith("/opportunities/search?")
				.contains("location_id=" + LOCATION)
				.contains("pipeline_id=" + ADS_PIPELINE)
				.contains("limit=100")
				.doesNotContain("locationId=")
				.doesNotContain("pipelineId=");

		// **The date window is camelCase, on the SAME endpoint that demands snake_case above.**
		// GHL really is inconsistent within one route: `start_date`/`end_date` answer 422
		// "property start_date should not exist". Format is mm-dd-yyyy and nothing else — a
		// wrong format is silently UNFILTERED rather than refused, which would show every deal
		// under a one-day window and look like a data bug rather than a formatting one.
		assertThat(requestLines.get(1))
				.contains("date=07-27-2026")
				.contains("endDate=08-26-2026")
				.doesNotContain("start_date=")
				.doesNotContain("end_date=");
	}

	/**
	 * <strong>A stage is counted in one request, not one per hundred deals.</strong>
	 *
	 * <p>This pins the fix for a live failure: counting by pagination cost 115 sequential requests
	 * on the email marketing pipeline's year (11,432 opportunities) and the browser timed out at
	 * 15s. GHL reports the match count in {@code meta.total} on any search, so a one-row request
	 * with the stage filter applied returns the exact figure immediately.
	 *
	 * <p><strong>The parameter name is asserted because getting it wrong shipped a 422.</strong>
	 * It is {@code pipeline_stage_id}, snake_case, matching {@code location_id} and
	 * {@code pipeline_id} beside it and not the camelCase {@code date}/{@code endDate} on the same
	 * route. The camelCase spelling was shipped first on the strength of a check made through a
	 * tool that normalises parameter names before sending — so the evidence was for a request the
	 * app never makes. GHL's answer was {@code 422 "property pipelineStageId should not exist"}.
	 * This is the assertion that stops that coming back.
	 */
	@Test
	void countsAStageFromGhlsMatchTotalWithoutPagingTheRows() {
		responses.add("""
				{"opportunities":[],"meta":{"total":11364,"startAfter":null,"startAfterId":null}}
				""");

		assertThat(client().countIn(ADS_PIPELINE, "s-new", FROM, TO)).isEqualTo(11364);

		// One request, whatever the count says. That is the whole point.
		assertThat(requestLines).hasSize(1);
		assertThat(requestLines.getFirst())
				.startsWith("/opportunities/search?")
				.contains("pipeline_stage_id=s-new")
				.doesNotContain("pipelineStageId=")
				// One row, because GHL does not accept a zero limit and the count is in the meta
				// block regardless.
				.contains("limit=1")
				// Still the same window, and still the same two snake_case names beside it.
				.contains("location_id=" + LOCATION)
				.contains("pipeline_id=" + ADS_PIPELINE)
				.contains("date=07-27-2026")
				.contains("endDate=08-26-2026");
	}

	/** An empty window returns meta with no total at all, which is zero rather than a failure. */
	@Test
	void treatsAMissingTotalAsZeroRatherThanFailing() {
		responses.add("""
				{"opportunities":[],"meta":{"total":null,"startAfter":null,"startAfterId":null}}
				""");

		assertThat(client().countIn(ADS_PIPELINE, "s-warm", FROM, TO)).isZero();
	}

	@Test
	void bindsOnlyTheThreeFieldsItNeedsOutOfGhlsFullRow() {
		responses.add(searchPage(
				opportunity("s-new", "1000", "Call Back Form-----ADS") + ","
						+ opportunity("s-warm", "null", null),
				LAST_PAGE_META));

		List<GhlPipelineClient.Opportunity> found = client().opportunitiesIn(ADS_PIPELINE, FROM, TO);

		assertThat(found).hasSize(2);
		assertThat(found.getFirst().pipelineStageId()).isEqualTo("s-new");
		assertThat(found.getFirst().monetaryValue()).isEqualByComparingTo(new BigDecimal("1000"));
		assertThat(found.getFirst().source()).isEqualTo("Call Back Form-----ADS");
		// GHL really does send both of these as null on rows nobody priced or attributed. The
		// service turns the money into zero; the client's job is only to not fall over.
		assertThat(found.get(1).monetaryValue()).isNull();
		assertThat(found.get(1).source()).isNull();
	}

	/**
	 * A full page means "ask again"; a short page means stop.
	 *
	 * <p>The cursor comes off {@code meta}, and the assertion is that the <em>second</em> request
	 * carries it — a pagination loop that silently re-reads page one is the failure that looks
	 * like success right up to the point the numbers are wrong.
	 */
	@Test
	void followsTheCursorUntilAShortPageEndsIt() {
		String fullPage = IntStream.range(0, 100)
				.mapToObj((i) -> opportunity("s-warm", "100", "Google Ads"))
				.collect(Collectors.joining(","));
		responses.add(searchPage(fullPage, """
				{"total":101,"startAfterId":"contact-99","startAfter":1778004500000,
				 "currentPage":1,"nextPage":2}"""));
		responses.add(searchPage(opportunity("s-won", "1200", "Google Ads"), LAST_PAGE_META));

		List<GhlPipelineClient.Opportunity> found = client().opportunitiesIn(ADS_PIPELINE, FROM, TO);

		assertThat(found).hasSize(101);
		assertThat(requestLines).hasSize(2);
		assertThat(requestLines.get(0)).doesNotContain("startAfter");
		assertThat(requestLines.get(1))
				.contains("startAfter=1778004500000")
				.contains("startAfterId=contact-99");
	}

	/** A single short page asks once. The obvious case, asserted so a regression cannot hide. */
	@Test
	void doesNotAskASecondTimeWhenTheFirstPageIsShort() {
		responses.add(searchPage(opportunity("s-new", "1000", "Google Ads"), LAST_PAGE_META));

		assertThat(client().opportunitiesIn(ADS_PIPELINE, FROM, TO)).hasSize(1);
		assertThat(requestLines).hasSize(1);
	}

	/**
	 * A pipeline name GHL does not have is a stated 502, not an empty funnel.
	 *
	 * <p>The failing direction matters: an empty funnel looks like a bad month, and somebody would
	 * act on it. The message names the configured pipeline (which came from this environment) and
	 * <strong>not</strong> the other pipelines in the account — those are other teams' funnels.
	 */
	/**
	 * <strong>The live sales pipeline is named {@code Aditya's··pipeline} — with two spaces.</strong>
	 *
	 * <p>Pinned with the fixture holding the real double-space name and the lookup passing the
	 * single-space name a human would actually type into configuration. Revert the normalisation in
	 * {@code pipelineNamed} and this fails, which is the point: the bug it prevents is a 502 whose
	 * cause is one invisible character, identical-looking in the config file and in GHL's UI, and
	 * the tempting "fix" is to paste the second space into three yml files and trust that no editor,
	 * shell or reviewer ever strips it.
	 *
	 * <p>The trailing-space case is asserted alongside because it is the same class of accident from
	 * the other direction — a copy-paste out of GHL's UI picks one up, and nothing shows it.
	 */
	@Test
	void matchesAPipelineWhoseGhlNameCarriesWhitespaceNobodyWouldRetype() {
		responses.add(PIPELINES_JSON);
		responses.add(PIPELINES_JSON);

		assertThat(client().pipelineNamed("Aditya's pipeline").id()).isEqualTo("tj2agZ90S1LQgCpDAoKi");
		assertThat(client().pipelineNamed("  Google ADS Pipeline ").id()).isEqualTo(ADS_PIPELINE);
	}

	/**
	 * Whitespace is forgiven; <strong>a real character is not.</strong>
	 *
	 * <p>The companion to the test above, and the reason it is a separate assertion rather than a
	 * line inside it: normalisation that drifted into stripping punctuation or fuzzy-matching would
	 * keep that test green while quietly making a rename in GHL invisible over here — which is the
	 * one thing matching by name exists to surface.
	 */
	@Test
	void stillRefusesANameThatDiffersByMoreThanWhitespace() {
		responses.add(PIPELINES_JSON);

		assertThatThrownBy(() -> client().pipelineNamed("Adityas pipeline"))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("Adityas pipeline");
	}

	@Test
	void aMisconfiguredPipelineNameFailsLoudlyWithoutNamingTheOthers() {
		responses.add(PIPELINES_JSON);

		assertThatThrownBy(() -> client().pipelineNamed("Facebook Ads Pipeline"))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("Facebook Ads Pipeline")
				.hasMessageNotContaining("Master Pipeline");
	}

	/** GHL refusing the request is a 502 the caller can retry, never a 500. */
	@Test
	void anErrorStatusFromGhlBecomesAnUpstreamFault() {
		server.removeContext("/");
		server.createContext("/", (exchange) -> {
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
		});

		assertThatThrownBy(() -> client().pipelineNamed("Google ADS Pipeline"))
				.isInstanceOf(GhlUnavailableException.class)
				// **The status is in the message**, so a 401 (grant missing the scope) is
				// distinguishable from a 404 (wrong location) and from a timeout. The first live
				// attempt was spent guessing between those three, which is what this asserts
				// against happening again.
				.hasMessageContaining("401")
				// The token is a default header, so it cannot leak into a message here even
				// though the failure is about authentication.
				.hasMessageNotContaining(TOKEN);
	}
}
