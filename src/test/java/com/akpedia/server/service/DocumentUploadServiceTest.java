package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.client.GotenbergClient;
import com.akpedia.server.dto.DocumentChunk;
import com.akpedia.server.dto.DocumentEmbeddingResponse;
import com.akpedia.server.dto.EmbeddingModelInfo;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.EmbeddingUnavailableException;
import com.akpedia.server.exception.InvalidDocumentUploadException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.EmbeddingRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * Exercises {@link DocumentUploadService} with every collaborator mocked: the repositories,
 * Gotenberg (contract covered by {@code GotenbergClientTest}) and the embedding client
 * (contract covered by {@code EmbeddingClientTest}).
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
    private EmbeddingClient embeddingClient;
    @Mock
    private EmbeddingRepository embeddingRepository;

    private DocumentUploadService service;
    private Category category;
    private User creator;

    @BeforeEach
    void setUp() {
        service = new DocumentUploadService(
                documents, documentFiles, categories, users, gotenberg, embeddingClient, embeddingRepository);
        category = new Category("Manuais", "manuais tecnicos");
        creator = new User("Ana", "ana@akpedia.test", "hash", new Sector("TI", "setor de TI"));
    }

    private static DocumentEmbeddingResponse embeddingResponse() {
        EmbeddingModelInfo model = new EmbeddingModelInfo("intfloat/multilingual-e5-small", 3, true);
        return new DocumentEmbeddingResponse("doc.pdf", model, 2, List.of(
                new DocumentChunk(0, "primeiro trecho", List.of(0.1f, 0.2f, 0.3f)),
                new DocumentChunk(1, "segundo trecho", List.of(0.4f, 0.5f, 0.6f))));
    }

    @Test
    @DisplayName("converts a non-PDF file through Gotenberg, stores it and embeds it through akpedia-ml")
    void uploadsConvertsAndIndexes() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        byte[] original = "docx content".getBytes();
        byte[] pdf = "%PDF-1.4 conteudo".getBytes();
        given(gotenberg.convertToPdf(original, "relatorio.docx", "application/msword")).willReturn(pdf);
        given(embeddingClient.embedDocument(pdf, "relatorio.pdf", "application/pdf")).willReturn(embeddingResponse());
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload(original, "relatorio.docx", "application/msword", 10L, 20L, null, null);

        assertThat(result.getName()).isEqualTo("relatorio.pdf");
        assertThat(result.getMimeType()).isEqualTo("application/pdf");
        assertThat(result.getFileSize()).isEqualTo((long) pdf.length);
        assertThat(result.getCategory()).isSameAs(category);
        assertThat(result.getCreator()).isSameAs(creator);
        assertThat(result.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(result.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(result.getProcessingError()).isNull();
        assertThat(result.getProcessedAt()).isNotNull();
        verify(documentFiles).save(any(DocumentFile.class));

        ArgumentCaptor<Embedding> captor = ArgumentCaptor.forClass(Embedding.class);
        verify(embeddingRepository, times(2)).save(captor.capture());
        List<Embedding> saved = captor.getAllValues();
        assertThat(saved).extracting(Embedding::getChunkIndex).containsExactly(0, 1);
        assertThat(saved).extracting(Embedding::getContent).containsExactly("primeiro trecho", "segundo trecho");
        assertThat(saved.get(0).getVector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(saved.get(0).getModelName()).isEqualTo("intfloat/multilingual-e5-small");
        assertThat(saved.get(0).getDimensions()).isEqualTo(3);
        assertThat(saved.get(0).getDocument()).isSameAs(result);
    }

    @Test
    @DisplayName("a PDF upload is stored as is, without calling Gotenberg, and still gets indexed")
    void pdfUploadSkipsConversionButStillIndexes() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        byte[] pdf = "%PDF-1.4 ja em pdf".getBytes();
        given(embeddingClient.embedDocument(pdf, "manual.pdf", "application/pdf")).willReturn(embeddingResponse());
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload(pdf, "manual.pdf", "application/pdf", 10L, 20L, null, null);

        assertThat(result.getFileSize()).isEqualTo((long) pdf.length);
        assertThat(result.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        verifyNoInteractions(gotenberg);
    }

    @Test
    @DisplayName("an explicit name and description override the derived ones")
    void explicitNameAndDescriptionAreKept() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        given(gotenberg.convertToPdf(any(), any(), any())).willReturn("%PDF-1.4".getBytes());
        given(embeddingClient.embedDocument(any(), any(), any())).willReturn(embeddingResponse());
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload(
                "x".getBytes(), "a.txt", "text/plain", 10L, 20L, "Nome Customizado", "uma descricao");

        assertThat(result.getName()).isEqualTo("Nome Customizado");
        assertThat(result.getDescription()).isEqualTo("uma descricao");
    }

    @Test
    @DisplayName("a failure to reach akpedia-ml does not fail the upload, only marks it FAILED")
    void embeddingFailureDegradesInsteadOfFailingUpload() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.of(creator));
        given(gotenberg.convertToPdf(any(), any(), any())).willReturn("%PDF-1.4".getBytes());
        willThrow(new EmbeddingUnavailableException("akpedia-ml esta fora do ar", null))
                .given(embeddingClient).embedDocument(any(), any(), any());
        given(documents.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        Document result = service.upload("x".getBytes(), "a.txt", "text/plain", 10L, 20L, null, null);

        assertThat(result.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(result.getProcessingError()).isEqualTo("akpedia-ml esta fora do ar");
        assertThat(result.getProcessedAt()).isNotNull();
        verifyNoInteractions(embeddingRepository);
    }

    @Test
    @DisplayName("an unknown category is rejected before touching Gotenberg or akpedia-ml")
    void unknownCategoryStopsEarly() {
        given(categories.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload("x".getBytes(), "a.txt", "text/plain", 10L, 20L, null, null))
                .isInstanceOf(CategoryNotFoundException.class);

        verifyNoInteractions(gotenberg, embeddingClient, embeddingRepository);
        verify(users, never()).findById(any());
    }

    @Test
    @DisplayName("an unknown creator is rejected before touching Gotenberg or akpedia-ml")
    void unknownCreatorStopsEarly() {
        given(categories.findById(10L)).willReturn(Optional.of(category));
        given(users.findById(20L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload("x".getBytes(), "a.txt", "text/plain", 10L, 20L, null, null))
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(gotenberg, embeddingClient, embeddingRepository);
    }

    @Test
    @DisplayName("an empty file is refused before any lookup")
    void emptyFileIsRefused() {
        assertThatThrownBy(() -> service.upload(new byte[0], "a.txt", "text/plain", 10L, 20L, null, null))
                .isInstanceOf(InvalidDocumentUploadException.class);

        verifyNoInteractions(categories, users, gotenberg, embeddingClient, embeddingRepository);
    }

}
