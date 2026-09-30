package com.ie.evalos.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /contacts/{id}} bound off the live shape (2026-09-30, values invented): the deal
 * screen's extras — country, tags, custom fields — and still none of the rest.
 */
class GhlContactClientHttpTest {

	private static final String CONTACT_JSON = """
			{"contact":{"id":"c-1","firstName":"Test","lastName":"Person",
			 "email":"test.person@example.invalid","phone":"+15550000000","companyName":null,
			 "country":"US","type":"lead","tags":["stage:hot"],
			 "attributionSource":{"sessionSource":"CRM UI","medium":"manual","mediumId":null},
			 "customFields":[{"id":"f_applicant","value":"Individual Applicant"},{"id":"f_blank","value":""}],
			 "additionalEmails":[],"dnd":false}}""";

	private HttpServer server;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", (exchange) -> {
			byte[] body = CONTACT_JSON.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void bindsCountryTagsAndCustomFieldValues() {
		GhlContactClient client = new GhlContactClient(new GhlHttp(
				"http://127.0.0.1:" + server.getAddress().getPort(), "2021-07-28", "token", "loc",
				Duration.ofSeconds(5)));

		GhlContactClient.Contact contact = client.byId("c-1");

		assertThat(contact.name()).isEqualTo("Test Person");
		assertThat(contact.country()).isEqualTo("US");
		assertThat(contact.tags()).containsExactly("stage:hot");
		// A contact item's value is under `value`, not an opportunity's `fieldValue`; a blank one
		// is not a value.
		assertThat(contact.customFields()).containsExactly(
				java.util.Map.entry("f_applicant", "Individual Applicant"));
	}
}
