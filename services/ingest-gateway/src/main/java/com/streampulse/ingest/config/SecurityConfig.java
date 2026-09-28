/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.ingest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security: JWT bearer tokens, stateless.
 *
 * <h2>The filter chain, and why order matters</h2>
 *
 * <pre>
 *   request
 *     │
 *     ├─ SecurityFilterChain            ← we configure THIS
 *     │    ├─ CORS
 *     │    ├─ CSRF ............... disabled (see below)
 *     │    ├─ BearerTokenAuthenticationFilter
 *     │    │     reads Authorization: Bearer …
 *     │    │     validates signature, issuer, expiry against the JWKS
 *     │    │     → SecurityContext
 *     │    └─ AuthorizationFilter   (permitAll / authenticated)
 *     │
 *     └─ DispatcherServlet → IngestController
 * </pre>
 *
 * Authentication happens <b>before</b> the controller runs, which is what lets the
 * controller assume a verified principal rather than re-checking one.
 *
 * <h2>Why CSRF is disabled here — and when that answer is wrong</h2>
 *
 * CSRF attacks rely on the browser <em>automatically</em> attaching credentials: a cookie
 * goes along with a cross-site form post without the attacker ever reading it. A bearer
 * token is not automatic — JavaScript must deliberately set the header — so there is nothing
 * for an attacker to ride on.
 *
 * <p>The moment this service authenticates with a <b>cookie</b> or a session, CSRF
 * protection must come back. "We disabled CSRF because it was failing" is the wrong reason
 * and a genuine vulnerability; "this API is token-only and stateless" is the right one.
 *
 * <h2>Why stateless</h2>
 *
 * {@code SessionCreationPolicy.STATELESS} means no {@code HttpSession} is ever created. A
 * session would make the service sticky — the same client would have to return to the same
 * pod — and it would be state to replicate when scaling. The JWT already carries everything
 * needed to authorize the request.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Safe here because we authenticate with bearer tokens, never cookies.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Probes and metrics must be reachable without a token: Kubernetes
                        // does not carry one, and a liveness probe that 401s is a restart loop.
                        .requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // Prometheus scraping is unauthenticated locally. In production this
                        // endpoint is exposed only on an internal port or behind network
                        // policy — metrics leak information about traffic shape.
                        .requestMatchers("/actuator/prometheus")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/events:batch")
                        .authenticated()
                        // Deny by default. An explicit anyRequest().authenticated() means a
                        // new endpoint is protected the moment it exists, rather than
                        // accidentally public until someone notices.
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
