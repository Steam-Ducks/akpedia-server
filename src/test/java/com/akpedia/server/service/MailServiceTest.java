package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.akpedia.server.config.MailProperties;
import com.akpedia.server.service.MailResult.Failed;
import com.akpedia.server.service.MailResult.Reason;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * {@link MailService} over the real SMTP protocol, against GreenMail: an SMTP server in memory that
 * keeps what it receives, the way Mailpit does in the compose.
 */
class MailServiceTest {

    private static final String USER = "akpedia";
    private static final String PASSWORD = "senha-certa";

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(USER, PASSWORD));

    private static final MailProperties SENDER = new MailProperties("nao-responda@akpedia.local", "Akpedia", 50);

    /** Counts the SMTP connections it opens, to tell one per batch from one per message. */
    private static final class CountingSender extends JavaMailSenderImpl {

        private int connections;

        @Override
        protected Transport connectTransport() throws MessagingException {
            connections++;
            return super.connectTransport();
        }
    }

    /** A sender set up the way application.yml sets it up, against the given port. */
    private static CountingSender sender(int port, Duration timeout) {
        CountingSender sender = new CountingSender();
        sender.setHost("localhost");
        sender.setPort(port);
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.connectiontimeout", String.valueOf(timeout.toMillis()));
        properties.put("mail.smtp.timeout", String.valueOf(timeout.toMillis()));
        properties.put("mail.smtp.writetimeout", String.valueOf(timeout.toMillis()));
        properties.put("mail.smtp.localhost", "akpedia.local");
        return sender;
    }

    private static MailService service() {
        return new MailService(sender(SMTP.getSmtp().getPort(), Duration.ofSeconds(5)), SENDER);
    }

    private static OutgoingMail notice(String to) {
        return new OutgoingMail(to, "Aviso de incidente de segurança",
                "Olá,\n\nIdentificamos um incidente que pode ter exposto seus dados de acesso.\n\nAção recomendada: troque sua senha.");
    }

    @Test
    @DisplayName("a message reaches the server with the configured sender, and accents as written")
    void sendsWithSenderAndAccents() throws Exception {
        MailResult result = service().send(notice("ana.silva@empresa.com"));

        assertThat(result.sent()).isTrue();
        assertThat(result.to()).isEqualTo("ana.silva@empresa.com");
        MimeMessage[] received = SMTP.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage message = received[0];
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("nao-responda@akpedia.local");
        assertThat(from.getPersonal()).isEqualTo("Akpedia");
        assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("ana.silva@empresa.com");
        assertThat(message.getSubject()).isEqualTo("Aviso de incidente de segurança");
        assertThat(message.getContentType()).containsIgnoringCase("text/plain").containsIgnoringCase("UTF-8");
        assertThat(((String) message.getContent()).replace("\r\n", "\n"))
                .contains("Olá,", "Ação recomendada: troque sua senha.");
    }

    @Test
    @DisplayName("a recipient that is not an e-mail address fails as INVALID_MESSAGE, and nothing is sent")
    void invalidRecipientIsRefusedBeforeSending() {
        for (String to : new String[] {"nao-e-um-email", "@empresa.com", "ana@", "ana silva@empresa.com",
            "ana@empresa.com, outro@empresa.com", "  ", null}) {
            MailResult result = service().send(notice(to));

            assertThat(result).as(String.valueOf(to)).isInstanceOf(Failed.class);
            assertThat(((Failed) result).reason()).as(String.valueOf(to)).isEqualTo(Reason.INVALID_MESSAGE);
        }
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("a subject with a line break is refused, so it cannot inject headers such as Bcc")
    void subjectCannotInjectHeaders() {
        MailResult result = service().send(new OutgoingMail(
                "ana@empresa.com", "Aviso\r\nBcc: intruso@fora.com", "corpo"));

        assertThat(((Failed) result).reason()).isEqualTo(Reason.INVALID_MESSAGE);
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("a message with no subject or no body, or no message at all, is refused without throwing")
    void incompleteMessageIsRefused() {
        assertThat(((Failed) service().send(new OutgoingMail("ana@empresa.com", " ", "corpo"))).reason())
                .isEqualTo(Reason.INVALID_MESSAGE);
        assertThat(((Failed) service().send(new OutgoingMail("ana@empresa.com", "Assunto", null))).reason())
                .isEqualTo(Reason.INVALID_MESSAGE);
        assertThat(((Failed) service().send(null)).reason()).isEqualTo(Reason.INVALID_MESSAGE);
    }

    @Test
    @DisplayName("in a batch, a failed message does not stop the ones after it, and results keep the order")
    void batchGoesOnAfterAFailure() throws Exception {
        List<OutgoingMail> mails = new ArrayList<>(List.of(
                notice("ana@empresa.com"), notice("nao-e-um-email"), notice("bruno@empresa.com")));

        List<MailResult> results = service().sendAll(mails);

        assertThat(results).extracting(MailResult::to)
                .containsExactly("ana@empresa.com", "nao-e-um-email", "bruno@empresa.com");
        assertThat(results).extracting(MailResult::sent).containsExactly(true, false, true);
        assertThat(SMTP.getReceivedMessages()).hasSize(2);
        assertThat(GreenMailUtil.getAddressList(SMTP.getReceivedMessages()[1].getAllRecipients()))
                .isEqualTo("bruno@empresa.com");
    }

    @Test
    @DisplayName("with the server down, the send fails as UNAVAILABLE, which is worth retrying, and the batch goes on")
    void serverDownIsUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        MailService down = new MailService(sender(closedPort, Duration.ofSeconds(2)), SENDER);

        List<MailResult> results = down.sendAll(List.of(notice("ana@empresa.com"), notice("bruno@empresa.com")));

        assertThat(results).hasSize(2).allSatisfy(result -> {
            assertThat(((Failed) result).reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(((Failed) result).reason().retryable()).isTrue();
        });
    }

    @Test
    @DisplayName("a server that accepts the connection and goes quiet fails within the timeout, instead of hanging")
    void silentServerTimesOut() throws IOException {
        try (ServerSocket silent = new ServerSocket(0)) {
            List<Socket> held = new ArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (!silent.isClosed()) {
                        held.add(silent.accept());
                    }
                } catch (IOException closed) {
                    // The test is over.
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            MailService stuck = new MailService(sender(silent.getLocalPort(), Duration.ofMillis(500)), SENDER);

            Instant start = Instant.now();
            MailResult result = stuck.send(notice("ana@empresa.com"));

            assertThat(((Failed) result).reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("a wrong password fails as AUTHENTICATION, a configuration problem retrying will not fix")
    void wrongPasswordIsAnAuthenticationFailure() {
        JavaMailSenderImpl withAuth = sender(SMTP.getSmtp().getPort(), Duration.ofSeconds(5));
        withAuth.setUsername(USER);
        withAuth.setPassword("senha-errada");
        withAuth.getJavaMailProperties().put("mail.smtp.auth", "true");

        MailResult result = new MailService(withAuth, SENDER).send(notice("ana@empresa.com"));

        assertThat(((Failed) result).reason()).isEqualTo(Reason.AUTHENTICATION);
        assertThat(((Failed) result).reason().retryable()).isFalse();
        assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    @DisplayName("with the right username and password, the message goes out")
    void rightPasswordSends() {
        JavaMailSenderImpl withAuth = sender(SMTP.getSmtp().getPort(), Duration.ofSeconds(5));
        withAuth.setUsername(USER);
        withAuth.setPassword(PASSWORD);
        withAuth.getJavaMailProperties().put("mail.smtp.auth", "true");

        assertThat(new MailService(withAuth, SENDER).send(notice("ana@empresa.com")).sent()).isTrue();
        assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("addresses are masked for the logs, including the ones quoted inside a server's answer")
    void addressesAreMaskedForTheLogs() {
        assertThat(MailService.mask("ana.silva@empresa.com")).isEqualTo("a***@empresa.com");
        assertThat(MailService.maskEmails("550 5.1.1 <bruno@empresa.com>: Recipient address rejected"))
                .isEqualTo("550 5.1.1 <b***@empresa.com>: Recipient address rejected");
        assertThat(MailService.mask(null)).isEqualTo("(sem destinatario)");
    }

    @Test
    @DisplayName("each message is named after the sender's domain, not the machine, and Sent carries that Message-ID")
    void messageIdIsTheSendersDomain() throws Exception {
        List<MailResult> results = service().sendAll(List.of(notice("ana@empresa.com"), notice("bruno@empresa.com")));

        MimeMessage[] received = SMTP.getReceivedMessages();
        for (int i = 0; i < 2; i++) {
            String sentId = ((MailResult.Sent) results.get(i)).messageId();
            assertThat(sentId).matches("<[0-9a-f-]{36}@akpedia\\.local>");
            assertThat(received[i].getMessageID()).isEqualTo(sentId);
        }
        assertThat(received[0].getMessageID()).isNotEqualTo(received[1].getMessageID());
    }

    @Test
    @DisplayName("a batch goes out over one SMTP connection, not one per message")
    void batchUsesOneConnection() {
        CountingSender sender = sender(SMTP.getSmtp().getPort(), Duration.ofSeconds(5));
        MailService service = new MailService(sender, SENDER);

        List<MailResult> results = service.sendAll(List.of(
                notice("a@empresa.com"), notice("b@empresa.com"), notice("nao-e-um-email"),
                notice("c@empresa.com"), notice("d@empresa.com")));

        assertThat(results).extracting(MailResult::sent).containsExactly(true, true, false, true, true);
        assertThat(sender.connections).isEqualTo(1);
        assertThat(SMTP.getReceivedMessages()).hasSize(4);
    }

    @Test
    @DisplayName("past the batch size, the next messages go out over a new connection")
    void largeBatchIsSplitAcrossConnections() {
        CountingSender sender = sender(SMTP.getSmtp().getPort(), Duration.ofSeconds(5));
        MailService service = new MailService(sender, new MailProperties("nao-responda@akpedia.local", "Akpedia", 2));

        List<MailResult> results = service.sendAll(List.of(
                notice("a@empresa.com"), notice("b@empresa.com"), notice("c@empresa.com"),
                notice("d@empresa.com"), notice("e@empresa.com")));

        assertThat(results).allMatch(MailResult::sent);
        assertThat(sender.connections).isEqualTo(3);
        assertThat(SMTP.getReceivedMessages()).hasSize(5);
    }

    @Test
    @DisplayName("the server is greeted with the configured name, not the machine's")
    void greetsWithTheConfiguredName() throws Exception {
        service().send(notice("ana@empresa.com"));

        assertThat(SMTP.getReceivedMessages()[0].getHeader("Received")).isNotNull()
                .anySatisfy(received -> assertThat(received).contains("akpedia.local"));
    }

}
