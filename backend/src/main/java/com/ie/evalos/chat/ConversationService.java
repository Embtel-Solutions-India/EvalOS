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
					// Insert-or-nothing, then read: whoever wins a race, both callers get the one row.
					conversations.createIfAbsent(subject.getBrandId(), subject.getId(), type.name());
					return conversations.findByBrandIdAndCaseIdAndType(subject.getBrandId(), subject.getId(), type)
							.orElseThrow(() -> new IllegalStateException("Conversation " + type + " of case "
									+ subject.getId() + " neither existed nor could be created"));
				});
	}

	private void sync(Conversation conversation, List<ExpectedMember> expected) {
		List<ConversationMember> current =
				members.findByBrandIdAndConversationIdAndLeftAtIsNull(conversation.getBrandId(), conversation.getId());
		Map<String, ExpectedMember> wanted = new java.util.LinkedHashMap<>();
		expected.forEach((m) -> wanted.put(m.kind() + ":" + m.id(), m));
		Set<String> kept = new java.util.HashSet<>();
		List<ExpectedMember> added = new ArrayList<>();
		List<ExpectedMember> removed = new ArrayList<>();

		for (ConversationMember member : current) {
			String key = member.getKind() + ":" + member.getMemberId();
			ExpectedMember target = wanted.get(key);
			if (target != null && target.role() == member.getRole()) {
				kept.add(key);
				continue;
			}
			// Gone, or still here under a different label: the old row is left, never rewritten.
			LeftReason reason = target != null ? LeftReason.ROLE_CHANGED : reasonFor(member, conversation.getCaseId());
			if (members.leave(member.getId(), reason.name()) == 1) {
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_MEMBER_REMOVED, Map.of("kind", member.getKind(), "id", member.getMemberId(),
								"role", member.getRole()), Map.of("reason", reason));
				if (target == null) {
					removed.add(new ExpectedMember(member.getKind(), member.getMemberId(), member.getRole()));
				}
			}
		}
		for (ExpectedMember member : expected) {
			if (!kept.contains(member.kind() + ":" + member.id())
					&& members.addIfAbsent(conversation.getBrandId(), conversation.getId(), member.kind().name(),
							member.id(), member.role().name()) == 1) {
				audit.recordSystemEvent(conversation.getBrandId(), OBJECT_TYPE, conversation.getId(),
						AuditAction.CHAT_MEMBER_ADDED, null,
						Map.of("kind", member.kind(), "id", member.id(), "role", member.role()));
				added.add(member);
			}
		}
		if (!added.isEmpty() || !removed.isEmpty()) {
			// Who joined and who left, so the live layer can tell each of them (Unit 57 §5).
			events.publishEvent(new ChatChanged(conversation.getBrandId(), conversation.getId(),
					ChatChanged.Kind.MEMBERS_CHANGED, new MembersChanged(added, removed)));
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
