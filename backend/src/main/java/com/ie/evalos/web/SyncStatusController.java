package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.SyncDrift;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.service.SyncAuditService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What EvalOS and GHL currently disagree about — Unit 45, slice B.
 *
 * <p><strong>This is the surface {@code 00c} §4c's guarantee rests on.</strong> That spec promises
 * every divergence is detected; {@code 00d} §6.3 points out the promise is only checkable if the
 * divergences are queryable. They are now, and this is where a human reads them.
 *
 * <p><strong>GM-only, and the reason is that nobody else can act on a row here.</strong> A
 * salesperson told "GHL and EvalOS disagree about your deal's stage" has no action available and no
 * way to judge whether it matters. The person who decides what to do about a broken sync is the one
 * who can look at both systems — and this reads the GHL location, which is invariant 1's stated
 * exception and licensed only for the cross-brand role.
 *
 * <p><strong>There is no route to resolve a row, deliberately.</strong> Resolution is per-field
 * ownership ({@code 00d} §6.2) applied by the sync engine, not a button a GM presses — a human
 * clearing a drift row would clear the <em>symptom</em> and leave the two systems disagreeing.
 * Rows close when a later audit finds the two sides agreeing again.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncStatusController {

	/**
	 * One disagreement.
	 *
	 * <p>Both values travel, because a row naming a field without saying what each side held is one
	 * somebody has to go and investigate by hand — the report-as-a-document failure the table
	 * replaced.
	 */
	public record Drift(UUID id, SyncEntity entityType, UUID entityId, String ghlId, String kind,
			String field, String localValue, String ghlValue, Instant firstDetectedAt,
			Instant lastSeenAt) {
	}

	/**
	 * @param oldest when the longest-standing open drift was first seen, or null when there is none.
	 *               <strong>Age is the number that matters</strong>: one disagreement this morning is
	 *               a sync catching up, and the same one for a fortnight is a sync that is not
	 *               working
	 */
	public record SyncStatus(int open, Instant oldest, List<Drift> drifts) {
	}

	private final SyncAuditService audit;

	SyncStatusController(SyncAuditService audit) {
		this.audit = audit;
	}

	@GetMapping("/drift")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<SyncStatus> drift() {
		List<SyncDrift> open = audit.open();
		return ApiResponse.ok(new SyncStatus(
				open.size(),
				open.stream().map(SyncDrift::getFirstDetectedAt).min(java.util.Comparator.naturalOrder())
						.orElse(null),
				open.stream().map(SyncStatusController::view).toList()));
	}

	private static Drift view(SyncDrift row) {
		return new Drift(row.getId(), row.getEntityType(), row.getEntityId(), row.getGhlId(),
				row.getKind().name(), row.getField(), row.getLocalValue(), row.getGhlValue(),
				row.getFirstDetectedAt(), row.getLastSeenAt());
	}

}
