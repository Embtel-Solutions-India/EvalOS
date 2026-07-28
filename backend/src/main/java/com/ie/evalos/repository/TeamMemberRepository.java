package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

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
}
