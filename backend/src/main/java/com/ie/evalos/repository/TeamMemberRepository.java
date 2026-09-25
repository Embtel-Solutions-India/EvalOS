package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
	Optional<TeamMember> findByIdAndBrandIdAndRoleAndActiveTrue(UUID id, UUID brandId, Role role);

	/**
	 * Every pipeline owned by an active member of one brand — the GM's board union (Unit 38).
	 *
	 * <p><strong>Brand-scoped, and that is open question P1's answer.</strong> The GM is the one
	 * cross-brand reader, so "every sales pipeline" could have meant every brand's. It means the
	 * selected brand's, because every other screen in the app follows the brand switcher and a
	 * board that silently spanned brands would be the one exception nobody was told about. Moot
	 * while the single-brand ceiling holds; defined anyway, because a screen undefined for a
	 * state the UI can reach is a bug waiting for the second selling brand.
	 *
	 * <p>Only {@code SALES} and {@code MARKETING} rows can have a pipeline at all — the
	 * {@code team_member_pipeline_matches_role} CHECK guarantees it — so the {@code IS NOT NULL}
	 * is the role filter, and adding an explicit role list here would be a second copy of the
	 * constraint that could disagree with it.
	 */
	@Query("select m.ghlPipelineId from TeamMember m "
			+ "where m.brandId = :brandId and m.active = true and m.ghlPipelineId is not null")
	List<String> findPipelinesOfActiveMembers(@Param("brandId") UUID brandId);

	/**
	 * The GM pool. Deliberately not brand-filtered — the GM is the one brand-less
	 * role — and deliberately narrowed to a role, so this cannot become a general
	 * cross-brand staff read.
	 */
	List<TeamMember> findByActiveTrueAndRole(Role role);

	/** One brand's members in one role: the Brand Manager half of the pool notification. */
	List<TeamMember> findByActiveTrueAndRoleAndBrandId(Role role, UUID brandId);
}
