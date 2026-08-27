package com.ie.evalos.repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.ExceptionState;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.GhlFunnelCache;
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.OfferOutcome;
import com.ie.evalos.domain.PayoutStatus;
import com.ie.evalos.domain.PortalAccess;
import com.ie.evalos.domain.PortalAudience;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.ServiceType;
import com.ie.evalos.domain.SlaStatus;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.domain.WebhookEvent;
import com.ie.evalos.domain.WebhookSource;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.AuditService;
import com.ie.evalos.service.ExpertLoadService;

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
 * <p><strong>It now runs whenever a usable Postgres is actually there.</strong> This was
 * gated on {@code -Devalos.db.test=true} alone, and that flag was the whole problem: it
 * left these 27 tests silently skipping on every developer machine that <em>did</em> have
 * a database, so a green {@code ./mvnw test} said nothing at all about the schema, the
 * migrations or the encryption. "A flag nobody sets is the same as a test nobody wrote"
 * was already written here as a warning; it was still true.
 *
 * <p>So the gate is now {@link #postgresIsUsable()}: it probes the connection and runs if
 * it works. The flag still wins when it is set, in <em>both</em> directions —
 * {@code -Devalos.db.test=true} forces the suite on so CI fails loudly if the database it
 * provisioned is broken (rather than quietly skipping and reporting success), and
 * {@code -Devalos.db.test=false} forces it off for anyone who wants a fast offline run.
 * See {@code .github/workflows/ci.yml}, which sets it to true and is unaffected by this
 * change.
 *
 * <p>The probe deliberately treats <em>every</em> connection failure as "skip", not
 * "fail": no Postgres installed, wrong credentials, and no {@code evalos} database are all
 * "this machine is not set up for these tests", and failing a fresh checkout's build for
 * that would push people straight back to disabling the suite.
 *
 * <p>No Testcontainers: it needs a running Docker daemon, and the point of this suite
 * is to run against whatever Postgres is already there.
 *
 * <pre>
 * ./mvnw test -Devalos.db.test=true -Dtest=LocalPostgresIntegrationTest
 * </pre>
 */
@SpringBootTest
@EnabledIf("postgresIsUsable")
@TestPropertySource(properties = {
		"spring.datasource.url=${DB_TEST_URL:jdbc:postgresql://localhost:5432/evalos?currentSchema=evalos_test}",
		"spring.flyway.schemas=evalos_test",
		"spring.flyway.create-schemas=true",
		// Declared, because the constants below are the seeded brand and staff ids and this
		// suite does not run without them. It never used to say so: the seed sat in
		// db/migration/local, Flyway recursed into it from the plain db/migration location,
		// and the whole suite rode on the same accident that put the seed into production.
		// Moving it to a sibling directory made the dependency real, which is the point.
		"spring.flyway.locations=classpath:db/migration,classpath:db/seed-local",
		// The seed outranks every real migration, so the next unit's V-N arrives behind it.
		"spring.flyway.out-of-order=true",
		"spring.jpa.properties.hibernate.default_schema=evalos_test",
		"spring.jpa.show-sql=false",
})
class LocalPostgresIntegrationTest {

	/** The suite's own URL, matching {@code @TestPropertySource} above. */
	private static final String TEST_URL = "jdbc:postgresql://localhost:5432/evalos?currentSchema=evalos_test";

	/**
	 * Whether these tests can reach a database, which is what decides if they run.
	 *
	 * <p>{@code -Devalos.db.test} overrides the probe in both directions when it is set:
	 * {@code true} to force the suite on (CI, so a broken provisioned database fails rather than
	 * skips), {@code false} to force it off.
	 *
	 * <p>The credentials mirror {@code application-local.yml}'s defaults because this probe runs
	 * <em>before</em> Spring exists, so it cannot ask Spring for them. That duplication is the cost
	 * of auto-detection; if those defaults change, change them here too.
	 */
	static boolean postgresIsUsable() {
		String forced = System.getProperty("evalos.db.test");
		if (forced != null && !forced.isBlank()) {
			return Boolean.parseBoolean(forced);
		}

		Properties credentials = new Properties();
		credentials.setProperty("user", envOr("DB_USER", "postgres"));
		credentials.setProperty("password", envOr("DB_PASSWORD", "1234"));
		// Seconds. Short on purpose: this runs on every build, so an unreachable host must cost
		// a moment and not a stalled pipeline.
		credentials.setProperty("connectTimeout", "2");
		credentials.setProperty("loginTimeout", "2");

		try (Connection probe = DriverManager.getConnection(envOr("DB_TEST_URL", TEST_URL), credentials)) {
			return probe.isValid(2);
		}
		catch (SQLException unusable) {
			// Deliberately not rethrown, and deliberately logged rather than swallowed silently:
			// the run is about to report 27 skips and the reason should be findable.
			System.out.println("[db] LocalPostgresIntegrationTest skipped — no usable Postgres: "
					+ unusable.getMessage());
			return false;
		}
	}

	private static String envOr(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}

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
	ExpertCaseOfferRepository offers;

	@Autowired
	PayoutLedgerRepository payouts;

	@Autowired
	PortalAccessRepository portalTokens;

	@Autowired
	GhlFunnelCacheRepository funnelCache;

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
						"14", "15", "16", "17", "18", "19", "20", "21", "22", "23");
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
	 * The batched aggregate is brand-blind, and that is a decision rather than an oversight.
	 * Its javadoc's "do not call it with ids that came from a request" is load-bearing: the
	 * ids come from {@code ExpertRepository.findScoped}, and nothing else keeps the two
	 * brands apart in this query.
	 *
	 * <p>It used to have company — the checklist and chase batch reads carried the same
	 * convention until both were given brand predicates. This one is the harder case and is
	 * now the only holdout: it aggregates <em>by expert</em>, and one expert is reachable
	 * from several brands' cases, so narrowing it means splitting the counts per brand and
	 * changing what it returns. Until that happens the convention is all there is, which is
	 * the reason to keep asserting it here.
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
	 * <p>The GHL id is the identity, so it is the key that always applies.
	 */
	@Test
	void aContactIsUniquePerBrandByGhlId() {
		String ghlId = "ghl-" + UUID.randomUUID();
		String email = "dup-" + UUID.randomUUID() + "@example.test";

		ContactSnapshot first = new ContactSnapshot(BRAND_IE, ghlId);
		first.syncFromGhl("First Contact", email, null, null, null, null, null, null, null);
		contacts.saveAndFlush(first);

		ContactSnapshot sameGhlId = new ContactSnapshot(BRAND_IE, ghlId);
		assertThatThrownBy(() -> contacts.saveAndFlush(sameGhlId))
				.hasStackTraceContaining("uq_contact_per_brand_ghl_id");

		// The other brand keeps its own contacts: the key is brand-scoped (invariant 1).
		ContactSnapshot otherBrand = new ContactSnapshot(BRAND_XP, ghlId);
		otherBrand.syncFromGhl("Other Brand", email, null, null, null, null, null, null, null);
		assertThat(contacts.saveAndFlush(otherBrand).getId()).isNotNull();
	}

	/**
	 * <strong>V27: email is a fallback key, not an identity.</strong> V16 made it unique
	 * for every row carrying one, which said "one email, one person" — true only while
	 * EvalOS has nothing better to go on. Two GHL contacts sharing a firm's office inbox
	 * are two clients, and the old index refused to let the second one exist, which is what
	 * pushed intake into merging the new client onto the old one's row.
	 *
	 * <p>So the index now applies only to rows with no GHL id. Both halves are asserted
	 * here, because the second is the one V16 was right about and V27 must not lose.
	 */
	@Test
	void emailIsUniqueOnlyAmongContactsWithNoGhlId() {
		String email = "office-" + UUID.randomUUID() + "@example.test";

		// Two distinct GHL contacts, one shared inbox. Two clients, and both may exist.
		ContactSnapshot firm = new ContactSnapshot(BRAND_IE, "ghl-" + UUID.randomUUID());
		firm.syncFromGhl("Marcus Vale", email, null, null, null, null, null, null, null);
		contacts.saveAndFlush(firm);

		ContactSnapshot colleague = new ContactSnapshot(BRAND_IE, "ghl-" + UUID.randomUUID());
		colleague.syncFromGhl("Anita Rao", email, null, null, null, null, null, null, null);
		assertThat(contacts.saveAndFlush(colleague).getId()).isNotEqualTo(firm.getId());

		// The race V16 closed stays closed: with no GHL id, email is all there is, and two
		// concurrent id-less deliveries for one address must still collide. Case-insensitive,
		// matching the finder intake uses (findByBrandIdAndEmailIgnoreCase).
		String idlessEmail = "idless-" + UUID.randomUUID() + "@example.test";
		ContactSnapshot idless = new ContactSnapshot(BRAND_IE, null);
		idless.syncFromGhl("No Id Yet", idlessEmail, null, null, null, null, null, null, null);
		contacts.saveAndFlush(idless);

		ContactSnapshot sameAddressNoId = new ContactSnapshot(BRAND_IE, null);
		sameAddressNoId.syncFromGhl("Also No Id", idlessEmail.toUpperCase(),
				null, null, null, null, null, null, null);
		assertThatThrownBy(() -> contacts.saveAndFlush(sameAddressNoId))
				.hasStackTraceContaining("uq_contact_per_brand_email");
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
	 * V24, and it guards a different thing from the gateway's idempotency: {@code event_id}
	 * stops a redelivered <em>webhook</em>, this stops a second <em>case</em> for one won
	 * opportunity — two deliveries of the same opportunity with different event ids are
	 * genuinely different deliveries.
	 *
	 * <p>The {@code current_stage <> 'CLOSED'} half is the one worth proving. Without it, a
	 * client returning on a re-won opportunity id would hit a constraint violation: a 5xx GHL
	 * retries forever, and no case for a deal that was paid for.
	 */
	@Test
	void oneOpenCasePerWonOpportunityIsEnforcedByTheDatabase() {
		UUID contactId = contacts.save(new ContactSnapshot(BRAND_IE, "ghl-" + UUID.randomUUID())).getId();
		String opportunityId = "opp-" + UUID.randomUUID();

		Case first = openCaseFor(contactId, ServiceType.EXPERT_OPINION_LETTER);
		first.setGhlOpportunityId(opportunityId);
		cases.saveAndFlush(first);

		// A different contact and service, so only the opportunity id collides.
		UUID otherContact = contacts.save(new ContactSnapshot(BRAND_IE, "ghl-" + UUID.randomUUID())).getId();
		Case duplicate = openCaseFor(otherContact, ServiceType.TRANSLATION);
		duplicate.setGhlOpportunityId(opportunityId);
		assertThatThrownBy(() -> cases.saveAndFlush(duplicate))
				.hasStackTraceContaining("uq_case_open_per_opportunity");

		// The other brand numbers its own opportunities.
		Case otherBrand = new Case(BRAND_XP, "XP-" + UUID.randomUUID(), Stage.DOC_COLLECTION);
		otherBrand.setGhlOpportunityId(opportunityId);
		assertThat(cases.saveAndFlush(otherBrand).getId()).isNotNull();

		// Once the first case closes, the same id may come round again — repeat business.
		first.setCurrentStage(Stage.CLOSED);
		cases.saveAndFlush(first);
		Case repeat = openCaseFor(otherContact, ServiceType.CREDENTIAL_EVALUATION);
		repeat.setGhlOpportunityId(opportunityId);
		assertThat(cases.saveAndFlush(repeat).getId()).isNotNull();

		// A null id is exempt, which is what every row created before V24 carries.
		assertThat(cases.saveAndFlush(openCaseFor(contactId, ServiceType.PERM)).getId()).isNotNull();
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
				WebhookSource.GHL, "payment.confirmed", externalId, BRAND_IE, "{\"invoice_ref\":\"x\"}"));

		assertThat(webhookEvents.findBySourceAndBrandIdAndExternalId(WebhookSource.GHL, BRAND_IE, externalId))
				.get().extracting(WebhookEvent::getId).isEqualTo(first.getId());

		assertThatThrownBy(() -> webhookEvents.saveAndFlush(new WebhookEvent(
				WebhookSource.GHL, "payment.confirmed", externalId, BRAND_IE, "{}")))
				.hasStackTraceContaining("uq_webhook_event_source_brand_external");

		// The same invoice ref from the other brand is a different event, and is allowed —
		// this is the case V12's constraint silently dropped.
		WebhookEvent otherBrand = webhookEvents.save(new WebhookEvent(
				WebhookSource.GHL, "payment.confirmed", externalId, BRAND_XP, "{}"));
		assertThat(otherBrand.getId()).isNotEqualTo(first.getId());
		assertThat(webhookEvents.findBySourceAndBrandIdAndExternalId(WebhookSource.GHL, BRAND_XP, externalId))
				.get().extracting(WebhookEvent::getId).isEqualTo(otherBrand.getId());

		// There was a third assertion here: the same id from a *different source* is a different
		// event. It cannot be written any more — `WebhookSource` has one value since the
		// e-signature provider was dropped, so there is no second source to contrast with. The
		// `source` column stays in the unique key deliberately (see WebhookSource), it is simply
		// unexercised on that axis until a second provider exists. Do not "restore" it by
		// inventing an enum value nothing sends.
	}

	/**
	 * {@code brand_id} is nullable, and Postgres treats NULLs as distinct by default —
	 * which would have let two brand-less rows share a key and lose the deduplication
	 * the constraint exists for. V13 declares NULLS NOT DISTINCT.
	 */
	@Test
	void twoBrandlessRowsStillDeduplicate() {
		String externalId = "EVT-" + UUID.randomUUID();
		// The source is incidental here — this test is about a NULL brand_id, so GHL serves.
		webhookEvents.save(new WebhookEvent(
				WebhookSource.GHL, "opportunity.won", externalId, null, "{}"));

		assertThatThrownBy(() -> webhookEvents.saveAndFlush(new WebhookEvent(
				WebhookSource.GHL, "opportunity.won", externalId, null, "{}")))
				.hasStackTraceContaining("uq_webhook_event_source_brand_external");
	}

	/**
	 * The endpoint token is the whole webhook credential now that the HMAC is gone, so
	 * what it resolves to is worth asserting against the real seed: its own brand, and
	 * nothing at all for a token nobody issued.
	 */
	@Test
	void eachSeededBrandIsResolvedByItsOwnEndpointToken() {
		assertThat(brands.findByWebhookEndpointTokenAndActiveTrue("local-ie-webhook-token"))
				.get().extracting(Brand::getId).isEqualTo(BRAND_IE);
		assertThat(brands.findByWebhookEndpointTokenAndActiveTrue("local-xp-webhook-token"))
				.get().extracting(Brand::getId).isEqualTo(BRAND_XP);
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
	 * path taking an item id straight from a request. The batched finder used to have no brand
	 * predicate at all, resting instead on a javadoc asking callers not to hand it request ids;
	 * it now takes the brands too and drops anything outside them. Both halves are asserted,
	 * because a caller passing an unscoped id would be a brand leak no mocked repository could
	 * ever show.
	 */
	@Test
	void checklistItemsAreScopedByBrandAndSoIsTheBatchedFinder() {
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

		// And the batched finder narrows on its own. Handed the other brand's case id — the
		// shape a request-supplied id would take — it returns nothing for it rather than that
		// brand's checklist.
		assertThat(checklistItems.findByBrandIdInAndCaseIdIn(List.of(BRAND_IE), List.of(ieCase)))
				.extracting(DocumentChecklistItem::getId).containsExactly(ieItem);
		assertThat(checklistItems.findByBrandIdInAndCaseIdIn(List.of(BRAND_IE), List.of(ieCase, xpCase)))
				.extracting(DocumentChecklistItem::getId).containsExactly(ieItem);
		assertThat(checklistItems.findByBrandIdInAndCaseIdIn(List.of(BRAND_IE), List.of(xpCase)))
				.isEmpty();

		// The GM reads across brands by holding both, not by skipping the predicate.
		assertThat(checklistItems.findByBrandIdInAndCaseIdIn(List.of(BRAND_IE, BRAND_XP), List.of(ieCase, xpCase)))
				.extracting(DocumentChecklistItem::getId).contains(ieItem, xpItem);
	}

	/**
	 * "Last chased" read back out of the append-only trail rather than kept in a column
	 * (Unit 10), against real SQL.
	 *
	 * <p>Worth a database because it is a native join over an enum column and two {@code IN}
	 * lists, and because {@code ChecklistService} reduces the result to one timestamp per case
	 * by taking the maximum — which only means anything if the query really returns every chase
	 * and nothing but chases.
	 *
	 * <p>The brand predicate is on the <em>case</em>, not the audit row, and the rows written
	 * below are why: {@code AuditService} stamps {@code brand_id} from {@code TenantContext},
	 * so these events carry null. A filter on the audit row's own brand would return nothing
	 * here, and in production would silently drop every action taken by the GM.
	 */
	@Test
	void theChaseFinderAnswersOnlyChasesAndOnlyWithinTheBrandsItIsGiven() {
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

		assertThat(auditEvents.findCaseActionScoped(
				"CASE", AuditAction.CHASED, List.of(ieCase), List.of(BRAND_IE)))
				.singleElement()
				.satisfies(event -> {
					assertThat(event.getObjectId()).isEqualTo(ieCase);
					assertThat(event.getCreatedAt()).isNotNull();
					// Null on the row, found anyway: the join is carrying the scope.
					assertThat(event.getBrandId()).isNull();
				});

		// Handed the other brand's case id, it drops it rather than answering for it.
		assertThat(auditEvents.findCaseActionScoped(
				"CASE", AuditAction.CHASED, List.of(ieCase, xpCase), List.of(BRAND_IE)))
				.extracting(AuditEvent::getObjectId).containsExactly(ieCase);

		assertThat(auditEvents.findCaseActionScoped(
				"CASE", AuditAction.CHASED, List.of(ieCase, xpCase), List.of(BRAND_IE, BRAND_XP)))
				.extracting(AuditEvent::getObjectId).containsExactlyInAnyOrder(ieCase, xpCase);

		// A case nobody chased has no chase, which is the null the board draws as "never chased".
		assertThat(auditEvents.findCaseActionScoped(
				"CASE", AuditAction.CHASED, List.of(UUID.randomUUID()), List.of(BRAND_IE))).isEmpty();
	}

	/**
	 * Unit 12's offer aggregate, against real SQL, because a mock cannot show any of what matters
	 * about it.
	 *
	 * <p>Three things are only true in a database. The grouped {@code count} over an enum column
	 * returns {@code [id, OfferOutcome, Long]} positionally, and the scorer casts each slot — a
	 * wrong order or a {@code String} where the enum was expected is a {@code ClassCastException}
	 * no stub would produce. The {@code brand_id} predicate is a real predicate rather than a
	 * calling convention, so the acceptance rate cannot be computed across brands. And V19's two
	 * CHECKs exist to refuse a row the enum alone would not.
	 */
	@Test
	void theOfferAggregateIsGroupedByOutcomeAndBrandIsolated() {
		UUID ieCase = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.EXPERT_ASSIGNMENT)).getId();
		UUID xpCase = cases.save(new Case(BRAND_XP, "XP-" + UUID.randomUUID(), Stage.EXPERT_ASSIGNMENT)).getId();
		UUID ieExpert = experts.save(new Expert(BRAND_IE, "Dr Offer Verify")).getId();
		UUID xpExpert = experts.save(new Expert(BRAND_XP, "Dr Other Brand")).getId();

		resolved(BRAND_IE, ieCase, ieExpert, OfferOutcome.ACCEPTED);
		resolved(BRAND_IE, ieCase, ieExpert, OfferOutcome.ACCEPTED);
		resolved(BRAND_IE, ieCase, ieExpert, OfferOutcome.DECLINED);
		// Withdrawn rather than answered: it must not reach the rate at all.
		resolved(BRAND_IE, ieCase, ieExpert, OfferOutcome.SUPERSEDED);
		resolved(BRAND_XP, xpCase, xpExpert, OfferOutcome.DECLINED);
		// Still open, so it belongs to the other finder and to neither half of the rate.
		UUID openOffer = offers.save(new ExpertCaseOffer(BRAND_IE, ieCase, ieExpert)).getId();

		Map<OfferOutcome, Long> byOutcome = new EnumMap<>(OfferOutcome.class);
		for (Object[] row : offers.countOutcomesPerExpert(BRAND_IE, List.of(ieExpert, xpExpert))) {
			assertThat((UUID) row[0]).as("the other brand's expert is not in this brand's aggregate")
					.isEqualTo(ieExpert);
			byOutcome.put((OfferOutcome) row[1], ((Number) row[2]).longValue());
		}
		assertThat(byOutcome).containsExactlyInAnyOrderEntriesOf(Map.of(
				OfferOutcome.ACCEPTED, 2L,
				OfferOutcome.DECLINED, 1L,
				OfferOutcome.SUPERSEDED, 1L,
				OfferOutcome.OFFERED, 1L));

		// 3 of the 5 rows count: 2 ACCEPTED + 1 DECLINED. SUPERSEDED and OFFERED are excluded.
			long countable = byOutcome.entrySet().stream()
				.filter(entry -> entry.getKey().countsTowardAcceptanceRate())
				.mapToLong(Map.Entry::getValue).sum();
		assertThat(countable).isEqualTo(3);

		// Asking as the other brand returns that brand's row and nothing of IE's.
		assertThat(offers.countOutcomesPerExpert(BRAND_XP, List.of(ieExpert, xpExpert)))
				.singleElement()
				.satisfies(row -> assertThat((UUID) row[0]).isEqualTo(xpExpert));

		// The open-offer finder the three resolving transitions use.
		assertThat(offers.findByCaseIdAndOutcome(ieCase, OfferOutcome.OFFERED))
				.extracting(ExpertCaseOffer::getId).containsExactly(openOffer);

		// V19's two CHECKs, each provoked on its own: an unknown outcome, and a resolved row with
		// no outcome_at. The unknown-outcome case has to set the timestamp too, or it breaks both
		// constraints at once and Postgres reports whichever it evaluated first — which is how this
		// assertion originally passed for the wrong reason.
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE expert_case_offer SET outcome = 'MAYBE', outcome_at = now() WHERE id = ?", openOffer))
				.hasStackTraceContaining("expert_case_offer_outcome_known");
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE expert_case_offer SET outcome = 'DECLINED' WHERE id = ?", openOffer))
				.hasStackTraceContaining("expert_case_offer_outcome_dated");
		// And the row is untouched by either refusal, so the open offer is still open.
		assertThat(offers.findByCaseIdAndOutcome(ieCase, OfferOutcome.OFFERED))
				.extracting(ExpertCaseOffer::getId).containsExactly(openOffer);
	}

	private void resolved(UUID brandId, UUID caseId, UUID expertId, OfferOutcome outcome) {
		ExpertCaseOffer offer = new ExpertCaseOffer(brandId, caseId, expertId);
		offer.resolve(outcome, outcome == OfferOutcome.DECLINED ? "outside my field" : null);
		offers.save(offer);
	}

	/**
	 * Unit 14's token table, in the three halves only a database has.
	 *
	 * <p>The unique index is what makes "one row per token" a guarantee rather than a convention —
	 * two mints racing on the same token value is astronomically unlikely, but a token that could
	 * appear twice is a credential with two lifetimes and only one of them revoked. The audience
	 * CHECK is the other writer the enum cannot reach (a seed script, a hand-run UPDATE), and an
	 * unrecognised audience would be a token whose audience check nothing matches. And the
	 * {@code (case_id, audience)} finder is what the mint retires through, so it has to return the
	 * previous rows and only this case's.
	 *
	 * <p><strong>{@code V23} is the half a mocked repository cannot show at all:</strong> "one live
	 * token per case per audience" was a check-then-act in the service until it became a partial
	 * unique index, so this is where the race is actually closed.
	 */
	@Test
	void aPortalTokenIsUniqueAndItsAudienceIsClosed() {
		UUID caseId = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DRAFT_GENERATION)).getId();
		UUID otherCaseId = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DRAFT_GENERATION)).getId();
		String hash = "hash-" + UUID.randomUUID();
		Instant expires = Instant.now().plus(Duration.ofDays(30));

		PortalAccess first = portalTokens.saveAndFlush(
				new PortalAccess(BRAND_IE, caseId, PortalAudience.CLIENT, hash, expires));

		assertThatThrownBy(() -> portalTokens.saveAndFlush(
				new PortalAccess(BRAND_IE, otherCaseId, PortalAudience.CLIENT, hash, expires)))
				.hasStackTraceContaining("uq_portal_access_token_hash");

		// V23: a second UNREVOKED token on the same case and audience is refused by the database.
		// This is what makes two concurrent mints impossible rather than merely unlikely — the loser
		// rolls back. Until V23 both inserts succeeded and the case had two live credentials.
		assertThatThrownBy(() -> portalTokens.saveAndFlush(new PortalAccess(
				BRAND_IE, caseId, PortalAudience.CLIENT, "hash-" + UUID.randomUUID(), expires)))
				.hasStackTraceContaining("uq_portal_access_one_unrevoked");

		// Retiring the previous row is what makes the re-mint legal, which is exactly the order
		// PortalAccessService.mint writes in.
		first.revoke(Instant.now());
		portalTokens.saveAndFlush(first);
		UUID second = portalTokens.saveAndFlush(new PortalAccess(
				BRAND_IE, caseId, PortalAudience.CLIENT, "hash-" + UUID.randomUUID(), expires)).getId();

		assertThat(portalTokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(caseId, PortalAudience.CLIENT))
				.extracting(PortalAccess::getId).contains(first.getId(), second);
		// The index is partial, so any number of retired rows may pile up for one case — which is what
		// lets a client be re-issued a link as often as they ask.
		PortalAccess retired = portalTokens.findById(second).orElseThrow();
		retired.revoke(Instant.now());
		portalTokens.saveAndFlush(retired);
		assertThat(portalTokens.saveAndFlush(new PortalAccess(
				BRAND_IE, caseId, PortalAudience.CLIENT, "hash-" + UUID.randomUUID(), expires)).getId()).isNotNull();

		// And the other audience is a different slot: Unit 15 can hold its own live token per case.
		assertThat(portalTokens.findByCaseIdAndAudienceOrderByCreatedAtDesc(caseId, PortalAudience.EXPERT))
				.as("Unit 15's audience shares the table and not the rows").isEmpty();
		assertThat(portalTokens.saveAndFlush(new PortalAccess(
				BRAND_IE, caseId, PortalAudience.EXPERT, "hash-" + UUID.randomUUID(), expires)).getId()).isNotNull();
		assertThat(portalTokens.findByTokenHash(hash)).get()
				.extracting(PortalAccess::getId).isEqualTo(first.getId());

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE portal_access SET audience = 'ANYBODY' WHERE id = ?", first.getId()))
				.hasStackTraceContaining("portal_access_audience_known");
	}

	/**
	 * The column added to the audit trail in Unit 14, and the distinction it exists for.
	 *
	 * <p>Three rows, all with a null {@code actor_id}: one written before the column existed (raw
	 * SQL, because no writer produces that shape any more), one by the webhook, and one by a client
	 * through their portal. Before {@code actor_type} the three were indistinguishable, and the
	 * third is the approval that sends a letter to an expert to sign.
	 *
	 * <p>Append-only is unaffected and asserted here too: the new column is written on insert and
	 * the trigger still refuses to let anything change it.
	 */
	@Test
	void theAuditTrailDistinguishesAClientFromTheSystemAndFromHistory() {
		UUID caseId = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DRAFT_GENERATION)).getId();

		// A row as every row looked before V22. Inserted directly because that shape is now
		// unreachable through AuditService — which is the point: it exists and cannot be backfilled.
		jdbc.update("INSERT INTO audit_event (brand_id, object_type, object_id, action, actor_id, actor_type) "
				+ "VALUES (?, 'CASE', ?, 'CREATED', NULL, NULL)", BRAND_IE, caseId);
		auditService.recordSystemEvent(BRAND_IE, "CASE", caseId, AuditAction.UPDATED, null, null);
		AuditEvent byTheClient = auditService.recordPortalEvent(BRAND_IE, PortalAudience.CLIENT, "CASE", caseId,
				AuditAction.STAGE_CHANGED, null, Map.of("note", "the client approved the draft"));
		// The general writer with no actor: its contract allows that for a system action, so the type
		// is derived rather than assumed. A STAFF row with a null actor_id would contradict the reading
		// rule above and, on an append-only table, could never be corrected.
		auditService.recordEvent("CASE", caseId, AuditAction.UPDATED, null, null, null);

		assertThat(auditEvents.findByObjectTypeAndObjectIdOrderByCreatedAtAsc("CASE", caseId))
				.hasSize(4)
				.allSatisfy(row -> assertThat(row.getActorId()).as("none of the four is a staff member").isNull())
				.extracting(AuditEvent::getActorType)
				.containsExactly(null, ActorType.SYSTEM, ActorType.CLIENT, ActorType.SYSTEM);

		// Still append-only, with the new column on the table.
		assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET actor_type = 'STAFF' WHERE id = ?",
				byTheClient.getId()))
				.hasMessageContaining("append-only");
	}

	/**
	 * {@code draft_link} is its own column and reaching it does not reach {@code drive_link}.
	 *
	 * <p>Trivial-looking, and it is the defect Unit 14 opened by closing: the frontend pointed the
	 * client-facing "open the draft" link at the folder holding that client's passport scans. Two
	 * columns, written independently, is the whole fix — so this asserts they are two.
	 */
	@Test
	void aCaseCarriesTheDraftLinkSeparatelyFromTheDocumentsFolder() {
		Case subject = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DRAFT_GENERATION);
		subject.setDriveLink("https://drive.google.com/drive/folders/documents");
		subject.setDraftLink("https://docs.google.com/document/d/the-draft/edit");
		UUID id = cases.saveAndFlush(subject).getId();

		assertThat(jdbc.queryForObject("SELECT draft_link FROM evalos_case WHERE id = ?", String.class, id))
				.isEqualTo("https://docs.google.com/document/d/the-draft/edit");
		assertThat(cases.findById(id)).get().satisfies(found -> {
			assertThat(found.getDraftLink()).isEqualTo("https://docs.google.com/document/d/the-draft/edit");
			assertThat(found.getDriveLink()).isEqualTo("https://drive.google.com/drive/folders/documents");
		});
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

	/**
	 * The marketing funnel cache is a real table, and the three things only Postgres can prove.
	 *
	 * <p>These are exactly the guarantees {@code MarketingPipelineServiceTest} cannot make: its
	 * in-memory fake is last-write-wins by construction, so asserting a unique key or an optimistic
	 * lock there would only assert the fake. This is where the cache stopped being heap memory, so
	 * this is where those claims have to be checked.
	 */
	@Test
	void theFunnelCacheRoundTripsJsonbAndEnforcesOneRowPerWindow() {
		// Cleared first, and this is not boilerplate. Every other test here stays re-runnable by
		// inserting a random id; these keys are fixed strings, so the second run would collide on
		// the very constraint the test is asserting. Safe to wipe: it is a cache, in the
		// `evalos_test` schema.
		funnelCache.deleteAll();

		Instant readAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		// **A window key, not a range name — the V26 shape.** `2026-01-01..2026-12-31` rather than
		// "YEAR", because every custom period is named `custom` and name-keyed rows would collide.
		GhlFunnelCache stored = funnelCache.saveAndFlush(new GhlFunnelCache("ADS", "2026-01-01..2026-12-31",
				"{\"pipelineName\":\"Google ADS Pipeline\",\"totalDeals\":93}", "READY", readAt, null));

		assertThat(stored.getId()).isNotNull();
		// jsonb survives the trip, which is what makes storing the payload as one document viable.
		assertThat(funnelCache.findByFunnelAndWindowKey("ADS", "2026-01-01..2026-12-31")).get().satisfies((row) -> {
			assertThat(row.getPayload()).contains("Google ADS Pipeline").contains("93");
			assertThat(row.getDetail()).isEqualTo("READY");
			assertThat(row.getReadAt()).isEqualTo(readAt);
			assertThat(row.getTotallingSince()).isNull();
		});

		// The same funnel and a DIFFERENT window is a different row — the key is both halves.
		funnelCache.saveAndFlush(new GhlFunnelCache("ADS", "2026-08-01..2026-08-26", "{}", "READY", readAt, null));
		assertThat(funnelCache.findByFunnelAndWindowKey("ADS", "2026-08-01..2026-08-26")).isPresent();

		// **Two custom windows, which is what V26 exists for.** Under the old range-name key both
		// of these were "CUSTOM" and the second insert would have been refused by the unique
		// constraint — or worse, an upsert would have served one period's figures for the other.
		funnelCache.saveAndFlush(new GhlFunnelCache("ADS", "2026-02-01..2026-02-28", "{}", "READY", readAt, null));
		funnelCache.saveAndFlush(new GhlFunnelCache("ADS", "2026-04-01..2026-04-30", "{}", "READY", readAt, null));
		assertThat(funnelCache.findByFunnelAndWindowKey("ADS", "2026-02-01..2026-02-28")).isPresent();
		assertThat(funnelCache.findByFunnelAndWindowKey("ADS", "2026-04-01..2026-04-30")).isPresent();

		// A second row for the SAME window is refused by the database rather than by a check-then-
		// insert, which two concurrent cold-cache callers would both pass.
		assertThatThrownBy(() -> funnelCache.saveAndFlush(
				new GhlFunnelCache("ADS", "2026-01-01..2026-12-31", "{}", "READY", readAt, null)))
				.hasStackTraceContaining("uq_ghl_funnel_cache_window");
	}

	/**
	 * The optimistic lock that stops a slow reader clobbering a completed background total.
	 *
	 * <p>The failure this prevents is specific: a caller whose inline count read finishes *after*
	 * the totaller wrote {@code READY} must not overwrite those figures with {@code TOTALLING}.
	 * Simulated the only way that race can be simulated deterministically — two entity instances
	 * loaded at the same version, written one after the other.
	 */
	@Test
	void aStaleWriterLosesToTheOneThatGotThereFirst() {
		// Same reason as above: a fixed window key needs a clean slate to stay re-runnable.
		funnelCache.deleteAll();

		Instant readAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		UUID id = funnelCache.saveAndFlush(
				new GhlFunnelCache("EMAIL", "YEAR", "{}", "TOTALLING", readAt, readAt)).getId();

		// Two readers of the same version. `getReferenceById` would share the persistence context,
		// so both are fetched detached via a fresh find after a clear.
		GhlFunnelCache first = funnelCache.findById(id).orElseThrow();
		long versionBothSaw = first.getVersion();
		GhlFunnelCache second = new GhlFunnelCache("EMAIL", "YEAR", "{}", "TOTALLING", readAt, readAt);

		first.refresh("{\"totalValue\":3000}", "READY", Instant.now(), null);
		funnelCache.saveAndFlush(first);

		// The winner's figures stand, and the version moved.
		assertThat(funnelCache.findById(id)).get().satisfies((row) -> {
			assertThat(row.getDetail()).isEqualTo("READY");
			assertThat(row.getVersion()).isGreaterThan(versionBothSaw);
		});
		// And the loser cannot insert over the top of it: same window, so the unique key holds even
		// though it never saw the winner's version.
		assertThatThrownBy(() -> funnelCache.saveAndFlush(second))
				.hasStackTraceContaining("uq_ghl_funnel_cache_window");
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

	// --- Unit 16b: the two races only a real database can settle ---------------
	//
	// Both of these exist because a mocked repository cannot lose a race. They are the
	// acceptance evidence for `uq_payout_per_case` and for the conditional UPDATE behind
	// `PayoutLedgerRepository.attachToPayment`, and neither property is observable from
	// the unit suite: `PayoutServiceTest` builds its service with `new`, so `@Transactional`
	// is unproxied and no two transactions ever exist at once.
	//
	// They interleave deliberately. A sequential version of either would pass against code
	// that has no guard at all, because the second statement would simply see the first
	// one's committed result — the whole question is what happens while the first is still
	// open, which is the window `deliverToClient`'s `deliveryDate == null` check cannot see.

	/**
	 * Two concurrent deliveries of one case open <strong>one</strong> payout row.
	 *
	 * <p>{@code deliverToClient} guards on {@code getDeliveryDate() == null}, and {@code Case}
	 * carries no {@code @Version}, so two callers can both read null and both proceed. The
	 * guard cannot stop it; {@code uq_payout_per_case} can, and this is the proof.
	 *
	 * <p>The second insert is issued while the first transaction is still open, so it blocks
	 * on the index rather than failing immediately. That block is asserted — it is what
	 * distinguishes a real race from two statements run in sequence.
	 */
	@Test
	void twoConcurrentDeliveriesOfOneCaseOpenOnePayoutRow() throws Exception {
		UUID expertId = experts.save(new Expert(BRAND_IE, "Dr Raced " + UUID.randomUUID())).getId();
		UUID caseId = caseWithId(expertId);

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection first = testConnection(); Connection second = testConnection()) {
			insertPendingPayout(first, caseId, expertId, "350.00");

			// Issued on another thread because it is expected to block: the unique index
			// holds it until the first transaction resolves. It returns the refusal's
			// message rather than the exception itself — AssertJ cannot disambiguate an
			// `assertThat(SQLException)` overload, and the message is what we assert on.
			Future<String> loser = pool.submit(() -> {
				try {
					insertPendingPayout(second, caseId, expertId, "350.00");
					second.commit();
					return "accepted";
				}
				catch (SQLException refused) {
					return refused.getMessage();
				}
			});

			assertThatThrownBy(() -> loser.get(1, TimeUnit.SECONDS))
					.describedAs("the second insert must block on the index while the first is open; "
							+ "if it returned immediately the two never actually raced")
					.isInstanceOf(TimeoutException.class);

			first.commit();

			assertThat(loser.get(10, TimeUnit.SECONDS))
					.describedAs("the loser must be refused by uq_payout_per_case, not silently accepted")
					.contains("uq_payout_per_case");
			second.rollback();
		}
		finally {
			pool.shutdownNow();
		}

		Integer rows = jdbc.queryForObject(
				"SELECT count(*) FROM payout_ledger WHERE case_id = ?", Integer.class, caseId);
		assertThat(rows).isOne();
	}

	/**
	 * Two settlements overlapping by one draft: one wins the shared row, the other comes up
	 * short and its whole settlement rolls back.
	 *
	 * <p>This is the property {@code attachToPayment}'s return value exists for. The
	 * precondition rides in the {@code WHERE} clause, so the loser's statement does not fail —
	 * it succeeds and simply affects fewer rows than it asked for, which is the signal
	 * {@code PayoutService.settle} turns into a rollback. A read-then-save would instead have
	 * both callers overwrite the shared row and both believe they settled it.
	 *
	 * <p>Also the end-to-end half of the rollback that {@code PayoutServiceTest} can only
	 * assert as a thrown exception.
	 */
	@Test
	void twoSettlementsOverlappingByOneDraftLeaveTheLoserWithNothing() throws Exception {
		UUID expertId = experts.save(new Expert(BRAND_IE, "Dr Overlap " + UUID.randomUUID())).getId();
		UUID onlyMine = openPendingPayout(expertId, "100.00");
		UUID shared = openPendingPayout(expertId, "200.00");
		UUID onlyTheirs = openPendingPayout(expertId, "300.00");

		UUID winningPayment = insertPayment(expertId, "300.00");
		UUID losingPayment = insertPayment(expertId, "500.00");

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection winner = testConnection(); Connection loser = testConnection()) {
			int taken = attach(winner, winningPayment, onlyMine, shared);
			assertThat(taken).isEqualTo(2);

			Future<Integer> lost = pool.submit(() -> attach(loser, losingPayment, shared, onlyTheirs));

			assertThatThrownBy(() -> lost.get(1, TimeUnit.SECONDS))
					.describedAs("the loser must block on the row the winner holds; "
							+ "without a real overlap this test proves nothing")
					.isInstanceOf(TimeoutException.class);

			winner.commit();

			// It does not fail — it affects one row instead of the two it named, because the
			// shared row no longer satisfies `status = 'PENDING'`. That shortfall is the signal.
			assertThat(lost.get(10, TimeUnit.SECONDS))
					.describedAs("the loser must come up short rather than overwrite the winner's row")
					.isEqualTo(1);
			loser.rollback();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(paymentOf(onlyMine)).isEqualTo(winningPayment);
		assertThat(paymentOf(shared))
				.describedAs("the contested row belongs to the winner alone")
				.isEqualTo(winningPayment);
		assertThat(paymentOf(onlyTheirs))
				.describedAs("the loser rolled back, so its untouched row keeps no payment")
				.isNull();
		assertThat(statusOf(onlyTheirs)).isEqualTo("PENDING");

		Integer orphaned = jdbc.queryForObject(
				"SELECT count(*) FROM payout_ledger WHERE payment_id = ?", Integer.class, losingPayment);
		assertThat(orphaned).isZero();
	}

	/** A connection on the suite's own schema, in an explicit transaction. */
	private static Connection testConnection() throws SQLException {
		Properties credentials = new Properties();
		credentials.setProperty("user", envOr("DB_USER", "postgres"));
		credentials.setProperty("password", envOr("DB_PASSWORD", "1234"));
		Connection connection = DriverManager.getConnection(envOr("DB_TEST_URL", TEST_URL), credentials);
		connection.setAutoCommit(false);
		return connection;
	}

	/** The insert `deliverToClient` makes, issued raw so two of them can overlap. */
	private static void insertPendingPayout(Connection connection, UUID caseId, UUID expertId, String amount)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO payout_ledger (brand_id, case_id, expert_id, amount, currency, status, due_date)
				VALUES (?, ?, ?, CAST(? AS numeric), 'USD', 'PENDING', now())
				""")) {
			statement.setObject(1, BRAND_IE);
			statement.setObject(2, caseId);
			statement.setObject(3, expertId);
			statement.setString(4, amount);
			statement.executeUpdate();
		}
	}

	/**
	 * {@code attachToPayment}'s statement, in SQL, so two transactions can contend for one row.
	 *
	 * <p><strong>This is a hand-kept mirror of the JPQL, and that is the one weakness of these
	 * two tests.</strong> Spring's repository shares the pooled connection and its transaction,
	 * so there is no way to have two of its calls genuinely overlap; raw connections are what
	 * make a real race possible at all. The consequence is that these tests prove the
	 * <em>database</em> honours the precondition-in-the-WHERE pattern, not that
	 * {@link PayoutLedgerRepository#attachToPayment} still writes it. If somebody drops the
	 * {@code status = 'PENDING'} predicate from the JPQL, this test keeps passing. The unit
	 * suite is what pins the production statement; this pins the semantics it relies on.
	 */
	private static int attach(Connection connection, UUID paymentId, UUID... payoutIds) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				UPDATE payout_ledger
				   SET payment_id = ?, status = 'PAID'
				 WHERE id = ANY (?) AND brand_id = ? AND status = 'PENDING'
				""")) {
			statement.setObject(1, paymentId);
			statement.setArray(2, connection.createArrayOf("uuid", payoutIds));
			statement.setObject(3, BRAND_IE);
			return statement.executeUpdate();
		}
	}

	private UUID openPendingPayout(UUID expertId, String amount) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO payout_ledger (id, brand_id, case_id, expert_id, amount, currency, status, due_date)
				VALUES (?, ?, ?, ?, CAST(? AS numeric), 'USD', 'PENDING', now())
				""", id, BRAND_IE, caseWithId(expertId), expertId, amount);
		return id;
	}

	private UUID insertPayment(UUID expertId, String amount) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO payout_payment
				    (id, brand_id, expert_id, amount, currency, method, reference, paid_date, recorded_by)
				VALUES (?, ?, ?, CAST(? AS numeric), 'USD', 'Zelle', ?, now(), ?)
				""", id, BRAND_IE, expertId, amount, "ZELLE-" + id, BM_IE);
		return id;
	}

	private UUID caseWithId(UUID expertId) {
		Case subject = new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.FINAL_DELIVERY);
		subject.setExpertId(expertId);
		return cases.saveAndFlush(subject).getId();
	}

	private UUID paymentOf(UUID payoutId) {
		return jdbc.queryForObject(
				"SELECT payment_id FROM payout_ledger WHERE id = ?", UUID.class, payoutId);
	}

	private String statusOf(UUID payoutId) {
		return jdbc.queryForObject("SELECT status FROM payout_ledger WHERE id = ?", String.class, payoutId);
	}
}
