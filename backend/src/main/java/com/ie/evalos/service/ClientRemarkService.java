package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Case;
import com.ie.evalos.domain.CaseClientRemark;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.CaseClientRemarkRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What staff tell the client about a case (D74) — kept apart from internal notes so nothing here
 * can leak the other way. Append-only: a correction is a newer remark. The case is loaded through
 * {@link CaseLifecycleService#load}, so brand, team and assignment scope apply exactly as they do
 * to every other case write.
 */
@Service
public class ClientRemarkService {

	public record Remark(UUID id, String body, UUID authorId, String authorName, Instant createdAt) {
	}

	private static final String OBJECT_TYPE = "CASE_CLIENT_REMARK";

	private final CaseLifecycleService lifecycle;
	private final CaseClientRemarkRepository remarks;
	private final TeamMemberRepository teamMembers;
	private final AuditService audit;

	ClientRemarkService(CaseLifecycleService lifecycle, CaseClientRemarkRepository remarks,
			TeamMemberRepository teamMembers, AuditService audit) {
		this.lifecycle = lifecycle;
		this.remarks = remarks;
		this.teamMembers = teamMembers;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<Remark> list(UUID caseId) {
		Case subject = lifecycle.load(caseId);
		List<CaseClientRemark> rows = remarks.findByCaseIdOrderByCreatedAtAsc(subject.getId());
		Map<UUID, String> names = teamMembers.findAllById(rows.stream().map(CaseClientRemark::getAuthorId).distinct().toList())
				.stream().collect(Collectors.toMap(TeamMember::getId, TeamMember::getDisplayName, (a, b) -> a));
		return rows.stream().map(r -> view(r, names.get(r.getAuthorId()))).toList();
	}

	@Transactional
	public Remark add(UUID caseId, String body) {
		Case subject = lifecycle.load(caseId);
		TenantContext caller = TenantContext.current();
		CaseClientRemark saved = remarks.save(new CaseClientRemark(subject.getBrandId(), subject.getId(),
				caller.memberId(), body.strip()));
		audit.recordEvent(OBJECT_TYPE, saved.getId(), AuditAction.NOTE_ADDED, caller.memberId(), null,
				Map.of("caseId", subject.getId()));
		String name = teamMembers.findById(caller.memberId()).map(TeamMember::getDisplayName).orElse(null);
		return view(saved, name);
	}

	private static Remark view(CaseClientRemark r, String authorName) {
		return new Remark(r.getId(), r.getBody(), r.getAuthorId(), authorName, r.getCreatedAt());
	}
}
