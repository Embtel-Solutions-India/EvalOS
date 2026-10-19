package com.ie.evalos.chat.push;

import java.time.Instant;
import java.util.UUID;

import com.ie.evalos.chat.ParticipantKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V92 on a real Postgres: a queued retry is invisible to the drain until it falls due, and a browser
 * forgotten for any reason takes its owed pushes with it (the cascade, not code). Same gate and schema
 * as {@code MemberTargetServiceDbTest}; it also proves V92 applies.
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
class PushOutboxDbTest {

	/** The seeded International Evaluations brand. */
	private static final UUID BRAND_IE = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Autowired
	PushSender sender;
	@Autowired
	PushOutboxRepository outbox;
	@Autowired
	PushSubscriptionRepository subscriptions;

	@Test
	void aRetryIsDueAfterItsWaitAndGoesWithItsBrowser() {
		PushSubscription laptop = subscriptions.save(new PushSubscription(BRAND_IE, ParticipantKind.STAFF,
				UUID.randomUUID(), "https://push.test/" + UUID.randomUUID(), "p", "a"));

		sender.queueRetry(laptop, "{\"title\":\"x\"}");

		PushRetry queued = outbox.findAll().stream()
				.filter((row) -> row.getSubscriptionId().equals(laptop.getId()))
				.findFirst()
				.orElseThrow();
		assertThat(queued.getBrandId()).isEqualTo(BRAND_IE);
		assertThat(outbox.findTop100ByDueAtLessThanEqualOrderByDueAtAsc(Instant.now()))
				.extracting(PushRetry::getId).doesNotContain(queued.getId());
		assertThat(outbox.findTop100ByDueAtLessThanEqualOrderByDueAtAsc(Instant.now().plusSeconds(31)))
				.extracting(PushRetry::getId).contains(queued.getId());

		subscriptions.deleteById(laptop.getId());

		assertThat(outbox.existsById(queued.getId())).isFalse();
	}
}
