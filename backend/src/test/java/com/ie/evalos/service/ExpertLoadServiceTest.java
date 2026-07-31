package com.ie.evalos.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.service.ExpertLoadService.Load;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Load is counted from the cases, not read off {@code expert.current_active_count} — the
 * column {@code V7} created and nothing has ever written. This test pins the two things
 * that would silently break that: the aggregate's rows have to be mapped back onto the
 * right expert, and an expert with no cases has to come back as zero rather than missing.
 *
 * <p>The SQL itself is proved in {@code LocalPostgresIntegrationTest}: a mocked repository
 * cannot show whether {@code FILTER (WHERE …)} counts the right rows.
 */
class ExpertLoadServiceTest {

	private static final UUID BUSY = UUID.randomUUID();

	private static final UUID IDLE = UUID.randomUUID();

	private final CaseRepository cases = mock(CaseRepository.class);

	private final ExpertLoadService loads = new ExpertLoadService(cases);

	@Test
	void countsAreKeyedByExpertAndAnExpertWithNoCasesIsZeroRatherThanAbsent() {
		given(cases.countCasesPerExpert(any())).willReturn(List.<Object[]>of(
				new Object[] { BUSY, 2L, 7L }));

		Map<UUID, Load> byExpert = loads.forExperts(List.of(BUSY, IDLE));

		assertThat(byExpert.get(BUSY)).isEqualTo(new Load(2, 7));
		// Absent from the aggregate because they have no cases at all. A missing key would
		// make every caller write the same getOrDefault, and one of them would forget.
		assertThat(byExpert.get(IDLE)).isEqualTo(new Load(0, 0));
	}

	@Test
	void anEmptyPageAsksTheDatabaseNothing() {
		assertThat(loads.forExperts(List.of())).isEmpty();

		// `IN ()` is not valid SQL, and a roster page can legitimately be empty.
		verify(cases, never()).countCasesPerExpert(any());
	}

	@Test
	void oneExpertGoesThroughTheSameBatchedQuery() {
		given(cases.countCasesPerExpert(any())).willReturn(List.<Object[]>of(
				new Object[] { BUSY, 1L, 0L }));

		assertThat(loads.forExpert(BUSY)).isEqualTo(new Load(1, 0));
	}
}
