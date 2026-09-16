package com.ie.evalos.integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ie.evalos.service.AuditService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * What {@link GhlCalendarClient#freeSlots} keeps out of the map.
 *
 * <p>GHL answers free-slots with the dates as keys and a {@code traceId} sitting among them, so
 * the response shape is data rather than a record. This is the one check that the sibling key is
 * dropped instead of being handed to a caller as a date with no slots.
 */
class GhlCalendarClientTest {

	private final GhlHttp http = mock(GhlHttp.class);

	private final GhlCalendarClient client = new GhlCalendarClient(http, mock(AuditService.class));

	@Test
	@DisplayName("free slots keeps the date keys and drops traceId")
	void freeSlotsDropsTraceId() {
		Map<String, Object> raw = new LinkedHashMap<>();
		raw.put("2026-09-15", Map.of("slots", List.of("2026-09-15T09:00:00+05:30")));
		raw.put("traceId", "d6e0-44");
		given(http.get(eq(Map.class), any())).willReturn(raw);

		GhlCalendarClient.FreeSlots slots = client.freeSlots("cal-1", 1L, 2L, "Asia/Kolkata");

		assertThat(slots.timezone()).isEqualTo("Asia/Kolkata");
		assertThat(slots.byDate())
				.containsExactly(entry("2026-09-15", List.of("2026-09-15T09:00:00+05:30")));
	}
}
