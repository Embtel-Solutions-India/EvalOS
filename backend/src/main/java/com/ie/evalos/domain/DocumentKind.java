package com.ie.evalos.domain;

/** What a {@link CaseDocument} version is. Mirrors {@code case_document_kind_known} (V31). */
public enum DocumentKind {

	/** The letter the Case Manager writes, reviewed by the PM and then by the client. */
	DRAFT,
	/** A document the client sent in, against a checklist item. */
	CLIENT_UPLOAD,
	/** What the expert signed and sent back (Unit 15). */
	SIGNED_LETTER
}
