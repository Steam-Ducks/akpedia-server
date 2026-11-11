package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.net.ConnectException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.akpedia.server.config.MailProperties;
import com.akpedia.server.service.MailResult.Failed;
import com.akpedia.server.service.MailResult.Reason;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * The failures an SMTP server in memory does not produce on demand -- a recipient refused in the
 * middle of a batch, a connection lost, a login refused, an error the library was not expected to
 * throw -- told apart from how Spring and Jakarta Mail report them. The real protocol is
 * {@code MailServiceTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class MailServiceFailureTest {

    @Mock
    private JavaMailSender sender;

    private MailService service;

    @BeforeEach
    void setUp() {
        service = new MailService(sender, new MailProperties("nao-responda@akpedia.local", "Akpedia", 50));
        // Lenient: an empty batch, or one with only invalid messages, never builds a message.
        lenient().when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
    }

    private static OutgoingMail mail(String to) {
        return new OutgoingMail(to, "Aviso", "corpo");
    }

    /** Has Spring's sender report the batch's message at {@code index} as failed with {@code failure}. */
    private void failsMessageAt(int index, Exception failure) {
        doAnswer(invocation -> {
            MimeMessage[] batch = (MimeMessage[]) invocation.getRawArguments()[0];
            Map<Object, Exception> failed = new LinkedHashMap<>();
            failed.put(batch[index], failure);
            throw new MailSendException(failed);
        }).when(sender).send(any(MimeMessage[].class));
    }

    @Test
    @DisplayName("a recipient refused in the middle of a batch fails alone, as REJECTED; the others are Sent")
    void refusedRecipientFailsAlone() throws Exception {
        failsMessageAt(1, new SendFailedException("550 5.1.1 <bruno@empresa.com>: User unknown",
                null, new InternetAddress[0], new InternetAddress[0],
                new InternetAddress[] {new InternetAddress("bruno@empresa.com")}));

        List<MailResult> results = service.sendAll(List.of(
                mail("ana@empresa.com"), mail("bruno@empresa.com"), mail("carla@empresa.com")));

        assertThat(results).extracting(MailResult::sent).containsExactly(true, false, true);
        assertThat(((Failed) results.get(1)).reason()).isEqualTo(Reason.REJECTED);
        assertThat(((Failed) results.get(1)).reason().retryable()).isFalse();
    }

    @Test
    @DisplayName("a connection lost midway fails the messages it took, as UNAVAILABLE, and keeps the ones already sent")
    void connectionLostMidwayIsUnavailable() {
        MessagingException couldNotConnect = new MessagingException("Couldn't connect to host", new ConnectException("refused"));
        doAnswer(invocation -> {
            MimeMessage[] batch = (MimeMessage[]) invocation.getRawArguments()[0];
            Map<Object, Exception> failed = new LinkedHashMap<>();
            failed.put(batch[1], couldNotConnect);
            failed.put(batch[2], couldNotConnect);
            throw new MailSendException("Mail server connection failed", couldNotConnect, failed);
        }).when(sender).send(any(MimeMessage[].class));

        List<MailResult> results = service.sendAll(List.of(
                mail("ana@empresa.com"), mail("bruno@empresa.com"), mail("carla@empresa.com")));

        assertThat(results.get(0).sent()).isTrue();
        assertThat(results.subList(1, 3)).allSatisfy(result -> {
            assertThat(((Failed) result).reason()).isEqualTo(Reason.UNAVAILABLE);
            assertThat(((Failed) result).reason().retryable()).isTrue();
        });
    }

    @Test
    @DisplayName("a login refused fails the whole batch as AUTHENTICATION")
    void refusedLoginFailsTheBatch() {
        doThrow(new MailAuthenticationException("535 Authentication failed"))
                .when(sender).send(any(MimeMessage[].class));

        List<MailResult> results = service.sendAll(List.of(mail("ana@empresa.com"), mail("bruno@empresa.com")));

        assertThat(results).allSatisfy(result -> assertThat(((Failed) result).reason()).isEqualTo(Reason.AUTHENTICATION));
    }

    @Test
    @DisplayName("an error nobody expected fails its batch as FAILED instead of escaping, and the next batch still goes out")
    void unexpectedErrorDoesNotStopTheNextBatch() {
        MailService oneByOne = new MailService(sender, new MailProperties("nao-responda@akpedia.local", "Akpedia", 1));
        doThrow(new IllegalStateException("boom")).doNothing().when(sender).send(any(MimeMessage[].class));

        List<MailResult> results = oneByOne.sendAll(List.of(mail("ana@empresa.com"), mail("bruno@empresa.com")));

        assertThat(((Failed) results.get(0)).reason()).isEqualTo(Reason.FAILED);
        assertThat(((Failed) results.get(0)).detail()).isEqualTo("boom");
        assertThat(results.get(1).sent()).isTrue();
    }

    @Test
    @DisplayName("an empty batch, or one with only invalid messages, never reaches the SMTP server")
    void nothingToSendNeverConnects() {
        assertThat(service.sendAll(List.of())).isEmpty();
        assertThat(service.sendAll(List.of(mail("nao-e-um-email")))).noneMatch(MailResult::sent);

        then(sender).should(never()).send(any(MimeMessage[].class));
    }

    @Test
    @DisplayName("the recipient is the address alone, stripped of surrounding spaces")
    void recipientIsStripped() throws Exception {
        doNothing().when(sender).send(any(MimeMessage[].class));
        ArgumentCaptor<MimeMessage[]> sent = ArgumentCaptor.forClass(MimeMessage[].class);

        assertThat(service.send(mail("  ana@empresa.com  ")).sent()).isTrue();

        then(sender).should().send(sent.capture());
        MimeMessage message = sent.getAllValues().get(0)[0];
        assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("ana@empresa.com");
    }

    @Test
    @DisplayName("the SMTP reply code decides: 5xx is a refusal for good, 4xx one for now, -1 a dropped connection")
    void replyCodeDecidesTheReason() throws Exception {
        InternetAddress ana = new InternetAddress("ana@empresa.com");
        Address[] none = new Address[0];

        assertThat(MailService.reasonOf(new SMTPAddressFailedException(ana, "RCPT TO:<ana@empresa.com>", 550,
                "550 5.1.1 User unknown"))).isEqualTo(Reason.REJECTED);
        assertThat(MailService.reasonOf(new SMTPAddressFailedException(ana, "RCPT TO:<ana@empresa.com>", 452,
                "452 4.2.2 Mailbox full"))).isEqualTo(Reason.UNAVAILABLE);
        assertThat(MailService.reasonOf(new SMTPSendFailedException(".", 554, "554 5.7.1 Message rejected",
                null, none, none, none))).isEqualTo(Reason.REJECTED);
        // What Angus throws when the connection drops before the server answers the message.
        assertThat(MailService.reasonOf(new SMTPSendFailedException(".", -1, "[EOF]",
                null, none, none, none))).isEqualTo(Reason.UNAVAILABLE);
    }

    @Test
    @DisplayName("a 5xx wrapped by Spring, the way a refused recipient reaches MailService, is still REJECTED")
    void wrappedRefusalIsStillRejected() throws Exception {
        SMTPAddressFailedException refused = new SMTPAddressFailedException(
                new InternetAddress("bruno@empresa.com"), "RCPT TO:<bruno@empresa.com>", 550, "550 5.1.1 User unknown");
        failsMessageAt(1, new SendFailedException("Invalid Addresses", refused));

        List<MailResult> results = service.sendAll(List.of(mail("ana@empresa.com"), mail("bruno@empresa.com")));

        assertThat(((Failed) results.get(1)).reason()).isEqualTo(Reason.REJECTED);
    }
}
