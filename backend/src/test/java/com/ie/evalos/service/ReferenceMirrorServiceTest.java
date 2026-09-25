package com.ie.evalos.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.integration.GhlCalendarClient;
import com.ie.evalos.integration.GhlCustomFieldClient;
import com.ie.evalos.integration.GhlUserClient;
import com.ie.evalos.integration.GhlUnavailableException;
import com.ie.evalos.repository.GhlCalendarRepository;
import com.ie.evalos.repository.GhlCustomFieldRepository;
import com.ie.evalos.repository.GhlUserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The reference mirror — Unit 47.
 *
 * <p>The behaviours worth pinning are the mirror's two oldest rules and this unit's one new one: a
 * row GHL stops returning is <strong>stamped, never deleted</strong>; a row that comes back
 * <strong>clears the stamp</strong>; and one list's failure does not lose the other two.
 */
class ReferenceMirrorServiceTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final GhlCustomFieldClient fieldClient = mock(GhlCustomFieldClient.class);
	private final GhlCalendarClient calendarClient = mock(GhlCalendarClient.class);
	private final GhlUserClient userClient = mock(GhlUserClient.class);
	private final GhlCustomFieldRepository fields = mock(GhlCustomFieldRepository.class);
	private final GhlCalendarRepository calendars = mock(GhlCalendarRepository.class);
	private final GhlUserRepository users = mock(GhlUserRepository.class);
	private final com.ie.evalos.integration.GhlTagClient tagClient =
			mock(com.ie.evalos.integration.GhlTagClient.class);
	private final com.ie.evalos.repository.GhlTagRepository tags =
			mock(com.ie.evalos.repository.GhlTagRepository.class);

	private final ReferenceMirrorService mirror = new ReferenceMirrorService(fieldClient,
			calendarClient, userClient, tagClient, fields, calendars, users, tags, new SellingBrand(BRAND));

	private final List<Object> saved = new ArrayList<>();

	@BeforeEach
	void emptyMirror() {
		given(fields.findByBrandIdAndGhlId(any(), anyString())).willReturn(Optional.empty());
		given(calendars.findByBrandIdAndGhlId(any(), anyString())).willReturn(Optional.empty());
		given(users.findByBrandIdAndGhlId(any(), anyString())).willReturn(Optional.empty());
		given(fields.findByBrandIdAndModelOrderByNameAsc(any(), anyString())).willReturn(List.of());
		given(calendars.findByBrandIdOrderByNameAsc(any())).willReturn(List.of());
		given(users.findByBrandIdOrderByNameAsc(any())).willReturn(List.of());
		given(fields.save(any())).willAnswer(record());
		given(calendars.save(any())).willAnswer(record());
		given(users.save(any())).willAnswer(record());
		given(fieldClient.forOpportunities()).willReturn(List.of());
		given(calendarClient.calendars()).willReturn(List.of());
		given(userClient.inLocation()).willReturn(List.of());
		given(tagClient.inLocation()).willReturn(List.of());
		given(tags.findByBrandIdAndGhlId(any(), anyString())).willReturn(Optional.empty());
		given(tags.findByBrandIdOrderByNameAsc(any())).willReturn(List.of());
		given(tags.save(any())).willAnswer(record());
	}

	private org.mockito.stubbing.Answer<Object> record() {
		return (call) -> {
			saved.add(call.getArgument(0));
			return call.getArgument(0);
		};
	}

	private static GhlCalendarClient.CalendarOption calendar(String id, String name) {
		return new GhlCalendarClient.CalendarOption(id, name, true, 30, "{{contact.name}}");
	}

	@Test
	void aListGhlReturnsIsUpsertedOnGhlsOwnId() {
		mirror.absorbCalendars(List.of(calendar("cal_1", "Sales calls")));

		assertThat(saved).singleElement().isInstanceOfSatisfying(GhlReference.Calendar.class, (row) -> {
			assertThat(row.getGhlId()).isEqualTo("cal_1");
			assertThat(row.getName()).isEqualTo("Sales calls");
			assertThat(row.isLive()).isTrue();
		});
	}

	@Test
	void aRenameUpdatesTheRowRatherThanAddingASecond() {
		GhlReference.Calendar held = new GhlReference.Calendar(BRAND, "cal_1", "Old name");
		given(calendars.findByBrandIdAndGhlId(BRAND, "cal_1")).willReturn(Optional.of(held));

		mirror.absorbCalendars(List.of(calendar("cal_1", "New name")));

		assertThat(saved).singleElement().isSameAs(held);
		assertThat(held.getName()).isEqualTo("New name");
	}

	/** Never deleted: a calendar hidden for an afternoon must not take its bookings' label away. */
	@Test
	void aRowGhlStopsReturningIsStampedAndKept() {
		GhlReference.Calendar held = new GhlReference.Calendar(BRAND, "cal_gone", "Retired");
		given(calendars.findByBrandIdOrderByNameAsc(BRAND)).willReturn(List.of(held));

		mirror.absorbCalendars(List.of(calendar("cal_1", "Sales calls")));

		assertThat(held.getMissingSince()).isNotNull();
		assertThat(held.isLive()).isFalse();
		verify(calendars, never()).delete(any());
	}

	/** The half that is easy to forget: without it, one outage permanently shrinks the form. */
	@Test
	void aRowThatComesBackClearsItsStamp() {
		GhlReference.Calendar held = new GhlReference.Calendar(BRAND, "cal_1", "Sales calls");
		held.markMissing(java.time.Instant.now());
		given(calendars.findByBrandIdAndGhlId(BRAND, "cal_1")).willReturn(Optional.of(held));

		mirror.absorbCalendars(List.of(calendar("cal_1", "Sales calls")));

		assertThat(held.getMissingSince()).isNull();
		assertThat(held.isLive()).isTrue();
	}

	/** A missing row is dated from when it went, not from the last sweep that noticed. */
	@Test
	void aRowAlreadyMissingKeepsItsOriginalDate() {
		GhlReference.Calendar held = new GhlReference.Calendar(BRAND, "cal_gone", "Retired");
		java.time.Instant went = java.time.Instant.now().minusSeconds(86_400);
		held.markMissing(went);
		given(calendars.findByBrandIdOrderByNameAsc(BRAND)).willReturn(List.of(held));

		mirror.absorbCalendars(List.of());

		assertThat(held.getMissingSince()).isEqualTo(went);
	}

	@Test
	void onlyLiveRowsAreOfferedToAForm() {
		GhlReference.Calendar live = new GhlReference.Calendar(BRAND, "cal_1", "Sales calls");
		GhlReference.Calendar gone = new GhlReference.Calendar(BRAND, "cal_2", "Retired");
		gone.markMissing(java.time.Instant.now());
		given(calendars.countByBrandId(BRAND)).willReturn(2L);
		given(calendars.findByBrandIdOrderByNameAsc(BRAND)).willReturn(List.of(live, gone));

		assertThat(mirror.bookableCalendars()).containsExactly(live);
	}

	/**
	 * The first-run cliff this closes: a fresh deployment's booking form would otherwise have no
	 * calendars until the first sweep, and "run the sweep" is not a fix anybody would guess.
	 */
	@Test
	void anEmptyListIsReadFromGhlOnceAndAPopulatedOneIsNot() {
		given(calendars.countByBrandId(BRAND)).willReturn(0L);
		mirror.bookableCalendars();
		verify(calendarClient).calendars();

		given(calendars.countByBrandId(BRAND)).willReturn(3L);
		mirror.bookableCalendars();
		verify(calendarClient, times(1)).calendars();
	}

	/**
	 * <strong>One list's failure is not the sweep's failure.</strong> The three come from three
	 * endpoints with three scopes — a location missing the users scope should still get its
	 * calendars, and taking the pass down for it would make one missing grant look like a dead sweep.
	 */
	@Test
	void aRefusalOnOneListStillRefreshesTheOthers() {
		willThrow(new GhlUnavailableException("no scope")).given(userClient).inLocation();
		given(calendarClient.calendars()).willReturn(List.of(calendar("cal_1", "Sales calls")));
		given(fieldClient.forOpportunities()).willReturn(List.of(
				new GhlCustomFieldClient.CustomField("f1", "Visa category", "visa", "SINGLE_OPTIONS",
						List.of("H-1B", "O-1"))));

		ReferenceMirrorService.RefreshResult result = mirror.refresh();

		assertThat(result.users()).isZero();
		assertThat(result.calendars()).isEqualTo(1);
		assertThat(result.fields()).isEqualTo(1);
		// Unit 47b: tags have their own scope (`locations/tags.readonly`), so they are their own
		// failure too — which is the whole reason each list is refreshed independently.
		assertThat(result.tags()).isZero();
	}

	/** A blank selling brand means no location to mirror, and is a log line rather than a throw. */
	@Test
	void noSellingBrandMeansNothingToMirror() {
		ReferenceMirrorService none = new ReferenceMirrorService(fieldClient, calendarClient,
				userClient, tagClient, fields, calendars, users, tags, new SellingBrand((java.util.UUID) null));

		assertThat(none.refresh().total()).isZero();
		assertThat(none.bookableCalendars()).isEmpty();
		verify(calendarClient, never()).calendars();
	}

	/**
	 * <strong>One nameless GHL row used to cost the whole list.</strong>
	 *
	 * <p>{@code ghl_reference.name} is {@code NOT NULL} (V60, V62) and only the id was guarded, so a
	 * row GHL returned with no name raised a {@code DataIntegrityViolationException} that
	 * {@code guarded} caught, logged as a warning and reported as zero — nothing from that endpoint
	 * mirrored at all. Worse, {@code refreshIfEmpty} then re-ran the failing GHL read on every
	 * booking-form request, because the table stayed empty. The id is a poor label and a readable
	 * one; an empty calendar list is a broken screen.
	 */
	@Test
	void aRowGhlSendsWithNoNameIsMirroredUnderItsIdRatherThanFailingTheWholeList() {
		given(userClient.inLocation()).willReturn(List.of(
				new GhlUserClient.User("user-nameless", null, "nobody@ie.test"),
				new GhlUserClient.User("user-2", "Dana Okafor", "dana@ie.test")));

		assertThat(mirror.refresh().users()).isEqualTo(2);
		assertThat(saved).extracting("name").contains("user-nameless", "Dana Okafor");
	}
}
