package com.ie.evalos.service;

import java.util.List;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.domain.Role;
import com.ie.evalos.repository.ContactDirectoryRepository;
import com.ie.evalos.repository.ContactDirectoryRepository.Page;
import com.ie.evalos.security.StaffPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Who sees how many contacts — the whole of this feature, and the part that cannot be checked by
 * looking at the screen.
 *
 * <p>The rule: <strong>a GM reads every brand, a Brand Manager reads its own, and a desk reads the
 * contacts on the deals it works.</strong> Each arm is pinned by asserting which repository method
 * is reached, because a scope bug does not throw — it renders a longer list, and a longer list
 * looks like a fuller CRM rather than like a breach.
 */
class ContactDirectoryScopeTest {

	private static final UUID BRAND = UUID.randomUUID();
	private static final UUID MEMBER = UUID.randomUUID();

	/** An empty page, so each arm's stub returns the shape the service hands back. */
	private static final Page EMPTY = new Page(List.of(), 0, 15);

	private final ContactDirectoryRepository contacts = mock(ContactDirectoryRepository.class);
	private final ContactDirectoryService directory = new ContactDirectoryService(contacts);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theGmReadsEveryBrand() {
		authenticate(Role.GM, null, List.of());
		given(contacts.all(null, 0, 15)).willReturn(EMPTY);

		directory.forCaller(null, 0, 15);

		verify(contacts).all(null, 0, 15);
		// Not merely "all was called": the brand-scoped arms must not ALSO run. A GM whose read
		// fell through to `inBrand(null, …)` would get an empty list and look like a GM with no
		// contacts rather than like a bug.
		verify(contacts, never()).inBrand(any(), any(), anyInt(), anyInt());
		verify(contacts, never()).onPipelines(any(), any(), any(), anyInt(), anyInt());
	}

	@Test
	void aBrandManagerReadsItsOwnBrandAndNoOther() {
		authenticate(Role.BRAND_MANAGER, BRAND, List.of());
		given(contacts.inBrand(eq(BRAND), any(), anyInt(), anyInt())).willReturn(EMPTY);

		directory.forCaller(null, 0, 15);

		verify(contacts).inBrand(BRAND, null, 0, 15);
		verify(contacts, never()).all(any(), anyInt(), anyInt());
	}

	@Test
	void aDeskReadsOnlyTheContactsOnItsOwnPipelines() {
		authenticate(Role.SALES, BRAND, List.of("pipe_mine", "pipe_also_mine"));
		given(contacts.onPipelines(eq(BRAND), any(), any(), anyInt(), anyInt())).willReturn(EMPTY);

		directory.forCaller(null, 0, 15);

		verify(contacts).onPipelines(BRAND, List.of("pipe_mine", "pipe_also_mine"), null, 0, 15);
		verify(contacts, never()).all(any(), anyInt(), anyInt());
		verify(contacts, never()).inBrand(any(), any(), anyInt(), anyInt());
	}

	/**
	 * <strong>Fail closed, not fail empty.</strong> The same call {@code PipelineScope.mine()}
	 * makes, for the same reason: a desk with no pipeline cannot do anything here, and an empty
	 * screen is a support call somebody has to guess the cause of.
	 */
	@Test
	void aDeskWithNoPipelineIsRefusedRatherThanShownNothing() {
		authenticate(Role.SALES, BRAND, List.of());

		assertThatExceptionOfType(ForbiddenException.class)
				.isThrownBy(() -> directory.forCaller(null, 0, 15))
				.withMessageContaining("no GHL pipeline");

		verify(contacts, never()).onPipelines(any(), any(), any(), anyInt(), anyInt());
	}

	/** A brand-locked caller carrying no brand matches nothing, so it is refused before it reads. */
	@Test
	void aBrandLockedCallerWithNoBrandIsRefused() {
		authenticate(Role.BRAND_MANAGER, null, List.of());

		assertThatExceptionOfType(ForbiddenException.class)
				.isThrownBy(() -> directory.forCaller(null, 0, 15))
				.withMessageContaining("no brand");
	}

	/**
	 * A production role reaching this route is refused by the service as well as by the route.
	 *
	 * <p>The {@code @PreAuthorize} on the controller already excludes them, so this asserts the
	 * second gate: if that annotation is ever widened, the tier switch still has no arm for
	 * {@code SELF} and refuses rather than falling through to somebody else's brand.
	 */
	@Test
	void aCaseManagerIsRefusedEvenIfTheRouteLetsThemIn() {
		authenticate(Role.CASE_MANAGER, BRAND, List.of());

		assertThatExceptionOfType(ForbiddenException.class)
				.isThrownBy(() -> directory.forCaller(null, 0, 15))
				.withMessageContaining("cases, not the CRM");
	}

	private static void authenticate(Role role, UUID brandId, List<String> pipelines) {
		StaffPrincipal principal = new StaffPrincipal(MEMBER, role + "@evalos.local", "Desk", role,
				brandId, null, pipelines, null, true);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
	}
}
