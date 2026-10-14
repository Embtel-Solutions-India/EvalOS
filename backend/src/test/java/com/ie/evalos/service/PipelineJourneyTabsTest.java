package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.config.SellingBrand;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.domain.Pipeline;
import com.ie.evalos.domain.PipelineStage;
import com.ie.evalos.domain.Role;
import com.ie.evalos.domain.TeamMember;
import com.ie.evalos.repository.GhlCustomFieldRepository;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;
import com.ie.evalos.security.StaffPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A desk holding two pipelines reads one at a time, by tab, and cannot name a pipeline it does not hold. */
class PipelineJourneyTabsTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();

	private final OpportunityMirrorService mirror = mock(OpportunityMirrorService.class);
	private final PipelineMirrorService pipelines = mock(PipelineMirrorService.class);
	private final TeamMemberPipelineRepository assignments = mock(TeamMemberPipelineRepository.class);
	private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
	private final PipelineJourneyService service = new PipelineJourneyService(mirror, pipelines, assignments, teamMembers,
			mock(GhlCustomFieldRepository.class), mock(MemberTargetService.class), mock(GmOverviewService.class),
			new SellingBrand(BRAND));

	@BeforeEach
	void deskWithTwoPipelines() {
		TeamMember desk = new TeamMember("sales@ie.test", "x");
		ReflectionTestUtils.setField(desk, "id", MEMBER);
		ReflectionTestUtils.setField(desk, "brandId", BRAND);
		ReflectionTestUtils.setField(desk, "role", Role.SALES);
		ReflectionTestUtils.setField(desk, "displayName", "Desk");
		when(teamMembers.findByActiveTrueAndRoleAndBrandId(Role.SALES, BRAND)).thenReturn(List.of(desk));
		when(assignments.ghlIdsFor(MEMBER)).thenReturn(List.of("p-master", "p-eval"));

		Pipeline master = pipeline("p-master", "Master Pipeline", 0);
		Pipeline eval = pipeline("p-eval", "Evaluation", 1);
		when(pipelines.all()).thenReturn(List.of(master, eval, pipeline("p-other", "Someone else's", 2)));
		when(pipelines.stagesOf(master.getId())).thenReturn(List.of(stage(master, "m-won", "Won")));
		when(pipelines.stagesOf(eval.getId())).thenReturn(List.of(stage(eval, "e-lead", "Lead")));
		when(mirror.onPipelines(List.of("p-master"))).thenReturn(List.of(deal(master, "m-won", "won")));
		when(mirror.onPipelines(List.of("p-eval"))).thenReturn(List.of(deal(eval, "e-lead", "open")));

		StaffPrincipal principal = new StaffPrincipal(MEMBER, "sales@ie.test", "Desk", Role.SALES, BRAND, null, List.of("p-master", "p-eval"),
				null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void defaultIsTheFirstPipelineAndOnlyItsStages() {
		var journey = service.forCaller(null, null, null, null, null);
		assertThat(journey.pipelines()).extracting(PipelineJourneyService.PipelineTab::id)
				.containsExactly("p-master", "p-eval");
		assertThat(journey.pipelineId()).isEqualTo("p-master");
		assertThat(journey.stages()).singleElement().satisfies((row) -> {
			assertThat(row.name()).isEqualTo("Won");
			assertThat(row.deals()).isEqualTo(1);
		});
	}

	@Test
	void aTabNarrowsToThatPipeline() {
		var journey = service.forCaller(null, null, null, null, "p-eval");
		assertThat(journey.pipelineId()).isEqualTo("p-eval");
		assertThat(journey.stages()).extracting(PipelineJourneyService.StageRow::name).containsExactly("Lead");
	}

	@Test
	void aPipelineTheDeskDoesNotHoldIsRefused() {
		assertThatThrownBy(() -> service.forCaller(null, null, null, null, "p-other"))
				.isInstanceOf(ForbiddenException.class);
	}

	private static Pipeline pipeline(String ghlId, String name, int position) {
		Pipeline row = new Pipeline(BRAND, ghlId, name, position);
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	private static PipelineStage stage(Pipeline pipeline, String ghlId, String name) {
		PipelineStage row = new PipelineStage(BRAND, pipeline.getId(), ghlId, name, 0);
		ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
		return row;
	}

	private static Opportunity deal(Pipeline pipeline, String stageId, String status) {
		Opportunity row = new Opportunity(BRAND, UUID.randomUUID().toString(), pipeline.getId());
		ReflectionTestUtils.setField(row, "ghlStageId", stageId);
		ReflectionTestUtils.setField(row, "status", status);
		return row;
	}
}
