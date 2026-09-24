package com.ie.evalos.job;

import java.util.List;

import com.ie.evalos.service.ContactMirrorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code contact_snapshot} filled from the GHL location.
 *
 * <p><strong>Hourly, for the pipeline and reference mirrors' reason, with one difference.</strong>
 * Those two mirror structure that changes a few times a year; contacts change daily. But nothing
 * on any screen needs a contact the instant it is created — the deal screen already falls back to
 * a live read for a contact the mirror has not met yet, and {@code contact.created} /
 * {@code contact.updated} webhooks land the urgent case immediately. This sweep is the floor under
 * both: it is what makes the contacts list complete rather than what makes it current.
 *
 * <p><strong>It costs about 1.7 seconds of the location's shared budget.</strong> Fifteen pages at
 * GHL's maximum of 100, paced at 110ms — an order of magnitude cheaper than the nightly sync
 * audit. Hourly is not a compromise at that price; it is well inside the noise.
 *
 * <p>On Unit 19's machinery like every other sweep, so the admin panel's "this sweep has stopped"
 * warning covers it for free. That matters here more than elsewhere: this sweep failing looks
 * exactly like a quiet CRM, which is the failure nobody reports.
 */
@Component
public class ContactMirrorSweep implements Sweep {

	static final String JOB_TYPE = "CONTACT_MIRROR";

	private static final Logger log = LoggerFactory.getLogger(ContactMirrorSweep.class);

	private final SweepRunner runner;
	private final ContactMirrorService mirror;

	ContactMirrorSweep(SweepRunner runner, ContactMirrorService mirror) {
		this.runner = runner;
		this.mirror = mirror;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.CONTACT_MIRROR}")
	@Override
	public boolean run() {
		// One "item": the paging lives in the service, which owns the cursor, the ceiling and the
		// selling brand. The ledger still records the attempt, which is what the staleness warning
		// reads — and `acted` is true only for a pass that actually wrote something, so a run that
		// found nothing is visibly different from one that never got started.
		return runner.sweep(JOB_TYPE, () -> List.of(Boolean.TRUE), (item) -> {
			ContactMirrorService.Result result = mirror.refresh();
			log.info("Contact mirror: {} of {} contacts in {} page(s){}", result.seen(),
					result.locationTotal(), result.pages(),
					result.complete() ? "" : " — INCOMPLETE, the rest arrives next pass");
			return result.seen() > 0;
		});
	}
}
