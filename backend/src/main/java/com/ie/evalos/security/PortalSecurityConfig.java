package com.ie.evalos.security;

import com.ie.evalos.common.ApiErrors;
import com.ie.evalos.service.PortalAccessService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The portal chain: link-based access for the client (Unit 14) and the expert (Unit 15).
 *
 * <p><strong>Fully separate from {@link SecurityConfig}, in both directions.</strong> This chain is
 * ordered first and matches {@code /api/portal/**}, so no staff JWT is ever read on a portal route
 * — {@code JwtFilter} is not in it. {@link PortalTokenFilter} is constructed here rather than
 * injected as a bean, so Spring Boot cannot also auto-register it as a global servlet filter, which
 * is what would otherwise let a portal token be read on a staff route. Two chains that accept each
 * other's credentials are one chain, and a client portal built as a narrower staff surface is how a
 * role-tier widening later becomes a client reading somebody's case.
 *
 * <p>Its own file, and not another {@code @Bean} beside the staff chain, for a second reason: a
 * dozen {@code @WebMvcTest} slices import {@code SecurityConfig} to get the real filter chain, and
 * none of them should need a portal service and a portal property to start.
 */
@Configuration
public class PortalSecurityConfig {

	/**
	 * {@code authenticated()} with no role rule: the token names one case and one audience, and the
	 * audience is checked in exactly one place, {@code PortalPrincipal.current}.
	 *
	 * <p>Unknown, expired, revoked and absent tokens all arrive here unauthenticated and get the one
	 * identical 401, so nothing about which it was is learnable from the response — the discipline
	 * a signature comparison demands. {@code ApiErrors} writes it, so a portal
	 * failure carries the same envelope as every other refusal in EvalOS.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain portalApi(HttpSecurity http, PortalAccessService portalAccess, ApiErrors apiErrors,
			@Value("${evalos.portal.rate-limit-per-minute}") int rateLimit) throws Exception {
		return http
				.securityMatcher("/api/portal/**")
				// No cookies or sessions, so there is no CSRF surface — and the credential is a
				// header the browser does not attach on its own.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint((request, response, ex) -> apiErrors.write(
								response, HttpStatus.UNAUTHORIZED, "PORTAL_LINK_INVALID",
								"This link is not valid. Please ask whoever sent it for a new one."))
						.accessDeniedHandler((request, response, ex) -> apiErrors.write(
								response, HttpStatus.FORBIDDEN, "FORBIDDEN",
								"This link does not admit you to that")))
				.addFilterBefore(new PortalTokenFilter(portalAccess, apiErrors, rateLimit),
						UsernamePasswordAuthenticationFilter.class)
				.build();
	}
}
