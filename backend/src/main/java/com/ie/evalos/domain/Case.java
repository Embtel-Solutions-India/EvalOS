package com.ie.evalos.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A case: the unit of work EvalOS owns from paid deal to signed delivery and
 * expert payout, and the system of record for it.
 *
 * <p>Foreign keys are held as raw UUIDs rather than associations, as in
 * {@link TeamMember} — scoping is a plain column predicate and must never depend
 * on loading another entity. The stage and SLA columns are written only by the
 * state machine (Unit 04); nothing else moves a case.
 */
@Entity
@Table(name = "evalos_case")
public class Case extends ScopedEntity {

	@Column(name = "team_id")
	private UUID teamId;

	/** Human-facing case id, generated on create. */
	@Column(name = "case_code")
	private String caseCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "pool_status")
	private PoolStatus poolStatus;

	@Column(name = "assigned_pm")
	private UUID assignedPm;

	@Column(name = "assigned_cm")
	private UUID assignedCm;

	@Column(name = "contact_id")
	private UUID contactId;

	@Enumerated(EnumType.STRING)
	@Column(name = "service_type")
	private ServiceType serviceType;

	@Enumerated(EnumType.STRING)
	@Column(name = "service_subtype")
	private ServiceSubtype serviceSubtype;

	@Enumerated(EnumType.STRING)
	@Column(name = "visa_category")
	private VisaCategory visaCategory;

	@Enumerated(EnumType.STRING)
	@Column(name = "client_type")
	private ClientType clientType;

	/** Role-restricted: only PM, Brand Manager and GM see this in a DTO. */
	@Column(name = "deal_value")
	private BigDecimal dealValue;

	@Column(name = "deadline")
	private Instant deadline;

	@Enumerated(EnumType.STRING)
	@Column(name = "current_stage", nullable = false)
	private Stage currentStage;

	@Enumerated(EnumType.STRING)
	@Column(name = "exception_state", nullable = false)
	private ExceptionState exceptionState = ExceptionState.NONE;

	@Column(name = "stage_entered_at")
	private Instant stageEnteredAt;

	/** Computed from {@link #deadline} by the state machine, never hand-set. */
	@Enumerated(EnumType.STRING)
	@Column(name = "sla_status")
	private SlaStatus slaStatus;

	@Column(name = "pm_strategy_notes")
	private String pmStrategyNotes;

	@Column(name = "expert_id")
	private UUID expertId;

	@Enumerated(EnumType.STRING)
	@Column(name = "expert_sign_status")
	private ExpertSignStatus expertSignStatus;

	@Column(name = "draft_version_count", nullable = false)
	private int draftVersionCount;

	@Enumerated(EnumType.STRING)
	@Column(name = "pm_approval_status")
	private PmApprovalStatus pmApprovalStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "client_approval_status")
	private ClientApprovalStatus clientApprovalStatus;

	@Column(name = "client_portal_read_at")
	private Instant clientPortalReadAt;

	/** Google Drive folder. EvalOS stores the link, never the document bytes. */
	@Column(name = "drive_link")
	private String driveLink;

	@Column(name = "invoice_ref")
	private String invoiceRef;

	@Column(name = "campaign_attribution")
	private String campaignAttribution;

	@Column(name = "delivery_date")
	private Instant deliveryDate;

	@Column(name = "case_closed_date")
	private Instant caseClosedDate;

	@Column(name = "google_review_requested", nullable = false)
	private boolean googleReviewRequested;

	@Column(name = "google_review_requested_at")
	private Instant googleReviewRequestedAt;

	@Column(name = "retention_30_sent_at")
	private Instant retention30SentAt;

	@Column(name = "retention_90_sent_at")
	private Instant retention90SentAt;

	@Column(name = "retention_180_sent_at")
	private Instant retention180SentAt;

	@Column(name = "retention_365_sent_at")
	private Instant retention365SentAt;

	protected Case() {
		// for JPA
	}

	public Case(UUID brandId, String caseCode, Stage currentStage) {
		super(brandId);
		this.caseCode = caseCode;
		this.currentStage = currentStage;
	}
}
