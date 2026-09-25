package com.ie.evalos.repository;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.domain.ApplicationDocument;
import com.ie.evalos.service.ScopePredicate;

import org.springframework.data.jpa.repository.JpaRepository;

/** Documents attached to a client request before it became a case (Unit 53). */
public interface ApplicationDocumentRepository extends JpaRepository<ApplicationDocument, UUID> {

	/**
	 * Brand only, and the application id is carried in every finder's signature besides.
	 *
	 * <p>There is no pipeline axis here and there should not be: a request document is reached
	 * either by the client who uploaded it (a portal token, matched on their own application) or by
	 * staff who can already open the opportunity it belongs to. {@code 53} §3 is explicit that the
	 * documents "ask no new authorisation question".
	 */
	ScopePredicate.Fields SCOPE = ScopePredicate.Fields.brandOnly("brandId");

	List<ApplicationDocument> findByBrandIdAndClientApplicationIdOrderByUploadedAtDesc(UUID brandId,
			UUID clientApplicationId);

	/** How many rode with this request — what the submission confirmation states. */
	int countByClientApplicationId(UUID clientApplicationId);

	/** What Handoff A still has to carry. Empty on a replay, which is how the skip is expressed. */
	List<ApplicationDocument> findByClientApplicationIdAndCarriedToCaseDocumentIdIsNull(
			UUID clientApplicationId);
}
