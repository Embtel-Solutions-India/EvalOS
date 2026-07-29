package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.service.CaseBoardService.BoardRow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The board's two filtering decisions, both of which are security-relevant enough to
 * assert rather than infer: a closed case is not work, and the {@code brandId} query
 * parameter can only ever narrow.
 */
class CaseBoardServiceTest {

	private static final UUID BRAND_IE = UUID.randomUUID();
	private static final UUID BRAND_XP = UUID.randomUUID();

	private final CaseLifecycleService lifecycle = mock(CaseLifecycleService.class);
	private final ContactSnapshotRepository contacts = mock(ContactSnapshotRepository.class);
	private final CaseBoardService board = new CaseBoardService(lifecycle, contacts);

	private static Case aCase(UUID brandId, String code, Stage stage) {
		return new Case(brandId, code, stage);
	}

	private List<String> codesOn(UUID brandFilter) {
		return board.forCaller(null, brandFilter).stream()
				.map(BoardRow::subject)
				.map(Case::getCaseCode)
				.toList();
	}

	@Test
	void aClosedCaseIsNotOnTheProductionBoard() {
		given(lifecycle.list(any(), any(), any())).willReturn(List.of(
				aCase(BRAND_IE, "IE-OPEN", Stage.DOC_COLLECTION),
				aCase(BRAND_IE, "IE-DONE", Stage.CLOSED)));

		assertThat(codesOn(null)).containsExactly("IE-OPEN");
	}

	/**
	 * The GM's brand switcher. Applied after the scoped read, so it selects among rows the
	 * caller could already see — which is the whole reason a brand may be named in a query
	 * string here when nothing else in EvalOS accepts one.
	 */
	@Test
	void theBrandFilterOnlyEverNarrows() {
		given(lifecycle.list(any(), any(), any())).willReturn(List.of(
				aCase(BRAND_IE, "IE-0001", Stage.DOC_COLLECTION),
				aCase(BRAND_XP, "XP-0001", Stage.DOC_COLLECTION)));

		// No filter: everything the scope already allowed (a GM, here).
		assertThat(codesOn(null)).containsExactly("IE-0001", "XP-0001");
		// One brand: a subset, never a different set.
		assertThat(codesOn(BRAND_IE)).containsExactly("IE-0001");
		// A brand with nothing the caller can read is empty, not an error and not a widening.
		assertThat(codesOn(UUID.randomUUID())).isEmpty();
	}
}
