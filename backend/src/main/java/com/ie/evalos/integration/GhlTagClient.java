package com.ie.evalos.integration;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The tags a location defines — Unit 47b.
 *
 * <p><strong>Read, never written.</strong> GHL workflows key off tags: a tag EvalOS created or
 * applied would be EvalOS triggering an automation the business wrote for its own reasons, which is
 * the one thing {@code 00b} kept GHL for. This lists the vocabulary so a screen can filter or
 * display by it; applying one is a decision with a screen behind it, not a side effect.
 *
 * <p><strong>The vocabulary, not the assignment.</strong> Which contact carries which tag arrives
 * on the contact record. This is the list of tags that exist.
 *
 * <p><strong>It needs its own scope</strong>, {@code locations/tags.readonly}, which is not implied
 * by {@code locations.readonly}. A location that has not granted it answers 401 — and
 * {@code ReferenceMirrorService} refreshes each list independently for exactly this reason, so a
 * missing tag grant does not cost the calendars their refresh.
 */
@Component
public class GhlTagClient {

	/** Listed for the same reason every other client here lists one: so a 401 is diagnosable. */
	public static final String READ_SCOPE = "locations/tags.readonly";

	private final GhlHttp http;

	GhlTagClient(GhlHttp http) {
		this.http = http;
	}

	/**
	 * One tag: GHL's id and the word itself.
	 *
	 * <p>{@code locationId} also comes back on the wire and is dropped — it is the location this
	 * client is already pinned to, and carrying it would invite somebody to trust a payload's idea
	 * of which location they are talking to.
	 */
	public record Tag(String id, String name) {
	}

	/**
	 * Every tag this location defines.
	 *
	 * @throws GhlUnavailableException if GHL is not configured here or refused the request
	 */
	public List<Tag> inLocation() {
		TagsResponse response = http.get(TagsResponse.class,
				(uri) -> uri.path("/locations/{locationId}/tags").build(http.locationId()));

		return Optional.ofNullable(response)
				.map(TagsResponse::tags)
				.orElseGet(List::of)
				.stream()
				.filter((row) -> row.id() != null && !row.id().isBlank())
				.map((row) -> new Tag(row.id(), row.name()))
				.toList();
	}

	// --- wire shapes -----------------------------------------------------------------

	record TagsResponse(List<TagRow> tags) {
	}

	record TagRow(String id, String name, String locationId) {
	}
}
