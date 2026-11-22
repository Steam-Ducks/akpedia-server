package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.akpedia.server.config.MailProperties;

/**
 * Manual check against the compose's Mailpit, for seeing the e-mails land in a real inbox.
 *
 * <p>The name ends in {@code IT}, so {@code mvn test} never picks it up: it needs Mailpit running,
 * which CI has no reason to provide. Bring it up and run it by hand:
 *
 * <pre>
 * docker compose up -d mailpit
 * ./mvnw test -Dtest=MailServiceManualIT
 * </pre>
 *
 * <p>Then open http://localhost:8025 to see the messages as a person would.
 *
 * <p>{@link #sendsOneSampleTo} sends one sample e-mail to the address in {@code MAIL_TEST_TO}, through
 * whatever server the {@code MAIL_*} variables name -- Mailpit by default, or a real one to see the
 * message in a real inbox. It only runs when {@code MAIL_TEST_TO} is set:
 *
 * <pre>
 * MAIL_TEST_TO=voce@example.com ./mvnw test -Dtest=MailServiceManualIT#sendsOneSampleTo
 * </pre>
 */
class MailServiceManualIT {

    private static final String HOST = System.getenv().getOrDefault("MAIL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MAIL_PORT", "1025"));
    private static final String MAILPIT_API = System.getenv().getOrDefault("MAILPIT_URL", "http://localhost:8025");

    @Test
    @DisplayName("a batch with one bad address lands in Mailpit, the good ones in full")
    void batchLandsInMailpit() throws IOException, InterruptedException {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(HOST);
        sender.setPort(PORT);
        sender.setDefaultEncoding("UTF-8");
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "5000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "10000");
        sender.getJavaMailProperties().put("mail.smtp.localhost", "akpedia.local");
        MailService service = new MailService(sender, new MailProperties("nao-responda@akpedia.local", "Akpedia", 50));
        String marker = UUID.randomUUID().toString().substring(0, 8);

        // example.com is reserved (RFC 2606) and receives no mail: run with a real server's MAIL_*,
        // this batch still reaches nobody.
        List<MailResult> results = service.sendAll(List.of(
                new OutgoingMail("ana@example.com", "Aviso de incidente de segurança " + marker,
                        "Olá, Ana.\n\nIdentificamos um incidente de segurança. Ação recomendada: troque sua senha."),
                new OutgoingMail("nao-e-um-email", "Aviso de incidente de segurança " + marker, "corpo"),
                new OutgoingMail("bruno@example.com", "Aviso de incidente de segurança " + marker,
                        "Olá, Bruno.\n\nIdentificamos um incidente de segurança.")));
        results.forEach(result -> System.out.println("[manual] " + result));

        assertThat(results).extracting(MailResult::sent).containsExactly(true, false, true);
        HttpResponse<String> inbox = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(MAILPIT_API + "/api/v1/search?query=" + marker)).build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("[manual] Mailpit: " + inbox.body());
        assertThat(inbox.body()).contains("\"messages_count\":2", "ana@example.com", "bruno@example.com",
                "Aviso de incidente de segurança " + marker);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MAIL_TEST_TO", matches = ".+")
    @DisplayName("one sample e-mail reaches MAIL_TEST_TO through the server the MAIL_* variables name")
    void sendsOneSampleTo() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(HOST);
        sender.setPort(PORT);
        sender.setUsername(env("MAIL_USERNAME", null));
        sender.setPassword(env("MAIL_PASSWORD", null));
        sender.setDefaultEncoding("UTF-8");
        String starttls = env("MAIL_SMTP_STARTTLS", "false");
        sender.getJavaMailProperties().put("mail.smtp.auth", env("MAIL_SMTP_AUTH", "false"));
        sender.getJavaMailProperties().put("mail.smtp.starttls.enable", starttls);
        sender.getJavaMailProperties().put("mail.smtp.starttls.required", starttls);
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "5000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "10000");
        sender.getJavaMailProperties().put("mail.smtp.writetimeout", "10000");
        sender.getJavaMailProperties().put("mail.smtp.localhost", env("MAIL_SMTP_LOCALHOST", "akpedia.local"));
        MailService service = new MailService(sender, new MailProperties(
                env("MAIL_FROM", "nao-responda@akpedia.local"), env("MAIL_FROM_NAME", "Akpedia"), 50));

        MailResult result = service.send(new OutgoingMail(System.getenv("MAIL_TEST_TO"),
                "[TESTE] Akpedia: e-mail de teste do servidor",
                """
                Olá,

                Este é um e-mail de teste do servidor do Akpedia, enviado para conferir o formato das \
                mensagens: texto puro, em UTF-8, com acentos (ç, ã, é, ô) e quebras de linha preservadas.

                Nenhuma ação é necessária. Se você não esperava esta mensagem, pode ignorá-la.

                --
                Akpedia
                Esta é uma mensagem automática; respostas a este endereço não são lidas.
                """));

        System.out.println("[manual] " + result);
        assertThat(result.sent()).as(result.toString()).isTrue();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
