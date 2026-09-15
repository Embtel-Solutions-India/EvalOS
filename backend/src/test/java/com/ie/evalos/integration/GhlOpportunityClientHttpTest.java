package com.ie.evalos.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link GhlOpportunityClient} actually puts on the wire, against a real socket.
 *
 * <p><strong>This exists because getting these parameter names wrong is a live 422 and nothing
 * else.</strong> `GhlPipelineClient` shipped a camelCase guess for `pipeline_stage_id` that looked
 * well-evidenced and was not — the spelling had been checked through a tool that normalises
 * parameter names before sending, so what was tested was never what the client sends. A local
 * server is the cheapest thing that sees the literal query string.
 *
 * <p>Modelled on {@code GhlPipelineClientHttpTest}, deliberately: same harness, same reason.
 */
class GhlOpportunityClientHttpTest {

	private static final String TOKEN = "pit-test-token-not-a-real-one";
	private static final String LOCATION = "WY6bW2xUCI8Tz8gw7aLJ";
	private static final String PIPELINE = "tj2agZ90S1LQgCpDAoKi";

	private HttpServer server;

	/** Every request the client made, as "path?query", in order. */
	private final List<String> requestLines = new ArrayList<>();

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

		byte[] body = (responses.isEmpty() ? "{}" : responses.removeFirst()).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private GhlOpportunityClient client() {
		return new GhlOpportunityClient(new GhlHttp("http://127.0.0.1:" + server.getAddress().getPort(),
				"2021-07-28", TOKEN, LOCATION, Duration.ofSeconds(5)));
	}

	/** GHL's real row shape, including the contact block this client deliberately does not bind. */
	private static String opportunity(String id, String stageId, String monetaryValue) {
		return """
				{"id":"%s","name":"Test Person","monetaryValue":%s,
				 "pipelineId":"tj2agZ90S1LQgCpDAoKi","pipelineStageId":"%s",
				 "assignedTo":null,"status":"open",
				 "createdAt":"2026-05-05T18:09:32.849Z","updatedAt":"2026-06-01T10:00:00.000Z",
				 "contactId":"contact-%s","locationId":"WY6bW2xUCI8Tz8gw7aLJ","customFields":[],
				 "contact":{"id":"contact-%s","name":"Test Person",
				            "email":"test.person@example.invalid","phone":"+15550000000",
				            "tags":["es_lead"],"score":[]}}"""
				.formatted(id, monetaryValue, stageId, id, id);
	}

	private static String searchPage(String opportunities, String meta) {
		return "{\"opportunities\":[" + opportunities + "],\"meta\":" + meta + "}";
	}

	private static final String LAST_PAGE_META = """
			{"total":1,"nextPageUrl":null,"startAfterId":"contact-1","startAfter":1778004572849,
			 "currentPage":1}""";

	/**
	 * <strong>`location_id` and `pipeline_id` are snake_case.</strong> Verified against the live
	 * API for the sibling client; camelCase answers 422 "property locationId should not exist".
	 * Pinned here so the next person cannot "tidy" them into camelCase.
	 */
	@Test
	void sendsSnakeCaseLocationAndPipelineFilters() {
		responses.add(searchPage(opportunity("o1", "a-new", "1200"), LAST_PAGE_META));

		client().inPipeline(PIPELINE);

		assertThat(requestLines).singleElement().satisfies((line) -> {
			assertThat(line).startsWith("/opportunities/search?");
			assertThat(line).contains("location_id=" + LOCATION);
			assertThat(line).contains("pipeline_id=" + PIPELINE);
			assertThat(line).doesNotContain("locationId=");
			assertThat(line).doesNotContain("pipelineId=");
		});
	}

	/**
	 * <strong>No date window, unlike the funnel read.</strong> A funnel asks what was created in
	 * a period; a board asks what is on the desk, and a deal opened last quarter that is still
	 * open is still on the desk. A `date` filter here would hide exactly the stale work a board
	 * exists to surface — which is a silent wrong answer, not a visible one.
	 */
	@Test
	void sendsNoDateWindow() {
		responses.add(searchPage(opportunity("o1", "a-new", "1200"), LAST_PAGE_META));

		client().inPipeline(PIPELINE);

		assertThat(requestLines).singleElement().satisfies((line) -> {
			assertThat(line).doesNotContain("date=");
			assertThat(line).doesNotContain("endDate=");
		});
	}

	@Test
	void bindsTheFieldsABoardDraws() {
		responses.add(searchPage(opportunity("o1", "a-new", "1200"), LAST_PAGE_META));

		List<GhlOpportunityClient.BoardOpportunity> found = client().inPipeline(PIPELINE);

		assertThat(found).singleElement().satisfies((deal) -> {
			assertThat(deal.id()).isEqualTo("o1");
			assertThat(deal.name()).isEqualTo("Test Person");
			assertThat(deal.contactId()).isEqualTo("contact-o1");
			assertThat(deal.pipelineStageId()).isEqualTo("a-new");
			assertThat(deal.status()).isEqualTo("open");
			assertThat(deal.monetaryValue()).isEqualByComparingTo("1200");
			assertThat(deal.updatedAt()).isNotNull();
		});
	}

	/**
	 * <strong>The pipeline on the row comes from the request, not the response.</strong> It is
	 * the scope the caller asked for; reading it back off the payload would turn a mislabelled
	 * row from GHL into a mis-scoped row here.
	 */
	@Test
	void thePipelineOnEachRowIsTheOneThatWasAskedFor() {
		responses.add(searchPage(
				// GHL claims a different pipeline on the row than the one filtered for.
				opportunity("o1", "a-new", "1").replace("\"pipelineId\":\"tj2agZ90S1LQgCpDAoKi\"",
						"\"pipelineId\":\"somebody-elses\""),
				LAST_PAGE_META));

		assertThat(client().inPipeline(PIPELINE)).singleElement()
				.satisfies((deal) -> assertThat(deal.pipelineId()).isEqualTo(PIPELINE));
	}

	/** A short page is the last page; the cursor is only followed when a full page comes back. */
	@Test
	void aShortPageEndsTheCursorLoop() {
		responses.add(searchPage(opportunity("o1", "a-new", "1"), LAST_PAGE_META));

		client().inPipeline(PIPELINE);

		assertThat(requestLines).hasSize(1);
		assertThat(requestLines.get(0)).doesNotContain("startAfter");
	}

	/** An empty pipeline is an empty board, not a failure. */
	@Test
	void anEmptyPipelineIsAnEmptyList() {
		responses.add(searchPage("", LAST_PAGE_META));

		assertThat(client().inPipeline(PIPELINE)).isEmpty();
	}

	/** A response with no opportunities array at all must not NPE. */
	@Test
	void aResponseWithNoOpportunitiesArrayIsEmpty() {
		responses.add("{\"meta\":" + LAST_PAGE_META + "}");

		assertThat(client().inPipeline(PIPELINE)).isEmpty();
	}
}
