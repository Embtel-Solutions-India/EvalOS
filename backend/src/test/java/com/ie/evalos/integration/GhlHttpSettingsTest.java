package com.ie.evalos.integration;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ie.evalos.config.AppSettings;
import com.ie.evalos.config.Setting;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * D83: the GHL credential, location and writes switch the Administrator saves take effect on the next call, on
 * the same {@link GhlHttp} — no restart, and no second instance that would split the per-location pacer.
 */
class GhlHttpSettingsTest {

	private HttpServer server;
	private final List<String> seen = new ArrayList<>();
	private final AppSettings settings = mock(AppSettings.class);
	private GhlHttp http;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::respond);
		server.start();
		http = new GhlHttp("http://127.0.0.1:" + server.getAddress().getPort(), "2021-07-28", "env-token",
				"env-location", Duration.ofSeconds(5));
		given(settings.app(Setting.GHL_TOKEN)).willReturn(Optional.empty());
		given(settings.app(Setting.GHL_LOCATION_ID)).willReturn(Optional.empty());
		given(settings.enabled(Setting.GHL_WRITES_ENABLED)).willReturn(true);
		http.useSettings(settings);
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private void respond(HttpExchange exchange) throws IOException {
		seen.add(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
		byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	@Test
	void withNothingSavedTheEnvironmentsCredentialAndLocationApply() {
		http.get(Map.class, (uri) -> uri.path("/x").build());

		assertThat(seen).containsExactly("GET Bearer env-token");
		assertThat(http.locationId()).isEqualTo("env-location");
	}

	@Test
	void aSavedTokenAndLocationApplyToTheNextCallWithNoRestart() {
		http.get(Map.class, (uri) -> uri.path("/x").build());
		given(settings.app(Setting.GHL_TOKEN)).willReturn(Optional.of("app-token"));
		given(settings.app(Setting.GHL_LOCATION_ID)).willReturn(Optional.of("app-location"));

		http.get(Map.class, (uri) -> uri.path("/x").build());

		assertThat(seen).containsExactly("GET Bearer env-token", "GET Bearer app-token");
		assertThat(http.locationId()).isEqualTo("app-location");
	}

	@Test
	void pausedWritesRefuseEveryWriteVerbButReadsStillGo() {
		given(settings.enabled(Setting.GHL_WRITES_ENABLED)).willReturn(false);

		for (Runnable write : List.<Runnable>of(
				() -> http.post(Map.class, (uri) -> uri.path("/contacts/upsert").build(), Map.of()),
				() -> http.put(Map.class, (uri) -> uri.path("/opportunities/1").build(), Map.of()),
				() -> http.delete((uri) -> uri.path("/opportunities/1").build()))) {
			assertThatThrownBy(write::run).isInstanceOfSatisfying(GhlUnavailableException.class,
					(paused) -> assertThat(paused.failure()).isEqualTo(GhlFailure.PAUSED));
		}
		http.get(Map.class, (uri) -> uri.path("/x").build());
		http.search(Map.class, "/contacts/search", Map.of());

		// Nothing written left the JVM; both reads did.
		assertThat(seen).containsExactly("GET Bearer env-token", "POST Bearer env-token");
		assertThat(GhlFailure.PAUSED.isRetriable()).isTrue();
		assertThat(GhlFailure.PAUSED.stopsEverything()).isTrue();
	}

	@Test
	void clearingTheSavedTokenLeavesGhlUnconfiguredWhenTheEnvironmentHasNone() {
		GhlHttp bare = new GhlHttp("http://127.0.0.1:1", "2021-07-28", "", "", Duration.ofSeconds(1));
		bare.useSettings(settings);
		assertThat(bare.isConfigured()).isFalse();

		given(settings.app(Setting.GHL_TOKEN)).willReturn(Optional.of("app-token"));
		given(settings.app(Setting.GHL_LOCATION_ID)).willReturn(Optional.of("app-location"));
		assertThat(bare.isConfigured()).isTrue();
	}
}
