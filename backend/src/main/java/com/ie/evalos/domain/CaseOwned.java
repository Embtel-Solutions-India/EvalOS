package com.ie.evalos.domain;

import java.util.UUID;

/**
 * A row whose write changes what a case's screens show (Unit 70 §2.1). The Hibernate listener in
 * {@code chat.live.CaseLiveHibernate} turns every insert, update and delete of one into a
 * {@code case.changed} signal after commit — so a new write path is covered without remembering it.
 */
public interface CaseOwned {

	UUID getBrandId();

	/** The case this row belongs to. */
	UUID liveCaseId();
}
