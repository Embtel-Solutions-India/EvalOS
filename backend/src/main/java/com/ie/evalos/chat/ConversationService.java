package com.ie.evalos.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.ExpertCaseOffer;
import com.ie.evalos.domain.Stage;
import com.ie.evalos.repository.ExpertCaseOfferRepository;
import com.ie.evalos.service.AuditService;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a case's three conversations in step with the case (Unit 57 §3).
 *
 * <p><strong>Idempotent by construction.</strong> The listener and the hourly sweep both call
 * {@link #ensureAndSync}; conversation creation relies on {@code UNIQUE (case_id, type)} and member
 * inserts on the partial unique index, so a race inserts once and the loser does nothing.
 *
 * <p><strong>Audited as the system.</strong> EvalOS computed the change from an assignment the
 * audit trail already records with its human actor; the chat row is the consequence.
 */
@Service
public class ConversationService {

	private static final String OBJECT_TYPE = "CONVERSATION";

	private final ConversationRepository conversations;
	private final ConversationMemberRepository members;
	private final ChatRosterLoader roster;
	private final ExpertCaseOfferRepository offers;
	private final AuditService audit;
	private final ApplicationEventPublisher events;

	ConversationService(ConversationRepository conversations, ConversationMemberRepository members,
			ChatRosterLoader roster, ExpertCaseOfferRepository offers, AuditService audit,
			ApplicationEventPublisher events) {
		this.conversations = conversations;
		this.members = members;
		this.roster = roster;
		this.offers = offers;
		this.audit = audit;
		this.events = events;
	}

	@Transactional
	public List<Conversation> ensureAndSync(Case subject) {
		CaseRoster loaded = roster.load(subject);
		List<Conversation> all = new ArrayList<>();
		for (ConversationType type : ConversationType.values()) {
			Conversation conversation = ensure(subject, type);
			all.add(conversation);
			if (!conversation.isReadOnly()) {
				sync(conversation, ChatMembership.expected(loaded, type));
			}
		}
		if (subject.getCurrentStage() == Stage.CLOSED) {
			makeReadOnly(subject);
		}
		return all;
	}

	@Transactional
	public void makeReadOnly(Case subject) {
		Instant now = Instant.now();
		for (Conversation conversation : conversations.findByBrandIdAndCaseId(subject.getBrandId(), subject.getId())) {
			if (conversation.makeReadOnly(now)) {
				conversations.save(conversation);
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_READ_ONLY, null, Map.of("caseId", subject.getId()));
				events.publishEvent(new ChatChanged(conversation.getBrandId(), conversation.getId(),
						ChatChanged.Kind.READ_ONLY, null));
			}
		}
	}

	/** The sweep's repair: freeze every conversation still open on a case that has closed. */
	@Transactional
	public void makeReadOnlyWhereClosed() {
		Instant now = Instant.now();
		for (Conversation conversation : conversations.findActiveOfClosedCases()) {
			if (conversation.makeReadOnly(now)) {
				conversations.save(conversation);
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_READ_ONLY, null, Map.of("caseId", conversation.getCaseId()));
				events.publishEvent(new ChatChanged(conversation.getBrandId(), conversation.getId(),
						ChatChanged.Kind.READ_ONLY, null));
			}
		}
	}

	private Conversation ensure(Case subject, ConversationType type) {
		return conversations.findByBrandIdAndCaseIdAndType(subject.getBrandId(), subject.getId(), type)
				.orElseGet(() -> {
					try {
						return conversations.saveAndFlush(new Conversation(subject.getBrandId(), subject.getId(), type));
					}
					catch (DataIntegrityViolationException raced) {
						// The sweep and a listener created it at the same moment; take the winner's.
						return conversations.findByBrandIdAndCaseIdAndType(subject.getBrandId(), subject.getId(), type)
								.orElseThrow(() -> raced);
					}
				});
	}

	private void sync(Conversation conversation, List<ExpectedMember> expected) {
		List<ConversationMember> current =
				members.findByBrandIdAndConversationIdAndLeftAtIsNull(conversation.getBrandId(), conversation.getId());
		Set<String> wanted = expected.stream().map((m) -> m.kind() + ":" + m.id()).collect(Collectors.toSet());
		Set<String> held = current.stream().map((m) -> m.getKind() + ":" + m.getMemberId()).collect(Collectors.toSet());
		boolean changed = false;

		for (ConversationMember member : current) {
			if (!wanted.contains(member.getKind() + ":" + member.getMemberId())) {
				member.leave(reasonFor(member, conversation.getCaseId()), Instant.now());
				members.save(member);
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_MEMBER_REMOVED, Map.of("kind", member.getKind(), "id", member.getMemberId(),
								"role", member.getRole()), Map.of("reason", member.getLeftReason()));
				changed = true;
			}
		}
		for (ExpectedMember member : expected) {
			if (!held.contains(member.kind() + ":" + member.id())
					&& members.addIfAbsent(conversation.getBrandId(), conversation.getId(), member.kind().name(),
							member.id(), member.role().name()) == 1) {
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_MEMBER_ADDED, null,
						Map.of("kind", member.kind(), "id", member.id(), "role", member.role()));
				changed = true;
			}
		}
		if (changed) {
			events.publishEvent(new ChatChanged(conversation.getBrandId(), conversation.getId(),
					ChatChanged.Kind.MEMBERS_CHANGED, null));
		}
	}

	private LeftReason reasonFor(ConversationMember gone, UUID caseId) {
		return switch (gone.getKind()) {
			case CLIENT -> LeftReason.ACCOUNT_REMOVED;
			case EXPERT -> offers.findByCaseIdOrderByOfferedAtDesc(caseId).stream()
					.filter((offer) -> offer.getExpertId().equals(gone.getMemberId()))
					.findFirst().map(ExpertCaseOffer::getOutcome)
					.map((outcome) -> switch (outcome) {
						case DECLINED -> LeftReason.OFFER_DECLINED;
						case TIMED_OUT -> LeftReason.OFFER_TIMED_OUT;
						case SUPERSEDED -> LeftReason.OFFER_SUPERSEDED;
						default -> LeftReason.REASSIGNED;
					}).orElse(LeftReason.REASSIGNED);
			case STAFF -> switch (gone.getRole()) {
				case SALES -> LeftReason.PIPELINE_REVOKED;
				case ENM -> LeftReason.DEACTIVATED;
				default -> LeftReason.REASSIGNED;
			};
		};
	}
}
