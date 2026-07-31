package com.ie.evalos.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.WebhookEvent;
import com.ie.evalos.domain.WebhookSource;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.AuditService;
import com.ie.evalos.service.ExpertLoadService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The acceptance evidence for Unit 03 that only a real PostgreSQL can produce:
 * the migrations apply, {@code ddl-auto=validate} agrees that every entity
 * matches the migrated schema (including {@code text[]} and {@code jsonb}), the
 * payment detail is ciphertext on disk, scoped finders keep two brands apart, and
 * the audit table refuses to be edited.
 *
 * <p><strong>It runs in its own schema.</strong> These tests insert freely, and until
 * they were given {@code evalos_test} their rows landed in {@code public} beside real
 * dev data — about 150 junk cases at the last count, filling the first column of the
 * board a developer was trying to read. {@code currentSchema} pins every unqualified
 * statement here, so a misconfiguration fails outright rather than quietly writing
 * next door. A schema and not a second database, because Flyway can create a schema
 * and cannot create a database: nothing here needs a setup step a fresh checkout
 * could skip.
 *
 * <p>The URL comes from {@code DB_TEST_URL} and deliberately <em>not</em> from
 * {@code DB_URL}. A developer with {@code DB_URL} exported at their dev database
 * would otherwise have these inserts follow it straight back into {@code public},
 * which is the problem this class just stopped having.
 *
 * <p>Still gated on {@code -Devalos.db.test=true}, so {@code ./mvnw test} stays green
 * on a machine with no Postgres. That gate is also why these were unproven for ten
 * units — a flag nobody sets is the same as a test nobody wrote — so CI now passes it
 * on every push; see {@code .github/workflows/ci.yml}.
 *
 * <p>No Testcontainers: it needs a running Docker daemon, and the point of this suite
 * is to run against whatever Postgres is already there.
 *
 * <pre>
 * ./mvnw test -Devalos.db.test=true -Dtest=LocalPostgresIntegrationTest
 * </pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "evalos.db.test", matches = "true")
@TestPropertySource(properties = {
		"spring.datasource.url=${DB_TEST_URL:jdbc:postgresql://localhost:5432/evalos?currentSchema=evalos_test}",
		"spring.flyway.schemas=evalos_test",
		"spring.flyway.create-schemas=true",
		"spring.jpa.properties.hibernate.default_schema=evalos_test",
		"spring.jpa.show-sql=false",
})
class LocalPostgresIntegrationTest {

	/** Seeded by {@code V900__seed_local.sql}. */
	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BRAND_XP = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID GM = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID BM_IE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
	// Seeded members, so the assignment columns' foreign keys resolve: V900's IE Case
	// Manager and V902's IE Coordinator.
	private static final UUID CM_IE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005");
	private static final UUID COORDINATOR_IE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000006");
	private static final UUID TEAM_IE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

	private static final String DETAIL = "Wire to Bank of Nowhere, acct 12345678";

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	BrandRepository brands;

	@Autowired
	WebhookEventRepository webhookEvents;

	@Autowired
	ExpertRepository experts;

	@Autowired
	CaseRepository cases;

	@Autowired
	ContactSnapshotRepository contacts;

	@Autowired
	NotificationRepository notifications;

	@Autowired
	DocumentChecklistItemRepository checklistItems;

	@Autowired
	PayoutLedgerRepository payouts;

	@Autowired
	AuditEventRepository auditEvents;

	@Autowired
	AuditService auditService;

	@Autowired
	ExpertLoadService expertLoads;

	@Test
	void everyMigrationApplied() {
		List<String> versions = jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

		assertThat(versions)
				.containsSubsequence("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13",
						"14", "15", "16", "17", "18");
	}

	/**
	 * Unit 11's closed vocabulary, in the half a Java enum cannot cover.
	 *
	 * <p>{@code FieldTag} stops a controller accepting an unknown tag; this is the other
	 * writer — a migration, a seed script, a hand-run UPDATE — and it is why the constraint
	 * exists as well as the enum. Unit 12 matches on these tags by equality, so a single
	 * row carrying "mechanical engg" would be an expert the scorer can never find.
	 */
	@Test
	void theDatabaseRefusesATagTheVocabularyDoesNotContain() {
		UUID id = experts.save(new Expert(BRAND_IE, "Dr Tagged " + UUID.randomUUID())).getId();

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE expert SET primary_fields = ARRAY['mechanical engg'] WHERE id = ?", id))
				.hasStackTraceContaining("expert_primary_fields_known");
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE expert SET secondary_fields = ARRAY['UNDERWATER_BASKETRY'] WHERE id = ?", id))
				.hasStackTraceContaining("expert_secondary_fields_known");
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE expert SET letter_types = ARRAY['CREDENTIAL_EVAL'] WHERE id = ?", id))
				.hasStackTraceContaining("expert_letter_types_known");

		// And the legal values go in, so the constraint is not simply refusing everything.
		assertThat(jdbc.update("UPDATE expert SET primary_fields = ARRAY['LAW', 'FINANCE'], "
				+ "letter_types = ARRAY['RFE_RESPONSE'] WHERE id = ?", id)).isEqualTo(1);
		// A tagless expert stays legal: `NULL <@ ARRAY[...]` is unknown, which a CHECK accepts.
		assertThat(jdbc.update("UPDATE expert SET primary_fields = NULL WHERE id = ?", id)).isEqualTo(1);
	}

	/**
	 * The sheet import's upsert key, which is why re-uploading a roster updates instead of
	 * duplicating.
	 *
	 * <p>An index and not the lookup: {@code ExpertImportService} reads by email and then
	 * inserts, which two concurrent uploads of the same sheet can both pass. Case-insensitive
	 * because the index keys on {@code lower(email)} and the finder is
	 * {@code findByBrandIdAndEmailIgnoreCase} — the two have to agree or the index does not
	 * apply to this write.
	 */
	@Test
	void oneEmailPerBrandCanOnlyBeOnTheRosterOnce() {
		String email = "roster-" + UUID.randomUUID() + "@example.test";

		Expert first = new Expert(BRAND_IE, "Dr First");
		first.setEmail(email);
		experts.saveAndFlush(first);

		Expert sameAddress = new Expert(BRAND_IE, "Dr Duplicate");
		sameAddress.setEmail(email.toUpperCase());
		assertThatThrownBy(() -> experts.saveAndFlush(sameAddress))
				.hasStackTraceContaining("uq_expert_per_brand_email");

		// The other brand recruits the same person independently: an expert is never shared,
		// so this is a second legitimate row (Expert's own class comment).
		Expert otherBrand = new Expert(BRAND_XP, "Dr First");
		otherBrand.setEmail(email);
		assertThat(experts.saveAndFlush(otherBrand).getId()).isNotNull();

		// Partial, so any number of experts may have no email at all — they simply cannot be
		// imported, which the report says.
		assertThat(experts.saveAndFlush(new Expert(BRAND_IE, "Dr No Email A")).getId()).isNotNull();
		assertThat(experts.saveAndFlush(new Expert(BRAND_IE, "Dr No Email B")).getId()).isNotNull();

		// The finder the import upserts through agrees with the index, capitalisation included.
		assertThat(experts.findByBrandIdAndEmailIgnoreCase(BRAND_IE, email.toUpperCase()))
				.get().extracting(Expert::getFullName).isEqualTo("Dr First");
		assertThat(experts.findByBrandIdAndEmailIgnoreCase(BRAND_XP, email))
				.get().extracting(Expert::getId).isNotEqualTo(first.getId());
	}

	/**
	 * The unit's load criterion, and the reason it is a criterion: an expert carrying two
	 * open cases reports an active load of 2 while {@code expert.current_active_count} is
	 * still 0 in the same row.
	 *
	 * <p>That column and {@code total_cases_completed} were created in {@code V7} and have
	 * never been written by anything. Reading them would have shown every expert as free and
	 * given Unit 12's scorer a constant, so both figures are derived here — and the assertion
	 * on the dead column is what would fail if somebody "fixed" the derivation by starting to
	 * increment it instead.
	 */
	@Test
	void anExpertsLoadIsDerivedFromTheirCasesAndNotFromTheDeadCounter() {
		UUID expertId = experts.save(new Expert(BRAND_IE, "Dr Busy " + UUID.randomUUID())).getId();

		caseFor(expertId, Stage.EXPERT_SIGNING, ExceptionState.NONE);
		caseFor(expertId, Stage.DRAFT_GENERATION, ExceptionState.NONE);
		caseFor(expertId, Stage.CLOSED, ExceptionState.NONE);
		// A closed case still holding REFUND_REQUESTED is a refund the GM approved, which is
		// not work delivered — the same reading as RefundService.isRefunded.
		caseFor(expertId, Stage.CLOSED, ExceptionState.REFUND_REQUESTED);

		assertThat(expertLoads.forExpert(expertId)).isEqualTo(new ExpertLoadService.Load(2, 1));

		Integer stored = jdbc.queryForObject(
				"SELECT current_active_count FROM expert WHERE id = ?", Integer.class, expertId);
		assertThat(stored).isZero();
	}

	/**
	 * The batched aggregate is brand-blind, and that is a decision rather than an oversight
	 * — the same one {@code DocumentChecklistItemRepository.findByCaseIdIn} carries. Its
	 * javadoc's "do not call it with ids that came from a request" is load-bearing: the ids
	 * come from {@code ExpertRepository.findScoped}, and nothing else keeps the two brands
	 * apart in this query.
	 */
	@Test
	void theExpertLoadAggregateAnswersForWhateverIdsItIsGiven() {
		UUID ieExpert = experts.save(new Expert(BRAND_IE, "Dr IE " + UUID.randomUUID())).getId();
		UUID xpExpert = experts.save(new Expert(BRAND_XP, "Dr XP " + UUID.randomUUID())).getId();
		caseFor(ieExpert, Stage.EXPERT_SIGNING, ExceptionState.NONE);
		caseFor(xpExpert, Stage.EXPERT_SIGNING, ExceptionState.NONE);

		assertThat(cases.countCasesPerExpert(List.of(ieExpert)))
				.singleElement()
				.satisfies(row -> assertThat(row[0]).isEqualTo(ieExpert));
		// Two brands' experts in one call: no predicate stops it, so the caller's scoped read
		// is the only thing that does.
		assertThat(cases.countCasesPerExpert(List.of(ieExpert, xpExpert))).hasSize(2);
		// An expert with no cases is absent rather than zero; ExpertLoadService adds the zero.
		assertThat(cases.countCasesPerExpert(List.of(UUID.randomUUID()))).isEmpty();
	}

	private void caseFor(UUID expertId, Stage stage, ExceptionState exceptionState) {
		Case subject = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), stage);
		subject.setExpertId(expertId);
		subject.setExceptionState(exceptionState);
		cases.saveAndFlush(subject);
	}

	/**
	 * V16's half of the duplicate-case race. V15 keyed on {@code contact_id}, which is
	 * only unique once a snapshot exists — so for a contact EvalOS had never seen, two
	 * concurrent deliveries each inserted their own snapshot and both passed V15.
	 *
	 * <p>Both keys are asserted because the payload does not guarantee a GHL id: intake
	 * falls back to email, so that path needs constraining too.
	 */
	@Test
	void aContactIsUniquePerBrandByGhlIdAndByEmail() {
		String ghlId = "ghl-" + UUID.randomUUID();
		String email = "dup-" + UUID.randomUUID() + "@example.test";

		ContactSnapshot first = new ContactSnapshot(BRAND_IE, ghlId);
		first.syncFromGhl("First Contact", email, null, null, null, null, null, null, null);
		contacts.saveAndFlush(first);

		ContactSnapshot sameGhlId = new ContactSnapshot(BRAND_IE, ghlId);
		assertThatThrownBy(() -> contacts.saveAndFlush(sameGhlId))
				.hasStackTraceContaining("uq_contact_per_brand_ghl_id");

		// Case-insensitive: a capitalised address is the same person, matching the finder
		// intake uses (findByBrandIdAndEmailIgnoreCase).
		ContactSnapshot sameEmail = new ContactSnapshot(BRAND_IE, "ghl-" + UUID.randomUUID());
		sameEmail.syncFromGhl("Same Email", email.toUpperCase(), null, null, null, null, null, null, null);
		assertThatThrownBy(() -> contacts.saveAndFlush(sameEmail))
				.hasStackTraceContaining("uq_contact_per_brand_email");

		// The other brand keeps its own contacts: both keys are brand-scoped (invariant 1).
		ContactSnapshot otherBrand = new ContactSnapshot(BRAND_XP, ghlId);
		otherBrand.syncFromGhl("Other Brand", email, null, null, null, null, null, null, null);
		assertThat(contacts.saveAndFlush(otherBrand).getId()).isNotNull();
	}

	/**
	 * Unit 06's finders, executed rather than merely resolved. Worth a real database
	 * because {@code read} is a column name close enough to SQL's reserved words that
	 * quoting matters, because {@code OrderByReadAscCreatedAtDesc} is a two-key sort that
	 * only proves itself on rows, and because {@code markAllReadFor} is a bulk UPDATE
	 * that no mock can get wrong.
	 */
	@Test
	void theNotificationCentreFindersRunAgainstRealSql() {
		UUID recipient = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
		UUID other = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
		UUID caseId = UUID.randomUUID();

		Notification read = new Notification(BRAND_IE, recipient, NotificationType.STAGE_CHANGED, caseId, "old");
		read.markRead();
		notifications.saveAll(List.of(
				read,
				new Notification(BRAND_IE, recipient, NotificationType.NEW_CASE_IN_POOL, caseId, "fresh"),
				new Notification(BRAND_IE, other, NotificationType.NEW_LEAD, caseId, "not yours")));
		notifications.flush();

		long unreadBefore = notifications.countByRecipientIdAndReadFalse(recipient);
		assertThat(unreadBefore).isPositive();

		// Unread first. Nothing else about the order is asserted: other tests share this
		// database, so only the relationship between these rows is stable.
		List<Notification> page = notifications.findByRecipientIdOrderByReadAscCreatedAtDesc(
				recipient, PageRequest.of(0, 50));
		assertThat(page).isNotEmpty()
				.extracting(Notification::getRecipientId).containsOnly(recipient);
		assertThat(page.get(0).isRead()).isFalse();

		// Another member's row is invisible through the recipient-keyed finder.
		assertThat(notifications.findByIdAndRecipientId(page.get(0).getId(), other)).isEmpty();
		assertThat(notifications.findByIdAndRecipientId(page.get(0).getId(), recipient)).isPresent();

		assertThat(notifications.existsByCaseIdAndType(caseId, NotificationType.NEW_CASE_IN_POOL)).isTrue();
		assertThat(notifications.existsByCaseIdAndType(UUID.randomUUID(), NotificationType.NEW_CASE_IN_POOL))
				.isFalse();

		// The bulk update clears this member's unread rows and nobody else's.
		assertThat(notifications.markAllReadFor(recipient)).isEqualTo((int) unreadBefore);
		assertThat(notifications.countByRecipientIdAndReadFalse(recipient)).isZero();
		assertThat(notifications.countByRecipientIdAndReadFalse(other)).isPositive();
	}

	/**
	 * "One open case per contact per service" is a database constraint for the same
	 * reason the webhook key is: intake looks the case up and then inserts, and two
	 * concurrent {@code contact.created} deliveries carrying different event ids are not
	 * deduplicated by the gateway, so both can pass the lookup. Only V15's partial unique
	 * index makes the second one lose.
	 *
	 * <p>Partial on {@code current_stage <> 'CLOSED'}, which is the half most easily got
	 * wrong: a contact coming back after their first case closed is new business, not a
	 * duplicate, and must still be allowed a row.
	 */
	@Test
	void oneOpenCasePerContactPerServiceIsEnforcedByTheDatabase() {
		UUID contactId = contacts.save(new ContactSnapshot(BRAND_IE, "ghl-" + UUID.randomUUID())).getId();

		cases.save(openCaseFor(contactId, ServiceType.EXPERT_OPINION_LETTER));

		assertThatThrownBy(() -> cases.saveAndFlush(openCaseFor(contactId, ServiceType.EXPERT_OPINION_LETTER)))
				.hasStackTraceContaining("uq_case_open_per_contact_service");

		// A different service for the same contact is a second, legitimate case.
		assertThat(cases.save(openCaseFor(contactId, ServiceType.CREDENTIAL_EVALUATION)).getId()).isNotNull();

		// And once the first case closes, the same service may be bought again.
		Case closed = openCaseFor(contactId, ServiceType.TRANSLATION);
		closed.setCurrentStage(Stage.CLOSED);
		cases.saveAndFlush(closed);
		assertThat(cases.saveAndFlush(openCaseFor(contactId, ServiceType.TRANSLATION)).getId()).isNotNull();
	}

	private static Case openCaseFor(UUID contactId, ServiceType serviceType) {
		Case subject = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION);
		subject.setContactId(contactId);
		subject.setServiceType(serviceType);
		return subject;
	}

	/**
	 * The gateway's idempotency guarantee is a database constraint, not an
	 * application check: two concurrent redeliveries would both pass a
	 * check-then-insert, so only the unique index actually stops the second case.
	 *
	 * <p>Scoped by brand (V13), because two brands are two GHL sub-accounts numbering
	 * their own invoices. A brand-agnostic key made the second brand's paid case look
	 * like the first brand's duplicate.
	 */
	@Test
	void oneExternalIdPerBrandPerSourceCanOnlyBeArchivedOnce() {
		String externalId = "INV-" + UUID.randomUUID();
		WebhookEvent first = webhookEvents.save(new WebhookEvent(
				WebhookSource.GHL, "payment.confirmed", externalId, BRAND_IE, true, "{\"invoice_ref\":\"x\"}"));

		assertThat(webhookEvents.findBySourceAndBrandIdAndExternalId(WebhookSource.GHL, BRAND_IE, externalId))
				.get().extracting(WebhookEvent::getId).isEqualTo(first.getId());

		assertThatThrownBy(() -> webhookEvents.saveAndFlush(new WebhookEvent(
				WebhookSource.GHL, "payment.confirmed", externalId, BRAND_IE, true, "{}")))
				.hasStackTraceContaining("uq_webhook_event_source_brand_external");

		// The same invoice ref from the other brand is a different event, and is allowed —
		// this is the case V12's constraint silently dropped.
		WebhookEvent otherBrand = webhookEvents.save(new WebhookEvent(
				WebhookSource.GHL, "payment.confirmed", externalId, BRAND_XP, true, "{}"));
		assertThat(otherBrand.getId()).isNotEqualTo(first.getId());
		assertThat(webhookEvents.findBySourceAndBrandIdAndExternalId(WebhookSource.GHL, BRAND_XP, externalId))
				.get().extracting(WebhookEvent::getId).isEqualTo(otherBrand.getId());

		// And the same id from a different source is a different event too.
		assertThat(webhookEvents.save(new WebhookEvent(
				WebhookSource.DROPBOX_SIGN, "signature_request.signed", externalId, BRAND_IE, true, "{}"))
				.getId()).isNotNull();
	}

	/**
	 * {@code brand_id} is nullable, and Postgres treats NULLs as distinct by default —
	 * which would have let two brand-less rows share a key and lose the deduplication
	 * the constraint exists for. V13 declares NULLS NOT DISTINCT.
	 */
	@Test
	void twoBrandlessRowsStillDeduplicate() {
		String externalId = "EVT-" + UUID.randomUUID();
		webhookEvents.save(new WebhookEvent(
				WebhookSource.DROPBOX_SIGN, "signature_request.viewed", externalId, null, true, "{}"));

		assertThatThrownBy(() -> webhookEvents.saveAndFlush(new WebhookEvent(
				WebhookSource.DROPBOX_SIGN, "signature_request.viewed", externalId, null, true, "{}")))
				.hasStackTraceContaining("uq_webhook_event_source_brand_external");
	}

	/** The per-brand secret column V11 added, mapped and readable through the entity. */
	@Test
	void eachSeededBrandCarriesItsOwnWebhookSecret() {
		assertThat(brands.findByWebhookEndpointTokenAndActiveTrue("local-ie-webhook-token"))
				.get().extracting(Brand::getGhlWebhookSecret).isEqualTo("local-ie-webhook-secret");
		assertThat(brands.findByWebhookEndpointTokenAndActiveTrue("local-xp-webhook-token"))
				.get().extracting(Brand::getGhlWebhookSecret).isEqualTo("local-xp-webhook-secret");
		// A token nobody issued resolves to nothing, so its deliveries are 404s.
		assertThat(brands.findByWebhookEndpointTokenAndActiveTrue("not-a-token")).isEmpty();
	}

	@Test
	void paymentDetailIsCiphertextOnDiskAndPlaintextThroughTheEntity() {
		Expert expert = new Expert(BRAND_IE, "Dr Ada Verify");
		expert.setPaymentDetail(DETAIL);
		UUID id = experts.save(expert).getId();

		String stored = jdbc.queryForObject(
				"SELECT payment_detail FROM expert WHERE id = ?", String.class, id);
		assertThat(stored).isNotNull().doesNotContain("12345678").doesNotContain("Bank of Nowhere");

		assertThat(experts.findById(id).orElseThrow().getPaymentDetail()).isEqualTo(DETAIL);
	}

	@Test
	void scopedFindersKeepTwoBrandsApart() {
		UUID ie = experts.save(new Expert(BRAND_IE, "IE Roster " + UUID.randomUUID())).getId();
		UUID xp = experts.save(new Expert(BRAND_XP, "XP Roster " + UUID.randomUUID())).getId();

		List<UUID> brandManagerSees = experts.findScoped(brandManagerOfIe()).stream()
				.map(Expert::getId).toList();
		assertThat(brandManagerSees).contains(ie).doesNotContain(xp);

		List<UUID> gmSees = experts.findScoped(gm()).stream().map(Expert::getId).toList();
		assertThat(gmSees).contains(ie, xp);

		// The single-row variant is scoped too: another brand's row is simply absent.
		assertThat(experts.findScoped(brandManagerOfIe(), xp)).isEmpty();
		assertThat(experts.findScoped(brandManagerOfIe(), ie)).isPresent();
	}

	@Test
	void aCasePersistsAgainstTheMigratedSchema() {
		Case saved = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION));

		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(cases.findScoped(brandManagerOfIe(), saved.getId())).isPresent();
	}

	/**
	 * Unit 04's board filters are Criteria predicates over attribute *names*, so a
	 * renamed field would only break when the SQL is actually generated — which no
	 * mocked repository ever does.
	 */
	@Test
	void theBoardFiltersGenerateValidSqlOnTopOfTheScope() {
		Case subject = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.EXPERT_SIGNING);
		subject.setStageEnteredAt(Instant.now());
		subject.setSlaStatus(SlaStatus.AT_RISK);
		UUID id = cases.save(subject).getId();

		assertThat(ids(cases.findScoped(brandManagerOfIe(), Stage.EXPERT_SIGNING, null)))
				.contains(id);
		assertThat(ids(cases.findScoped(brandManagerOfIe(), Stage.DOC_COLLECTION, null)))
				.doesNotContain(id);
		// A deadline filter on a case with no deadline keeps the row — see the dedicated test
		// below for why dropping it was a defect rather than a detail.
		assertThat(ids(cases.findScoped(brandManagerOfIe(), null, Instant.now())))
				.contains(id);

		// And a case in another brand stays out however the filters are combined.
		UUID other = cases.save(new Case(BRAND_XP, "XP-" + UUID.randomUUID(), Stage.EXPERT_SIGNING)).getId();
		assertThat(ids(cases.findScoped(brandManagerOfIe(), Stage.EXPERT_SIGNING, null)))
				.doesNotContain(other);
	}

	/**
	 * A case with no deadline is still on the board.
	 *
	 * <p>The board always sends a deadline window (the shell's date filter has no "all" option),
	 * and `deadline <= :dueBefore` alone drops undated rows, because SQL `NULL <= x` is unknown
	 * rather than true. Intake leaves the column null whenever the GHL payload carries no date —
	 * there is no `@NotNull` on it — so those cases were invisible in every column and every
	 * lane, on every screen, with no setting that revealed them. Exactly the kind of thing only
	 * real SQL shows: a mocked repository has no NULL semantics to get wrong.
	 */
	@Test
	void aCaseWithNoDeadlineSurvivesTheDeadlineFilter() {
		Case undated = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION));
		Case dated = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION);
		dated.setDeadline(Instant.now().plus(Duration.ofDays(3)));
		UUID datedId = cases.save(dated).getId();

		Case wayOut = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION);
		wayOut.setDeadline(Instant.now().plus(Duration.ofDays(400)));
		UUID wayOutId = cases.save(wayOut).getId();

		List<UUID> withinAWeek = ids(cases.findScoped(brandManagerOfIe(), null,
				Instant.now().plus(Duration.ofDays(7))));

		assertThat(withinAWeek).contains(undated.getId(), datedId);
		// The filter still filters: a case due next year is not "due within a week".
		assertThat(withinAWeek).doesNotContain(wayOutId);
	}

	/**
	 * The defect Unit 08 fixed, proved against real SQL: a Self-tier caller matches a case
	 * naming them in <em>any</em> assignment slot.
	 *
	 * <p>Before {@code V17} and the widened axis, {@code evalos_case}'s only assignee
	 * column was {@code assigned_cm}, so a Coordinator's scoped read matched nothing at
	 * all — their board was empty and the four transitions they are the actor for answered
	 * 403 on their own cases. This is the one that would silently regress if the OR were
	 * dropped back to a single column, and no mocked repository would notice.
	 */
	@Test
	void aSelfCallerReadsCasesAssignedToThemInEitherSlot() {
		Case coordinated = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION);
		coordinated.setAssignedCoordinator(COORDINATOR_IE);
		UUID coordinatedId = cases.save(coordinated).getId();

		Case managed = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DRAFT_GENERATION);
		managed.setAssignedCm(CM_IE);
		UUID managedId = cases.save(managed).getId();

		UUID unassignedId = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION)).getId();

		// Each Self caller sees their own slot and neither sees the other's or the pool's.
		assertThat(ids(cases.findScoped(coordinatorOfIe())))
				.contains(coordinatedId)
				.doesNotContain(managedId, unassignedId);
		assertThat(ids(cases.findScoped(caseManagerOfIe())))
				.contains(managedId)
				.doesNotContain(coordinatedId, unassignedId);

		// And the single-row variant agrees, which is what the write path loads through.
		assertThat(cases.findScoped(coordinatorOfIe(), coordinatedId)).isPresent();
		assertThat(cases.findScoped(coordinatorOfIe(), managedId)).isEmpty();

		// A case assigned to a Coordinator in another brand stays out regardless.
		Case otherBrand = new Case(BRAND_XP, "XP-" + UUID.randomUUID(), Stage.DOC_COLLECTION);
		otherBrand.setAssignedCoordinator(COORDINATOR_IE);
		UUID otherBrandId = cases.save(otherBrand).getId();
		assertThat(ids(cases.findScoped(coordinatorOfIe()))).doesNotContain(otherBrandId);
	}

	/** The two derived finders Unit 04 added, executed rather than merely resolved. */
	@Test
	void theCaseScopedFindersReturnOnlyThatCasesRows() {
		UUID caseId = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION)).getId();

		assertThat(checklistItems.findByCaseId(caseId)).isEmpty();
		assertThat(payouts.findByCaseIdAndStatus(caseId, PayoutStatus.PENDING)).isEmpty();
	}

	/**
	 * Unit 10's checklist reads, and the line between what the database enforces and what a
	 * calling convention does.
	 *
	 * <p>{@code findScoped} keeps two brands' items apart, and that is what protects the one
	 * path taking an item id straight from a request. {@code findByCaseIdIn} has no brand
	 * predicate at all — it answers for whatever case ids it is handed — so its javadoc's "do
	 * not call it with ids that came from a request" is load-bearing rather than decorative.
	 * Both halves are asserted, because a future caller passing an unscoped id would be a brand
	 * leak that no mocked repository could ever show.
	 */
	@Test
	void checklistItemsAreScopedByBrandAndTheBatchedFinderIsNot() {
		UUID ieCase = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION)).getId();
		UUID xpCase = cases.save(new Case(BRAND_XP, "XP-" + UUID.randomUUID(), Stage.DOC_COLLECTION)).getId();

		UUID ieItem = checklistItems.save(new DocumentChecklistItem(
				BRAND_IE, ieCase, "Passport", ChecklistItemStatus.REQUIRED)).getId();
		UUID xpItem = checklistItems.save(new DocumentChecklistItem(
				BRAND_XP, xpCase, "Passport", ChecklistItemStatus.REQUIRED)).getId();

		// The single-row read every write goes through: the other brand's item is simply absent,
		// which is what refuses a caller who guessed an id.
		assertThat(checklistItems.findScoped(brandManagerOfIe(), ieItem)).isPresent();
		assertThat(checklistItems.findScoped(brandManagerOfIe(), xpItem)).isEmpty();
		assertThat(checklistItems.findScoped(gm(), xpItem)).isPresent();

		// And the batched finder is brand-blind by design: the caller's already-scoped id list
		// is the only thing keeping the two brands apart on the board.
		assertThat(checklistItems.findByCaseIdIn(List.of(ieCase)))
				.extracting(DocumentChecklistItem::getId).containsExactly(ieItem);
		assertThat(checklistItems.findByCaseIdIn(List.of(ieCase, xpCase)))
				.extracting(DocumentChecklistItem::getId).contains(ieItem, xpItem);
	}

	/**
	 * "Last chased" read back out of the append-only trail rather than kept in a column
	 * (Unit 10), against real SQL.
	 *
	 * <p>Worth a database because it is a three-key derived query over an enum column and an
	 * {@code IN} list, and because {@code ChecklistService} reduces the result to one timestamp
	 * per case by taking the maximum — which only means anything if the query really returns
	 * every chase and nothing but chases. Brand-blind for the same reason as the finder above,
	 * and asserted so that stays a decision rather than a surprise.
	 */
	@Test
	void theChaseFinderAnswersOnlyChasesAndOnlyForTheIdsItIsGiven() {
		UUID ieCase = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION)).getId();
		UUID xpCase = cases.save(new Case(BRAND_XP, "XP-" + UUID.randomUUID(), Stage.DOC_COLLECTION)).getId();

		auditService.recordEvent("CASE", ieCase, AuditAction.CHASED, COORDINATOR_IE, null,
				Map.of("note", "Document chase sent to the client"));
		auditService.recordEvent("CASE", xpCase, AuditAction.CHASED, COORDINATOR_IE, null,
				Map.of("note", "Document chase sent to the client"));
		// A status edit on the same case must not read as a chase, or the queue would retire a
		// row the client was never contacted about.
		auditService.recordEvent("CASE", ieCase, AuditAction.UPDATED, COORDINATOR_IE, null,
				Map.of("note", "Passport: REQUIRED → UPLOADED"));

		assertThat(auditEvents.findByObjectTypeAndActionAndObjectIdIn(
				"CASE", AuditAction.CHASED, List.of(ieCase)))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.getObjectId()).isEqualTo(ieCase);
					assertThat(event.getCreatedAt()).isNotNull();
				});

		assertThat(auditEvents.findByObjectTypeAndActionAndObjectIdIn(
				"CASE", AuditAction.CHASED, List.of(ieCase, xpCase)))
				.extracting(AuditEvent::getObjectId).containsExactlyInAnyOrder(ieCase, xpCase);

		// A case nobody chased has no chase, which is the null the board draws as "never chased".
		assertThat(auditEvents.findByObjectTypeAndActionAndObjectIdIn(
				"CASE", AuditAction.CHASED, List.of(UUID.randomUUID()))).isEmpty();
	}

	@Test
	void aRowWithNoBrandNeverReachesTheDatabase() {
		assertThatThrownBy(() -> experts.save(new Expert(null, "Nobody's Expert")))
				.hasStackTraceContaining("brand_id");
	}

	@Test
	void auditRowsAreWrittenAndCannotBeChanged() {
		UUID objectId = UUID.randomUUID();
		AuditEvent recorded = auditService.recordEvent(
				"EXPERT", objectId, AuditAction.CREATED, GM, null, Map.of("fullName", "Dr Ada Verify"));

		List<AuditEvent> trail = auditEvents.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("EXPERT", objectId);
		assertThat(trail).singleElement().satisfies(event -> {
			assertThat(event.getAction()).isEqualTo(AuditAction.CREATED);
			assertThat(event.getActorId()).isEqualTo(GM);
			assertThat(event.getAfterSnapshot()).contains("Dr Ada Verify");
			assertThat(event.getCreatedAt()).isNotNull();
		});

		assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET action = 'UPDATED' WHERE id = ?",
				recorded.getId()))
				.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE id = ?", recorded.getId()))
				.hasMessageContaining("append-only");
	}

	private static List<UUID> ids(List<Case> found) {
		return found.stream().map(Case::getId).toList();
	}

	private static TenantContext brandManagerOfIe() {
		return new TenantContext(BM_IE, Role.BRAND_MANAGER, BRAND_IE, null);
	}

	private static TenantContext gm() {
		return new TenantContext(GM, Role.GM, null, null);
	}

	private static TenantContext coordinatorOfIe() {
		return new TenantContext(COORDINATOR_IE, Role.PROJECT_COORDINATOR, BRAND_IE, TEAM_IE);
	}

	private static TenantContext caseManagerOfIe() {
		return new TenantContext(CM_IE, Role.CASE_MANAGER, BRAND_IE, TEAM_IE);
	}
}
