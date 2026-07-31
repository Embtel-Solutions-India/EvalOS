package com.ie.evalos.domain;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ie.evalos.repository.AuditEventRepository;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.repository.ContactSnapshotRepository;
import com.ie.evalos.repository.DocumentChecklistItemRepository;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.repository.NotificationRepository;
import com.ie.evalos.repository.PayoutLedgerRepository;
import com.ie.evalos.service.CaseIntakeService;
import com.ie.evalos.service.CaseLifecycleService;
import com.ie.evalos.service.ExpertMatchService;
import com.ie.evalos.service.ScopePredicate;
import com.ie.evalos.webhook.GhlContactHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.CrudRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The two structural invariants no runtime test would catch until it was too
 * late: audit rows cannot be changed, and a scope field name is a real column.
 *
 * <p>A misspelled scope attribute compiles fine and only fails when a query runs,
 * which in the worst case is the query that was supposed to keep two brands
 * apart — so the names are checked against the mappings here.
 */
class DomainInvariantsTest {

	private static Stream<Arguments> scopedRepositories() {
		return Stream.of(
				arguments(ContactSnapshotRepository.SCOPE, ContactSnapshot.class),
				arguments(CaseRepository.SCOPE, Case.class),
				arguments(DocumentChecklistItemRepository.SCOPE, DocumentChecklistItem.class),
				arguments(ExpertRepository.SCOPE, Expert.class),
				arguments(ExpertCaseOfferRepository.SCOPE, ExpertCaseOffer.class),
				arguments(PayoutLedgerRepository.SCOPE, PayoutLedger.class),
				arguments(NotificationRepository.SCOPE, Notification.class));
	}

	@ParameterizedTest
	@MethodSource("scopedRepositories")
	void everyScopeFieldNamesARealMappedAttribute(ScopePredicate.Fields scope, Class<?> entity) {
		assertThat(scope.brand()).isNotNull();
		Stream<String> attributes = Stream.concat(
				Stream.of(scope.brand(), scope.team()),
				scope.assignees().stream());
		attributes.filter(Objects::nonNull).forEach(attribute ->
				assertThat(declaresField(entity, attribute))
						.as("%s has no field '%s'", entity.getSimpleName(), attribute)
						.isTrue());
		// Forgetting an axis the entity actually has widens reads silently, which is
		// the one failure direction ScopePredicate cannot fail closed on. The team
		// axis is mechanical, so it is checked; the assignee axis is a judgement
		// (PayoutLedger.recorded_by is not one) and stays the repository's to state.
		if (declaresField(entity, "teamId")) {
			assertThat(scope.team())
					.as("%s has a team_id column, so its scope must narrow by it", entity.getSimpleName())
					.isEqualTo("teamId");
		}
	}

	/**
	 * A new scoped entity whose repository never declared its axes would read
	 * brand-wide without anything failing, so adding one has to break this test.
	 */
	@Test
	void everyScopedEntityHasARepositoryThatDeclaresItsScope() {
		var scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(ScopedEntity.class));

		Set<String> mapped = scanner.findCandidateComponents(ScopedEntity.class.getPackageName()).stream()
				.map(BeanDefinition::getBeanClassName)
				.collect(Collectors.toSet());
		Set<String> declared = scopedRepositories()
				.map(arguments -> ((Class<?>) arguments.get()[1]).getName())
				.collect(Collectors.toSet());

		assertThat(mapped)
				.as("every ScopedEntity needs a ScopedRepository listed in scopedRepositories()")
				.isNotEmpty()
				.isEqualTo(declared);
	}

	/**
	 * Invariant 8: a case is created by the inbound GHL contact webhook and by nothing
	 * else. Asserted structurally because the failure mode is somebody adding a
	 * {@code POST /api/cases} — which would compile, pass every other test, and let a
	 * case exist that GHL has never heard of.
	 *
	 * <p>Payment no longer gates creation, so this is now the only thing keeping case
	 * creation to one door. It matters more than it did, not less.
	 */
	@Test
	void onlyTheGhlContactHandlerCanCreateACase() {
		var scanner = new ClassPathScanningCandidateComponentProvider(true);

		Set<String> injectors = scanner.findCandidateComponents("com.ie.evalos").stream()
				.map(BeanDefinition::getBeanClassName)
				.filter(DomainInvariantsTest::takesTheIntakeService)
				.collect(Collectors.toSet());

		assertThat(injectors)
				.as("case creation is Handoff A's alone — see CaseIntakeService")
				.containsExactly(GhlContactHandler.class.getName());
	}

	private static boolean takesTheIntakeService(String className) {
		try {
			return Stream.of(Class.forName(className).getDeclaredConstructors())
					.flatMap(constructor -> Stream.of(constructor.getParameterTypes()))
					.anyMatch(CaseIntakeService.class::equals);
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Assist mode, held structurally: nothing that moves a case may depend on the match scorer.
	 *
	 * <p>Asserted this way because the failure mode is somebody making the shortlist a
	 * precondition — "assign only an expert the engine proposed" would compile, would look like
	 * a safeguard, and would quietly take the decision away from the PM who read the documents.
	 * There is no auto-assign in EvalOS and there is not meant to be one; the dependency arrow
	 * runs {@code ExpertMatchService → CaseLifecycleService} and never back.
	 */
	@Test
	void theMatchEngineIsNeverAPreconditionForAnAssignment() {
		assertThat(Stream.of(CaseLifecycleService.class.getDeclaredConstructors())
				.flatMap(constructor -> Stream.of(constructor.getParameterTypes())))
				.as("the state machine suggests nothing and consults nothing — see ExpertMatchService")
				.doesNotContain(ExpertMatchService.class);
	}

	/**
	 * The audit snapshot must not carry {@code deal_value}.
	 *
	 * <p>Unit 09 reads the trail back out as a timeline, visible to every role that can open
	 * the case — including the Case Manager, whom invariant 3 keeps away from the deal value.
	 * The timeline is safe because the snapshot never held it, not because the timeline filters
	 * it out, and that is a property worth failing the build over: adding it here would leak it
	 * through a screen nobody would think to re-check.
	 */
	@Test
	void theAuditSnapshotCarriesNoRoleRestrictedField() {
		assertThat(CaseLifecycleService.CaseSnapshot.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.doesNotContain("dealValue", "invoiceRef", "pmStrategyNotes");
	}

	/**
	 * The whitelist is the point: a method added here has to be added deliberately, and the
	 * only ones that may be are {@code save} and reads. Unit 10 added
	 * {@code findByObjectTypeAndActionAndObjectIdIn} so the Coordinator's board can read "last
	 * chased" back out of the trail rather than keeping a second copy of it on the case.
	 */
	@Test
	void theAuditRepositoryCannotChangeHistory() {
		assertThat(AuditEventRepository.class.getMethods())
				.extracting(Method::getName)
				.containsExactlyInAnyOrder(
						"save",
						"findByObjectTypeAndObjectIdOrderByCreatedAtAsc",
						"findByBrandIdOrderByCreatedAtDesc",
						"findByObjectTypeAndActionAndObjectIdIn");

		// Not a CrudRepository, so delete/deleteAll are not inherited either.
		assertThat(CrudRepository.class.isAssignableFrom(AuditEventRepository.class)).isFalse();
	}

	@Test
	void aScopedRowWithNoBrandIsRefusedBeforeItIsWritten() {
		Case orphan = new Case(null, "EV-0001", Stage.DOC_COLLECTION);

		assertThatIllegalStateException()
				.isThrownBy(orphan::stampCreatedAtAndRequireBrand)
				.withMessageContaining("brand_id");
	}

	@Test
	void aScopedRowIsStampedOnPersist() {
		Case scoped = new Case(UUID.randomUUID(), "EV-0002", Stage.DOC_COLLECTION);

		scoped.stampCreatedAtAndRequireBrand();

		assertThat(scoped.getCreatedAt()).isNotNull();
	}

	private static boolean declaresField(Class<?> type, String name) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (field.getName().equals(name)) {
					return true;
				}
			}
		}
		return false;
	}
}
