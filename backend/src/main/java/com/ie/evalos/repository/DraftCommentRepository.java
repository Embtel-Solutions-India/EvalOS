package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.DraftComment;

import org.springframework.data.repository.Repository;

/** Only save and read exist: a comment is never edited or deleted (the V70 trigger agrees). */
public interface DraftCommentRepository extends Repository<DraftComment, UUID> {

	DraftComment save(DraftComment comment);

	List<DraftComment> findByDocumentIdOrderByCreatedAtAsc(UUID documentId);
}
