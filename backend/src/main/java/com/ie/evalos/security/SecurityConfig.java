package com.ie.evalos.security;

import com.ie.evalos.common.ApiErrors;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The staff API chain: stateless, bearer-token only.
 *
 * <p>The link-based portals are the <strong>second</strong> chain, in
 * {@link PortalSecurityConfig} — a separate file because they are a separate surface, and because
 * this one is imported by a dozen {@code @WebMvcTest} slices that have no business needing a portal
 * service to start. Do not widen this chain to cover them: two chains that accept each other's
 * credentials are one chain. That chain is ordered first and matches {@code /api/portal/**}; this
 * one matches everything else.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	@Bean
	@Order(2)
	SecurityFilterChain staffApi(HttpSecurity http, JwtFilter jwtFilter, ApiErrors apiErrors) throws Exception {
		return http
				// No cookies or sessions are used, so there is no CSRF surface.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/auth/login", "/api/health", "/actuator/health").permitAll()
						// Inbound webhooks carry no EvalOS token: the source is a machine in
						// another company. They are authenticated by the per-brand endpoint
						// token in the path, resolved in WebhookGateway. Nothing here reads
						// the security context.
						.requestMatchers("/api/webhooks/**").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint((request, response, ex) -> apiErrors.write(
								response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
								"A valid bearer token is required"))
						.accessDeniedHandler((request, response, ex) -> apiErrors.write(
								response, HttpStatus.FORBIDDEN, "FORBIDDEN",
								"Not permitted for this role, brand, or assignment")))
				.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}

	/**
	 * Keeps {@link JwtFilter} out of the global servlet chain, leaving it only where it is wired.
	 *
	 * <p><strong>{@link PortalTokenFilter} solves the same problem by not being a bean at all</strong>
	 * — this chain constructs it, so Boot never sees it. {@code JwtFilter} cannot do that: it is a
	 * {@code @Component} several test slices inject by type. So it gets the other half of the same
	 * discipline. Without this, Boot auto-registers any {@code Filter} bean for {@code /*} and a
	 * staff {@code Bearer} token is read on {@code /api/portal/**} — including the four
	 * unauthenticated auth routes, where an authenticated context changes nothing today and
	 * falsifies what {@link PortalSecurityConfig} and {@code PortalTokenFilter} both say in
	 * writing: that no staff JWT is ever read on a portal route. Two chains that accept each
	 * other's credentials are one chain, and the drift starts as documentation.
	 *
	 * <p>Registering it on this chain via {@code addFilterBefore} is unaffected: a filter chain
	 * holds its own instance and does not consult the servlet registration.
	 */
	@Bean
	FilterRegistrationBean<JwtFilter> jwtFilterIsChainOnly(JwtFilter jwtFilter) {
		FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(jwtFilter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	AuthenticationManager authenticationManager(EvalOsUserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}
}
