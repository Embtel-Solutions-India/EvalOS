package com.ie.evalos.integration;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The location's GHL users, for the booking dialog's "Team member" picker.
 *
 * <p><strong>Read-only, and EvalOS holds none of them.</strong> A GHL user is not an EvalOS
 * {@code team_member} — there is no column linking the two, which is exactly why
 * {@code SalesOpportunityController} refuses to send {@code assignedTo} on an opportunity: a
 * guess would assign the deal to the wrong person. A meeting is different only because the
 * salesperson picks the name themselves, from this list, so nothing is being inferred.
 *
 * <p><strong>Names and emails only.</strong> GHL's payload also carries {@code roles},
 * {@code scopes}, {@code permissions} and {@code lcPhone}. A picker needs a label and a value, and
 * carrying a colleague's permission set into EvalOS would be holding a second copy of GHL's access
 * model.
 */
@Component
public class GhlUserClient {

	/** Deleted users stay in GHL's list and must not reach a picker. */
	public record User(String id, String name, String email) {
	}

	private final GhlHttp http;

	GhlUserClient(GhlHttp http) {
		this.http = http;
	}

	public List<User> inLocation() {
		UsersResponse response = http.get(UsersResponse.class,
				(uri) -> uri.path("/users/").queryParam("locationId", http.locationId()).build());

		return Optional.ofNullable(response == null ? null : response.users()).orElseGet(List::of)
				.stream()
				.filter((row) -> !Boolean.TRUE.equals(row.deleted()))
				.map((row) -> new User(row.id(), row.name(), row.email()))
				.toList();
	}

	// --- wire shapes -----------------------------------------------------------------

	record UsersResponse(List<UserRow> users) {
	}

	record UserRow(String id, String name, String email, Boolean deleted) {
	}
}
