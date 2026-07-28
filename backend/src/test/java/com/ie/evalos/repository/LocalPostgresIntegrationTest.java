package com.ie.evalos.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.AuditEvent;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.AuditService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
	ExpertRepository experts;

	@Autowired
	CaseRepository cases;

	@Autowired
	AuditEventRepository auditEvents;

	@Autowired
	AuditService auditService;

	@Test
	void everyMigrationApplied() {
		List<String> versions = jdbc.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

		assertThat(versions).containsSubsequence("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
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

	private static TenantContext brandManagerOfIe() {
		return new TenantContext(BM_IE, Role.BRAND_MANAGER, BRAND_IE, null);
	}

	private static TenantContext gm() {
		return new TenantContext(GM, Role.GM, null, null);
	}
}
