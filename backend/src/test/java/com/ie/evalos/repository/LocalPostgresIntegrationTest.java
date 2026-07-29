package com.ie.evalos.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Brand;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ContactSnapshot;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The acceptance evidence for Unit 03 that only a real PostgreSQL can produce:
 * the migrations apply, {@code ddl-auto=validate} agrees that every entity
 * matches the migrated schema (including {@code text[]} and {@code jsonb}), the
 * payment detail is ciphertext on disk, scoped finders keep two brands apart, and
 * the audit table refuses to be edited.
 *
 * <p>Disabled unless {@code -Devalos.db.test=true} is passed, because it needs a
 * database that a clean checkout has no way to provide. There is no Testcontainers
 * dependency: this machine has no Docker, and a test that cannot run is worse than
 * one that says how to run it.
 *
 * <pre>
 * ./mvnw test -Devalos.db.test=true -Dtest=LocalPostgresIntegrationTest \
 *     -DDB_URL=jdbc:postgresql://localhost:5432/evalos
 * </pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "evalos.db.test", matches = "true")
class LocalPostgresIntegrationTest {

	/** Seeded by {@code V900__seed_local.sql}. */
	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID BRAND_XP = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID GM = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID BM_IE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

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

	@Test
	void everyMigrationApplied() {
		List<String> versions = jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

		assertThat(versions)
				.containsSubsequence("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13",
						"14", "15", "16");
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
		// A deadline filter on a case with no deadline: the row drops out, it does not error.
		assertThat(ids(cases.findScoped(brandManagerOfIe(), null, Instant.now())))
				.doesNotContain(id);

		// And a case in another brand stays out however the filters are combined.
		UUID other = cases.save(new Case(BRAND_XP, "XP-" + UUID.randomUUID(), Stage.EXPERT_SIGNING)).getId();
		assertThat(ids(cases.findScoped(brandManagerOfIe(), Stage.EXPERT_SIGNING, null)))
				.doesNotContain(other);
	}

	/** The two derived finders Unit 04 added, executed rather than merely resolved. */
	@Test
	void theCaseScopedFindersReturnOnlyThatCasesRows() {
		UUID caseId = cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DOC_COLLECTION)).getId();

		assertThat(checklistItems.findByCaseId(caseId)).isEmpty();
		assertThat(payouts.findByCaseIdAndStatus(caseId, PayoutStatus.PENDING)).isEmpty();
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
}
