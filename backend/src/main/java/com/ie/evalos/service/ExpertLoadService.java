package com.ie.evalos.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ie.evalos.repository.CaseRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How much work each expert is carrying, counted from the cases themselves.
 *
 * <p><strong>Why this is derived.</strong> {@code expert.current_active_count} and
 * {@code total_cases_completed} were created {@code NOT NULL DEFAULT 0} in {@code V7}
 * and nothing has ever written either, so they are a permanent zero. This unit is the
 * first to display an expert's load and Unit 12 scores on it: reading those columns
 * would show every expert as free and give the scorer a constant. The alternative was
 * to start maintaining them, which means adjusting a counter on assignment, close,
 * refund, reassignment and decline — five chances to drift on a figure that is worse
 * wrong than slow — plus a backfill for every existing row. At 50–100 cases per brand
 * per month a grouped count is trivial, so the columns are left dead and unread.
 *
 * <p>One batched query per page, never one per row. Unit 12 reuses this rather than
 * counting again.
 */
@Service
public class ExpertLoadService {

	/**
	 * @param active    cases naming this expert that have not closed
	 * @param completed cases naming this expert that closed un-refunded
	 */
	public record Load(int active, int completed) {

		static final Load NONE = new Load(0, 0);
	}

	private final CaseRepository cases;

	ExpertLoadService(CaseRepository cases) {
		this.cases = cases;
	}

	/**
	 * The load for every id given, including the experts with no cases at all — a
	 * missing key would make every caller write the same {@code getOrDefault} zero.
	 *
	 * <p>The ids must have come from a scoped read: the aggregate underneath has no
	 * brand predicate, by the same convention as the other batched finders.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, Load> forExperts(Collection<UUID> expertIds) {
		Map<UUID, Load> loads = new HashMap<>();
		expertIds.forEach(id -> loads.put(id, Load.NONE));
		if (expertIds.isEmpty()) {
			return loads;
		}

		for (Object[] row : cases.countCasesPerExpert(expertIds)) {
			// Positional rather than a projection interface: a native query's column
			// aliases are the mapping, and three columns read here beat three aliases
			// that have to keep agreeing with a getter name.
			loads.put((UUID) row[0], new Load(count(row[1]), count(row[2])));
		}
		return loads;
	}

	/** One expert, for the profile screen. */
	@Transactional(readOnly = true)
	public Load forExpert(UUID expertId) {
		return forExperts(List.of(expertId)).get(expertId);
	}

	private static int count(Object value) {
		return ((Number) value).intValue();
	}
}
