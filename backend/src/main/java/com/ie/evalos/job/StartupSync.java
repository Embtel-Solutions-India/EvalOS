package com.ie.evalos.job;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fills the mirror once, as soon as the app is up.
 *
 * <p><strong>Nobody should have to run a job by hand to see their own pipeline.</strong> Every
 * sweep here is already scheduled — but {@code fixedDelay} counts from the <em>end of the previous
 * run</em>, so on a fresh start the first pipeline mirror is an hour away and the first delta sweep
 * five minutes away. On a brand-new database that means an hour of empty boards with a "Sync
 * delayed" banner and a support answer of "ask a GM to run a job", which is not an answer.
 *
 * <p><strong>Order matters and is the whole reason this is one class rather than three
 * annotations.</strong> Opportunities are absorbed onto mirrored pipelines, and a desk's
 * assignment is resolved against them — so pipelines first, then the reference lists, then the
 * deals. Run in the wrong order the first pass does nothing and the second one has to fix it.
 *
 * <p><strong>Off the startup thread.</strong> {@code @Async} so a slow or unreachable GHL delays
 * nobody's login: the app is already serving by the time this begins, and a board that is empty for
 * thirty seconds is a different thing from an app that will not start.
 *
 * <p><strong>Respects {@code evalos.jobs.enabled}.</strong> Tests and any deployment that has
 * deliberately turned the sweeps off must not get a surprise GHL call at boot.
 */
@Component
public class StartupSync {

	private static final Logger log = LoggerFactory.getLogger(StartupSync.class);

	/**
	 * Pipelines, then reference lists, then deals.
	 *
	 * <p>The names are the sweeps' own job types, so this cannot drift from what the admin panel
	 * and the ledger call them — and a job type that stopped existing fails here loudly rather than
	 * being silently skipped.
	 */
	private static final List<String> IN_ORDER =
			List.of("PIPELINE_MIRROR", "REFERENCE_MIRROR", "MIRROR_DELTA");

	private final JobAdminService jobs;
	private final boolean enabled;

	StartupSync(JobAdminService jobs, @Value("${evalos.jobs.enabled:true}") boolean enabled) {
		this.jobs = jobs;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void fillTheMirror() {
		if (!enabled) {
			log.info("Startup sync skipped: evalos.jobs.enabled is false.");
			return;
		}
		Thread worker = new Thread(this::run, "startup-sync");
		worker.setDaemon(true);
		worker.start();
	}

	private void run() {
		for (String jobType : IN_ORDER) {
			try {
				// Through the same advisory lock every scheduled pass uses, so two instances
				// starting together do not both pull the same lists.
				boolean started = jobs.runNow(jobType);
				log.info("Startup sync: {} {}", jobType, started ? "ran" : "was already running");
			}
			catch (RuntimeException failed) {
				// A GHL outage at boot is not a boot failure. The scheduled pass will try again,
				// and the board says "sync delayed" in the meantime, which is true.
				log.warn("Startup sync: {} failed ({}). The scheduled sweep will retry.", jobType,
						failed.getMessage());
			}
		}
	}
}
