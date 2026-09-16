package com.ie.evalos.integration;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The custom fields a location defines on its opportunities.
 *
 * <p><strong>Why this exists.</strong> GHL's own "add opportunity" form is not the documented
 * request body — a location's custom fields are part of it, and IE's location defines twenty. Six
 * of them carry the intake facts EvalOS's own `GhlOpportunityHandler` says a PM has to fill in by
 * hand ("Visa category, subtype, deadline, invoice ref, expert and the intake note are a PM's to
 * fill in"). A sales form that omitted them would be asking a salesperson to open a deal that is
 * missing the data production will later have to chase the client for.
 *
 * <p><strong>Read, never written.</strong> EvalOS does not create, rename or delete custom field
 * definitions — they are the location's, and a definition invented here would be one GHL's own
 * forms do not know about. This client lists them so a form can render them and so a create can
 * send values against ids it did not guess.
 *
 * <p><strong>The ids are not stable across locations.</strong> Every one of them is scoped to
 * {@code locationId}, which is why nothing here is hardcoded and why the list is fetched rather
 * than configured — the sub-account swap on 2026-09-11 would have invalidated a hardcoded set
 * exactly as it invalidated every pipeline id.
 */
@Component
public class GhlCustomFieldClient {

	/** The scope this read needs. Listed for the same reason every other client here lists one. */
	public static final String READ_SCOPE = "locations.readonly";

	/**
	 * One field definition, narrowed to what a form needs to draw it.
	 *
	 * <p>GHL's payload also carries {@code position}, {@code parentId}, {@code documentType},
	 * {@code standard}, {@code scopes} and {@code dateAdded}. None of them changes how a control
	 * is rendered, and the narrowing is the same discipline every other client here applies.
	 *
	 * @param dataType        GHL's own word — {@code TEXT}, {@code LARGE_TEXT}, {@code NUMERICAL},
	 *                        {@code DATE}, {@code SINGLE_OPTIONS}. Passed through rather than
	 *                        mapped onto an EvalOS enum: a location can add a type tomorrow, and
	 *                        an enum would turn that into a deserialization failure on a form that
	 *                        only wanted to pick an input.
	 * @param picklistOptions empty for every type except {@code SINGLE_OPTIONS}
	 */
	public record CustomField(String id, String name, String fieldKey, String dataType,
			List<String> picklistOptions) {

		public CustomField {
			picklistOptions = picklistOptions == null ? List.of() : List.copyOf(picklistOptions);
		}
	}

	private final GhlHttp http;

	GhlCustomFieldClient(GhlHttp http) {
		this.http = http;
	}

	/**
	 * Every custom field this location defines on an opportunity, in GHL's own order.
	 *
	 * @throws GhlUnavailableException if GHL is not configured here or refused the request
	 */
	public List<CustomField> forOpportunities() {
		FieldsResponse response = http.get(FieldsResponse.class,
				(uri) -> uri.path("/locations/{locationId}/customFields")
						.queryParam("model", "opportunity")
						.build(http.locationId()));

		return Optional.ofNullable(response)
				.map(FieldsResponse::customFields)
				.orElseGet(List::of)
				.stream()
				.map((row) -> new CustomField(row.id(), row.name(), row.fieldKey(), row.dataType(),
						row.picklistOptions()))
				.toList();
	}

	// --- wire shapes -----------------------------------------------------------------

	record FieldsResponse(List<FieldRow> customFields) {
	}

	record FieldRow(String id, String name, String fieldKey, String dataType,
			List<String> picklistOptions) {
	}
}
