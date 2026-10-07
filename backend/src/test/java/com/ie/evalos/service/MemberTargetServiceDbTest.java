package com.ie.evalos.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.TeamMemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D75: the newest-row, "not set" and brand-scoping rules of {@code member_monthly_target} are SQL, so
 * they are proved on a real Postgres. Same database, schema and gate as {@code CaseLiveHibernateTest};
 * it also proves V86 applies. Each run uses a month no other run uses, because rows are never deleted.
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
class MemberTargetServiceDbTest {

	@Autowired
	MemberTargetService service;
	@Autowired
	TeamMemberRepository members;
	@Autowired
	JdbcTemplate jdbc;

	private TeamMember sales;
	private LocalDate month;
	private UUID gm;

	@BeforeEach
	void aSalesMemberAndAMonthNobodyElseUses() {
		sales = members.findAll().stream().filter((m) -> m.getRole() == Role.SALES).findFirst().orElseThrow();
		gm = members.findAll().stream().filter((m) -> m.getRole() == Role.GM).findFirst().orElseThrow().getId();
		month = LocalDate.of(2100 + ThreadLocalRandom.current().nextInt(800), 1 + ThreadLocalRandom.current().nextInt(12), 1);
	}

	private int rows() {
		return jdbc.queryForObject("SELECT count(*) FROM member_monthly_target WHERE team_member_id = ? AND month = ?",
				Integer.class, sales.getId(), month);
	}

	@Test
	void aMonthWithNoRowIsNotSetAndAZeroTargetIsZero() {
		assertThat(service.current(sales.getBrandId(), sales.getId(), month)).isEmpty();

		service.set(sales.getId(), month, BigDecimal.ZERO, gm);

		assertThat(service.current(sales.getBrandId(), sales.getId(), month)).hasValueSatisfying(
				(zero) -> assertThat(zero).isEqualByComparingTo("0"));
	}

	@Test
	void theNewestRowIsTheTargetAndTheHistoryIsKept() {
		service.set(sales.getId(), month, new BigDecimal("1000"), gm);
		service.set(sales.getId(), month, new BigDecimal("1500"), gm);

		assertThat(service.current(sales.getBrandId(), sales.getId(), month)).hasValueSatisfying(
				(target) -> assertThat(target).isEqualByComparingTo("1500"));
		assertThat(rows()).isEqualTo(2);
	}

	@Test
	void theMonthsAmountsAreTheNewestPerMemberAndOnlyThatBrands() {
		service.set(sales.getId(), month, new BigDecimal("1000"), gm);
		service.set(sales.getId(), month, new BigDecimal("1500"), gm);

		assertThat(service.latestForMonth(sales.getBrandId(), month)).hasEntrySatisfying(sales.getId(),
				(target) -> assertThat(target).isEqualByComparingTo("1500"));
		assertThat(service.latestForMonth(UUID.randomUUID(), month)).isEmpty();
	}

	@Test
	void aTargetIsInvisibleToAnotherBrand() {
		service.set(sales.getId(), month, new BigDecimal("1000"), gm);

		assertThat(service.current(UUID.randomUUID(), sales.getId(), month)).isEmpty();
	}

	@Test
	void anyDayOfTheMonthReadsTheSameTarget() {
		service.set(sales.getId(), month.withDayOfMonth(20), new BigDecimal("700"), gm);

		assertThat(service.current(sales.getBrandId(), sales.getId(), month)).hasValueSatisfying(
				(target) -> assertThat(target).isEqualByComparingTo("700"));
	}
}
