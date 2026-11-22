package com.akpedia.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * What {@code application.yml} makes of the mail settings: the {@code MAIL_*} variables reach the
 * sender, the timeouts are set, and a bad sender address stops the boot. Only the mail beans are
 * started, so no database or SMTP server is needed.
 */
class MailConfigTest {

    @Nested
    @SpringBootTest(classes = {MailSenderAutoConfiguration.class, ValidationAutoConfiguration.class, MailConfig.class})
    @DisplayName("with no MAIL_* variable set")
    class Defaults {

        @Autowired
        private JavaMailSender sender;
        @Autowired
        private MailProperties mail;

        @Test
        @DisplayName("sends through the compose's Mailpit, as UTF-8, with no login and bounded waits")
        void pointsAtMailpitWithTimeouts() {
            JavaMailSenderImpl impl = (JavaMailSenderImpl) sender;
            assertThat(impl.getHost()).isEqualTo("localhost");
            assertThat(impl.getPort()).isEqualTo(1025);
            assertThat(impl.getUsername()).isNullOrEmpty();
            assertThat(impl.getDefaultEncoding()).isEqualTo("UTF-8");
            Properties properties = impl.getJavaMailProperties();
            assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("false");
            assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
            assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
            assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("10000");
            assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("10000");
            assertThat(properties.getProperty("mail.smtp.localhost")).isEqualTo("akpedia.local");
            assertThat(mail.from()).isEqualTo("nao-responda@akpedia.local");
            assertThat(mail.fromName()).isEqualTo("Akpedia");
            assertThat(mail.batchSize()).isEqualTo(50);
            assertThat(mail.domain()).isEqualTo("akpedia.local");
        }
    }

    @Nested
    @SpringBootTest(
            classes = {MailSenderAutoConfiguration.class, ValidationAutoConfiguration.class, MailConfig.class},
            properties = {
                "MAIL_HOST=smtp.empresa.com", "MAIL_PORT=587", "MAIL_USERNAME=akpedia", "MAIL_PASSWORD=segredo",
                "MAIL_SMTP_AUTH=true", "MAIL_SMTP_STARTTLS=true", "MAIL_CONNECT_TIMEOUT_MS=2000",
                "MAIL_FROM=avisos@empresa.com", "MAIL_FROM_NAME=Akpedia Avisos",
                "MAIL_SMTP_LOCALHOST=akpedia.empresa.com", "MAIL_BATCH_SIZE=20"})
    @DisplayName("with the MAIL_* variables of a real server")
    class RealServer {

        @Autowired
        private JavaMailSender sender;
        @Autowired
        private MailProperties mail;

        @Test
        @DisplayName("each variable lands where it belongs")
        void variablesReachTheSender() {
            JavaMailSenderImpl impl = (JavaMailSenderImpl) sender;
            assertThat(impl.getHost()).isEqualTo("smtp.empresa.com");
            assertThat(impl.getPort()).isEqualTo(587);
            assertThat(impl.getUsername()).isEqualTo("akpedia");
            assertThat(impl.getPassword()).isEqualTo("segredo");
            Properties properties = impl.getJavaMailProperties();
            assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
            assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
            assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
            assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("2000");
            assertThat(properties.getProperty("mail.smtp.localhost")).isEqualTo("akpedia.empresa.com");
            assertThat(mail.from()).isEqualTo("avisos@empresa.com");
            assertThat(mail.fromName()).isEqualTo("Akpedia Avisos");
            assertThat(mail.batchSize()).isEqualTo(20);
            assertThat(mail.domain()).isEqualTo("empresa.com");
        }
    }

    @Test
    @DisplayName("a sender that is not an e-mail address stops the boot, instead of failing every send")
    void invalidSenderFailsTheBoot() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(MailConfig.class)
                .withPropertyValues("akpedia.mail.from=nao-e-um-email")
                .run(context -> assertThat(context).hasFailed());
    }
}
