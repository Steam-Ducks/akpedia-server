package com.akpedia.server.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.client.GotenbergClient;
import com.akpedia.server.dto.DocumentChunk;
import com.akpedia.server.dto.DocumentEmbeddingResponse;
import com.akpedia.server.dto.EmbeddingModelInfo;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.EmbeddingException;
import com.akpedia.server.exception.InvalidDocumentUploadException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.EmbeddingRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * Stores an uploaded document: whatever format arrives, what ends up in {@code document_files}
 * is always a PDF, converted through {@link GotenbergClient} before anything is persisted.
 * Once saved, the PDF is handed to {@link EmbeddingClient} (akpedia-ml) and the resulting
 * chunks land in {@code embeddings}.
 *
 * <p>A file that is already a PDF is saved as is -- asking Gotenberg to convert a PDF to
 * itself would only add a network round trip for no gain.
 *
 * <p>A failure to embed does not fail the upload: the document and its PDF are already
 * safely stored by that point, so the document is kept with {@code processing_status}
 * {@code FAILED} and the reason in {@code processing_error} instead of losing the upload
 * over an indexing problem that a retry could fix later.
 */
@Service
public class DocumentUploadService {

    private final DocumentRepository documents;
    private final DocumentFileRepository documentFiles;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final GotenbergClient gotenberg;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingRepository embeddingRepository;

    public DocumentUploadService(
            DocumentRepository documents,
            DocumentFileRepository documentFiles,
            CategoryRepository categories,
            UserRepository users,
            GotenbergClient gotenberg,
            EmbeddingClient embeddingClient,
            EmbeddingRepository embeddingRepository) {
        this.documents = documents;
        this.documentFiles = documentFiles;
        this.categories = categories;
        this.users = users;
        this.gotenberg = gotenberg;
        this.embeddingClient = embeddingClient;
        this.embeddingRepository = embeddingRepository;
    }

    /**
     * Converts the file to PDF (if it is not one already) and persists it.
     *
     * @param content     raw bytes of the uploaded file
     * @param filename    original file name, used to derive the stored name and pick the LibreOffice filter
     * @param contentType media type the caller sent for the upload
     * @param categoryId  category the document is filed under
     * @param creatorId   user the document is attributed to
     * @param name        display name; falls back to {@code filename} with a {@code .pdf} extension when blank
     * @param description optional description
     * @return the persisted document, with its category and creator already loaded
     * @throws InvalidDocumentUploadException     if the file is empty
     * @throws CategoryNotFoundException          if no category exists with {@code categoryId}
     * @throws UserNotFoundException              if no user exists with {@code creatorId}
     * @throws com.akpedia.server.exception.PdfConversionException if the conversion to PDF fails
     */
    @Transactional
    public Document upload(byte[] content, String filename, String contentType,
            Long categoryId, Long creatorId, String name, String description) {
        if (content == null || content.length == 0) {
            throw new InvalidDocumentUploadException("O arquivo enviado esta vazio.");
        }

        Category category = categories.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
        User creator = users.findById(creatorId)
                .orElseThrow(() -> new UserNotFoundException(creatorId));

        byte[] pdf = isAlreadyPdf(filename, contentType, content)
                ? content
                : gotenberg.convertToPdf(content, filename, contentType);

        String documentName = (name != null && !name.isBlank()) ? name : withPdfExtension(filename);

        Document document = new Document(
                documentName, MediaType.APPLICATION_PDF_VALUE, (long) pdf.length, category, creator, DocumentStatus.DRAFT);
        if (description != null && !description.isBlank()) {
            document.setDescription(description);
        }

        documents.save(document);
        documentFiles.save(new DocumentFile(document, pdf));

        index(document, pdf);

        return document;
    }

    /**
     * Embeds the stored PDF through akpedia-ml and saves one {@link Embedding} row per chunk.
     *
     * <p>Runs in the same transaction as the document and file save: the document row is
     * already visible to this call, and a chunk-persistence problem (which should not
     * happen once the response matches {@link Embedding#VECTOR_DIMENSIONS}) is left to roll
     * the whole upload back rather than silently keeping a half-indexed document. A failure
     * to reach or from akpedia-ml itself, on the other hand, is expected to happen
     * occasionally and is caught here so it degrades the document instead of losing the
     * upload.
     */
    private void index(Document document, byte[] pdf) {
        try {
            DocumentEmbeddingResponse response =
                    embeddingClient.embedDocument(pdf, document.getName(), MediaType.APPLICATION_PDF_VALUE);
            EmbeddingModelInfo model = response.model();
            for (DocumentChunk chunk : response.chunks()) {
                embeddingRepository.save(new Embedding(
                        document, chunk.index(), chunk.text(), toVector(chunk.embedding()), model.name(), model.dimensions()));
            }
            document.setProcessingStatus(ProcessingStatus.COMPLETED);
            document.setProcessingError(null);
        } catch (EmbeddingException e) {
            document.setProcessingStatus(ProcessingStatus.FAILED);
            document.setProcessingError(e.getMessage());
        }
        document.setProcessedAt(OffsetDateTime.now());
    }

    private static float[] toVector(List<Float> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }

    private boolean isAlreadyPdf(String filename, String contentType, byte[] content) {
        boolean magicBytes = content.length >= 5
                && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F' && content[4] == '-';
        if (magicBytes) {
            return true;
        }
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("application/pdf")) {
            return true;
        }
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private String withPdfExtension(String filename) {
        String base = (filename == null || filename.isBlank()) ? "documento" : filename;
        int lastDot = base.lastIndexOf('.');
        String withoutExtension = lastDot > 0 ? base.substring(0, lastDot) : base;
        return withoutExtension + ".pdf";
    }

}
