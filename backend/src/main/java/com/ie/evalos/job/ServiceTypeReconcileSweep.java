package com.ie.evalos.job;

import java.util.Set;

import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.CaseRepository;
import com.ie.evalos.service.CaseServiceTypeService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings each case still collecting documents into line with its deal's Service Requested (2026-10-30).
 *
 * <p>Reads the opportunity mirror only, never GHL. Its first run backfills the cases opened as Credential
 * evaluation while the webhook carried no service; after that it catches a case whose deal was not yet
 * mirrored, or not yet filled in, at the win. Only a case still on that fallback type: one a PM chose is
 * left alone. A case whose checklist has been touched, or that has left document collection, is the PM's
 * "Change service type".
 */
@Component
public class ServiceTypeReconcileSweep implements Sweep {

	static final String JOB_TYPE = "SERVICE_TYPE_RECONCILE";

	private final SweepRunner runner;
	private final CaseRepository cases;
	private final CaseServiceTypeService serviceTypes;

	ServiceTypeReconcileSweep(SweepRunner runner, CaseRepository cases, CaseServiceTypeService serviceTypes) {
		this.runner = runner;
		this.cases = cases;
		this.serviceTypes = serviceTypes;
	}

	@Override
	public String jobType() {
		return JOB_TYPE;
	}

	@Scheduled(fixedDelayString = "${evalos.jobs.intervals.SERVICE_TYPE_RECONCILE}")
	@Override
	public boolean run() {
		return runner.sweep(JOB_TYPE, () -> cases.findActiveForSweep(Set.of(Stage.CLOSED)), serviceTypes::reconcile);
	}
}
