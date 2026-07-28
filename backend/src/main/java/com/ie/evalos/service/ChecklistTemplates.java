package com.ie.evalos.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.ie.evalos.domain.ServiceType;

/**
 * What the client has to send in, per service type. Read once at intake to seed the
 * checklist; the rows are the case's own copy from then on, so editing a template
 * never changes a case already in flight.
 *
 * <p>ponytail: a static map, not a table. It moves into the database the first time
 * a Brand Manager needs to edit a checklist without a deploy — at which point the
 * seed for the new table is this map.
 */
public final class ChecklistTemplates {

	/** Every service type needs the identity and the credential itself. */
	private static final List<String> IDENTITY_AND_CREDENTIAL = List.of(
			"Passport or government photo ID",
			"Degree certificate or diploma",
			"Official transcripts / mark sheets");

	private static final Map<ServiceType, List<String>> BY_SERVICE = new EnumMap<>(ServiceType.class);

	static {
		BY_SERVICE.put(ServiceType.CREDENTIAL_EVALUATION, concat(IDENTITY_AND_CREDENTIAL,
				"Certified English translation of any non-English document"));
		BY_SERVICE.put(ServiceType.EXPERT_OPINION_LETTER, concat(IDENTITY_AND_CREDENTIAL,
				"CV or résumé",
				"Job offer letter or position description",
				"Employment verification letters"));
		BY_SERVICE.put(ServiceType.PERM, concat(IDENTITY_AND_CREDENTIAL,
				"Job description / ETA-9089 details",
				"Prior experience letters"));
		BY_SERVICE.put(ServiceType.RFE_RESPONSE, concat(IDENTITY_AND_CREDENTIAL,
				"The RFE notice as issued",
				"Copy of the original petition"));
		BY_SERVICE.put(ServiceType.TRANSLATION, List.of(
				"Source documents to be translated",
				"Passport or government photo ID"));
	}

	private ChecklistTemplates() {
	}

	/**
	 * The documents to open as {@code REQUIRED}. Falls back to the identity and
	 * credential set for a service type nobody has written a template for yet —
	 * an empty checklist would let {@code markDocsComplete} pass with no documents.
	 */
	public static List<String> forService(ServiceType serviceType) {
		return BY_SERVICE.getOrDefault(serviceType, IDENTITY_AND_CREDENTIAL);
	}

	private static List<String> concat(List<String> base, String... extra) {
		return List.copyOf(Stream.concat(base.stream(), Stream.of(extra)).toList());
	}
}
