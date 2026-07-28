package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * What every brand-scoped repository inherits: the two reads that apply the
 * caller's scope, built from the one {@link ScopePredicate}. Each repository
 * declares which of its columns carry brand, team and assignee, and gets both
 * finders for free.
 *
 * <p>The inherited {@code findAll()} and {@code findById()} are not scoped —
 * Spring Data provides them and they cannot be removed. Use {@code findScoped}
 * for reads, and {@code OwnershipGuard} before a write that targets a row found
 * any other way. A scoped read that does not go through here is a defect.
 */
@NoRepositoryBean
public interface ScopedRepository<T> extends JpaRepository<T, UUID>, JpaSpecificationExecutor<T> {

	/** The entity attributes holding brand, team and assignee for this type. */
	ScopePredicate.Fields scopeFields();

	/** Every row this caller may read. */
	default List<T> findScoped(TenantContext ctx) {
		return findAll(ScopePredicate.of(ctx, scopeFields()));
	}

	/** One row by id, empty when it exists but is out of the caller's scope. */
	default Optional<T> findScoped(TenantContext ctx, UUID id) {
		return findOne(ScopePredicate.<T>of(ctx, scopeFields())
				.and((root, query, cb) -> cb.equal(root.get("id"), id)));
	}
}
