package com.akpedia.server.client;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.akpedia.server.config.KeycloakAdminProperties;
import com.akpedia.server.exception.InvalidUserException;
import com.akpedia.server.exception.KeycloakUnavailableException;
import com.akpedia.server.exception.UserExistsException;
import com.akpedia.server.exception.UserNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The server's door to Keycloak's Admin API, as the {@code akpedia-server} service account.
 *
 * <p>The service account's token is kept until shortly before it expires, so a page of the users
 * screen costs one token request at most. A token Keycloak stopped accepting before then (the
 * realm keys rotated, say) is dropped and the call made once more with a new one.
 *
 * <p>An unknown user id leaves as {@link UserNotFoundException}; every other failure as
 * {@link KeycloakUnavailableException}.
 */
@Component
public class KeycloakAdminClient {

    /** A token this close to expiring is not used: it could expire on the way. */
    static final long EXPIRY_MARGIN_SECONDS = 10;

    private static final ParameterizedTypeReference<List<KeycloakUser>> USERS = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<KeycloakRole>> ROLES = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Object>> REPRESENTATION =
            new ParameterizedTypeReference<>() { };

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final KeycloakAdminProperties properties;
    private final Clock clock;

    private String token;
    private Instant tokenExpiresAt = Instant.MIN;

    @Autowired
    public KeycloakAdminClient(
            @Qualifier("keycloakAdminRestClient") RestClient keycloakAdminRestClient, KeycloakAdminProperties properties) {
        this(keycloakAdminRestClient, properties, Clock.systemUTC());
    }

    KeycloakAdminClient(RestClient restClient, KeycloakAdminProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * One page of the realm's users, sorted by username as Keycloak sorts them.
     *
     * @param search text Keycloak looks for in the username, email, first and last name; all
     *               users when {@code null} or blank
     */
    public List<KeycloakUser> users(String search, int first, int max) {
        return call("listar os usuarios", token -> restClient.get()
                .uri(builder -> {
                    builder.path("/admin/realms/{realm}/users")
                            .queryParam("first", first)
                            .queryParam("max", max)
                            .queryParam("briefRepresentation", false);
                    if (search != null && !search.isBlank()) {
                        builder.queryParam("search", "{search}");
                    }
                    return builder.build(Map.of("realm", properties.realm(), "search", search == null ? "" : search.strip()));
                })
                .headers(bearer(token))
                .retrieve()
                .body(USERS));
    }

    /** How many users {@link #users} would list across all pages. */
    public long countUsers(String search) {
        Long count = call("contar os usuarios", token -> restClient.get()
                .uri(builder -> {
                    builder.path("/admin/realms/{realm}/users/count");
                    if (search != null && !search.isBlank()) {
                        builder.queryParam("search", "{search}");
                    }
                    return builder.build(Map.of("realm", properties.realm(), "search", search == null ? "" : search.strip()));
                })
                .headers(bearer(token))
                .retrieve()
                .body(Long.class));
        return count == null ? 0 : count;
    }

    /** @throws UserNotFoundException if the realm has no user with this id */
    public KeycloakUser user(String id) {
        return callForUser(id, "ler o usuario", token -> restClient.get()
                .uri("/admin/realms/{realm}/users/{id}", properties.realm(), id)
                .headers(bearer(token))
                .retrieve()
                .body(KeycloakUser.class));
    }

    /**
     * Enables or disables the account. The whole representation is sent back with only
     * {@code enabled} changed, so nothing else of the user can be lost on the way.
     */
    public void setEnabled(String id, boolean enabled) {
        Map<String, Object> representation = callForUser(id, "ler o usuario", token -> restClient.get()
                .uri("/admin/realms/{realm}/users/{id}", properties.realm(), id)
                .headers(bearer(token))
                .retrieve()
                .body(REPRESENTATION));
        Map<String, Object> changed = new LinkedHashMap<>(representation == null ? Map.of() : representation);
        changed.put("enabled", enabled);
        callForUser(id, "ativar ou desativar o usuario", token -> restClient.put()
                .uri("/admin/realms/{realm}/users/{id}", properties.realm(), id)
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(changed)
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Ends every session of the user, so they cannot refresh their token. An access token already
     * issued stays valid until it expires.
     */
    public void logout(String id) {
        callForUser(id, "encerrar as sessoes do usuario", token -> restClient.post()
                .uri("/admin/realms/{realm}/users/{id}/logout", properties.realm(), id)
                .headers(bearer(token))
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * The users whose email is exactly this one (Keycloak compares it without case). At most one
     * while the realm forbids duplicate emails.
     */
    public List<KeycloakUser> usersWithEmail(String email) {
        List<KeycloakUser> found = call("procurar o e-mail", token -> restClient.get()
                .uri(builder -> builder.path("/admin/realms/{realm}/users")
                        .queryParam("email", "{email}")
                        .queryParam("exact", true)
                        .queryParam("briefRepresentation", false)
                        .build(Map.of("realm", properties.realm(), "email", email)))
                .headers(bearer(token))
                .retrieve()
                .body(USERS));
        return found == null ? List.of() : found;
    }

    /**
     * Changes the user's account. The whole representation is read and sent back with the change
     * applied: Keycloak replaces what it is sent, attributes included, so a field left out would be
     * lost.
     *
     * @param change edits the representation (the Admin API's UserRepresentation) in place; its
     *               {@code attributes} is a mutable map of mutable lists
     * @param email  the email the change gives the account, for the 409's message; {@code null}
     *               when the email is not changed
     * @throws UserNotFoundException if the realm has no user with this id
     * @throws UserExistsException   if another user has that email
     * @throws InvalidUserException  if Keycloak refused a field, its user profile's rules say which
     */
    public void updateUser(String id, Consumer<Map<String, Object>> change, String email) {
        Map<String, Object> representation = callForUser(id, "ler o usuario", token -> restClient.get()
                .uri("/admin/realms/{realm}/users/{id}", properties.realm(), id)
                .headers(bearer(token))
                .retrieve()
                .body(REPRESENTATION));
        Map<String, Object> changed = new LinkedHashMap<>(representation == null ? Map.of() : representation);
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        if (changed.get("attributes") instanceof Map<?, ?> existing) {
            existing.forEach((name, values) -> attributes.put(String.valueOf(name), values instanceof List<?> list
                    ? new ArrayList<>(list.stream().map(String::valueOf).toList())
                    : new ArrayList<>(List.of(String.valueOf(values)))));
        }
        changed.put("attributes", attributes);
        change.accept(changed);
        refusedAsInvalid("alterar o usuario", email, () -> callForUser(id, "alterar o usuario", token -> restClient.put()
                .uri("/admin/realms/{realm}/users/{id}", properties.realm(), id)
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(changed)
                .retrieve()
                .toBodilessEntity()));
    }

    /** The realm roles mapped to the user directly, not through a composite role or a group. */
    public List<KeycloakRole> realmRoles(String id) {
        return roles(id, "/admin/realms/{realm}/users/{id}/role-mappings/realm");
    }

    /** Every realm role the user has, directly or through a composite (STANDARD comes from the default role). */
    public List<KeycloakRole> effectiveRealmRoles(String id) {
        return roles(id, "/admin/realms/{realm}/users/{id}/role-mappings/realm/composite");
    }

    /** The realm roles the user does not have yet, with the ids needed to add them. */
    public List<KeycloakRole> availableRealmRoles(String id) {
        return roles(id, "/admin/realms/{realm}/users/{id}/role-mappings/realm/available");
    }

    public void addRealmRoles(String id, List<KeycloakRole> roles) {
        callForUser(id, "atribuir papeis", token -> restClient.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.realm(), id)
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(roles)
                .retrieve()
                .toBodilessEntity());
    }

    public void removeRealmRoles(String id, List<KeycloakRole> roles) {
        callForUser(id, "remover papeis", token -> restClient.method(HttpMethod.DELETE)
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.realm(), id)
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(roles)
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Creates a user from this representation (the Admin API's UserRepresentation).
     *
     * @return the new user's id
     * @throws UserExistsException  if the realm already has a user with that username or email
     * @throws InvalidUserException if Keycloak refused a field, its user profile's rules say which
     */
    public String createUser(Map<String, Object> representation) {
        ResponseEntity<Void> response = refusedAsInvalid("criar o usuario", String.valueOf(representation.get("email")),
                () -> call("criar o usuario", token -> restClient.post()
                        .uri("/admin/realms/{realm}/users", properties.realm())
                        .headers(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(representation)
                        .retrieve()
                        .toBodilessEntity()));
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new KeycloakUnavailableException("criar o usuario", null);
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Sets the user's password, for good (not one to change at the next login).
     *
     * @throws InvalidUserException if the realm's password policy refuses it
     */
    public void setPassword(String id, String password) {
        Map<String, Object> credential = Map.of("type", "password", "value", password, "temporary", false);
        refusedAsInvalid("definir a senha", null, () -> callForUser(id, "definir a senha", token -> restClient.put()
                .uri("/admin/realms/{realm}/users/{id}/reset-password", properties.realm(), id)
                .headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(credential)
                .retrieve()
                .toBodilessEntity()));
    }

    /**
     * Deletes the user, with their sessions.
     *
     * @return {@code false} if there was no such user, which is fine: it is gone either way
     */
    public boolean deleteUser(String id) {
        try {
            callForUser(id, "excluir o usuario", token -> restClient.delete()
                    .uri("/admin/realms/{realm}/users/{id}", properties.realm(), id)
                    .headers(bearer(token))
                    .retrieve()
                    .toBodilessEntity());
            return true;
        } catch (UserNotFoundException e) {
            return false;
        }
    }

    /**
     * Turns Keycloak's 409 and 400 answers into the errors the admin can act on.
     *
     * @param email the user's email, for the 409's message; {@code null} when it cannot be a 409
     */
    private static <T> T refusedAsInvalid(String operation, String email, Supplier<T> request) {
        try {
            return request.get();
        } catch (KeycloakUnavailableException e) {
            if (e.getCause() instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                if (status == HttpStatus.CONFLICT.value() && email != null) {
                    throw new UserExistsException(email);
                }
                if (status == HttpStatus.BAD_REQUEST.value()) {
                    throw new InvalidUserException("O Keycloak recusou ao %s: %s".formatted(operation, reason(response)));
                }
            }
            throw e;
        }
    }

    /** Keycloak's own words for a refusal: {@code errorMessage}, or the first of {@code errors}. */
    private static String reason(RestClientResponseException response) {
        try {
            JsonNode body = JSON.readTree(response.getResponseBodyAsString());
            JsonNode first = body.path("errors").path(0);
            JsonNode node = first.isMissingNode() ? body : first;
            for (String field : List.of("errorMessage", "error_description", "error")) {
                if (node.hasNonNull(field)) {
                    String attribute = node.path("field").asText("");
                    String message = node.get(field).asText();
                    return attribute.isEmpty() ? message : attribute + ": " + message;
                }
            }
        } catch (JsonProcessingException | RuntimeException e) {
            // Not JSON: the status says enough.
        }
        return "dados invalidos";
    }

    private List<KeycloakRole> roles(String id, String path) {
        List<KeycloakRole> roles = callForUser(id, "ler os papeis do usuario", token -> restClient.get()
                .uri(path, properties.realm(), id)
                .headers(bearer(token))
                .retrieve()
                .body(ROLES));
        return roles == null ? List.of() : roles;
    }

    /** Like {@link #call}, but a 404 means the user does not exist. */
    private <T> T callForUser(String id, String operation, Function<String, T> request) {
        try {
            return call(operation, request);
        } catch (KeycloakUnavailableException e) {
            if (e.getCause() instanceof RestClientResponseException response
                    && response.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new UserNotFoundException(id);
            }
            throw e;
        }
    }

    /**
     * Runs one Admin API call with the service account's token, trying once more with a new token
     * if Keycloak no longer accepts the one kept.
     *
     * @param operation what was being done, phrased to read inside the error message
     */
    private <T> T call(String operation, Function<String, T> request) {
        try {
            try {
                return request.apply(token());
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
                    throw e;
                }
                forgetToken();
                return request.apply(token());
            }
        } catch (RestClientException e) {
            throw new KeycloakUnavailableException(operation, e);
        }
    }

    /** The service account's token, asking Keycloak for a new one when the one kept is about to expire. */
    private synchronized String token() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpiresAt)) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        TokenResponse response;
        try {
            response = restClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException e) {
            // Its own failure, not the call's: a 404 here is a wrong realm, not an unknown user.
            throw new KeycloakUnavailableException("obter o token do servidor", e);
        }
        if (response == null || response.accessToken() == null) {
            throw new KeycloakUnavailableException("obter o token do servidor", null);
        }
        token = response.accessToken();
        tokenExpiresAt = now.plusSeconds(Math.max(0, response.expiresIn() - EXPIRY_MARGIN_SECONDS));
        return token;
    }

    private synchronized void forgetToken() {
        token = null;
    }

    private static Consumer<HttpHeaders> bearer(String token) {
        return headers -> headers.setBearerAuth(token);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {
    }
}
