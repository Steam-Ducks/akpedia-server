package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.client.GotenbergClient;
import com.akpedia.server.dto.DocumentAnalysisResponse;
import com.akpedia.server.dto.DocumentClassification;
import com.akpedia.server.entity.Category;
import com.akpedia.server.exception.CategorySuggestionUnavailableException;
import com.akpedia.server.exception.EmbeddingRejectedException;
import com.akpedia.server.exception.EmbeddingUnavailableException;
import com.akpedia.server.exception.InvalidDocumentUploadException;
import com.akpedia.server.exception.UnknownSuggestedCategoryException;
import com.akpedia.server.exception.UnsupportedDocumentFormatException;
import com.akpedia.server.repository.CategoryRepository;

/**
 * The rules of the analysis, with the converter, the classifier and the catalog mocked:
 * which formats get in, what reaches the classifier, and how each failure is reported.
 */
@ExtendWith(MockitoExtension.class)
class DocumentAnalysisServiceTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock
    private CategoryRepository categories;
    @Mock
    private GotenbergClient gotenberg;
    @Mock
    private EmbeddingClient embeddingClient;

    private DocumentAnalysisService service;
    private Category tecnico;

    @BeforeEach
    void setUp() {
        service = new DocumentAnalysisService(categories, gotenberg, embeddingClient);
        tecnico = new Category("Tecnico", "documentos tecnicos");
        ReflectionTestUtils.setField(tecnico, "id", 7L);
    }

    private static final String TITLE = "Manual do Trem de Pouso";
    private static final String DESCRIPTION = "Este manual descreve os procedimentos de inspecao.";

    /** What akpedia-ml answers, with the title and description every test shares. */
    private static DocumentClassification analysis(String category, BigDecimal confidence) {
        Map<String, BigDecimal> scores = category == null || confidence == null ? Map.of() : Map.of(category, confidence);
        return new DocumentClassification(TITLE, DESCRIPTION, category, confidence, scores, 3);
    }

    /** The happy path of the two collaborators, so each test only states what it is about. */
    private void givenClassifierSuggests(String category, String confidence) {
        given(embeddingClient.classifyDocument(any(), any(), any()))
                .willReturn(analysis(category, new BigDecimal(confidence)));
        given(categories.findByName(category)).willReturn(
                "Tecnico".equals(category) ? Optional.of(tecnico) : Optional.empty());
    }

    @Test
    @DisplayName("a PDF is classified as it arrived, without going through Gotenberg")
    void pdfSkipsConversion() {
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        givenClassifierSuggests("Tecnico", "0.873");

        DocumentAnalysisResponse result = service.analyze(pdf, "manual.pdf", "application/pdf");

        assertThat(result.title()).isEqualTo(TITLE);
        assertThat(result.description()).isEqualTo(DESCRIPTION);
        assertThat(result.suggestedCategoryId()).isEqualTo(7L);
        assertThat(result.confidence()).isEqualByComparingTo("0.873");
        verifyNoInteractions(gotenberg);
        verify(embeddingClient).classifyDocument(pdf, "manual.pdf", "application/pdf");
    }

    @Test
    @DisplayName("a non-PDF is converted first, and it is the PDF that gets classified")
    void nonPdfIsConvertedBeforeClassifying() {
        byte[] original = "docx conteudo".getBytes();
        byte[] converted = "%PDF-1.4 convertido".getBytes();
        given(gotenberg.convertToPdf(original, "manual.docx", DOCX)).willReturn(converted);
        givenClassifierSuggests("Tecnico", "0.5");

        service.analyze(original, "manual.docx", DOCX);

        // The classifier must never see the original bytes: it reads text out of a PDF.
        verify(embeddingClient).classifyDocument(converted, "manual.docx", "application/pdf");
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({
        "manual.pdf,    application/pdf",
        "manual.doc,    application/msword",
        "manual.docx,   application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "planilha.xls,  application/vnd.ms-excel",
        "planilha.xlsx, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    })
    @DisplayName("every whitelisted format is accepted")
    void acceptsEveryWhitelistedFormat(String filename, String contentType) {
        // Lenient because the PDF case in this list legitimately never converts, so for that one
        // parameter set the stub goes unused. Dropping it would break the other four.
        lenient().when(gotenberg.convertToPdf(any(), any(), any())).thenReturn("%PDF-1.4".getBytes());
        givenClassifierSuggests("Tecnico", "0.9");

        assertThat(service.analyze("conteudo".getBytes(), filename, contentType)).isNotNull();
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({
        "apresentacao.pptx, application/vnd.ms-powerpoint",
        "imagem.png,        image/png",
        "texto.txt,         text/plain",
        "arquivo.zip,       application/zip"
    })
    @DisplayName("a format outside the whitelist is refused before anything else happens")
    void refusesFormatsOutsideTheWhitelist(String filename, String contentType) {
        assertThatThrownBy(() -> service.analyze("conteudo".getBytes(), filename, contentType))
                .isInstanceOf(UnsupportedDocumentFormatException.class)
                .hasMessageContaining(filename);

        // Refusing early is the point: no conversion, no classifier call, no catalog lookup.
        verifyNoInteractions(gotenberg, embeddingClient, categories);
    }

    @Test
    @DisplayName("a recognised extension is enough when the browser sent a generic media type")
    void extensionAloneIsEnough() {
        given(gotenberg.convertToPdf(any(), any(), any())).willReturn("%PDF-1.4".getBytes());
        givenClassifierSuggests("Tecnico", "0.9");

        assertThat(service.analyze("conteudo".getBytes(), "manual.docx", "application/octet-stream")).isNotNull();
    }

    @Test
    @DisplayName("a recognised media type is enough when the file arrived without a name")
    void mediaTypeAloneIsEnough() {
        givenClassifierSuggests("Tecnico", "0.9");

        assertThat(service.analyze("%PDF-1.4".getBytes(), null, "application/pdf")).isNotNull();
    }

    @Test
    @DisplayName("a media type carrying parameters is still recognised")
    void mediaTypeWithParametersIsRecognised() {
        given(gotenberg.convertToPdf(any(), any(), any())).willReturn("%PDF-1.4".getBytes());
        givenClassifierSuggests("Tecnico", "0.9");

        assertThat(service.analyze("conteudo".getBytes(), "sem-extensao", "application/msword; charset=utf-8"))
                .isNotNull();
    }

    @Test
    @DisplayName("an empty file is refused before the format is even looked at")
    void emptyFileIsRefused() {
        assertThatThrownBy(() -> service.analyze(new byte[0], "manual.pdf", "application/pdf"))
                .isInstanceOf(InvalidDocumentUploadException.class);

        verifyNoInteractions(gotenberg, embeddingClient, categories);
    }

    @Test
    @DisplayName("an unreachable classifier becomes the failure the screen recovers from")
    void unavailableClassifierBecomesASuggestionFailure() {
        Throwable outage = new EmbeddingUnavailableException("nao respondeu", new RuntimeException("refused"));
        willThrow(outage).given(embeddingClient).classifyDocument(any(), any(), any());

        assertThatThrownBy(() -> service.analyze("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf"))
                .isInstanceOf(CategorySuggestionUnavailableException.class)
                .hasMessageContaining("manualmente")
                // The outage is kept as the cause, so the log still says what actually happened.
                .hasCause(outage);
    }

    @Test
    @DisplayName("a document the classifier actively refused is not reported as an outage")
    void rejectedDocumentIsNotTranslatedIntoAnOutage() {
        willThrow(new EmbeddingRejectedException(
                "sem texto", HttpStatus.UNPROCESSABLE_ENTITY, null, new RuntimeException()))
                .given(embeddingClient).classifyDocument(any(), any(), any());

        // Reporting this as 503 would tell the screen to retry something that will never work.
        assertThatThrownBy(() -> service.analyze("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf"))
                .isInstanceOf(EmbeddingRejectedException.class);
    }

    @Test
    @DisplayName("a suggestion outside the catalog is a downstream contract violation")
    void categoryOutsideTheCatalogIsRejected() {
        givenClassifierSuggests("Financeiro", "0.9");

        assertThatThrownBy(() -> service.analyze("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf"))
                .isInstanceOf(UnknownSuggestedCategoryException.class)
                .hasMessageContaining("Financeiro");
    }

    @ParameterizedTest(name = "category \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    @DisplayName("no category from akpedia-ml is no suggestion, not an error: title and description still come back")
    void missingCategoryIsNoSuggestion(String category) {
        given(embeddingClient.classifyDocument(any(), any(), any())).willReturn(analysis(category, null));

        DocumentAnalysisResponse result = service.analyze("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf");

        assertThat(result.title()).isEqualTo(TITLE);
        assertThat(result.description()).isEqualTo(DESCRIPTION);
        assertThat(result.suggestedCategoryId()).isNull();
        assertThat(result.confidence()).isNull();
        // Nothing to resolve, so the catalog is not even asked: a 502 here would hide the title.
        verifyNoInteractions(categories);
    }

    @Test
    @DisplayName("a missing confidence comes back as zero instead of null")
    void missingConfidenceBecomesZero() {
        given(embeddingClient.classifyDocument(any(), any(), any()))
                .willReturn(analysis("Tecnico", null));
        given(categories.findByName("Tecnico")).willReturn(Optional.of(tecnico));

        DocumentAnalysisResponse result = service.analyze("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf");

        assertThat(result.confidence()).isEqualByComparingTo("0");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"1.5, 1", "-0.2, 0", "0.4, 0.4"})
    @DisplayName("a confidence outside 0 to 1 is clamped, so the upload's CHECK cannot refuse it later")
    void confidenceIsClamped(String returned, String expected) {
        givenClassifierSuggests("Tecnico", returned);

        DocumentAnalysisResponse result = service.analyze("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf");

        assertThat(result.confidence()).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("nothing is written: the catalog is only read")
    void nothingIsPersisted() {
        givenClassifierSuggests("Tecnico", "0.9");

        service.analyze("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf");

        verify(categories, never()).save(any());
    }
}
