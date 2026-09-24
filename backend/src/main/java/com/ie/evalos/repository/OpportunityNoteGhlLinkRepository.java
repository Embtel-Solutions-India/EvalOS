package com.ie.evalos.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.OpportunityNoteGhlLink;

import org.springframework.data.repository.Repository;

/**
 * Pushed notes' GHL ids (Unit 54).
 *
 * <p>A bare {@link Repository}: save, find, and the one delete the drain needs once GHL has
 * confirmed a note's deletion (Unit 54a).
 */
public interface OpportunityNoteGhlLinkRepository extends Repository<OpportunityNoteGhlLink, UUID> {

	OpportunityNoteGhlLink save(OpportunityNoteGhlLink link);

	boolean existsById(UUID noteId);

	java.util.Optional<OpportunityNoteGhlLink> findById(UUID noteId);

	void delete(OpportunityNoteGhlLink link);

	/** The links among these notes — which of a deal's EvalOS notes have reached GHL. */
	List<OpportunityNoteGhlLink> findByBrandIdAndNoteIdIn(UUID brandId, Collection<UUID> noteIds);

	/** The links among these GHL notes — which mirrored notes are echoes of EvalOS's own. */
	List<OpportunityNoteGhlLink> findByBrandIdAndGhlNoteIdIn(UUID brandId, Collection<String> ghlNoteIds);
}
