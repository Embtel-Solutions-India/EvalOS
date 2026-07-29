package com.ie.evalos.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.NotificationRepository;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The centre reads one member's rows and no others'. The point of these tests is that
 * the *identity*, not the tier, is what narrows every read — a GM is Tier.ALL and must
 * still see only their own bell.
 */
class NotificationServiceTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID ME = UUID.randomUUID();
	private static final UUID SOMEBODY_ELSE = UUID.randomUUID();
	private static final UUID CASE_ID = UUID.randomUUID();

	private final NotificationRepository notifications = mock(NotificationRepository.class);
	private final NotificationService service = new NotificationService(notifications);

	@AfterEach
	void clearCaller() {
		SecurityContextHolder.clearContext();
	}

	private void actAs(UUID memberId, Role role) {
		StaffPrincipal principal = new StaffPrincipal(memberId, "staff@evalos.local", "Staff", role,
				role == Role.GM ? null : BRAND, null, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@Test
	void theListIsKeyedOnTheCallersOwnMemberIdEvenForAGm() {
		actAs(ME, Role.GM);

		service.mine(0, 20);

		// A GM's tier is ALL. If this read went through findScoped it would return every
		// member's notifications in every brand, which is the one thing a bell must not do.
		verify(notifications).findByRecipientIdOrderByReadAscCreatedAtDesc(eq(ME), any(Pageable.class));
	}

	@Test
	void theListIsUnreadFirstAndPaged() {
		actAs(ME, Role.CASE_MANAGER);

		service.mine(2, 5);

		ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
		verify(notifications).findByRecipientIdOrderByReadAscCreatedAtDesc(eq(ME), page.capture());
		assertThat(page.getValue()).isEqualTo(PageRequest.of(2, 5));
	}

	@Test
	void aNonsensePageFallsBackRatherThanThrowing() {
		actAs(ME, Role.CASE_MANAGER);

		service.mine(-3, 0);

		ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
		verify(notifications).findByRecipientIdOrderByReadAscCreatedAtDesc(eq(ME), page.capture());
		assertThat(page.getValue()).isEqualTo(PageRequest.of(0, NotificationService.DEFAULT_PAGE_SIZE));
	}

	@Test
	void theUnreadCountIsTheCallersOwn() {
		actAs(ME, Role.PROJECT_MANAGER);
		given(notifications.countByRecipientIdAndReadFalse(ME)).willReturn(3L);

		assertThat(service.myUnreadCount()).isEqualTo(3L);
	}

	@Test
	void markingOnesOwnReadFlipsItAndSaves() {
		actAs(ME, Role.PROJECT_MANAGER);
		Notification mine = new Notification(BRAND, ME, NotificationType.STAGE_CHANGED, CASE_ID, "yours");
		given(notifications.findByIdAndRecipientId(any(), eq(ME))).willReturn(Optional.of(mine));
		given(notifications.save(mine)).willReturn(mine);

		assertThat(service.markRead(UUID.randomUUID()).isRead()).isTrue();
		verify(notifications).save(mine);
	}

	/**
	 * Somebody else's id and a nonexistent id are the same answer. The recipient is in
	 * the query, so there is no row in hand to tell the two apart — which is what stops
	 * the endpoint being used to probe for other members' notifications.
	 */
	@Test
	void markingSomebodyElsesIsIndistinguishableFromOneThatDoesNotExist() {
		actAs(ME, Role.PROJECT_MANAGER);
		UUID theirs = UUID.randomUUID();
		UUID nobodys = UUID.randomUUID();
		// Neither is stubbed, because neither can come back for this caller.

		String forTheirs = assertThatThrownByMessage(theirs);
		String forNobodys = assertThatThrownByMessage(nobodys);

		assertThat(forTheirs).isEqualTo(forNobodys);
		assertThat(forTheirs).doesNotContain(theirs.toString(), SOMEBODY_ELSE.toString());
	}

	private String assertThatThrownByMessage(UUID id) {
		try {
			service.markRead(id);
			throw new AssertionError("expected ForbiddenException");
		}
		catch (ForbiddenException expected) {
			return expected.getMessage();
		}
	}

	@Test
	void readAllOnlyTouchesTheCallersOwnRows() {
		actAs(ME, Role.BRAND_MANAGER);
		given(notifications.markAllReadFor(ME)).willReturn(4);

		assertThat(service.markAllRead()).isEqualTo(4);
		verify(notifications).markAllReadFor(ME);
	}

	@Test
	void createWritesOneRowPerRecipientTaggedWithTheGivenBrand() {
		service.create(BRAND, List.of(ME, SOMEBODY_ELSE), NotificationType.NEW_LEAD, CASE_ID, "a lead");

		ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
		verify(notifications, org.mockito.Mockito.times(2)).save(saved.capture());
		assertThat(saved.getAllValues())
				.allSatisfy(row -> assertThat(row.getBrandId()).isEqualTo(BRAND))
				.extracting(Notification::getRecipientId)
				.containsExactly(ME, SOMEBODY_ELSE);
	}

	/** No caller, no problem: a listener creates rows outside any request. */
	@Test
	void createNeedsNoAuthenticatedCaller() {
		service.create(BRAND, List.of(ME), NotificationType.NEW_LEAD, CASE_ID, "a lead");

		verify(notifications).save(any(Notification.class));
	}

	@Test
	void createWithNoRecipientsWritesNothing() {
		service.create(BRAND, List.of(), NotificationType.NEW_LEAD, CASE_ID, "a lead");

		verify(notifications, org.mockito.Mockito.never()).save(any());
	}
}
