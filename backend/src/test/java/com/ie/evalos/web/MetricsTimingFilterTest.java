package com.ie.evalos.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsTimingFilterTest {

	private final MetricsTimingFilter filter = new MetricsTimingFilter();

	@Test
	void aMetricsReadCarriesItsDurationAndKeepsItsBody() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/metrics/pm");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
			@Override
			protected void doGet(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res)
					throws java.io.IOException {
				res.getWriter().write("{\"ok\":true}");
			}
		}));

		assertThat(response.getHeader("Server-Timing")).matches("app;dur=\\d+");
		assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
	}

	@Test
	void otherRoutesAreLeftAlone() {
		assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/cases/board"))).isTrue();
		assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/metrics/gm/overview"))).isFalse();
	}
}
