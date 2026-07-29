package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ContactSnapshotRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The board read: the caller's cases plus the one thing a card needs that the case row
 * does not carry — the client's name.
 *
 * <p>Deliberately thin. Scope and the SLA recompute both belong to
 * {@link CaseLifecycleService#list}, so this calls it rather than building a second
 * scoped query: a board that filtered cases its own way is a board that can disagree
 * with every other read in the system about what the caller may see.
 */
@Service
public class CaseBoardService {

	/** One card's worth of data before the role gate is applied. */
	public record BoardRow(Case subject, String clientName) {
	}

	private final CaseLifecycleService lifecycle;
	private final ContactSnapshotRepository contacts;

	CaseBoardService(CaseLifecycleService lifecycle, ContactSnapshotRepository contacts) {
		this.lifecycle = lifecycle;
		this.contacts = contacts;
	}

	/**
	 * Everything on this caller's board, optionally narrowed to cases due by
	 * {@code dueBefore} (the shell's date filter) and to one {@code brandId} (the GM's
	 * brand switcher).
	 *
	 * <p>{@code CLOSED} cases are left out. This is a production board — the columns are
	 * the five stages work moves through, and a finished case is not work. It also means
	 * an approved refund (which closes the case) drops out of the Refund Requested lane
	 * once it is settled, leaving the lane a queue of things still needing a decision.
	 *
	 * <p><strong>{@code brandId} can only ever narrow.</strong> It is applied <em>after</em>
	 * the scoped read, so a Brand Manager naming another brand gets an empty board rather
	 * than that brand's cases — the parameter selects among rows the caller could already
	 * see and cannot reach past them. That is why it is safe to accept a brand from a
	 * query string at all, which nothing else in EvalOS does (invariant 1).
	 */
	@Transactional(readOnly = true)
	public List<BoardRow> forCaller(Instant dueBefore, UUID brandId) {
		List<Case> open = lifecycle.list(null, null, dueBefore).stream()
				.filter(subject -> subject.getCurrentStage() != Stage.CLOSED)
				.filter(subject -> brandId == null || brandId.equals(subject.getBrandId()))
				.toList();

		Map<UUID, String> names = clientNames(open);
		return open.stream()
				// The null check is load-bearing, not defensive: `contact_id` is nullable and
				// `Map.of()` throws on a null key rather than answering null.
				.map(subject -> new BoardRow(subject,
						subject.getContactId() == null ? null : names.get(subject.getContactId())))
				.toList();
	}

	/**
	 * One query for every name on the board rather than one per card.
	 *
	 * <p>{@code findAllById} rather than a scoped read, and that is safe here for a
	 * specific reason: the ids come from cases {@code lifecycle.list} already decided this
	 * caller may read. Nothing is disclosed that the case rows did not already disclose.
	 * Do not copy this into a path where the ids come from a request.
	 */
	private Map<UUID, String> clientNames(List<Case> cases) {
		List<UUID> contactIds = cases.stream()
				.map(Case::getContactId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		if (contactIds.isEmpty()) {
			return Map.of();
		}
		return contacts.findAllById(contactIds).stream()
				.filter(contact -> contact.getFullName() != null)
				.collect(Collectors.toMap(ContactSnapshot::getId, ContactSnapshot::getFullName,
						(first, second) -> first));
	}
}
