package com.ie.evalos.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.GhlNote;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Notes written in GHL (Unit 47b).
 *
 * <p>Brand only. A note is reached through the opportunity or contact it hangs off, both of which
 * are already scoped by the time anything asks for its notes — narrowing again here would be the
 * same fact expressed twice.
 */
public interface GhlNoteRepository extends JpaRepository<GhlNote, UUID> {

	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	Optional<GhlNote> findByBrandIdAndGhlId(UUID brandId, String ghlId);

	List<GhlNote> findByBrandIdAndGhlOpportunityIdOrderByDateAddedDesc(UUID brandId,
			String ghlOpportunityId);

	/** Notes on the contact that no deal claims — shown on every deal of that contact (Unit 54). */
	List<GhlNote> findByBrandIdAndGhlContactIdAndGhlOpportunityIdIsNull(UUID brandId, String ghlContactId);
}
