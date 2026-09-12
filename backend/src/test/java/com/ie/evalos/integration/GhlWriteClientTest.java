package com.ie.evalos.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.service.AuditService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What EvalOS's first writes to GHL actually put on the wire, and that each leaves an audit row.
 *
 * <p>A real socket rather than a mocked {@code RestClient}, for the reason
 * {@code GhlPipelineClientHttpTest} gives: a tool that normalises what it sends is a tool that
 * tests something other than what ships. This sees the literal path and the literal body.
 */
class GhlWriteClientTest {

	private static final String LOCATION = "kBumF0uUOmMBB5bneYjx";
	private static final String PIPELINE = "tj2agZ90S1LQgCpDAoKi";

	private HttpServer server;

	private final List<String> paths = new ArrayList<>();
	private final List<String> bodies = new ArrayList<>();
	private final Deque<String> responses = new ArrayDeque<>();
	private final AuditService audit = mock(AuditService.class);

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
		paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
		bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

		byte[] body = (responses.isEmpty() ? "{}" : responses.removeFirst()).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private GhlWriteClient client() {
		return new GhlWriteClient(new GhlHttp("http://127.0.0.1:" + server.getAddress().getPort(),
				"2021-07-28", "pit-test-token", LOCATION, Duration.ofSeconds(5)), audit);
	}

	/**
	 * <strong>Creates go to /upsert, never to the plain create route.</strong>
	 *
	 * <p>GHL offers no idempotency key, so upsert is the only thing standing between a
	 * double-submit and two contacts. A "tidy-up" that swapped this for {@code POST /contacts/}
	 * would look equivalent and silently remove that protection.
	 */
	@Test
	void aContactIsUpsertedNotCreated() {
		responses.add("{\"contact\":{\"id\":\"c1\",\"contactName\":\"Ada Lovelace\","
				+ "\"email\":\"ada@example.test\",\"phone\":null}}");

		GhlWriteClient.UpsertedContact contact = client().upsertContact("Ada", "Lovelace",
				"ada@example.test", null);

		assertThat(paths).containsExactly("POST /contacts/upsert");
		assertThat(bodies.get(0)).contains("\"locationId\":\"" + LOCATION + "\"");
		assertThat(bodies.get(0)).contains("\"email\":\"ada@example.test\"");
		// Absent fields are omitted rather than sent as null: a null phone on an upsert is an
		// instruction to blank the phone, which is not what "the marketer left it empty" means.
		assertThat(bodies.get(0)).doesNotContain("\"phone\"");
		assertThat(contact.id()).isEqualTo("c1");
	}

	@Test
	void anOpportunityIsUpsertedOnTheGivenPipeline() {
		responses.add("{\"opportunity\":{\"id\":\"o1\",\"name\":\"Ada\",\"contactId\":\"c1\","
				+ "\"pipelineStageId\":\"s1\",\"status\":\"open\",\"monetaryValue\":500},\"new\":true}");

		GhlWriteClient.UpsertedOpportunity opportunity = client().upsertOpportunity(PIPELINE, "c1", "Ada",
				new BigDecimal("500"));

		assertThat(paths).containsExactly("POST /opportunities/upsert");
		assertThat(bodies.get(0)).contains("\"pipelineId\":\"" + PIPELINE + "\"");
		assertThat(bodies.get(0)).contains("\"contactId\":\"c1\"");
		assertThat(bodies.get(0)).contains("\"status\":\"open\"");
		assertThat(opportunity.isNew()).isTrue();
		assertThat(opportunity.monetaryValue()).isEqualByComparingTo("500");
		// The pipeline on the result is the one asked for, not one read back off the payload.
		assertThat(opportunity.pipelineId()).isEqualTo(PIPELINE);
	}

	/** GHL's `new` flag is what distinguishes CREATED from UPDATED in the trail. */
	@Test
	void aMatchedOpportunityIsAuditedAsUpdatedNotCreated() {
		responses.add("{\"opportunity\":{\"id\":\"o1\",\"name\":\"Ada\",\"contactId\":\"c1\","
				+ "\"pipelineStageId\":\"s1\",\"status\":\"open\",\"monetaryValue\":null},\"new\":false}");

		client().upsertOpportunity(PIPELINE, "c1", "Ada", null);

		verify(audit).recordEvent(eq("GHL_OPPORTUNITY"), any(UUID.class), eq(AuditAction.UPDATED),
				any(), any(), any());
	}

	@Test
	void aNewOpportunityIsAuditedAsCreated() {
		responses.add("{\"opportunity\":{\"id\":\"o1\",\"name\":\"Ada\",\"contactId\":\"c1\","
				+ "\"pipelineStageId\":\"s1\",\"status\":\"open\",\"monetaryValue\":null},\"new\":true}");

		client().upsertOpportunity(PIPELINE, "c1", "Ada", null);

		verify(audit).recordEvent(eq("GHL_OPPORTUNITY"), any(UUID.class), eq(AuditAction.CREATED),
				any(), any(), any());
	}

	/**
	 * <strong>The audit row carries GHL's real id, and its object_id is stable.</strong>
	 *
	 * <p>{@code audit_event.object_id} is a UUID and a GHL id is a string, so the key is derived.
	 * Stability is what makes {@code idx_audit_object} still answer "this deal's history" — two
	 * writes to one opportunity must land on one key.
	 */
	@Test
	void theAuditKeyIsStableAcrossWritesToTheSameOpportunity() {
		responses.add("{\"opportunity\":{\"id\":\"o1\",\"name\":\"A\",\"contactId\":\"c1\","
				+ "\"pipelineStageId\":\"s1\",\"status\":\"open\",\"monetaryValue\":null},\"new\":true}");
		responses.add("{\"opportunity\":{\"id\":\"o1\",\"name\":\"B\",\"contactId\":\"c1\","
				+ "\"pipelineStageId\":\"s1\",\"status\":\"open\",\"monetaryValue\":null}}");

		GhlWriteClient client = client();
		client.upsertOpportunity(PIPELINE, "c1", "A", null);
		client.updateOpportunity("o1", PIPELINE, "B", null, null);

		ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
		verify(audit, times(2)).recordEvent(eq("GHL_OPPORTUNITY"), keys.capture(),
				any(), any(), any(), any());
		assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
	}

	/** A contact and an opportunity whose ids collide as strings do not collide as audit keys. */
	@Test
	void contactAndOpportunityAuditKeysDoNotCollide() {
		responses.add("{\"contact\":{\"id\":\"same\",\"contactName\":\"A\",\"email\":\"a@b.test\"}}");
		responses.add("{\"opportunity\":{\"id\":\"same\",\"name\":\"A\",\"contactId\":\"same\","
				+ "\"pipelineStageId\":\"s1\",\"status\":\"open\",\"monetaryValue\":null},\"new\":true}");

		GhlWriteClient client = client();
		client.upsertContact("A", null, "a@b.test", null);
		client.upsertOpportunity(PIPELINE, "same", "A", null);

		ArgumentCaptor<UUID> contactKey = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<UUID> opportunityKey = ArgumentCaptor.forClass(UUID.class);
		verify(audit).recordEvent(eq("GHL_CONTACT"), contactKey.capture(), any(), any(), any(), any());
		verify(audit).recordEvent(eq("GHL_OPPORTUNITY"), opportunityKey.capture(), any(), any(), any(),
				any());
		assertThat(contactKey.getValue()).isNotEqualTo(opportunityKey.getValue());
	}

	@Test
	void updatingAnOpportunityUsesPutOnItsOwnId() {
		responses.add("{\"opportunity\":{\"id\":\"o1\",\"name\":\"Renamed\",\"contactId\":\"c1\","
				+ "\"pipelineStageId\":\"s1\",\"status\":\"open\",\"monetaryValue\":2500}}");

		client().updateOpportunity("o1", PIPELINE, "Renamed", new BigDecimal("2500"), null);

		assertThat(paths).containsExactly("PUT /opportunities/o1");
		assertThat(bodies.get(0)).contains("\"monetaryValue\":2500");
	}

	// --- Unit 40: the sales desk's writes ---------------------------------------

	/**
	 * <strong>A pipeline move is an update in place, and the id survives it.</strong>
	 *
	 * <p>This was Unit 40's gating check. {@code PUT /opportunities/{id}} accepts a
	 * {@code pipelineId}, so GHL treats the pipeline as a mutable field rather than as identity
	 * — which is why the note stream survives the marketing-to-sales handoff with no migration
	 * step. If GHL had minted a new opportunity instead, notes would not follow and Unit 40
	 * would have needed one.
	 */
	@Test
	void movingAStageIsAnUpdateOnTheSameOpportunityId() {
		responses.add("{\"opportunity\":{\"id\":\"o1\",\"name\":\"Acme\",\"contactId\":\"c1\","
				+ "\"pipelineStageId\":\"s2\",\"status\":\"open\",\"monetaryValue\":1200}}");

		GhlWriteClient.UpsertedOpportunity moved = client().moveStage("o1", PIPELINE, "s2");

		assertThat(paths).containsExactly("PUT /opportunities/o1");
		assertThat(bodies.get(0)).contains("\"pipelineStageId\":\"s2\"");
		assertThat(moved.id()).isEqualTo("o1");
		assertThat(moved.stageId()).isEqualTo("s2");
	}

	@Test
	void closingADealPutsItsStatusAndAuditsTheChange() {
		responses.add("{\"opportunity\":{\"id\":\"o1\",\"name\":\"Acme\",\"contactId\":\"c1\","
				+ "\"pipelineStageId\":\"s2\",\"status\":\"won\",\"monetaryValue\":1200}}");

		GhlWriteClient.UpsertedOpportunity closed = client().setStatus("o1", PIPELINE, "won");

		assertThat(paths).containsExactly("PUT /opportunities/o1/status");
		assertThat(bodies.get(0)).contains("\"status\":\"won\"");
		assertThat(closed.status()).isEqualTo("won");
		verify(audit).recordEvent(eq("GHL_OPPORTUNITY"), any(UUID.class), eq(AuditAction.STAGE_CHANGED),
				any(), any(), any());
	}

	/**
	 * A follow-up is a GHL task on the contact, and it needs no scope beyond
	 * {@code contacts.write} — which is why it shipped while meetings did not.
	 */
	@Test
	void aFollowUpIsAGhlTaskOnTheContact() {
		responses.add("{\"task\":{\"id\":\"t1\",\"title\":\"Call back\","
				+ "\"dueDate\":\"2026-09-18T09:00:00Z\"}}");

		String taskId = client().createFollowUp("c1", "o1", PIPELINE, "Call back",
				"2026-09-18T09:00:00Z");

		assertThat(paths).containsExactly("POST /contacts/c1/tasks");
		assertThat(bodies.get(0)).contains("\"title\":\"Call back\"");
		assertThat(bodies.get(0)).contains("\"dueDate\":\"2026-09-18T09:00:00Z\"");
		assertThat(taskId).isEqualTo("t1");
	}

	/**
	 * The follow-up is audited against the <em>opportunity</em>, not the contact.
	 *
	 * <p>The task hangs off the contact because that is where GHL puts tasks, but the thing a
	 * salesperson is chasing is the deal — and an audit trail keyed on the contact would scatter
	 * one deal's chases across every deal that client has ever had.
	 */
	@Test
	void aFollowUpIsAuditedAgainstTheDealNotTheContact() {
		responses.add("{\"task\":{\"id\":\"t1\",\"title\":\"Call back\",\"dueDate\":\"x\"}}");

		client().createFollowUp("c1", "o1", PIPELINE, "Call back", "2026-09-18T09:00:00Z");

		verify(audit).recordEvent(eq("GHL_OPPORTUNITY"), any(UUID.class), eq(AuditAction.CHASED),
				any(), any(), any());
		verify(audit, never()).recordEvent(eq("GHL_CONTACT"), any(), any(), any(), any(), any());
	}

	/**
	 * <strong>A write GHL accepted but answered emptily is a 502, not a silent success.</strong>
	 *
	 * <p>The alternative is returning a record full of nulls and letting the caller record a row
	 * naming no opportunity — the one outcome worse than the write having failed, because it
	 * looks like it worked.
	 */
	@Test
	void anEmptyResponseBodyIsAFailureNotAnEmptyResult() {
		responses.add("{}");

		assertThatThrownBy(() -> client().upsertOpportunity(PIPELINE, "c1", "Ada", null))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("returned no opportunity");
	}

	/** An unconfigured environment writes nothing at all, and says which variables are missing. */
	@Test
	void anUnconfiguredEnvironmentWritesNothing() {
		GhlWriteClient client = new GhlWriteClient(
				new GhlHttp("http://127.0.0.1:1", "2021-07-28", "", "", Duration.ofMillis(200)), audit);

		assertThatThrownBy(() -> client.upsertContact("Ada", null, "ada@example.test", null))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("GHL_API_TOKEN");

		assertThat(paths).isEmpty();
	}
}
