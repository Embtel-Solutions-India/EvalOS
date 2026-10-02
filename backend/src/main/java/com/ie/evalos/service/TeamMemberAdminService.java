package com.ie.evalos.service;

import java.util.Objects;
import java.util.UUID;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.Segment;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.BrandRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The GM's staff directory writes (Unit 68): create, describe, deactivate, set a password. The
 * controller gates every route to the GM; this checks the rules the CHECKs would otherwise answer
 * with a 500, and audits each write without ever writing a password or its hash to the trail.
 *
 * <p><strong>No staff mail.</strong> The GM sets the password and hands it over; invariant 14 names
 * the messages EvalOS sends, and a staff credential is not one of them (spec 68 §1.3, Q18).
 */
@Service
public class TeamMemberAdminService {

	static final String OBJECT_TYPE = "TEAM_MEMBER";
	static final int MIN_PASSWORD = 12;

	/** What a GM types. The password travels only on create and on {@link #setPassword}. */
	public record Details(String displayName, String email, Role role, UUID brandId, Segment segment, String ghlUserId) {
	}

	/** What the trail records: never a password, never a hash. */
	record Snapshot(String displayName, String email, Role role, UUID brandId, Segment segment, String ghlUserId,
			boolean active) {

		static Snapshot of(TeamMember m) {
			return new Snapshot(m.getDisplayName(), m.getEmail(), m.getRole(), m.getBrandId(), m.getSegment(),
					m.getGhlUserId(), m.isActive());
		}
	}

	private final TeamMemberRepository members;
	private final TeamMemberPipelineRepository grants;
	private final BrandRepository brands;
	private final PasswordEncoder passwords;
	private final AuditService audit;

	TeamMemberAdminService(TeamMemberRepository members, TeamMemberPipelineRepository grants, BrandRepository brands,
			PasswordEncoder passwords, AuditService audit) {
		this.members = members;
		this.grants = grants;
		this.brands = brands;
		this.passwords = passwords;
		this.audit = audit;
	}

	@Transactional
	public TeamMember create(Details details, String password) {
		requirePassword(password);
		Details clean = validated(details, null);
		TeamMember member = new TeamMember(clean.email(), passwords.encode(password));
		describe(member, clean);
		TeamMember saved = members.save(member);
		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.CREATED, me(), null, Snapshot.of(saved));
		return saved;
	}

	@Transactional
	public TeamMember update(UUID id, Details details) {
		TeamMember member = existing(id);
		Details clean = validated(details, member);
		if (member.getId().equals(me()) && clean.role() != member.getRole()) {
			throw new InvalidRequestException("You cannot change your own role");
		}
		// A desk's grants are to pipelines of its brand and its role; moving either under them
		// would leave a grant meaning something nobody chose.
		boolean deskChanges = member.getRole().isPipelineScoped()
				&& (clean.role() != member.getRole() || !Objects.equals(clean.brandId(), member.getBrandId()));
		if (deskChanges && !grants.ghlIdsFor(member.getId()).isEmpty()) {
			throw new InvalidRequestException("Take this member off their pipelines before changing their role or brand");
		}
		Snapshot before = Snapshot.of(member);
		describe(member, clean);
		TeamMember saved = members.save(member);
		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.UPDATED, me(), before, Snapshot.of(saved));
		return saved;
	}

	/** Takes effect on their next request: {@code JwtFilter} refuses an inactive member. */
	@Transactional
	public TeamMember setActive(UUID id, boolean active) {
		TeamMember member = existing(id);
		if (!active && member.getId().equals(me())) {
			throw new InvalidRequestException("You cannot deactivate yourself");
		}
		Snapshot before = Snapshot.of(member);
		member.setActive(active);
		TeamMember saved = members.save(member);
		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.UPDATED, me(), before, Snapshot.of(saved));
		return saved;
	}

	@Transactional
	public void setPassword(UUID id, String password) {
		requirePassword(password);
		TeamMember member = existing(id);
		member.setPasswordHash(passwords.encode(password));
		members.save(member);
		audit.recordEvent(OBJECT_TYPE, member.getId(), AuditAction.UPDATED, me(), null, "password set by the GM");
	}

	private Details validated(Details d, TeamMember current) {
		if (d.role() == null) {
			throw new InvalidRequestException("A role is required");
		}
		String name = d.displayName() == null ? "" : d.displayName().trim();
		if (name.isEmpty()) {
			throw new InvalidRequestException("A name is required");
		}
		String email = d.email() == null ? "" : d.email().trim().toLowerCase();
		if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
			throw new InvalidRequestException("A valid email is required");
		}
		members.findByEmailIgnoreCase(email).filter(other -> current == null || !other.getId().equals(current.getId()))
				.ifPresent(other -> {
					throw new InvalidRequestException("Another staff member already signs in with " + email);
				});
		// The GM is cross-brand and only the GM is (team_member_brand_required).
		UUID brandId = d.role() == Role.GM ? null : d.brandId();
		if (d.role() != Role.GM && (brandId == null || brands.findById(brandId).isEmpty())) {
			throw new InvalidRequestException("Choose this member's brand");
		}
		// A segment for the two desks and nobody else (team_member_segment_matches_role).
		Segment segment = d.role().isPipelineScoped() ? d.segment() : null;
		if (d.role().isPipelineScoped() && segment == null) {
			throw new InvalidRequestException("Sales and Marketing members need a segment");
		}
		String ghlUser = d.ghlUserId() == null || d.ghlUserId().isBlank() ? null : d.ghlUserId().trim();
		if (ghlUser != null) {
			members.findByGhlUserId(ghlUser).filter(other -> current == null || !other.getId().equals(current.getId()))
					.ifPresent(other -> {
						throw new InvalidRequestException("That GHL user is already linked to " + other.getDisplayName());
					});
		}
		return new Details(name, email, d.role(), brandId, segment, ghlUser);
	}

	private static void describe(TeamMember member, Details d) {
		member.describe(d.displayName(), d.email(), d.role(), d.brandId(), d.segment(), d.ghlUserId());
	}

	private static void requirePassword(String password) {
		if (password == null || password.length() < MIN_PASSWORD) {
			throw new InvalidRequestException("A password needs at least " + MIN_PASSWORD + " characters");
		}
	}

	private TeamMember existing(UUID id) {
		return members.findById(id).orElseThrow(() -> new InvalidRequestException("No such team member"));
	}

	private static UUID me() {
		return TenantContext.current().memberId();
	}
}
