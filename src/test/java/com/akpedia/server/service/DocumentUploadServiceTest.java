package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.akpedia.server.client.GotenbergClient;
import com.akpedia.server.config.DefaultUserProvider;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.InvalidDocumentUploadException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * Exercises {@link DocumentUploadService} with every collaborator mocked: the repositories and
 * Gotenberg (contract covered by {@code GotenbergClientTest}).
 *
 * <p>The service has no embedding client or embedding repository to mock: an upload cannot index
 * anything, which is what keeps {@code embeddings} empty until the document is approved.
 */
@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

    @Mock
    private DocumentRepository documents;
    @Mock
    private DocumentFileRepository documentFiles;
    @Mock
    private CategoryRepository categories;
    @Mock
    private UserRepository users;
    @Mock
    private GotenbergClient gotenberg;
    @Mock
    private DefaultUserProvider defaultUser;

    private DocumentUploadService service;
    private Category category;
    private User creator;

    @BeforeEach
    void setUp() {
        service = new DocumentUploadService(documents, documentFiles, categories, users, gotenberg, defaultUser);
        category = new Category("Manuais", "manuais tecnicos");
        creator = new User("Ana", "ana@akpedia.test", "hash", new Sector("TI", "setor de TI"));
    }

    @Test
    @DisplayName("converts a non-PDF file through Gotenberg and stores it pending approval, not indexed")
    void uploadsConvertsAndStoresPendingApproval() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        byte[] original = "docx content".getBytes();
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        given(gotenberg.convertToPdf(original, "relatorio.docx", "application/msword")).willReturn(pdf);
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload(
                original, "relatorio.docx", "application/msword", 10L, 20L, null, null, null, null);

        assertThat(result.getName()).isEqualTo("relatorio.pdf");
        assertThat(result.getMimeType()).isEqualTo("application/pdf");
        assertThat(result.getFileSize()).isEqualTo((long) pdf.length);
        assertThat(result.getCategory()).isSameAs(category);
        assertThat(result.getCreator()).isSameAs(creator);
        assertThat(result.getStatus()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(result.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(result.getProcessingError()).isNull();
        assertThat(result.getProcessedAt()).isNull();
        assertThat(result.getSuggestedCategory()).isNull();
        assertThat(result.getCategoryConfidence()).isNull();

        verify(documents).save(result);
        ArgumentCaptor<DocumentFile> file = ArgumentCaptor.forClass(DocumentFile.class);
        verify(documentFiles).save(file.capture());
        assertThat(file.getValue().getDocument()).isSameAs(result);
        assertThat(file.getValue().getFileData()).isEqualTo(pdf);
    }

    @Test
    @DisplayName("a PDF upload is stored as is, without calling Gotenberg")
    void pdfUploadSkipsConversion() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        byte[] pdf = "%PDF-1.4 ja em pdf".getBytes();
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload(pdf, "manual.pdf", "application/pdf", 10L, 20L, null, null, null, null);

        assertThat(result.getFileSize()).isEqualTo((long) pdf.length);
        assertThat(result.getStatus()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(result.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
        verifyNoInteractions(gotenberg);
    }

    @Test
    @DisplayName("an explicit name and description override the derived ones")
    void explicitNameAndDescriptionAreKept() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        given(gotenberg.convertToPdf(any(), any(), any())).willReturn("%PDF-1.4".getBytes());
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload(
                "x".getBytes(), "a.txt", "text/plain", 10L, 20L, "Nome Customizado", "uma descricao", null, null);

        assertThat(result.getName()).isEqualTo("Nome Customizado");
        assertThat(result.getDescription()).isEqualTo("uma descricao");
    }

    @Test
    @DisplayName("the suggested category and its confidence are stored with the document")
    void suggestedCategoryAndConfidenceAreStored() {
        Category suggested = new Category("Tecnico", "Documentos tecnicos");
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(categories.findById(11L)).willReturn(Optional.of(suggested));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload(
                pdf, "manual.pdf", "application/pdf", 10L, 20L, null, null, 11L, new BigDecimal("0.87"));

        assertThat(result.getSuggestedCategory()).isSameAs(suggested);
        assertThat(result.getCategoryConfidence()).isEqualByComparingTo("0.87");
        // The suggestion is advisory: the document stays filed under the category it was sent with.
        assertThat(result.getCategory()).isSameAs(category);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1"})
    @DisplayName("the ends of the confidence range are accepted")
    void confidenceBoundsAreAccepted(String confidence) {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(categories.findById(11L)).willReturn(Optional.of(new Category("Tecnico", "Documentos tecnicos")));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf",
                10L, 20L, null, null, 11L, new BigDecimal(confidence));

        assertThat(result.getCategoryConfidence()).isEqualByComparingTo(confidence);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.001", "1.001"})
    @DisplayName("a confidence outside 0 to 1 is refused before any lookup")
    void confidenceOutOfRangeIsRefused(String confidence) {
        assertThatThrownBy(() -> service.upload("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf",
                10L, 20L, null, null, 11L, new BigDecimal(confidence)))
                .isInstanceOf(InvalidDocumentUploadException.class);

        verifyNoInteractions(categories, users, gotenberg, documents, documentFiles);
    }

    @Test
    @DisplayName("a confidence with no suggested category is refused before any lookup")
    void confidenceWithoutSuggestionIsRefused() {
        assertThatThrownBy(() -> service.upload("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf",
                10L, 20L, null, null, null, new BigDecimal("0.5")))
                .isInstanceOf(InvalidDocumentUploadException.class);

        verifyNoInteractions(categories, users, gotenberg, documents, documentFiles);
    }

    @Test
    @DisplayName("a suggested category outside the catalog is rejected and nothing is stored")
    void unknownSuggestedCategoryStopsEarly() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(categories.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload("%PDF-1.4".getBytes(), "manual.pdf", "application/pdf",
                10L, 20L, null, null, 999L, new BigDecimal("0.5")))
                .isInstanceOf(CategoryNotFoundException.class)
                .hasMessageContaining("999");

        verifyNoInteractions(gotenberg, documents, documentFiles);
    }

    @Test
    @DisplayName("an unknown category is rejected before touching Gotenberg")
    void unknownCategoryStopsEarly() {
        given(categories.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload("x".getBytes(), "a.txt", "text/plain", 10L, 20L, null, null, null, null))
                .isInstanceOf(CategoryNotFoundException.class);

        verifyNoInteractions(gotenberg);
        verify(users, never()).findById(any());
    }

    @Test
    @DisplayName("an unknown creator is rejected before touching Gotenberg")
    void unknownCreatorStopsEarly() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload("x".getBytes(), "a.txt", "text/plain", 10L, 20L, null, null, null, null))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(gotenberg);
    }

    @Test
    @DisplayName("an empty file is refused before any lookup")
    void emptyFileIsRefused() {
        assertThatThrownBy(() -> service.upload(new byte[0], "a.txt", "text/plain", 10L, 20L, null, null, null, null))
                .isInstanceOf(InvalidDocumentUploadException.class);

        verifyNoInteractions(categories, users, gotenberg);
    }

    @Test
    @DisplayName("an upload with no creator is attributed to the configured default user")
    void noCreatorFallsBackToTheDefaultUser() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(defaultUser.userId()).willReturn(99L);
        given(users.findById(99L)).willReturn(Optional.of(creator));
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload(pdf, "manual.pdf", "application/pdf", 10L, null, null, null, null, null);

        assertThat(result.getCreator()).isSameAs(creator);
        verify(users).findById(99L);
    }

    @Test
    @DisplayName("an explicit creator is used as given, without consulting the default user")
    void explicitCreatorIgnoresTheDefaultUser() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        service.upload(pdf, "manual.pdf", "application/pdf", 10L, 20L, null, null, null, null);

        // The fallback must not leak into uploads that name their author.
        verifyNoInteractions(defaultUser);
    }

}
