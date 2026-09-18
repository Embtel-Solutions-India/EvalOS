package com.ie.evalos.config;

import java.util.UUID;

import com.ie.evalos.domain.Brand;
import com.ie.evalos.repository.BrandRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which brand owns the configured GHL location — resolved once, for everything.
 *
 * <p><strong>What {@code evalos.ghl.sales-brand} is.</strong> {@code evalos.ghl.location-id} names
 * one GHL sub-account, and a sub-account belongs to no brand on its own — that is invariant 1's one
 * stated exception. This setting is the answer: it names the EvalOS brand that owns that location,
 * so every mirrored row (pipeline, opportunity, calendar, tag…) has a {@code brand_id} to carry and
 * every scoped query still works. Unit 36 added it precisely to <em>narrow</em> the exception rather
 * than widen it.
 *
 * <p><strong>Blank is legal and means "no brand sells yet"</strong> — but it is not harmless, and
 * that is worth knowing before setting it that way: with it blank, <em>every</em> mirror is a no-op.
 * The pipeline sweep, the delta sweep and the reference sweep each log a warning and return zero, so
 * boards are empty and unsynced for a reason that looks nothing like a configuration mistake. That
 * cost a morning on 2026-09-17, which is why {@code OpportunityBoardService.Board.syncConfigured}
 * now carries the fact to the screen.
 *
 * <p><strong>It accepts a slug as well as a UUID, and the slug is the one to use.</strong> A brand's
 * id differs per database — International Evaluations is
 * {@code 11111111-1111-1111-1111-111111111111} in the local seed and
 * {@code 33333333-3333-3333-3333-333333333333} in testprod — so a UUID in a shared {@code .env} or
 * deployment template is right in exactly one environment and silently wrong in the others.
 * {@code international-evaluations} is the same in all of them.
 *
 * <p><strong>Resolved once, here, rather than in nine services.</strong> Nine classes parsed this
 * string themselves, two of them with their own bespoke error handling and seven with none — which
 * is nine chances for the failure modes to disagree. The lookup happens only for a slug; a UUID
 * costs no query, so nothing that does not need the database touches it.
 */
@Component
public class SellingBrand {

	private static final Logger log = LoggerFactory.getLogger(SellingBrand.class);

	private final UUID id;

	@org.springframework.beans.factory.annotation.Autowired
	SellingBrand(@Value("${evalos.ghl.sales-brand:}") String configured, BrandRepository brands) {
		this.id = resolve(configured == null ? "" : configured.trim(), brands);
	}

	/** For tests and for callers that already hold an id. */
	public SellingBrand(UUID id) {
		this.id = id;
	}

	private static UUID resolve(String configured, BrandRepository brands) {
		if (configured.isBlank()) {
			log.warn("evalos.ghl.sales-brand is blank: no brand owns the configured GHL location, so "
					+ "every mirror is a no-op and every board will read as unsynced. Set it to a "
					+ "brand slug (for example 'international-evaluations') to turn the sync on.");
			return null;
		}
		try {
			return UUID.fromString(configured);
		}
		catch (IllegalArgumentException notAUuid) {
			// Not a UUID, so it is a slug. Failing the BOOT rather than the first board load is the
			// same ruling OpportunityBoardService already made about a malformed UUID: a deployment
			// mistake discovered as a 500 on somebody's screen is discovered in the worst place.
			return brands.findBySlug(configured)
					.map(Brand::getId)
					.orElseThrow(() -> new IllegalStateException(
							"evalos.ghl.sales-brand is \"" + configured + "\", which is neither a UUID "
									+ "nor the slug of any brand in this database. It names the brand "
									+ "that owns evalos.ghl.location-id — use a slug such as "
									+ "'international-evaluations', which is the same in every "
									+ "environment, rather than an id, which is not."));
		}
	}

	/** The brand that owns the GHL location, or <strong>null when none is configured</strong>. */
	public UUID id() {
		return id;
	}

	/** Whether anything should be mirrored at all. */
	public boolean isConfigured() {
		return id != null;
	}
}
