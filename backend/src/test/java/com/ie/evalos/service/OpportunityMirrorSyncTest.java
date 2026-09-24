package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit 45d's two writes: what a webhook absorbs, and what the delta sweep refreshes.
 *
 * <p>The absence pass is the thing to hold still here. {@code absorb} marks a row GHL stopped
 * returning as missing, because it was given a pipeline's whole list; {@code absorbForContact} must
 * not, because it was given one person's deals — and a person not having a deal is not that deal
 * being gone.
 */
class OpportunityMirrorSyncTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final GhlPipelineClient ghl = mock(GhlPipelineClient.class);

	private final OpportunityRepository opportunities = mock(OpportunityRepository.class);

	private final PipelineRepository pipelines = mock(PipelineRepository.class);
	/** Unit 47b's three tier-2/3 sinks, which ride on the same read. */
	private final com.ie.evalos.repository.GhlNoteRepository notes =
			mock(com.ie.evalos.repository.GhlNoteRepository.class);
	private final com.ie.evalos.repository.FollowUpRepository followUps =
			mock(com.ie.evalos.repository.FollowUpRepository.class);
	private final com.ie.evalos.repository.MeetingRepository meetings =
			mock(com.ie.evalos.repository.MeetingRepository.class);

	private final OpportunityMirrorService mirror =
			new OpportunityMirrorService(ghl, opportunities, pipelines, notes, followUps, meetings,
					new SellingBrand(BRAND));

	private final List<Opportunity> saved = new ArrayList<>();

	private Pipeline sales;

	@BeforeEach
	void oneMirroredPipeline() {
		sales = pipeline("pipe-1", "Sales");
		given(pipelines.findByBrandIdAndGhlId(BRAND, "pipe-1")).willReturn(Optional.of(sales));
		given(pipelines.findByBrandIdAndGhlId(BRAND, "pipe-unmirrored")).willReturn(Optional.empty());
		given(opportunities.save(any())).willAnswer((call) -> {
			saved.add(call.getArgument(0));
			return call.getArgument(0);
		});
	}

	private Pipeline pipeline(String ghlId, String name) {
		Pipeline row = new Pipeline(BRAND, ghlId, name, 0);
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	private static GhlPipelineClient.Opportunity fromGhl(String id, String pipelineId, String stageId,
			String name, String status) {
		return new GhlPipelineClient.Opportunity(id, name, "ghl-c-1", pipelineId, stageId, status,
				new BigDecimal("1450.00"), "CRM UI", "ghl-user-7", Instant.parse("2026-09-01T00:00:00Z"),
				Instant.parse("2026-09-17T00:00:00Z"), null, null);
	}

	@Test
	void aDealTheMirrorHasNeverSeenIsCreatedFromGhlsOwnFields() {
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());

		int written = mirror.absorbForContact(List.of(fromGhl("opp-1", "pipe-1", "stage-2", "Rao", "open")));

		assertThat(written).isEqualTo(1);
		assertThat(saved).singleElement().satisfies((row) -> {
			assertThat(row.getGhlId()).isEqualTo("opp-1");
			assertThat(row.getPipelineId()).isEqualTo(sales.getId());
			assertThat(row.getGhlStageId()).isEqualTo("stage-2");
			assertThat(row.getStatus()).isEqualTo("open");
		});
	}

	/**
	 * The staleness {@code isOnPipeline} names: a deal dragged onto another rep's board changes
	 * hands in the mirror at the moment GHL says so, rather than staying authorised to the old
	 * owner until something happened to re-read it.
	 */
	@Test
	void aDealThatMovedPipelineLandsOnTheOneGhlNamesNotTheOneItHeld() {
		Pipeline delivery = pipeline("pipe-2", "Case Delivery");
		given(pipelines.findByBrandIdAndGhlId(BRAND, "pipe-2")).willReturn(Optional.of(delivery));
		Opportunity held = new Opportunity(BRAND, "opp-1", sales.getId());
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.of(held));

		mirror.absorbForContact(List.of(fromGhl("opp-1", "pipe-2", "stage-9", "Rao", "won")));

		assertThat(saved).singleElement().satisfies((row) -> {
			assertThat(row.getPipelineId()).isEqualTo(delivery.getId());
			assertThat(row.getStatus()).isEqualTo("won");
		});
	}

	/**
	 * A contact's deals are not a pipeline's whole list, so nothing here may mark a row missing —
	 * doing so would stamp every deal the contact does not happen to have.
	 */
	@Test
	void aDealMissingFromOnePersonsAnswerIsNotMarkedMissing() {
		Opportunity other = new Opportunity(BRAND, "opp-other", sales.getId());
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());

		mirror.absorbForContact(List.of(fromGhl("opp-1", "pipe-1", "stage-2", "Rao", "open")));

		assertThat(other.isLive()).isTrue();
		verify(opportunities, never()).findByPipelineIdIn(any());
	}

	/** A pipeline the 44a sweep has not mirrored yet is skipped and logged, never guessed at. */
	@Test
	void aDealOnAnUnmirroredPipelineIsSkippedRatherThanFiledSomewhere() {
		int written = mirror.absorbForContact(List.of(fromGhl("opp-1", "pipe-unmirrored", "s", "Rao", "open")));

		assertThat(written).isZero();
		assertThat(saved).isEmpty();
	}

	/** The sweep's whole promise: a pipeline inside the TTL costs no GHL call. */
	@Test
	void theDeltaSweepSkipsAPipelineADeskHasAlreadyWarmed() {
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(List.of(sales));
		sales.opportunitiesSynced();

		assertThat(mirror.refreshStale(Duration.ofMinutes(10))).isEqualTo(1);
		verify(ghl, never()).allIn(any());
	}

	@Test
	void theDeltaSweepRereadsAPipelineNobodyHasLookedAt() {
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(List.of(sales));
		ReflectionTestUtils.setField(sales, "opportunitiesSyncedAt",
				Instant.now().minus(Duration.ofHours(2)));
		given(opportunities.findByPipelineIdIn(List.of(sales.getId()))).willReturn(List.of());
		given(ghl.allIn("pipe-1")).willReturn(List.of(fromGhl("opp-1", "pipe-1", "s", "Rao", "open")));

		mirror.refreshStale(Duration.ofMinutes(10));

		verify(ghl).allIn("pipe-1");
		assertThat(saved).hasSize(1);
	}

	/** A pipeline GHL stopped returning keeps its row and is not re-read — 44a stamps it missing. */
	@Test
	void theDeltaSweepLeavesAPipelineThatDisappearedFromGhlAlone() {
		sales.markMissing(Instant.now());
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(List.of(sales));

		assertThat(mirror.refreshStale(Duration.ofMinutes(10))).isZero();
		verify(ghl, never()).allIn(any());
	}

	/**
	 * <strong>The bug that made every board read "never synced".</strong>
	 *
	 * <p>The age used to be {@code max(opportunity.synced_at)} over the pipeline's deals, so a
	 * pipeline with no deals could not say it had been synced — it answered exactly like one that
	 * never had. Latent while the board refilled from GHL on every render; visible the moment Unit
	 * 46 removed the refill and turned a null into a "Sync delayed" banner.
	 */
	@Test
	void aPipelineSyncedWithNoDealsReportsASyncTimeRatherThanNever() {
		given(pipelines.findByBrandIdOrderByPositionAscNameAsc(BRAND)).willReturn(List.of(sales));
		given(opportunities.findByPipelineIdIn(List.of(sales.getId()))).willReturn(List.of());

		mirror.absorb(sales, List.of());

		assertThat(sales.getOpportunitiesSyncedAt()).isNotNull();
		assertThat(mirror.lastSynced(List.of("pipe-1"))).isNotNull();
	}

	/** A pipeline nothing has ever read makes the whole board's age unknown, not the others' age. */
	@Test
	void oneNeverReadPipelineMakesTheWholeBoardsAgeUnknown() {
		Pipeline second = pipeline("pipe-2", "Case Delivery");
		given(pipelines.findByBrandIdAndGhlId(BRAND, "pipe-2")).willReturn(Optional.of(second));
		sales.opportunitiesSynced();

		assertThat(mirror.lastSynced(List.of("pipe-1", "pipe-2"))).isNull();
	}

	/** Two read pipelines: the board is only as current as its stalest one. */
	@Test
	void theBoardsAgeIsItsStalestPipelineNotItsFreshest() {
		Pipeline second = pipeline("pipe-2", "Case Delivery");
		given(pipelines.findByBrandIdAndGhlId(BRAND, "pipe-2")).willReturn(Optional.of(second));
		second.opportunitiesSynced();
		ReflectionTestUtils.setField(second, "opportunitiesSyncedAt",
				Instant.now().minus(Duration.ofHours(3)));
		sales.opportunitiesSynced();

		assertThat(mirror.lastSynced(List.of("pipe-1", "pipe-2")))
				.isEqualTo(second.getOpportunitiesSyncedAt());
	}

	// --- Unit 47b: the tier-2/3 collections that ride on the same read -----------

	private static GhlPipelineClient.Opportunity withCollections(
			List<GhlPipelineClient.CustomFieldValue> fields, List<GhlPipelineClient.Note> notes,
			List<GhlPipelineClient.Task> tasks, List<GhlPipelineClient.CalendarEvent> events) {
		return new GhlPipelineClient.Opportunity("opp-1", "Rao", "ghl-c-1", "pipe-1", "stage-2",
				"open", new BigDecimal("1450.00"), "CRM UI", "ghl-user-7", null, null, null, null,
				fields, new GhlPipelineClient.NoteEnvelope(notes, notes.size()), tasks, events);
	}

	/**
	 * <strong>Custom field VALUES, keyed by GHL's field id.</strong> Keying by name would lose the
	 * value the day somebody renames the field in GHL — the id is what survives a rename, and
	 * `ghl_custom_field` is what turns it back into a label.
	 */
	@Test
	void customFieldValuesAreMirroredKeyedByFieldId() {
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());

		mirror.absorbForContact(List.of(withCollections(
				List.of(new GhlPipelineClient.CustomFieldValue("f_visa", "H-1B", null),
						new GhlPipelineClient.CustomFieldValue("f_docs", null,
								List.of("Transcript", "Degree"))),
				List.of(), List.of(), List.of())));

		assertThat(saved).singleElement().satisfies((row) -> assertThat(row.getCustomFields())
				.containsEntry("f_visa", "H-1B")
				.containsEntry("f_docs", "Transcript, Degree"));
	}

	/**
	 * <strong>The read-back Unit 47 §4 called impossible.</strong> It said GHL lists tasks only per
	 * contact, so a desk-wide refresh would be one request per contact. `getTasks` is a parameter on
	 * the search the mirror already makes — so a task completed in GHL closes on the desk, at no
	 * extra request.
	 */
	@Test
	void aTaskCompletedInGhlClosesTheFollowUpHere() {
		com.ie.evalos.domain.FollowUp held = mock(com.ie.evalos.domain.FollowUp.class);
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());
		given(followUps.findByBrandIdAndGhlTaskId(BRAND, "task-1")).willReturn(Optional.of(held));

		mirror.absorbForContact(List.of(withCollections(List.of(), List.of(),
				List.of(new GhlPipelineClient.Task("task-1", "Call back", "notes", "u1", null, true)),
				List.of())));

		verify(held).syncFromGhl("Call back", "notes", null, true);
		verify(followUps).save(held);
	}

	/** A task GHL knows and EvalOS never created is left alone: a sync must not invent desk work. */
	@Test
	void aTaskEvalOsNeverCreatedIsNotInvented() {
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());
		given(followUps.findByBrandIdAndGhlTaskId(BRAND, "task-x")).willReturn(Optional.empty());

		mirror.absorbForContact(List.of(withCollections(List.of(), List.of(),
				List.of(new GhlPipelineClient.Task("task-x", "Theirs", null, null, null, false)),
				List.of())));

		verify(followUps, never()).save(any());
	}

	/** An appointment cancelled in GHL's own UI reaches the diary. */
	@Test
	void anAppointmentChangedInGhlReachesTheMeetingRow() {
		com.ie.evalos.domain.Meeting held = mock(com.ie.evalos.domain.Meeting.class);
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());
		given(meetings.findByBrandIdAndGhlAppointmentId(BRAND, "appt-1")).willReturn(Optional.of(held));

		mirror.absorbForContact(List.of(withCollections(List.of(), List.of(), List.of(),
				List.of(new GhlPipelineClient.CalendarEvent("appt-1", "cal-1", "Intro call", null,
						null, "cancelled", "booked")))));

		// appointmentStatus wins over status: it is the field GHL cancels in.
		verify(held).syncFromGhl("Intro call", null, null, "cancelled");
	}

	/** GHL's notes are mirrored, and are NOT opportunity_note — that one is EvalOS's, append-only. */
	@Test
	void ghlNotesAreMirroredIntoTheirOwnTable() {
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());
		given(notes.findByBrandIdAndGhlId(BRAND, "note-1")).willReturn(Optional.empty());
		given(notes.findByBrandIdAndGhlOpportunityIdOrderByDateAddedDesc(BRAND, "opp-1"))
				.willReturn(List.of());

		mirror.absorbForContact(List.of(withCollections(List.of(),
				List.of(new GhlPipelineClient.Note("note-1", "Call summary", "Wants expedited",
						"u1", null, null, null)),
				List.of(), List.of())));

		org.mockito.ArgumentCaptor<com.ie.evalos.domain.GhlNote> written =
				org.mockito.ArgumentCaptor.forClass(com.ie.evalos.domain.GhlNote.class);
		verify(notes).save(written.capture());
		assertThat(written.getValue().getGhlId()).isEqualTo("note-1");
		assertThat(written.getValue().getBody()).isEqualTo("Wants expedited");
		assertThat(written.getValue().getGhlOpportunityId()).isEqualTo("opp-1");
	}

	/**
	 * <strong>Filed under the deal GHL says, not the deal it was listed under</strong> (Unit 54).
	 *
	 * <p>The search repeats a contact's notes under every one of that contact's deals — probed live
	 * on 2026-09-24 — so a note listed under opp-1 but related to opp-2 belongs to opp-2, and one
	 * related to no deal belongs to the contact alone.
	 */
	@Test
	void aGhlNoteIsFiledUnderItsRelatedDealOrTheContact() {
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());
		given(notes.findByBrandIdAndGhlId(org.mockito.ArgumentMatchers.eq(BRAND), org.mockito.ArgumentMatchers.anyString())).willReturn(Optional.empty());
		given(notes.findByBrandIdAndGhlOpportunityIdOrderByDateAddedDesc(BRAND, "opp-1"))
				.willReturn(List.of());

		mirror.absorbForContact(List.of(withCollections(List.of(), List.of(
				new GhlPipelineClient.Note("n-other-deal", null, "about the second deal", null, null,
						new GhlPipelineClient.CreatedBy("u9"),
						List.of(new GhlPipelineClient.Relation("opportunity", "opp-2"),
								new GhlPipelineClient.Relation("contact", "contact-1"))),
				new GhlPipelineClient.Note("n-contact", null, "about the person", null, null, null,
						List.of(new GhlPipelineClient.Relation("contact", "contact-1")))),
				List.of(), List.of())));

		org.mockito.ArgumentCaptor<com.ie.evalos.domain.GhlNote> written =
				org.mockito.ArgumentCaptor.forClass(com.ie.evalos.domain.GhlNote.class);
		verify(notes, org.mockito.Mockito.times(2)).save(written.capture());
		assertThat(written.getAllValues()).extracting(com.ie.evalos.domain.GhlNote::getGhlOpportunityId)
				.containsExactly("opp-2", null);
		// The author GHL actually sends, in `createdBy`, not the documented top-level field.
		assertThat(written.getAllValues().get(0).getGhlUserId()).isEqualTo("u9");
	}

	/**
	 * <strong>Freshness is written last, so a pass that dies half way does not claim to be fresh.</strong>
	 *
	 * <p>The stamp used to be the first thing {@code absorb} did. A failure anywhere in the loops
	 * after it — a constraint race against a concurrent {@code absorbForContact}, a row this code
	 * cannot map — therefore left a half-absorbed mirror advertising itself as current, and the TTL
	 * then suppressed the re-read that would have finished the job for a full interval. Every write
	 * in the loop is an upsert keyed by GHL's id, so being re-absorbed costs nothing; being skipped
	 * costs the reader a board that is quietly wrong.
	 */
	@Test
	void aFailureDuringAbsorbLeavesThePipelineUnstampedSoTheNextReadRetries() {
		given(opportunities.findByBrandIdAndGhlId(BRAND, "opp-1")).willReturn(Optional.empty());
		given(opportunities.save(any())).willThrow(new IllegalStateException("constraint race"));

		assertThatThrownBy(() -> mirror.absorb(sales, List.of(fromGhl("opp-1", "pipe-1", "s1", "Ana", "open"))))
				.isInstanceOf(IllegalStateException.class);

		assertThat(sales.getOpportunitiesSyncedAt())
				.describedAs("an unstamped pipeline is simply refreshed again on the next request")
				.isNull();
	}
}
