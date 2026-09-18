package com.ie.evalos.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.FieldOwnership;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.SyncDrift;
import com.ie.evalos.domain.SyncEntity;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.repository.OpportunityRepository;
import com.ie.evalos.repository.PipelineRepository;
import com.ie.evalos.repository.SyncDriftRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks whether the mirror is actually right — Unit 45, slice B.
 *
 * <p><strong>This is the slice everything else in Unit 45 is checkable against.</strong> The outbox,
 * the webhooks and the delta sweep are all <em>writers</em>; you want the detector before the
 * writers, because a writer you cannot audit is a writer you have to take on trust. {@code 00c} §4c
 * promises that every divergence is detected, and {@code 00d} §6.3 points out that the promise is
 * only checkable if the divergences are stored — so they are, in {@code sync_drift}.
 *
 * <p><strong>It detects and records. It never repairs.</strong> Conflict resolution is per-field
 * ownership ({@code 00d} §6.2) and is a later slice, deliberately: a detector that also mutates
 * cannot be trusted to tell the truth, because its own writes become tomorrow's findings.
 *
 * <p><strong>A paged full-list diff, never a per-row {@code GET}</strong> ({@code 00d} §6.3, third
 * bullet, which asks for this to be said out loud because the per-row version "is the one somebody
 * writes first, because it is easier to reason about"). The budget: ~115 pages at 110ms ≈ 30s at
 * ~9 req/s, an order of magnitude inside GHL's 100-per-10-seconds. The per-row version is 11.4k
 * requests — about 21 minutes of continuous budget, starving every desk while it runs.
 *
 * <p><strong>Opportunities only, and the two absences are reasoned rather than pending.</strong>
 * Pipelines and stages are overwritten hourly by the 44a sweep <em>through the same code path an
 * audit would compare against</em>, so auditing them would report zero by construction. Contacts
 * have no GHL <em>read</em> client at all — {@code GhlWriteClient} only upserts — so there is
 * nothing to compare with until one exists.
 */
@Service
public class SyncAuditService {

	private static final Logger log = LoggerFactory.getLogger(SyncAuditService.class);

	/**
	 * What one audit found.
	 *
	 * @param compared   opportunities GHL returned
	 * @param opened     drift rows recorded for the first time
	 * @param stillWrong drift rows that were already open and still are
	 * @param resolved   drift rows whose two sides now agree
	 */
	public record AuditResult(int compared, int opened, int stillWrong, int resolved) {

		public int changed() {
			return opened + resolved;
		}
	}

	/**
	 * The fields compared, and the reason the list is short.
	 *
	 * <p>Every one of these is a field <strong>a desk acts on</strong>: which column the card is in,
	 * whether the deal is still live, what it is worth, and what it is called. A field nobody reads
	 * cannot produce a finding anybody can act on, and a drift report full of those is a report that
	 * gets ignored — which is the failure mode that makes the whole table pointless.
	 *
	 * <p>{@code assignedTo} is deliberately absent: {@code 00d} §6.2 gives it to GHL outright, so
	 * EvalOS disagreeing with GHL about it is not drift, it is EvalOS being behind.
	 */
	private static final String STAGE = FieldOwnership.STAGE;

	private static final String STATUS = FieldOwnership.STATUS;

	private static final String AMOUNT = FieldOwnership.AMOUNT;

	private static final String NAME = FieldOwnership.NAME;

	private final GhlPipelineClient ghl;
	private final OpportunityRepository opportunities;
	private final PipelineRepository pipelines;
	private final SyncDriftRepository drifts;
	private final UUID sellingBrandId;

	SyncAuditService(GhlPipelineClient ghl, OpportunityRepository opportunities,
			PipelineRepository pipelines, SyncDriftRepository drifts,
			SellingBrand sellingBrand) {
		this.ghl = ghl;
		this.opportunities = opportunities;
		this.pipelines = pipelines;
		this.drifts = drifts;
		this.sellingBrandId = sellingBrand.id();
	}

	/**
	 * One full pass over every mirrored pipeline.
	 *
	 * <p>The GHL reads happen here, outside the transaction that writes the findings — holding a
	 * pooled connection open across a network round trip is how one slow upstream becomes an
	 * exhausted pool, and this read is the longest one EvalOS makes.
	 */
	public AuditResult audit() {
		if (sellingBrandId == null) {
			log.warn("Sync audit skipped: evalos.ghl.sales-brand is blank, so there is no mirror to audit.");
			return new AuditResult(0, 0, 0, 0);
		}

		List<Pipeline> mirrored = pipelines.findByBrandIdOrderByPositionAscNameAsc(sellingBrandId).stream()
				.filter(Pipeline::isLive)
				.toList();

		Map<String, GhlPipelineClient.Opportunity> fromGhl = new HashMap<>();
		for (Pipeline pipeline : mirrored) {
			// The paged full list, per pipeline. NOT a per-row GET — see the class note.
			for (GhlPipelineClient.Opportunity row : ghl.allIn(pipeline.getGhlId())) {
				if (row.id() != null && !row.id().isBlank()) {
					fromGhl.put(row.id(), row);
				}
			}
		}

		return compare(mirrored, fromGhl);
	}

	@Transactional
	AuditResult compare(List<Pipeline> mirrored, Map<String, GhlPipelineClient.Opportunity> fromGhl) {
		Set<UUID> pipelineIds = new HashSet<>(mirrored.stream().map(Pipeline::getId).toList());
		List<Opportunity> held = pipelineIds.isEmpty() ? List.of()
				: opportunities.findByPipelineIdIn(pipelineIds);

		List<SyncDrift> open = drifts.findByBrandIdAndEntityTypeAndResolvedAtIsNull(sellingBrandId,
				SyncEntity.OPPORTUNITY);
		Set<String> confirmed = new HashSet<>();
		int opened = 0;
		int stillWrong = 0;

		Map<String, Opportunity> byGhlId = new HashMap<>();
		for (Opportunity row : held) {
			// A row with no GHL id is a portal-born deal GHL has not acknowledged. That is a LEGAL
			// state (`00c` §2a), not drift — recording it would fill the report with rows nobody
			// should act on, on the busiest day the portal has.
			if (row.getGhlId() != null && row.isLive()) {
				byGhlId.put(row.getGhlId(), row);
			}
		}

		for (Map.Entry<String, GhlPipelineClient.Opportunity> entry : fromGhl.entrySet()) {
			Opportunity local = byGhlId.get(entry.getKey());
			if (local == null) {
				Finding finding = record(SyncDrift.Kind.MISSING_LOCALLY, entry.getKey(), null, null,
						null, entry.getValue().name());
				opened += finding.opened() ? 1 : 0;
				stillWrong += finding.opened() ? 0 : 1;
				confirmed.add(key(entry.getKey(), null));
				continue;
			}
			for (Mismatch mismatch : mismatches(local, entry.getValue())) {
				Finding finding = record(SyncDrift.Kind.FIELD_MISMATCH, entry.getKey(), local.getId(),
						mismatch.field(), mismatch.local(), mismatch.ghl());
				opened += finding.opened() ? 1 : 0;
				stillWrong += finding.opened() ? 0 : 1;
				confirmed.add(key(entry.getKey(), mismatch.field()));
			}
		}

		for (Opportunity row : byGhlId.values()) {
			if (!fromGhl.containsKey(row.getGhlId())) {
				Finding finding = record(SyncDrift.Kind.MISSING_IN_GHL, row.getGhlId(), row.getId(), null,
						row.getName(), null);
				opened += finding.opened() ? 1 : 0;
				stillWrong += finding.opened() ? 0 : 1;
				confirmed.add(key(row.getGhlId(), null));
			}
		}

		// Anything open that tonight did not see again has stopped being true.
		int resolved = 0;
		for (SyncDrift row : open) {
			if (!confirmed.contains(key(row.getGhlId(), row.getField()))) {
				row.resolve();
				drifts.save(row);
				resolved++;
			}
		}

		AuditResult result = new AuditResult(fromGhl.size(), opened, stillWrong, resolved);
		log.info("Sync audit: {}", result);
		return result;
	}

	/** Whether this row was newly opened, so the sweep can report new findings separately. */
	private record Finding(boolean opened) {
	}

	private Finding record(SyncDrift.Kind kind, String ghlId, UUID entityId, String field,
			String localValue, String ghlValue) {
		Optional<SyncDrift> existing = field == null
				? drifts.findByBrandIdAndEntityTypeAndGhlIdAndFieldIsNullAndResolvedAtIsNull(
						sellingBrandId, SyncEntity.OPPORTUNITY, ghlId)
				: drifts.findByBrandIdAndEntityTypeAndGhlIdAndFieldAndResolvedAtIsNull(
						sellingBrandId, SyncEntity.OPPORTUNITY, ghlId, field);
		if (existing.isPresent()) {
			existing.get().seenAgain(localValue, ghlValue);
			drifts.save(existing.get());
			return new Finding(false);
		}
		drifts.save(new SyncDrift(sellingBrandId, SyncEntity.OPPORTUNITY, entityId, ghlId, kind, field,
				localValue, ghlValue));
		return new Finding(true);
	}

	private record Mismatch(String field, String local, String ghl) {
	}

	/**
	 * The four fields a desk acts on.
	 *
	 * <p><strong>{@code amount} is compared by value, not by string</strong>: {@code 1000} and
	 * {@code 1000.00} are the same money and a report that called them drift would be wrong every
	 * night, which is how a report stops being read.
	 */
	private static List<Mismatch> mismatches(Opportunity local, GhlPipelineClient.Opportunity remote) {
		List<Mismatch> found = new ArrayList<>();
		if (!Objects.equals(local.getGhlStageId(), remote.pipelineStageId())) {
			found.add(new Mismatch(STAGE, local.getGhlStageId(), remote.pipelineStageId()));
		}
		if (!Objects.equals(local.getStatus(), remote.status())) {
			found.add(new Mismatch(STATUS, local.getStatus(), remote.status()));
		}
		if (!sameMoney(local.getAmount(), remote.monetaryValue())) {
			found.add(new Mismatch(AMOUNT, text(local.getAmount()), text(remote.monetaryValue())));
		}
		if (!Objects.equals(local.getName(), remote.name())) {
			found.add(new Mismatch(NAME, local.getName(), remote.name()));
		}
		return found;
	}

	private static boolean sameMoney(BigDecimal local, BigDecimal remote) {
		if (local == null || remote == null) {
			return local == remote;
		}
		return local.compareTo(remote) == 0;
	}

	private static String text(BigDecimal value) {
		return value == null ? null : value.toPlainString();
	}

	private static String key(String ghlId, String field) {
		return ghlId + "|" + (field == null ? "" : field);
	}

	/** What is wrong right now — the GM's read. */
	@Transactional(readOnly = true)
	public List<SyncDrift> open() {
		return sellingBrandId == null ? List.of()
				: drifts.findByBrandIdAndResolvedAtIsNullOrderByLastSeenAtDesc(sellingBrandId);
	}

	/** One open disagreement with the engine's answer attached — Unit 45e. */
	public record Assessed(SyncDrift row, FieldOwnership owner, SyncDrift.Resolution resolution) {
	}

	/**
	 * The same read, with <strong>what the engine will do</strong> beside each row (45e).
	 *
	 * <p>A field name and two values tell a GM what disagrees; they do not tell them whether it
	 * matters. Most rows fix themselves at the next sync, and a report that reads the same for
	 * those as for a deal GHL has lost is a report that gets skimmed. So each row is classified
	 * against {@link FieldOwnership} and against the mirror's own state.
	 *
	 * <p><strong>Why the opportunity is loaded rather than inferred:</strong> a shared field
	 * disagreeing means one of two opposite things — EvalOS is defending an edit GHL has not
	 * confirmed, or the mirror is simply stale — and only {@code local_updated_at} on the row can
	 * say which. One batched {@code findAllById}, not a query per drift row.
	 */
	@Transactional(readOnly = true)
	public List<Assessed> openAssessed() {
		List<SyncDrift> open = open();
		Set<UUID> entityIds = open.stream()
				.map(SyncDrift::getEntityId)
				.filter(Objects::nonNull)
				.collect(java.util.stream.Collectors.toSet());
		Map<UUID, Opportunity> byId = new HashMap<>();
		if (!entityIds.isEmpty()) {
			for (Opportunity row : opportunities.findAllById(entityIds)) {
				byId.put(row.getId(), row);
			}
		}
		return open.stream()
				.map((row) -> new Assessed(row, FieldOwnership.of(row.getField()),
						resolutionOf(row, byId.get(row.getEntityId()))))
				.toList();
	}

	/**
	 * <strong>Only a row GHL no longer returns needs a person</strong>, and that is deliberate:
	 * everything else is either GHL's to win or an EvalOS edit on its way out. Re-creating a deal
	 * GHL has deleted, or deleting the mirror's copy, is a business decision — a sweep doing it
	 * would turn one mistaken archive in GHL into lost EvalOS history.
	 */
	private static SyncDrift.Resolution resolutionOf(SyncDrift row, Opportunity subject) {
		if (row.getKind() == SyncDrift.Kind.MISSING_IN_GHL) {
			return SyncDrift.Resolution.NEEDS_A_HUMAN;
		}
		if (row.getKind() == SyncDrift.Kind.MISSING_LOCALLY) {
			// The mirror absorbs it at the next refresh — MIRROR_DELTA at the latest (45d).
			return SyncDrift.Resolution.GHL_WINS;
		}
		if (FieldOwnership.of(row.getField()) != FieldOwnership.SHARED) {
			return SyncDrift.Resolution.GHL_WINS;
		}
		// A shared field, so it turns on whether EvalOS is defending anything. A drift row whose
		// opportunity has gone is not a field question any more.
		if (subject == null) {
			return SyncDrift.Resolution.NEEDS_A_HUMAN;
		}
		return subject.getLocalUpdatedAt() != null
				? SyncDrift.Resolution.EVALOS_WINS
				: SyncDrift.Resolution.GHL_WINS;
	}

}
