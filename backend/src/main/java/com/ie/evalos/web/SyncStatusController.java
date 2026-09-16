package com.ie.evalos.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.domain.SyncDrift;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.service.SyncAuditService;
import com.ie.evalos.service.SyncOutboxService;

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
 * <p><strong>There is still no route to resolve a row, and 45e is what makes that defensible.</strong>
 * Resolution is per-field ownership ({@code 00d} §6.2) applied by the sync engine, not a button a GM
 * presses — a human clearing a drift row would clear the <em>symptom</em> and leave the two systems
 * disagreeing. Rows close when a later audit finds the two sides agreeing again. What a GM is owed
 * instead is the engine's answer, and every row now carries it: {@code owner} names who wins the
 * field and {@code resolution} says whether it fixes itself. {@code needsAHuman} is the count that
 * is worth an alert.
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
			Instant lastSeenAt, String owner, String resolution) {
	}

	/**
	 * @param oldest      when the longest-standing open drift was first seen, or null when there is
	 *                    none. <strong>Age is the number that matters</strong>: one disagreement this
	 *                    morning is a sync catching up, and the same one for a fortnight is a sync
	 *                    that is not working
	 * @param needsAHuman how many of them <strong>will not fix themselves</strong> (45e). The open
	 *                    count alone reads the same whether every row closes at tonight's sync or
	 *                    none of them do, which is how a number stops being looked at
	 */
	public record SyncStatus(int open, Instant oldest, List<Drift> drifts, int needsAHuman,
			Outbox outbox) {
	}

	/**
	 * The queue's health — Unit 45c.
	 *
	 * <p><strong>{@code dead} is the number that needs a human</strong>: a pending backlog is a
	 * sync catching up, while a dead row is a write that <em>never reached GHL</em> and never will
	 * without somebody looking. {@code oldestPending} is the other half — one minute of backlog is
	 * a drain doing its job, an hour of it is a drain that is not.
	 */
	public record Outbox(int pending, int dead, Instant oldestPending, List<DeadPush> recentlyDead) {
	}

	public record DeadPush(UUID id, UUID entityId, String intent, int attempts, String lastFailure,
			String reason, Instant deadAt) {
	}

	private final SyncAuditService audit;
	private final SyncOutboxService outbox;

	SyncStatusController(SyncAuditService audit, SyncOutboxService outbox) {
		this.audit = audit;
		this.outbox = outbox;
	}

	@GetMapping("/drift")
	@PreAuthorize("hasRole('GM')")
	public ApiResponse<SyncStatus> drift() {
		List<SyncAuditService.Assessed> open = audit.openAssessed();
		return ApiResponse.ok(new SyncStatus(
				open.size(),
				open.stream().map((assessed) -> assessed.row().getFirstDetectedAt())
						.min(java.util.Comparator.naturalOrder()).orElse(null),
				open.stream().map(SyncStatusController::view).toList(),
				(int) open.stream()
						.filter((assessed) -> assessed.resolution() == SyncDrift.Resolution.NEEDS_A_HUMAN)
						.count(),
				outbox()));
	}

	private Outbox outbox() {
		SyncOutboxService.Backlog backlog = this.outbox.backlog();
		return new Outbox(backlog.pending(), backlog.dead(), backlog.oldestPending(),
				backlog.recentlyDead().stream()
						.map((row) -> new DeadPush(row.getId(), row.getEntityId(), row.getIntent().name(),
								row.getAttempts(),
								row.getLastFailure() == null ? null : row.getLastFailure().name(),
								row.getDeadReason(), row.getDeadAt()))
						.toList());
	}

	private static Drift view(SyncAuditService.Assessed assessed) {
		SyncDrift row = assessed.row();
		return new Drift(row.getId(), row.getEntityType(), row.getEntityId(), row.getGhlId(),
				row.getKind().name(), row.getField(), row.getLocalValue(), row.getGhlValue(),
				row.getFirstDetectedAt(), row.getLastSeenAt(),
				assessed.owner().name(), assessed.resolution().name());
	}

}
