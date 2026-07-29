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

	/**
	 * The Coordinator who chases the documents and drives delivery. A second assignment
	 * slot rather than a reuse of {@link #assignedCm}: both people are on the case at
	 * once, and {@code ScopePredicate} reads every slot, so whoever is assigned sees the
	 * case on their board.
	 */
	@Column(name = "assigned_coordinator")
	private UUID assignedCoordinator;

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

	/**
	 * Whether the money has arrived. Since Handoff A moved to contact intake a case
	 * exists before it is paid, so this is a fact recorded on the case rather than the
	 * reason it exists. Written only by {@code CaseLifecycleService.markPaid} — or by
	 * intake when GHL already knows the contact paid.
	 *
	 * <p>Two things depend on it: no case reaches an expert unpaid (the guard is on
	 * {@code markDocsComplete}), and no unpaid case counts as earned revenue
	 * (invariant 5, via {@code RefundService.isRevenueRecognized}).
	 */
	@Column(name = "paid", nullable = false)
	private boolean paid;

	@Column(name = "paid_at")
	private Instant paidAt;

	@Column(name = "deadline")
	private Instant deadline;

	@Enumerated(EnumType.STRING)
	@Column(name = "current_stage", nullable = false)
	private Stage currentStage;

	@Enumerated(EnumType.STRING)
	@Column(name = "exception_state", nullable = false)
	private ExceptionState exceptionState = ExceptionState.NONE;

	/**
	 * When the wait the case is in now began — restamped by every transition, not
	 * only the ones that change stage. The SLA budget belongs to the wait, not to
	 * the stage as a whole: a PM review round inside {@code DRAFT_GENERATION} has
	 * its own 12 hours. The stage timeline itself is reconstructable from the audit
	 * trail, which is append-only, so nothing is lost by reusing this column.
	 */
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

	// The setters below are the state machine's write surface. They are deliberately
	// plain: the rule about which of them may be called, and when, lives in
	// CaseTransitions and CaseLifecycleService, never in the entity.

	public UUID getTeamId() {
		return teamId;
	}

	public void setTeamId(UUID teamId) {
		this.teamId = teamId;
	}

	public String getCaseCode() {
		return caseCode;
	}

	public PoolStatus getPoolStatus() {
		return poolStatus;
	}

	public void setPoolStatus(PoolStatus poolStatus) {
		this.poolStatus = poolStatus;
	}

	public UUID getAssignedPm() {
		return assignedPm;
	}

	public void setAssignedPm(UUID assignedPm) {
		this.assignedPm = assignedPm;
	}

	public UUID getAssignedCm() {
		return assignedCm;
	}

	public void setAssignedCm(UUID assignedCm) {
		this.assignedCm = assignedCm;
	}

	public UUID getAssignedCoordinator() {
		return assignedCoordinator;
	}

	public void setAssignedCoordinator(UUID assignedCoordinator) {
		this.assignedCoordinator = assignedCoordinator;
	}

	// The setters below are written exactly once, by Handoff A at intake (Unit 05).
	// Nothing afterwards changes what the customer bought or what they paid.

	public UUID getContactId() {
		return contactId;
	}

	public void setContactId(UUID contactId) {
		this.contactId = contactId;
	}

	public ServiceType getServiceType() {
		return serviceType;
	}

	public void setServiceType(ServiceType serviceType) {
		this.serviceType = serviceType;
	}

	public ServiceSubtype getServiceSubtype() {
		return serviceSubtype;
	}

	public void setServiceSubtype(ServiceSubtype serviceSubtype) {
		this.serviceSubtype = serviceSubtype;
	}

	public VisaCategory getVisaCategory() {
		return visaCategory;
	}

	public void setVisaCategory(VisaCategory visaCategory) {
		this.visaCategory = visaCategory;
	}

	public void setClientType(ClientType clientType) {
		this.clientType = clientType;
	}

	/** Role-restricted: a DTO exposes this to GM, Brand Manager and PM only. */
	public BigDecimal getDealValue() {
		return dealValue;
	}

	public void setDealValue(BigDecimal dealValue) {
		this.dealValue = dealValue;
	}

	public boolean isPaid() {
		return paid;
	}

	public void setPaid(boolean paid) {
		this.paid = paid;
	}

	public Instant getPaidAt() {
		return paidAt;
	}

	public void setPaidAt(Instant paidAt) {
		this.paidAt = paidAt;
	}

	public Instant getDeadline() {
		return deadline;
	}

	public void setDeadline(Instant deadline) {
		this.deadline = deadline;
	}

	public String getDriveLink() {
		return driveLink;
	}

	public String getInvoiceRef() {
		return invoiceRef;
	}

	public void setDriveLink(String driveLink) {
		this.driveLink = driveLink;
	}

	public void setInvoiceRef(String invoiceRef) {
		this.invoiceRef = invoiceRef;
	}

	public void setCampaignAttribution(String campaignAttribution) {
		this.campaignAttribution = campaignAttribution;
	}

	public Stage getCurrentStage() {
		return currentStage;
	}

	public void setCurrentStage(Stage currentStage) {
		this.currentStage = currentStage;
	}

	public ExceptionState getExceptionState() {
		return exceptionState;
	}

	public void setExceptionState(ExceptionState exceptionState) {
		this.exceptionState = exceptionState;
	}

	public Instant getStageEnteredAt() {
		return stageEnteredAt;
	}

	public void setStageEnteredAt(Instant stageEnteredAt) {
		this.stageEnteredAt = stageEnteredAt;
	}

	public SlaStatus getSlaStatus() {
		return slaStatus;
	}

	public void setSlaStatus(SlaStatus slaStatus) {
		this.slaStatus = slaStatus;
	}

	public UUID getExpertId() {
		return expertId;
	}

	public void setExpertId(UUID expertId) {
		this.expertId = expertId;
	}

	public ExpertSignStatus getExpertSignStatus() {
		return expertSignStatus;
	}

	public void setExpertSignStatus(ExpertSignStatus expertSignStatus) {
		this.expertSignStatus = expertSignStatus;
	}

	public int getDraftVersionCount() {
		return draftVersionCount;
	}

	public void setDraftVersionCount(int draftVersionCount) {
		this.draftVersionCount = draftVersionCount;
	}

	public PmApprovalStatus getPmApprovalStatus() {
		return pmApprovalStatus;
	}

	public void setPmApprovalStatus(PmApprovalStatus pmApprovalStatus) {
		this.pmApprovalStatus = pmApprovalStatus;
	}

	public ClientApprovalStatus getClientApprovalStatus() {
		return clientApprovalStatus;
	}

	public void setClientApprovalStatus(ClientApprovalStatus clientApprovalStatus) {
		this.clientApprovalStatus = clientApprovalStatus;
	}

	public String getCampaignAttribution() {
		return campaignAttribution;
	}

	public Instant getDeliveryDate() {
		return deliveryDate;
	}

	public void setDeliveryDate(Instant deliveryDate) {
		this.deliveryDate = deliveryDate;
	}

	public Instant getCaseClosedDate() {
		return caseClosedDate;
	}

	public void setCaseClosedDate(Instant caseClosedDate) {
		this.caseClosedDate = caseClosedDate;
	}
}
