package com.akpedia.server.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.akpedia.server.security.RequestAuditFilter;
import com.akpedia.server.security.TermsGateFilter;
import com.akpedia.server.service.AuditService;
import com.akpedia.server.service.TermsService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Every {@code /api} route needs a Keycloak access token; reviewing documents also needs the
 * {@code APPROVER} or {@code ADMIN} realm role, and the admin routes the {@code ADMIN} one. Until
 * the user accepts the current terms of use, {@link TermsGateFilter} holds the rest back. Every
 * {@code /api} request, refused or not, is recorded by {@link RequestAuditFilter}. A deleted
 * account's token, still valid for a few minutes, is refused by {@link DeletedAccountFilter}.
 *
 * <p>The {@code /internal} routes are left to {@link InternalTokenFilter}: only the akpedia-ml
 * worker calls them, with its shared token, never with a user's.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Realm roles that may see the review queue and approve or return documents. */
    static final String[] REVIEWER_ROLES = {"APPROVER", "ADMIN"};

    @Bean
    public SecurityFilterChain apiSecurity(
            HttpSecurity http, TermsService terms, AuditService audit, AccountDeletionLog deletions, ObjectMapper json)
            throws Exception {
        http
                // Bearer tokens only, no cookies: there is no session for a forged request to ride on.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/documents/pending").hasAnyRole(REVIEWER_ROLES)
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/documents/*/approve", "/api/v1/documents/*/reject").hasAnyRole(REVIEWER_ROLES)
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(realmRoles())))
                // After the role checks: someone without the role gets 403 for that, not for the terms.
                .addFilterAfter(new TermsGateFilter(terms, json), AuthorizationFilter.class)
                // Before the token check, so it sees the 401s too, and the user once it returns.
                .addFilterBefore(new RequestAuditFilter(audit), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Turns the token's realm roles ({@code realm_access.roles}) into {@code ROLE_*} authorities. */
    static JwtAuthenticationConverter realmRoles() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRolesConverter());
        return converter;
    }

    static final class RealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

        @Override
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
                return List.of();
            }
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }
    }
}
