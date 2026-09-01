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
	 * Whether the money has arrived. <strong>Written only by {@code CaseIntakeService}</strong>,
	 * and always true there: Handoff A fires on the opportunity being marked Won, and GHL
	 * invoices and collects before that happens. There is no staff path that sets it —
	 * a second way to say "paid" is a second thing that can disagree with GHL.
	 *
	 * <p>The column stays even though every new row arrives {@code true}, because two things
	 * read it: no case reaches an expert unpaid (the guard is on
	 * {@code markDocsComplete}), and no unpaid case counts as earned revenue
	 * (invariant 5, via {@code RefundService.isRevenueRecognized}) — which a GM-approved
	 * refund still has to be able to make false.
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
	/**
	 * Why <em>this</em> expert (Unit 32).
	 *
	 * <p><strong>Its own field rather than more prose in {@code pmStrategyNotes}</strong>, because
	 * it is a different fact rather than a longer one: it is rewritten per expert (and a
	 * reassignment is a normal path), it is read by the Expert Network Manager and not by the Case
	 * Manager, and it is the answer to the question asked after something goes wrong.
	 *
	 * <p>Not versioned. The current rationale is what matters; who was assigned when is already
	 * recorded twice, in the audit trail and in {@code expert_case_offer}.
	 */
	@Column(name = "expert_selection_rationale")
	private String expertSelectionRationale;

	@Column(name = "drive_link")
	private String driveLink;

	/**
	 * The drafted letter the client reviews (Unit 14). Written by
	 * {@code CaseLifecycleService.submitDraft}, so the link arrives with the draft it names.
	 *
	 * <p><strong>Distinct from {@link #driveLink} and never defaulted to it.</strong> That column
	 * is the client's own document folder — passports, transcripts — and it is the one field the
	 * client portal must never be shown: EvalOS does not control what is in that folder or who it
	 * is shared with, so presenting it as "your draft" would be a leak rather than a mislabel. A
	 * case with no draft link shows the portal an honest "not ready".
	 */
	@Column(name = "draft_link")
	private String draftLink;

	@Column(name = "invoice_ref")
	private String invoiceRef;

	/**
	 * The GHL opportunity this case was born from (Handoff A, v2.0). Unit 18 closes that
	 * opportunity with it. <strong>Never an idempotency key</strong> — it is a resource id,
	 * and a legitimately re-won opportunity would look like a redelivered webhook. What it
	 * does guard is a second *case* for one opportunity, via {@code V24}'s partial unique
	 * index.
	 */
	@Column(name = "ghl_opportunity_id")
	private String ghlOpportunityId;

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

	/**
	 * The PM's guidance to the Case Manager. Role-restricted in the DTO, and written only by
	 * {@code CaseLifecycleService.updateStrategyNotes} — which is not a transition, so it
	 * deliberately leaves the stage clock alone.
	 */
	public String getPmStrategyNotes() {
		return pmStrategyNotes;
	}

	public void setPmStrategyNotes(String pmStrategyNotes) {
		this.pmStrategyNotes = pmStrategyNotes;
	}

	public String getExpertSelectionRationale() {
		return expertSelectionRationale;
	}

	/** @param rationale blank is stored as null, so "not written" has one representation. */
	public void setExpertSelectionRationale(String rationale) {
		this.expertSelectionRationale = rationale == null || rationale.isBlank() ? null : rationale.strip();
	}

	public String getDriveLink() {
		return driveLink;
	}

	public String getDraftLink() {
		return draftLink;
	}

	public void setDraftLink(String draftLink) {
		this.draftLink = draftLink;
	}

	/**
	 * When the client first opened their portal link. Stamped once, by {@code PortalCaseService} —
	 * "when did they last look" is {@code portal_access.last_seen_at}, which moves every time.
	 */
	public Instant getClientPortalReadAt() {
		return clientPortalReadAt;
	}

	public void setClientPortalReadAt(Instant clientPortalReadAt) {
		this.clientPortalReadAt = clientPortalReadAt;
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

	public String getGhlOpportunityId() {
		return ghlOpportunityId;
	}

	public void setGhlOpportunityId(String ghlOpportunityId) {
		this.ghlOpportunityId = ghlOpportunityId;
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
