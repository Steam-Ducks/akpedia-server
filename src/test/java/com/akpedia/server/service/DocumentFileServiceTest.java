package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.DocumentArchivedException;
import com.akpedia.server.exception.DocumentFileNotFoundException;
import com.akpedia.server.exception.DocumentNotFoundException;
import com.akpedia.server.exception.DocumentNotProcessedException;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;

/**
 * Exercises {@link DocumentFileService} with both repositories mocked. What matters here is what
 * the browser ends up receiving -- a media type it can render, a name with an extension, and
 * validators that identify this version of the file -- which of the two 404s each missing row
 * produces, and that a document that must not be opened is refused before its blob is ever loaded.
 */
@ExtendWith(MockitoExtension.class)
class DocumentFileServiceTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 conteudo".getBytes();

    @Mock
    private DocumentRepository documents;
    @Mock
    private DocumentFileRepository documentFiles;

    private DocumentFileService service;

    @BeforeEach
    void setUp() {
        service = new DocumentFileService(documents, documentFiles);
    }

    private static Document document(String name, String mimeType) {
        return document(name, mimeType, DocumentStatus.APPROVED, ProcessingStatus.COMPLETED);
    }

    private static Document document(String name, String mimeType, DocumentStatus status, ProcessingStatus processingStatus) {
        Category category = new Category("Manuais", "manuais tecnicos");
        User creator = new User("Ana", "ana@akpedia.test", "hash", new Sector("TI", "setor de TI"));
        Document document = new Document(name, mimeType, (long) PDF_BYTES.length, category, creator, status);
        document.setProcessingStatus(processingStatus);
        ReflectionTestUtils.setField(document, "id", 7L);
        return document;
    }

    /** Stubs both rows, the way an uploaded document leaves the database. */
    private void storedDocument(Document document) {
        given(documents.findById(7L)).willReturn(Optional.of(document));
        given(documentFiles.findByDocumentId(7L)).willReturn(Optional.of(new DocumentFile(document, PDF_BYTES)));
    }

    /** Stubs only the metadata, for the cases that must never reach the blob. */
    private void metadataOnly(Document document) {
        given(documents.findById(7L)).willReturn(Optional.of(document));
    }

    @Test
    @DisplayName("describes the file with the recorded media type, name and size, and serves the stored bytes")
    void describesAndServesStoredFile() {
        storedDocument(document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE));

        DocumentFileDescriptor file = service.describe(7L);

        assertThat(file.documentId()).isEqualTo(7L);
        assertThat(file.filename()).isEqualTo("relatorio.pdf");
        assertThat(file.contentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(file.size()).isEqualTo(PDF_BYTES.length);
        assertThat(service.contentOf(file)).isEqualTo(PDF_BYTES);
    }

    @Test
    @DisplayName("a document named without an extension is still served as a .pdf")
    void addsPdfExtensionWhenTheNameHasNone() {
        metadataOnly(document("Manual de integracao", MediaType.APPLICATION_PDF_VALUE));

        assertThat(service.describe(7L).filename()).isEqualTo("Manual de integracao.pdf");
    }

    @Test
    @DisplayName("an extension already there is not duplicated, whatever its case")
    void keepsAnExistingPdfExtension() {
        metadataOnly(document("RELATORIO.PDF", MediaType.APPLICATION_PDF_VALUE));

        assertThat(service.describe(7L).filename()).isEqualTo("RELATORIO.PDF");
    }

    @Test
    @DisplayName("path separators in the name do not reach the header")
    void stripsPathSeparatorsFromTheName() {
        metadataOnly(document("../etc/passwd", MediaType.APPLICATION_PDF_VALUE));

        assertThat(service.describe(7L).filename()).isEqualTo(".._etc_passwd.pdf");
    }

    @Test
    @DisplayName("a document left without a name falls back to one derived from its id")
    void fallsBackToTheIdWhenTheNameIsBlank() {
        metadataOnly(document("   ", MediaType.APPLICATION_PDF_VALUE));

        assertThat(service.describe(7L).filename()).isEqualTo("documento-7.pdf");
    }

    @Test
    @DisplayName("an unparseable mime_type falls back to application/pdf instead of failing the request")
    void fallsBackToPdfWhenTheMediaTypeIsInvalid() {
        metadataOnly(document("relatorio.pdf", "nao e um media type"));

        assertThat(service.describe(7L).contentType()).isEqualTo(MediaType.APPLICATION_PDF);
    }

    @Test
    @DisplayName("the validators come from the last change, cut to the second an HTTP date carries")
    void validatorsAreBuiltFromTheLastChange() {
        Document document = document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE);
        OffsetDateTime changedAt = OffsetDateTime.of(2026, 9, 24, 22, 31, 5, 987_654_321, ZoneOffset.UTC);
        ReflectionTestUtils.setField(document, "updatedAt", changedAt);
        metadataOnly(document);

        DocumentFileDescriptor file = service.describe(7L);

        assertThat(file.lastModified()).isEqualTo(Instant.parse("2026-09-24T22:31:05Z"));
        assertThat(file.etag()).isEqualTo("\"7-%d-%d\"".formatted(PDF_BYTES.length, file.lastModified().toEpochMilli()));
    }

    @Test
    @DisplayName("a document that changed gets a different validator, so a stale copy is not confirmed")
    void changedDocumentGetsANewValidator() {
        Document before = document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE);
        ReflectionTestUtils.setField(before, "updatedAt", OffsetDateTime.of(2026, 9, 24, 22, 31, 0, 0, ZoneOffset.UTC));
        metadataOnly(before);
        String firstEtag = service.describe(7L).etag();

        Document after = document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE);
        after.setFileSize(PDF_BYTES.length + 10L);
        ReflectionTestUtils.setField(after, "updatedAt", OffsetDateTime.of(2026, 9, 25, 1, 10, 0, 0, ZoneOffset.UTC));
        given(documents.findById(7L)).willReturn(Optional.of(after));

        assertThat(service.describe(7L).etag()).isNotEqualTo(firstEtag);
    }

    @Test
    @DisplayName("an unknown document is a document_not_found, and the binary is never looked up")
    void unknownDocumentIsRejected() {
        given(documents.findById(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.describe(404L))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining("404");

        verifyNoInteractions(documentFiles);
    }

    @Test
    @DisplayName("a document whose binary is missing is a document_file_not_found, not a 500")
    void documentWithoutStoredFileIsRejected() {
        metadataOnly(document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE));
        given(documentFiles.findByDocumentId(7L)).willReturn(Optional.empty());

        DocumentFileDescriptor file = service.describe(7L);

        assertThatThrownBy(() -> service.contentOf(file)).isInstanceOf(DocumentFileNotFoundException.class);
    }

    @Test
    @DisplayName("an archived document is refused, and its blob is never loaded")
    void archivedDocumentIsRefused() {
        metadataOnly(document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE,
                DocumentStatus.ARCHIVED, ProcessingStatus.COMPLETED));

        assertThatThrownBy(() -> service.describe(7L)).isInstanceOf(DocumentArchivedException.class);

        verifyNoInteractions(documentFiles);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PENDING", "PROCESSING"})
    @DisplayName("a document whose indexing has not settled is refused, and its blob is never loaded")
    void documentStillBeingIndexedIsRefused(ProcessingStatus processingStatus) {
        metadataOnly(document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE, DocumentStatus.APPROVED, processingStatus));

        assertThatThrownBy(() -> service.describe(7L))
                .isInstanceOf(DocumentNotProcessedException.class)
                .hasMessageContaining(processingStatus.name());

        verifyNoInteractions(documentFiles);
    }

    @Test
    @DisplayName("a document whose indexing failed still opens: the PDF was stored before that step ran")
    void documentWithFailedIndexingIsServed() {
        storedDocument(document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE,
                DocumentStatus.APPROVED, ProcessingStatus.FAILED));

        assertThat(service.contentOf(service.describe(7L))).isEqualTo(PDF_BYTES);
    }

    @Test
    @DisplayName("a document still in DRAFT opens: only archiving and indexing restrict the file")
    void draftDocumentIsServed() {
        storedDocument(document("relatorio.pdf", MediaType.APPLICATION_PDF_VALUE,
                DocumentStatus.DRAFT, ProcessingStatus.COMPLETED));

        assertThat(service.contentOf(service.describe(7L))).isEqualTo(PDF_BYTES);
    }

}
