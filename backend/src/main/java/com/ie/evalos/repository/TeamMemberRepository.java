package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Reads go through {@link JpaSpecificationExecutor} with a scope Specification —
 * see {@code ScopePredicate}. Do not add an unscoped finder that can return rows
 * across brands; the login lookup below is the one deliberate exception, because
 * authentication happens before a tenant is known.
 */
public interface TeamMemberRepository extends JpaRepository<TeamMember, UUID>, JpaSpecificationExecutor<TeamMember> {

	Optional<TeamMember> findByEmailIgnoreCaseAndActiveTrue(String email);

	/**
	 * One member of one brand in one role — the lookup behind putting somebody on a
	 * case. Brand and role are part of the query on purpose: a caller must not be
	 * able to tell a member id belonging to another brand from one that does not
	 * exist, and that difference is only invisible if the row never comes back.
	 */
	Optional<TeamMember> findByIdAndBrandIdAndRole(UUID id, UUID brandId, Role role);

	/**
	 * The GM pool. Deliberately not brand-filtered — the GM is the one brand-less
	 * role — and deliberately narrowed to a role, so this cannot become a general
	 * cross-brand staff read.
	 */
	List<TeamMember> findByActiveTrueAndRole(Role role);

	/** One brand's members in one role: the Brand Manager half of the pool notification. */
	List<TeamMember> findByActiveTrueAndRoleAndBrandId(Role role, UUID brandId);
}
