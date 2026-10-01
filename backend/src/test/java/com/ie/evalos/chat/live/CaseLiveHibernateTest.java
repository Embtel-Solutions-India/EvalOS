package com.ie.evalos.chat.live;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.domain.ActorType;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseDocument;
import com.ie.evalos.domain.ChecklistItemStatus;
import com.ie.evalos.domain.DocumentChecklistItem;
import com.ie.evalos.domain.DocumentKind;
import com.ie.evalos.domain.DraftComment;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.PayoutLedger;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseDocumentRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.DraftCommentRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/**
 * Unit 70 §6: on a real Postgres and a real Hibernate, saving each case-owned row tells
 * {@link CaseLive} which case changed — the listener is registered, sees every entity in §2.1, and
 * resolves a draft comment through its document. {@code CaseLive} is a mock here, so this proves
 * the wiring; its own after-commit and rollback behaviour is {@code CaseLiveTest}'s.
 *
 * <p>Same database, schema and gate as {@code LocalPostgresIntegrationTest}: it runs when a usable
 * Postgres is there and skips otherwise.
 */
@SpringBootTest
@EnabledIf("com.ie.evalos.repository.LocalPostgresIntegrationTest#postgresIsUsable")
@TestPropertySource(properties = {
		"spring.datasource.url=${DB_TEST_URL:jdbc:postgresql://localhost:5432/evalos?currentSchema=evalos_test}",
		"spring.flyway.schemas=evalos_test",
		"spring.flyway.create-schemas=true",
		"spring.flyway.locations=classpath:db/migration,classpath:db/seed-local",
		"spring.flyway.out-of-order=true",
		"spring.flyway.ignore-migration-patterns=*:missing",
		"spring.jpa.properties.hibernate.default_schema=evalos_test",
		"spring.jpa.show-sql=false",
		"evalos.jobs.enabled=false",
})
class CaseLiveHibernateTest {

	/** Seeded by the local seed: the IE brand and an IE expert. */
	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID EXPERT_IE = UUID.fromString("e0000000-0000-0000-0000-000000000002");

	@MockitoBean
	CaseLive live;

	@Autowired
	TransactionTemplate tx;
	@Autowired
	CaseRepository cases;
	@Autowired
	CaseDocumentRepository documents;
	@Autowired
	DocumentChecklistItemRepository checklist;
	@Autowired
	ExpertCaseOfferRepository offers;
	@Autowired
	PayoutLedgerRepository payouts;
	@Autowired
	DraftCommentRepository comments;

	private Case subject;

	@BeforeEach
	void aCase() {
		subject = tx.execute(s -> cases.save(new Case(BRAND_IE, "EV-" + UUID.randomUUID(), Stage.DRAFT_REVIEW)));
		clearInvocations(live);
	}

	@Test
	void savingTheCaseItselfSignalsIt() {
		tx.executeWithoutResult(s -> {
			Case loaded = cases.findById(subject.getId()).orElseThrow();
			loaded.setDraftVersionCount(loaded.getDraftVersionCount() + 1);
		});

		verify(live, atLeastOnce()).touched(BRAND_IE, subject.getId());
	}

	@Test
	void everyCaseOwnedRowSignalsItsCase() {
		tx.executeWithoutResult(s -> checklist.save(
				new DocumentChecklistItem(BRAND_IE, subject.getId(), "Transcript", ChecklistItemStatus.REQUIRED)));
		verify(live).touched(BRAND_IE, subject.getId());
		clearInvocations(live);

		tx.executeWithoutResult(s -> documents.save(draft()));
		verify(live).touched(BRAND_IE, subject.getId());
		clearInvocations(live);

		tx.executeWithoutResult(s -> offers.save(new ExpertCaseOffer(BRAND_IE, subject.getId(), EXPERT_IE)));
		verify(live).touched(BRAND_IE, subject.getId());
		clearInvocations(live);

		tx.executeWithoutResult(s -> payouts.save(new PayoutLedger(BRAND_IE, subject.getId(), EXPERT_IE,
				new BigDecimal("350.00"), "USD", Instant.parse("2026-11-01T00:00:00Z"))));
		verify(live).touched(BRAND_IE, subject.getId());
	}

	/** A draft comment has no case of its own: the listener hands over its document. */
	@Test
	void aDraftCommentSignalsThroughItsDocument() {
		CaseDocument version = tx.execute(s -> documents.save(draft()));
		clearInvocations(live);

		tx.executeWithoutResult(s -> comments.save(new DraftComment(BRAND_IE, version.getId(),
				DraftComment.AuthorKind.STAFF, UUID.randomUUID(), "Fix para 2", 1)));

		verify(live).touchedDocument(BRAND_IE, version.getId());
	}

	private CaseDocument draft() {
		return new CaseDocument(BRAND_IE, subject.getId(), DocumentKind.DRAFT, 1, null, ActorType.STAFF, null);
	}
}
