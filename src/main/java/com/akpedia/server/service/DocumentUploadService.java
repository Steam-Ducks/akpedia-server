package com.akpedia.server.service;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.client.GotenbergClient;
import com.akpedia.server.config.DefaultUserProvider;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.InvalidDocumentUploadException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * Stores an uploaded document: whatever format arrives, what ends up in {@code document_files}
 * is always a PDF, converted through {@link GotenbergClient} before anything is persisted.
 *
 * <p>A file that is already a PDF is saved as is -- asking Gotenberg to convert a PDF to
 * itself would only add a network round trip for no gain.
 *
 * <p>The upload does not index anything. The document is stored as {@code PENDING_APPROVAL}
 * with {@code processing_status} {@code PENDING} and waits for the approver; embedding it for
 * search only happens once it is approved, so no work is spent on a document that may still be
 * rejected.
 */
@Service
public class DocumentUploadService {

    private final DocumentRepository documents;
    private final DocumentFileRepository documentFiles;
    private final CategoryRepository categories;
    private final UserRepository users;
    private final GotenbergClient gotenberg;
    private final DefaultUserProvider defaultUser;

    public DocumentUploadService(
            DocumentRepository documents,
            DocumentFileRepository documentFiles,
            CategoryRepository categories,
            UserRepository users,
            GotenbergClient gotenberg,
            DefaultUserProvider defaultUser) {
        this.documents = documents;
        this.documentFiles = documentFiles;
        this.categories = categories;
        this.users = users;
        this.gotenberg = gotenberg;
        this.defaultUser = defaultUser;
    }

    /**
     * Converts the file to PDF (if it is not one already) and persists it, pending approval.
     *
     * @param content     raw bytes of the uploaded file
     * @param filename    original file name, used to derive the stored name and pick the LibreOffice filter
     * @param contentType media type the caller sent for the upload
     * @param categoryId  category the document is filed under
     * @param creatorId   user the document is attributed to; when {@code null}, the document falls
     *                    back to the configured default user, which is the only author this sprint
     *                    has while there is no registration or login
     * @param name        display name; falls back to {@code filename} with a {@code .pdf} extension when blank
     * @param description optional description
     * @param suggestedCategoryId category the classifier suggested for the document, if any; like
     *                            {@code categoryId}, it has to be one of the catalog's categories
     * @param categoryConfidence  how confident the classifier is in that suggestion, from 0 to 1;
     *                            only accepted together with {@code suggestedCategoryId}
     * @return the persisted document, with its category and creator already loaded
     * @throws InvalidDocumentUploadException     if the file is empty, or the confidence is out of
     *                                            range or comes without a suggested category
     * @throws CategoryNotFoundException          if no category exists with {@code categoryId} or
     *                                            {@code suggestedCategoryId}
     * @throws UserNotFoundException              if no user exists with the resolved creator id
     * @throws com.akpedia.server.exception.PdfConversionException if the conversion to PDF fails
     */
    @Transactional
    public Document upload(byte[] content, String filename, String contentType,
            Long categoryId, Long creatorId, String name, String description,
            Long suggestedCategoryId, BigDecimal categoryConfidence) {
        if (content == null || content.length == 0) {
            throw new InvalidDocumentUploadException("The uploaded file is empty.");
        }
        checkConfidence(suggestedCategoryId, categoryConfidence);

        Category category = categories.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
        Category suggestedCategory = suggestedCategoryId == null
                ? null
                : categories.findById(suggestedCategoryId)
                        .orElseThrow(() -> new CategoryNotFoundException(suggestedCategoryId));
        // No registration or login yet, so an upload that names no creator is attributed to the
        // seeded default user instead of being refused. The id was resolved at startup, so this
        // cannot be the reason the lookup below fails.
        Long authorId = creatorId != null ? creatorId : defaultUser.userId();
        User creator = users.findById(authorId)
                .orElseThrow(() -> new UserNotFoundException(authorId));

        byte[] pdf = isAlreadyPdf(filename, contentType, content)
                ? content
                : gotenberg.convertToPdf(content, filename, contentType);

        String documentName = (name != null && !name.isBlank()) ? name : withPdfExtension(filename);

        Document document = new Document(
                documentName, MediaType.APPLICATION_PDF_VALUE, (long) pdf.length, category, creator,
                DocumentStatus.PENDING_APPROVAL);
        if (description != null && !description.isBlank()) {
            document.setDescription(description);
        }
        document.setSuggestedCategory(suggestedCategory);
        document.setCategoryConfidence(categoryConfidence);

        documents.save(document);
        documentFiles.save(new DocumentFile(document, pdf));

        return document;
    }

    /**
     * Refuses a confidence the {@code ck_documents_category_confidence} constraint would reject,
     * so it answers as a bad request instead of surfacing as a database error.
     */
    private static void checkConfidence(Long suggestedCategoryId, BigDecimal categoryConfidence) {
        if (categoryConfidence == null) {
            return;
        }
        if (suggestedCategoryId == null) {
            throw new InvalidDocumentUploadException("categoryConfidence requires suggestedCategoryId.");
        }
        if (categoryConfidence.signum() < 0 || categoryConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidDocumentUploadException("categoryConfidence must be between 0 and 1.");
        }
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
