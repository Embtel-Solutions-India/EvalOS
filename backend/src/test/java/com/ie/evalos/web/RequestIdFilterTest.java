package com.ie.evalos.web;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** One id per request: in the MDC while the request runs, on the response, and gone afterwards. */
class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	/** Runs the filter and returns the id the MDC held while the chain ran. */
	private String run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		filter.doFilter(request, response, new MockFilterChain() {
			@Override
			public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
				seen.set(MDC.get(RequestIdFilter.MDC_KEY));
			}
		});
		return seen.get();
	}

	@Test
	void aRequestWithoutAnIdGetsOneThatIsLoggedAndReturned() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		String id = run(new MockHttpServletRequest("GET", "/api/me"), response);

		assertThat(id).isNotBlank();
		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("cleared after the request").isNull();
	}

	@Test
	void aProxysPlainIdIsKeptSoTheTwoLogsJoinUp() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
		request.addHeader(RequestIdFilter.HEADER, "nginx-3f2a.91_b");
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertThat(run(request, response)).isEqualTo("nginx-3f2a.91_b");
		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("nginx-3f2a.91_b");
	}

	/** The id is written into every log line, so a header that could forge a line is replaced. */
	@Test
	void anIdThatCouldForgeALogLineIsReplaced() throws Exception {
		for (String hostile : new String[] {"abc\nERROR forged entry", "x".repeat(65), "a b", ""}) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
			request.addHeader(RequestIdFilter.HEADER, hostile);

			String id = run(request, new MockHttpServletResponse());

			assertThat(id).as(hostile).isNotEqualTo(hostile).matches("[0-9a-f-]{36}");
		}
	}
}
