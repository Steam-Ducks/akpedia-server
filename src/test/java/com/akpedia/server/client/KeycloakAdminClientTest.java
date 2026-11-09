package com.akpedia.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.akpedia.server.config.KeycloakAdminProperties;
import com.akpedia.server.exception.InvalidUserException;
import com.akpedia.server.exception.KeycloakUnavailableException;
import com.akpedia.server.exception.UserExistsException;
import com.akpedia.server.exception.UserNotFoundException;

/** Exercises {@link KeycloakAdminClient} against a mocked Keycloak. */
class KeycloakAdminClientTest {

    private static final String BASE_URL = "http://keycloak.test:8080";
    private static final String TOKEN_URL = BASE_URL + "/realms/akpedia/protocol/openid-connect/token";
    private static final String USERS_URL = BASE_URL + "/admin/realms/akpedia/users";
    private static final String TOKEN = "{\"access_token\": \"tok-1\", \"expires_in\": 300}";

    private static final KeycloakAdminProperties PROPERTIES = new KeycloakAdminProperties(
            BASE_URL, "akpedia", "akpedia-server", "segredo", Duration.ofSeconds(5), Duration.ofSeconds(10));

    private MockRestServiceServer keycloak;
    private KeycloakAdminClient client;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        keycloak = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(Instant.parse("2026-11-07T12:00:00Z"));
        client = new KeycloakAdminClient(builder.build(), PROPERTIES, clock);
    }

    private void expectToken(String accessToken) {
        keycloak.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andExpect(content().string(containsString("client_secret=segredo")))
                .andRespond(withSuccess("{\"access_token\": \"" + accessToken + "\", \"expires_in\": 300}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("lists users with the service account's token, the search encoded, and reads their fields")
    void listsUsers() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "?first=20&max=10&briefRepresentation=false&search=ana%2Bsouza%20%7Bx%7D"))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("""
                        [{"id": "u1", "username": "ana@akpedia.test", "email": "ana@akpedia.test",
                          "firstName": "Ana", "lastName": "Souza", "enabled": true,
                          "createdTimestamp": 1762000000000, "attributes": {"sector": ["Engenharia"]},
                          "totp": false}]""", MediaType.APPLICATION_JSON));

        List<KeycloakUser> users = client.users("ana+souza {x}", 20, 10);

        assertThat(users).singleElement().satisfies(user -> {
            assertThat(user.name()).isEqualTo("Ana Souza");
            assertThat(user.attribute("sector")).isEqualTo("Engenharia");
            assertThat(user.isEnabled()).isTrue();
        });
        keycloak.verify();
    }

    @Test
    @DisplayName("the token is kept until shortly before it expires")
    void tokenIsCached() {
        keycloak.expect(once(), requestTo(TOKEN_URL)).andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON));
        keycloak.expect(twice(), requestTo(USERS_URL + "/count"))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("7", MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\": \"tok-2\", \"expires_in\": 300}", MediaType.APPLICATION_JSON));
        keycloak.expect(once(), requestTo(USERS_URL + "/count"))
                .andExpect(header("Authorization", "Bearer tok-2"))
                .andRespond(withSuccess("7", MediaType.APPLICATION_JSON));

        assertThat(client.countUsers(null)).isEqualTo(7);
        clock.advance(Duration.ofSeconds(280));
        assertThat(client.countUsers(" ")).isEqualTo(7);
        // Within 10 seconds of expiring: a new one is asked for.
        clock.advance(Duration.ofSeconds(15));
        assertThat(client.countUsers(null)).isEqualTo(7);
        keycloak.verify();
    }

    @Test
    @DisplayName("a token Keycloak no longer accepts is replaced once, and the call made again")
    void staleTokenIsReplaced() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/u1")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken("tok-2");
        keycloak.expect(requestTo(USERS_URL + "/u1"))
                .andExpect(header("Authorization", "Bearer tok-2"))
                .andRespond(withSuccess("{\"id\": \"u1\", \"email\": \"ana@akpedia.test\"}", MediaType.APPLICATION_JSON));

        assertThat(client.user("u1").email()).isEqualTo("ana@akpedia.test");
        keycloak.verify();
    }

    @Test
    @DisplayName("an unknown user id is UserNotFoundException")
    void unknownUser() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/nope")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.user("nope")).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("a Keycloak error, or a refused service account, is KeycloakUnavailableException")
    void failuresAreUnavailable() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/u1/role-mappings/realm/composite"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.effectiveRealmRoles("u1")).isInstanceOf(KeycloakUnavailableException.class);

        setUp();
        keycloak.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        // Not a 404 for the user: the token request itself failed.
        assertThatThrownBy(() -> client.user("u1")).isInstanceOf(KeycloakUnavailableException.class);
    }

    @Test
    @DisplayName("enabling sends the whole representation back with only enabled changed")
    void setEnabledKeepsTheRest() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id": "u1", "email": "ana@akpedia.test", "enabled": true,
                         "attributes": {"sector": ["Engenharia"], "cpf": ["12345678909"]}}""",
                        MediaType.APPLICATION_JSON));
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.attributes.sector[0]").value("Engenharia"))
                .andExpect(jsonPath("$.attributes.cpf[0]").value("12345678909"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.setEnabled("u1", false);
        keycloak.verify();
    }

    @Test
    @DisplayName("updating sends the whole representation back, attributes kept, with the change applied")
    void updateUserKeepsTheRest() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id": "u1", "email": "ana@akpedia.test", "firstName": "Ana", "enabled": true,
                         "attributes": {"sector": ["Engenharia"], "cpf": ["12345678909"]}}""",
                        MediaType.APPLICATION_JSON));
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.firstName").value("Ana Maria"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.attributes.sector[0]").value("Qualidade"))
                .andExpect(jsonPath("$.attributes.cpf[0]").value("12345678909"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.updateUser("u1", representation -> {
            representation.put("firstName", "Ana Maria");
            @SuppressWarnings("unchecked")
            Map<String, List<String>> attributes = (Map<String, List<String>>) representation.get("attributes");
            attributes.put("sector", List.of("Qualidade"));
        }, null);
        keycloak.verify();
    }

    @Test
    @DisplayName("updating: 404 is user_not_found, 409 is user_exists, 400 is invalid_user")
    void updateRefusals() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/nope")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\": \"u1\"}", MediaType.APPLICATION_JSON));
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\": \"u1\"}", MediaType.APPLICATION_JSON));
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"field\": \"firstName\", \"errorMessage\": \"error-person-name-invalid-character\"}"));

        assertThatThrownBy(() -> client.updateUser("nope", representation -> { }, null))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> client.updateUser("u1", representation -> { }, "bia@akpedia.test"))
                .isInstanceOf(UserExistsException.class).hasMessageContaining("bia@akpedia.test");
        assertThatThrownBy(() -> client.updateUser("u1", representation -> { }, null))
                .isInstanceOf(InvalidUserException.class).hasMessageContaining("firstName");
    }

    @Test
    @DisplayName("looks a user up by exact email, encoded")
    void usersWithEmail() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "?email=ana%2Bx%40akpedia.test&exact=true&briefRepresentation=false"))
                .andRespond(withSuccess("[{\"id\": \"u1\", \"email\": \"ana+x@akpedia.test\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client.usersWithEmail("ana+x@akpedia.test")).extracting(KeycloakUser::id).containsExactly("u1");
    }

    @Test
    @DisplayName("role mappings are added and removed with the roles' id and name")
    void changesRoleMappings() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/u1/role-mappings/realm")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$[0].id").value("r-adm"))
                .andExpect(jsonPath("$[0].name").value("ADMIN"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        keycloak.expect(requestTo(USERS_URL + "/u1/role-mappings/realm")).andExpect(method(HttpMethod.DELETE))
                .andExpect(jsonPath("$[0].name").value("APPROVER"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.addRealmRoles("u1", List.of(new KeycloakRole("r-adm", "ADMIN")));
        client.removeRealmRoles("u1", List.of(new KeycloakRole("r-app", "APPROVER")));
        keycloak.verify();
    }

    @Test
    @DisplayName("creates a user and answers the id from the Location header")
    void createsUser() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.username").value("ana@akpedia.test"))
                .andExpect(jsonPath("$.attributes.cpf[0]").value("12345678909"))
                .andRespond(withStatus(HttpStatus.CREATED).location(URI.create(USERS_URL + "/new-id")));

        String id = client.createUser(Map.of("username", "ana@akpedia.test", "email", "ana@akpedia.test",
                "attributes", Map.of("cpf", List.of("12345678909"))));

        assertThat(id).isEqualTo("new-id");
        keycloak.verify();
    }

    @Test
    @DisplayName("creating: 409 is user_exists, 400 is invalid_user with Keycloak's reason")
    void createRefusals() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL)).andRespond(withStatus(HttpStatus.CONFLICT));
        keycloak.expect(requestTo(USERS_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"field\": \"cpf\", \"errorMessage\": \"O CPF deve ter 11 dígitos\"}"));

        assertThatThrownBy(() -> client.createUser(Map.of("email", "ana@akpedia.test")))
                .isInstanceOf(UserExistsException.class).hasMessageContaining("ana@akpedia.test");
        assertThatThrownBy(() -> client.createUser(Map.of("email", "ana@akpedia.test")))
                .isInstanceOf(InvalidUserException.class).hasMessageContaining("cpf: O CPF deve ter 11 dígitos");
    }

    @Test
    @DisplayName("sets a password for good; the policy's refusal is invalid_user")
    void setsPassword() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/u1/reset-password"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.type").value("password"))
                .andExpect(jsonPath("$.value").value("s3gr3d0"))
                .andExpect(jsonPath("$.temporary").value(false))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        keycloak.expect(requestTo(USERS_URL + "/u1/reset-password"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": \"invalidPasswordMinLengthMessage\"}"));

        client.setPassword("u1", "s3gr3d0");
        assertThatThrownBy(() -> client.setPassword("u1", "x"))
                .isInstanceOf(InvalidUserException.class).hasMessageContaining("invalidPasswordMinLengthMessage");
    }

    @Test
    @DisplayName("deletes a user; one already gone is not an error")
    void deletesUser() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/u1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        keycloak.expect(requestTo(USERS_URL + "/gone")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.deleteUser("u1")).isTrue();
        assertThat(client.deleteUser("gone")).isFalse();
        keycloak.verify();
    }

    /** A clock the test moves forward by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Test
    @DisplayName("finds the users with a CPF by an exact attribute query")
    void findsUsersWithCpf() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "?q=cpf%3A12345678909&exact=true&briefRepresentation=false"))
                .andRespond(withSuccess("""
                        [{"id": "u1", "username": "ana@akpedia.test", "attributes": {"cpf": ["12345678909"]}}]""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.usersWithCpf("12345678909")).extracting(KeycloakUser::id).containsExactly("u1");
        keycloak.verify();
    }

    @Test
    @DisplayName("ends a user's sessions")
    void logsOut() {
        expectToken("tok-1");
        keycloak.expect(requestTo(USERS_URL + "/u1/logout"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withNoContent());

        client.logout("u1");
        keycloak.verify();
    }
}
