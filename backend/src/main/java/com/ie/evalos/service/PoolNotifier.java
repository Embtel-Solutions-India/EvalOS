package com.ie.evalos.service;

import java.util.UUID;
import java.util.stream.Stream;

import com.ie.evalos.domain.Notification;
import com.ie.evalos.domain.NotificationType;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.NotificationRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import org.springframework.stereotype.Component;

/**
 * Raises an in-app alert for the people who watch a brand's pool: the GM, who reads
 * every brand, and that brand's own Brand Managers. Nobody else — a pool alert is
 * about work nobody owns yet, and the roles who assign it are these two.
 *
 * <p>Exists because more than one caller needs it. Three call sites today:
 * {@code CaseIntakeService} raises {@code NEW_LEAD} when a contact arrives and
 * {@code NEW_CASE_IN_POOL} when that contact arrived already paid, and
 * {@code CaseLifecycleService.markPaid} raises {@code NEW_CASE_IN_POOL} when the money
 * turns up later. Change the recipients or the copy for one and check the others.
 *
 * <p>Unit 06 replaces this with event listeners and a recipient resolver; until then
 * this is the one place the recipient rule lives.
 */
@Component
public class PoolNotifier {

	private final TeamMemberRepository teamMembers;
	private final NotificationRepository notifications;

	PoolNotifier(TeamMemberRepository teamMembers, NotificationRepository notifications) {
		this.teamMembers = teamMembers;
		this.notifications = notifications;
	}

	/** Ids rather than entities, so a caller holding only a case need not load a brand. */
	public void alert(UUID brandId, UUID caseId, NotificationType type, String body) {
		Stream.concat(
				teamMembers.findByActiveTrueAndRole(Role.GM).stream(),
				teamMembers.findByActiveTrueAndRoleAndBrandId(Role.BRAND_MANAGER, brandId).stream())
				.map(TeamMember::getId)
				.forEach(recipient -> notifications.save(new Notification(
						brandId, recipient, type, caseId, body)));
	}
}
