package com.akpedia.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The mail settings live in four files that have to agree: {@code application.yml} reads the
 * {@code MAIL_*} variables, {@code docker-compose.yml} hands them to the app and to Keycloak, the
 * realm file reads them on import, and {@code .env.example} documents them. A variable renamed in
 * one and not the others fails silently at runtime -- the default kicks in -- so it fails here.
 */
class MailSettingsConsistencyTest {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{(MAIL_[A-Z_]+)");

    private static Set<String> variablesIn(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = VARIABLE.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> composeEnvironmentOf(String service) throws IOException {
        Map<String, Object> compose = new Yaml().load(read("docker-compose.yml"));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> definition = (Map<String, Object>) services.get(service);
        Map<String, Object> environment = (Map<String, Object>) definition.get("environment");
        return environment.keySet();
    }

    @Test
    @DisplayName("every MAIL_* variable application.yml reads is handed to the app by the compose")
    void composeGivesTheAppEveryVariable() throws IOException {
        Set<String> read = variablesIn(read("src/main/resources/application.yml"));

        assertThat(read).isNotEmpty();
        assertThat(composeEnvironmentOf("app")).containsAll(read);
    }

    @Test
    @DisplayName("every MAIL_* variable the realm's SMTP reads is handed to Keycloak by the compose")
    void composeGivesKeycloakEveryVariable() throws IOException {
        JsonNode smtp = new ObjectMapper().readTree(read("keycloak/akpedia-realm.json")).get("smtpServer");
        Set<String> read = variablesIn(smtp.toString());

        assertThat(read).contains("MAIL_HOST", "MAIL_PORT", "MAIL_FROM");
        assertThat(composeEnvironmentOf("keycloak")).containsAll(read);
    }

    @Test
    @DisplayName(".env.example documents every MAIL_* variable the app reads")
    void envExampleDocumentsEveryVariable() throws IOException {
        String example = read(".env.example");

        for (String variable : variablesIn(read("src/main/resources/application.yml"))) {
            assertThat(example).as(variable).containsPattern("(?m)^#? ?" + variable + "=");
        }
    }

    @Test
    @DisplayName("the compose runs a Mailpit with its SMTP and web ports published")
    void composeRunsMailpit() throws IOException {
        assertThat(read("docker-compose.yml"))
                .contains("image: axllent/mailpit:")
                .contains("\"1025:1025\"")
                .contains("\"8025:8025\"");
    }
}
