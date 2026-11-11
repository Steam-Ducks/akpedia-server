package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.akpedia.server.config.MailProperties;
import com.akpedia.server.service.MailResult.Failed;
import com.akpedia.server.service.MailResult.Reason;

/**
 * What happens to a batch when the SMTP connection misbehaves halfway through, against a small fake
 * SMTP server that does exactly that -- something neither GreenMail nor Mailpit can be told to do.
 *
 * <p>These pin the behaviour {@link MailService} relies on from Spring's {@code JavaMailSenderImpl}:
 * it checks the connection before each message and opens a new one when the server dropped it, and
 * it reports a failure to close the connection without listing any message as failed.
 */
class MailServiceConnectionTest {

    /** How the fake server misbehaves. */
    private enum Fault {
        /** Accepts the first message, then drops the connection. */
        DROP_AFTER_FIRST_MESSAGE,
        /** Drops the connection while the first message is being accepted, before answering it. */
        DROP_DURING_FIRST_MESSAGE,
        /** Accepts every message, then answers QUIT with an error and drops the connection. */
        REFUSE_QUIT
    }

    private FakeSmtpServer server;

    @AfterEach
    void stop() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    private MailService serviceFor(FakeSmtpServer smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(smtp.port());
        sender.setDefaultEncoding("UTF-8");
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "2000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "2000");
        sender.getJavaMailProperties().put("mail.smtp.writetimeout", "2000");
        return new MailService(sender, new MailProperties("nao-responda@akpedia.local", "Akpedia", 50));
    }

    private static List<OutgoingMail> three() {
        return List.of(
                new OutgoingMail("ana@empresa.com", "Aviso", "corpo"),
                new OutgoingMail("bruno@empresa.com", "Aviso", "corpo"),
                new OutgoingMail("carla@empresa.com", "Aviso", "corpo"));
    }

    @Test
    @DisplayName("a connection the server drops between messages is opened again, and every message goes out")
    void droppedConnectionIsOpenedAgain() throws IOException {
        server = new FakeSmtpServer(Fault.DROP_AFTER_FIRST_MESSAGE);

        List<MailResult> results = serviceFor(server).sendAll(three());

        assertThat(results).allMatch(MailResult::sent);
        assertThat(server.accepted()).isEqualTo(3);
        assertThat(server.connections()).isEqualTo(2);
    }

    @Test
    @DisplayName("a connection dropped while a message is in flight costs that message alone, as UNAVAILABLE")
    void connectionDroppedMidMessageCostsThatMessage() throws IOException {
        server = new FakeSmtpServer(Fault.DROP_DURING_FIRST_MESSAGE);

        List<MailResult> results = serviceFor(server).sendAll(three());

        assertThat(results).extracting(MailResult::sent).containsExactly(false, true, true);
        assertThat(((Failed) results.get(0)).reason()).isEqualTo(Reason.UNAVAILABLE);
        assertThat(server.accepted()).isEqualTo(2);
        assertThat(server.connections()).isEqualTo(2);
    }

    @Test
    @DisplayName("messages the server accepted stay Sent even when closing the connection afterwards fails")
    void failingToCloseDoesNotUndoTheSends() throws IOException {
        server = new FakeSmtpServer(Fault.REFUSE_QUIT);

        List<MailResult> results = serviceFor(server).sendAll(three());

        assertThat(results).allMatch(MailResult::sent);
        assertThat(server.accepted()).isEqualTo(3);
    }

    /**
     * Just enough SMTP for Jakarta Mail to send plain messages, one connection at a time, with one
     * fault on the first connection.
     */
    private static final class FakeSmtpServer implements AutoCloseable {

        private final ServerSocket socket = new ServerSocket(0);
        private final Fault fault;
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger accepted = new AtomicInteger();
        private final Thread acceptor;

        FakeSmtpServer(Fault fault) throws IOException {
            this.fault = fault;
            acceptor = new Thread(this::serve, "fake-smtp");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        int connections() {
            return connections.get();
        }

        int accepted() {
            return accepted.get();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    talk(client, connections.incrementAndGet() == 1);
                } catch (IOException e) {
                    // The client went away, or the test is over: wait for the next connection.
                }
            }
        }

        private void talk(Socket client, boolean first) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
            reply(out, "220 fake ESMTP");
            int onThisConnection = 0;
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line.toUpperCase();
                switch (command) {
                    case "EHLO", "HELO" -> reply(out, "250 fake");
                    case "MAIL", "RCPT", "RSET", "NOOP" -> reply(out, "250 OK");
                    case "DATA" -> {
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        while ((line = in.readLine()) != null && !line.equals(".")) {
                            // The message body; its content is MailServiceTest's business.
                        }
                        onThisConnection++;
                        if (first && fault == Fault.DROP_DURING_FIRST_MESSAGE) {
                            return;
                        }
                        accepted.incrementAndGet();
                        reply(out, "250 queued");
                        if (first && fault == Fault.DROP_AFTER_FIRST_MESSAGE && onThisConnection == 1) {
                            return;
                        }
                    }
                    case "QUIT" -> {
                        reply(out, fault == Fault.REFUSE_QUIT ? "500 no" : "221 bye");
                        return;
                    }
                    default -> reply(out, "502 not implemented");
                }
            }
        }

        private static void reply(PrintWriter out, String line) {
            out.print(line + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
