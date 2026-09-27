package com.akpedia.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.akpedia.server.config.GotenbergProperties;
import com.akpedia.server.exception.PdfConversionException;
import com.akpedia.server.exception.PdfConversionRejectedException;
import com.akpedia.server.exception.PdfConversionUnavailableException;

/** Exercises {@link GotenbergClient} against a mocked Gotenberg service. */
class GotenbergClientTest {

    private static final String BASE_URL = "http://gotenberg.test:3000";
    private static final String CONVERT_URL = BASE_URL + "/forms/libreoffice/convert";

    private static final GotenbergProperties PROPERTIES =
            new GotenbergProperties(BASE_URL, Duration.ofSeconds(5), Duration.ofSeconds(90));

    private MockRestServiceServer service;
    private GotenbergClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.service = MockRestServiceServer.bindTo(builder).build();
        this.client = new GotenbergClient(builder.build(), PROPERTIES);
    }

    @Test
    @DisplayName("convertToPdf sends the file as multipart and reads back the converted bytes")
    void convertToPdfReturnsBytes() {
        byte[] pdf = "%PDF-1.4 conteudo convertido".getBytes(StandardCharsets.UTF_8);

        service.expect(requestTo(CONVERT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)))
                .andExpect(content().string(containsString("name=\"files\"")))
                .andExpect(content().string(containsString("filename=\"relatorio.docx\"")))
                .andRespond(withSuccess(pdf, MediaType.APPLICATION_PDF));

        byte[] result = client.convertToPdf(
                "conteudo original".getBytes(StandardCharsets.UTF_8), "relatorio.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThat(result).isEqualTo(pdf);
        service.verify();
    }

    @Test
    @DisplayName("a refused file surfaces as a rejection carrying Gotenberg's status and message")
    void refusedFileBecomesRejected() {
        service.expect(requestTo(CONVERT_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("invalid or corrupted file"));

        assertThatThrownBy(() -> client.convertToPdf(new byte[] {1}, "quebrado.xyz", "application/octet-stream"))
                .isInstanceOf(PdfConversionRejectedException.class)
                .hasMessageContaining("Gotenberg refused the conversion of 'quebrado.xyz' to PDF")
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("invalid or corrupted file")
                .asInstanceOf(type(PdfConversionRejectedException.class))
                .satisfies(e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        service.verify();
    }

    @Test
    @DisplayName("service down: the connection is refused and the message says where and why")
    void connectionRefusedBecomesUnavailable() {
        service.expect(requestTo(CONVERT_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "I/O error on POST request", new ConnectException("Connection refused"));
                });

        assertThatThrownBy(() -> client.convertToPdf(new byte[] {1}, "relatorio.docx", "application/msword"))
                .isInstanceOf(PdfConversionUnavailableException.class)
                .hasMessageContaining("Gotenberg is unreachable at " + CONVERT_URL)
                .hasMessageContaining("the conversion of 'relatorio.docx' to PDF did not happen")
                .hasMessageContaining("Check that the service is running")
                .hasRootCauseInstanceOf(ConnectException.class);
        service.verify();
    }

    @Test
    @DisplayName("service too slow: the read timeout is reported with the configured values")
    void readTimeoutBecomesUnavailable() {
        service.expect(requestTo(CONVERT_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "I/O error on POST request", new SocketTimeoutException("Read timed out"));
                });

        assertThatThrownBy(() -> client.convertToPdf(new byte[] {1}, "relatorio.docx", "application/msword"))
                .isInstanceOf(PdfConversionUnavailableException.class)
                .hasMessageContaining("connect timeout 5s, read timeout 90s")
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        service.verify();
    }

    @Test
    @DisplayName("an empty success body fails as PdfConversionException, not silently")
    void emptySuccessBodyBecomesConversionException() {
        service.expect(requestTo(CONVERT_URL))
                .andRespond(withSuccess(new byte[0], MediaType.APPLICATION_PDF));

        assertThatThrownBy(() -> client.convertToPdf(new byte[] {1}, "relatorio.docx", "application/msword"))
                .isInstanceOf(PdfConversionException.class)
                .isNotInstanceOf(PdfConversionUnavailableException.class)
                .isNotInstanceOf(PdfConversionRejectedException.class)
                .hasMessageContaining("empty body");
        service.verify();
    }

}
