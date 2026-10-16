package com.akpedia.server.service;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.client.GotenbergClient;
import com.akpedia.server.dto.DocumentAnalysisResponse;
import com.akpedia.server.dto.DocumentClassification;
import com.akpedia.server.entity.Category;
import com.akpedia.server.exception.CategorySuggestionUnavailableException;
import com.akpedia.server.exception.EmbeddingUnavailableException;
import com.akpedia.server.exception.InvalidDocumentUploadException;
import com.akpedia.server.exception.UnknownSuggestedCategoryException;
import com.akpedia.server.exception.UnsupportedDocumentFormatException;
import com.akpedia.server.repository.CategoryRepository;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * Suggests a category for a document the user is about to upload, without storing anything.
 *
 * <p>By the architecture the upload screen never talks to akpedia-ml: it posts the file here, and
 * this server decides whether the format is acceptable, converts it, asks the classifier, and
 * answers with a category the screen can hand straight back to the upload. Nothing is persisted,
 * so an analysis the user abandons leaves no trace.
 */
@Service
public class DocumentAnalysisService {

    private final CategoryRepository categories;
    private final GotenbergClient gotenberg;
    private final EmbeddingClient embeddingClient;

    public DocumentAnalysisService(
            CategoryRepository categories,
            GotenbergClient gotenberg,
            EmbeddingClient embeddingClient) {
        this.categories = categories;
        this.gotenberg = gotenberg;
        this.embeddingClient = embeddingClient;
    }

    /**
     * Converts the document if needed and returns the category the classifier suggests.
     *
     * @param content     raw bytes of the uploaded file
     * @param filename    original file name, used to validate the format and to pick the converter
     * @param contentType media type the caller declared for the upload
     * @return the suggested category id and the classifier's confidence
     * @throws InvalidDocumentUploadException          if the file is empty
     * @throws UnsupportedDocumentFormatException      if the format is not on the accepted list
     * @throws CategorySuggestionUnavailableException  if akpedia-ml never answered
     * @throws UnknownSuggestedCategoryException       if the suggested name is not in the catalog
     * @throws com.akpedia.server.exception.PdfConversionException if the conversion to PDF fails
     */
    public DocumentAnalysisResponse analyze(byte[] content, String filename, String contentType) {
        if (content == null || content.length == 0) {
            throw new InvalidDocumentUploadException("The uploaded file is empty.");
        }
        if (!DocumentFormats.isAcceptedForAnalysis(filename, contentType)) {
            throw new UnsupportedDocumentFormatException(filename, DocumentFormats.ACCEPTED_EXTENSIONS);
        }

        // The classifier reads text out of a PDF, same as the indexing does, so the conversion has
        // to happen before the call and not inside akpedia-ml.
        boolean alreadyPdf = DocumentFormats.isAlreadyPdf(filename, contentType, content);
        byte[] pdf = alreadyPdf ? content : gotenberg.convertToPdf(content, filename, contentType);

        DocumentClassification classification = classify(pdf, filename);
        Category suggested = categories.findByName(classification.category())
                .orElseThrow(() -> new UnknownSuggestedCategoryException(classification.category()));

        return new DocumentAnalysisResponse(suggested.getId(), confidenceOf(classification));
    }

    /**
     * Calls the classifier, turning an outage into the one failure the screen can recover from.
     *
     * <p>Only the "never answered" case is translated: a document the service actively refused is
     * a different problem, and letting {@link com.akpedia.server.exception.EmbeddingRejectedException}
     * through keeps the reason it gave instead of blaming the outage.
     */
    private DocumentClassification classify(byte[] pdf, String filename) {
        try {
            return embeddingClient.classifyDocument(pdf, filename, MediaType.APPLICATION_PDF_VALUE);
        } catch (EmbeddingUnavailableException e) {
            throw new CategorySuggestionUnavailableException(e);
        }
    }

    /**
     * Clamps the confidence into the 0 to 1 the column accepts, and defaults a missing one to 0.
     *
     * <p>The value is echoed straight back to the screen, which posts it to the upload, where
     * {@code category_confidence} has a CHECK between 0 and 1. Passing a stray value through would
     * turn a classifier quirk into a failed upload one step later.
     */
    private BigDecimal confidenceOf(DocumentClassification classification) {
        BigDecimal confidence = classification.confidence();
        if (confidence == null) {
            return BigDecimal.ZERO;
        }
        return confidence.min(BigDecimal.ONE).max(BigDecimal.ZERO);
    }
}
