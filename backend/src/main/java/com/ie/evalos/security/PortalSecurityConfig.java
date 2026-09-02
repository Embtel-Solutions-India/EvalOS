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
	 * CORS for the portal chain only.
	 *
	 * <p><strong>Named origins, never {@code *}.</strong> This chain is credentialed — it carries
	 * {@code X-Portal-Token} — and a wildcard origin on a credentialed API hands any page on the
	 * internet the ability to make authenticated calls with a token it has phished. The property
	 * is a comma-separated list so an environment can name its own, and it is <strong>empty by
	 * default</strong>: an environment that forgets it gets a failing preflight, which is loud,
	 * rather than an open one, which is not.
	 *
	 * <p><strong>Scoped to {@code /api/portal/**} by living on this chain.</strong> The staff API
	 * is same-origin and has no reason to answer a preflight at all; giving it CORS would widen a
	 * surface for no caller.
	 *
	 * <p>{@code X-Portal-Token} is in the allowed headers deliberately. Omit it and the preflight
	 * passes while the real request arrives with the header stripped — a 401 that looks exactly
	 * like a bad token and is not one.
	 */
	private static org.springframework.web.cors.CorsConfigurationSource portalCors(String allowedOrigins) {
		org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins.isBlank() ? java.util.List.of()
				: java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim)
						.filter(origin -> !origin.isEmpty()).toList());
		config.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
		config.setAllowedHeaders(java.util.List.of("Content-Type", PortalTokenFilter.HEADER));
		// No cookies are used and none should be: the credential is a header, and allowing
		// credentials would turn a mistaken origin into a session-riding hole.
		config.setAllowCredentials(false);
		config.setMaxAge(java.time.Duration.ofMinutes(30));

		org.springframework.web.cors.UrlBasedCorsConfigurationSource source =
				new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/portal/**", config);
		return source;
	}

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
			@Value("${evalos.portal.rate-limit-per-minute}") int rateLimit,
			@Value("${evalos.portal.allowed-origins:}") String allowedOrigins) throws Exception {
		return http
				.securityMatcher("/api/portal/**")
				// **CORS, and it is required rather than a nicety (Unit 30).** The client and expert
				// portals are a SEPARATE frontend on another origin, so every non-simple request is
				// preflighted — and without this the preflight fails before any filter runs, while
				// the very same call passes a curl test. That is how this gets diagnosed as a token
				// problem for an afternoon.
				.cors(cors -> cors.configurationSource(portalCors(allowedOrigins)))
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
