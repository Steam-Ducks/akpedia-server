package com.akpedia.server.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.dto.MeResponse;
import com.akpedia.server.entity.User;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.UserRepository;

/** The signed-in user, as the app reads them right after login ({@code GET /me}). */
@Service
public class MeService {

    private final UserRepository users;
    private final TermsService terms;

    public MeService(UserRepository users, TermsService terms) {
        this.users = users;
        this.terms = terms;
    }

    /**
     * @param userId      the user's row, already provisioned
     * @param keycloakId  their Keycloak account id
     * @param authorities the token's authorities; the realm roles are the {@code ROLE_*} ones
     */
    @Transactional(readOnly = true)
    public MeResponse me(Long userId, String keycloakId, Collection<? extends GrantedAuthority> authorities) {
        User user = users.findWithSectorById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        Set<String> granted = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .collect(Collectors.toSet());
        List<String> roles = AdminUserService.KNOWN_ROLES.stream().filter(granted::contains).toList();
        return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getSector().getName(), roles,
                terms.mustAccept(keycloakId));
    }
}
