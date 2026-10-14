package com.ie.evalos.security;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.TeamMemberPipelineRepository;
import com.ie.evalos.repository.TeamMemberRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A desk's pipelines are read per request, so a grant or revoke after sign-in takes effect at once (D19b). */
class JwtFilterPipelinesTest {

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void thePipelinesAreTheCurrentGrantsNotTheTokens() throws Exception {
		UUID member = UUID.randomUUID();
		JwtService jwt = new JwtService("x".repeat(40), Duration.ofHours(1));
		String token = jwt.issue(new StaffPrincipal(member, "desk@ie.test", "Desk", Role.SALES, UUID.randomUUID(), null,
				List.of("granted-at-sign-in", "revoked-since"), null, true));

		TeamMemberRepository members = mock(TeamMemberRepository.class);
		when(members.existsByIdAndActiveTrue(member)).thenReturn(true);
		TeamMemberPipelineRepository pipelines = mock(TeamMemberPipelineRepository.class);
		when(pipelines.ghlIdsFor(member)).thenReturn(List.of("granted-at-sign-in", "granted-since"));

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + token);
		new JwtFilter(jwt, provider(members), provider(pipelines))
				.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		assertThat(TenantContext.current().ghlPipelineIds()).containsExactly("granted-at-sign-in", "granted-since");
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> provider(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(bean);
		return provider;
	}
}
