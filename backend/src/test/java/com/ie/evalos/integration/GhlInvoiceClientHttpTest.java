package com.ie.evalos.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the invoice read puts on the wire, and what it says when the grant is missing.
 *
 * <p>{@link #aRefusedReadNamesTheMissingScope} is the one that will save an afternoon: every
 * other GHL screen in EvalOS works on the current token, so a 401 here reads as "the token is
 * broken" when it actually means "the token is missing one scope".
 */
class GhlInvoiceClientHttpTest {

	private static final String LOCATION = "WY6bW2xUCI8Tz8gw7aLJ";
	private static final String CONTACT = "contact_1";

	private HttpServer server;
	private final List<String> requestLines = new ArrayList<>();
	private volatile int status = 200;
	private volatile String body = "{}";

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
		byte[] payload = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, payload.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(payload);
		}
	}

	private GhlInvoiceClient client() {
		return new GhlInvoiceClient(new GhlHttp("http://127.0.0.1:" + server.getAddress().getPort(),
				"2021-07-28", "pit-test-token", LOCATION, Duration.ofSeconds(5)));
	}

	/**
	 * <strong>The invoice API addresses the sub-account with {@code altId}/{@code altType}</strong>,
	 * not with {@code locationId} like the opportunity endpoints. Pinned because getting it wrong
	 * is a 422 and nothing else — the same class of mistake that cost this codebase a live
	 * afternoon on {@code pipeline_stage_id}.
	 */
	@Test
	void addressesTheLocationWithAltIdAndFiltersByContact() {
		body = "{\"invoices\":[],\"total\":0}";

		client().forContact(CONTACT);

		assertThat(requestLines).singleElement().satisfies((line) -> {
			assertThat(line).startsWith("/invoices/?");
			assertThat(line).contains("altId=" + LOCATION);
			assertThat(line).contains("altType=location");
			assertThat(line).contains("contactId=" + CONTACT);
			assertThat(line).doesNotContain("locationId=");
		});
	}

	@Test
	void bindsWhatAClientNeedsToSee() {
		body = "{\"invoices\":[{\"invoiceNumber\":\"INV-014\",\"status\":\"partially_paid\","
				+ "\"total\":1200,\"amountPaid\":400,\"amountDue\":800,\"currency\":\"USD\","
				+ "\"issueDate\":\"2026-09-01\",\"dueDate\":\"2026-09-15\","
				+ "\"_id\":\"internal\",\"invoiceItems\":[{\"name\":\"line\"}]}],\"total\":1}";

		List<GhlInvoiceClient.ClientInvoice> invoices = client().forContact(CONTACT);

		assertThat(invoices).singleElement().satisfies((invoice) -> {
			assertThat(invoice.invoiceNumber()).isEqualTo("INV-014");
			assertThat(invoice.status()).isEqualTo("partially_paid");
			assertThat(invoice.total()).isEqualByComparingTo("1200");
			assertThat(invoice.amountPaid()).isEqualByComparingTo("400");
			assertThat(invoice.amountDue()).isEqualByComparingTo("800");
			assertThat(invoice.currency()).isEqualTo("USD");
		});
	}

	/** Several invoices for one client, which is the case the portal screen exists for. */
	@Test
	void returnsEveryInvoiceForTheContact() {
		body = "{\"invoices\":["
				+ "{\"invoiceNumber\":\"INV-1\",\"status\":\"paid\",\"total\":100},"
				+ "{\"invoiceNumber\":\"INV-2\",\"status\":\"unpaid\",\"total\":200}],\"total\":2}";

		assertThat(client().forContact(CONTACT))
				.extracting(GhlInvoiceClient.ClientInvoice::invoiceNumber)
				.containsExactly("INV-1", "INV-2");
	}

	@Test
	void aClientWithNoInvoicesGetsAnEmptyListRatherThanAFailure() {
		body = "{\"total\":0}";

		assertThat(client().forContact(CONTACT)).isEmpty();
	}

	/**
	 * <strong>A 401 says which grant is missing.</strong>
	 *
	 * <p>Every other GHL-backed screen works on the current token, so the first assumption on
	 * seeing a 401 here is that the token is wrong. It is not — it is missing one scope that
	 * nothing else in EvalOS uses.
	 */
	@Test
	void aRefusedReadNamesTheMissingScope() {
		status = 401;
		body = "{\"message\":\"scope not authorized\"}";

		assertThatThrownBy(() -> client().forContact(CONTACT))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("401")
				.hasMessageContaining(GhlInvoiceClient.REQUIRED_SCOPE)
				.hasMessageContaining("before suspecting the token");
	}

	/**
	 * A 500 is not decorated with a scope hint.
	 *
	 * <p>A guard that fires on everything teaches the reader to ignore it. Only the statuses
	 * that actually mean "not permitted" carry the grant advice; anything else means something
	 * different and should not send the next reader down this path.
	 */
	@Test
	void anUpstreamFaultIsNotBlamedOnTheScope() {
		status = 500;

		assertThatThrownBy(() -> client().forContact(CONTACT))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("500")
				.hasMessageNotContaining(GhlInvoiceClient.REQUIRED_SCOPE);
	}

	/** An unconfigured environment answers 502 naming both variables, and sends nothing. */
	@Test
	void anUnconfiguredEnvironmentReadsNothing() {
		GhlInvoiceClient client = new GhlInvoiceClient(
				new GhlHttp("http://127.0.0.1:1", "2021-07-28", "", "", Duration.ofMillis(200)));

		assertThatThrownBy(() -> client.forContact(CONTACT))
				.isInstanceOf(GhlUnavailableException.class)
				.hasMessageContaining("GHL_API_TOKEN");

		assertThat(requestLines).isEmpty();
	}
}
