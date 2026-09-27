package com.akpedia.server.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.DocumentArchivedException;
import com.akpedia.server.exception.DocumentFileNotFoundException;
import com.akpedia.server.exception.DocumentNotFoundException;
import com.akpedia.server.exception.DocumentNotProcessedException;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;

/**
 * Reads a stored document back out, ready to be opened in a browser.
 *
 * <p>Deliberately two steps. {@link #describe} answers from the metadata alone -- name, media type,
 * validators, and whether the document may be opened at all -- and {@link #contentOf} is what
 * actually pulls the blob out of the database. A refused document and a browser that already holds
 * the file both stop at the first step, so neither pays for reading bytes nobody will receive.
 *
 * <p>Two states are not openable: an archived document, which is out of circulation, and one whose
 * indexing is still in flight, which is not ready to be handed out yet. A document whose indexing
 * <em>failed</em> does open -- see {@link #checkIsOpenable}.
 */
@Service
public class DocumentFileService {

    /** Used when {@code documents.mime_type} holds something that is not a valid media type. */
    private static final MediaType FALLBACK_CONTENT_TYPE = MediaType.APPLICATION_PDF;

    /** Indexing states that have not settled yet, and may still become {@code COMPLETED} on their own. */
    private static final Set<ProcessingStatus> INDEXING_IN_FLIGHT =
            EnumSet.of(ProcessingStatus.PENDING, ProcessingStatus.PROCESSING);

    /** Path separators and control characters, which have no business in a header value. */
    private static final String UNSAFE_IN_FILENAME = "[\\\\/\\p{Cntrl}]";

    private final DocumentRepository documents;
    private final DocumentFileRepository documentFiles;

    public DocumentFileService(DocumentRepository documents, DocumentFileRepository documentFiles) {
        this.documents = documents;
        this.documentFiles = documentFiles;
    }

    /**
     * Describes the file of a document, refusing the states that must not be served.
     *
     * @param documentId document whose file is being opened
     * @return the name, media type and validators the response should carry
     * @throws DocumentNotFoundException     if no document exists with {@code documentId}
     * @throws DocumentArchivedException     if the document is archived
     * @throws DocumentNotProcessedException if akpedia-ml is still to index the document
     */
    @Transactional(readOnly = true)
    public DocumentFileDescriptor describe(Long documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        checkIsOpenable(document);

        MediaType contentType = contentTypeOf(document);
        Instant lastModified = lastModifiedOf(document);
        return new DocumentFileDescriptor(
                document.getId(),
                filenameOf(document, contentType),
                contentType,
                document.getFileSize(),
                lastModified,
                etagOf(document, lastModified));
    }

    /**
     * Loads the stored bytes of an already described file.
     *
     * @param descriptor what {@link #describe} returned for the document
     * @return the raw bytes of the stored file
     * @throws DocumentFileNotFoundException if the document exists but its binary does not
     */
    @Transactional(readOnly = true)
    public byte[] contentOf(DocumentFileDescriptor descriptor) {
        return documentFiles.findByDocumentId(descriptor.documentId())
                .orElseThrow(() -> new DocumentFileNotFoundException(descriptor.documentId()))
                .getFileData();
    }

    /**
     * Refuses the two document states that must not be served.
     *
     * <p>{@code ARCHIVED} is refused for good, so it answers 403 through the handler, while a
     * document whose indexing has not settled answers 409: that one becomes openable on its own
     * once the indexing lands, and the caller can retry the same request.
     *
     * <p>{@code FAILED} indexing, on the other hand, does <em>not</em> close the document. Its PDF
     * was converted and stored before the embedding step ever ran -- that is the whole point of
     * the upload tolerating an indexing failure -- so the bytes are intact and only search is
     * missing them. With no reindexing route to recover from a 409, refusing it would lock an
     * intact document away for good over a step that never touched it.
     *
     * <p>Runs on every request, revalidation included, which is what lets the response be cached
     * at all: a browser holding a document that has since been archived asks again and is refused,
     * instead of going on showing its own copy.
     */
    private static void checkIsOpenable(Document document) {
        if (document.getStatus() == DocumentStatus.ARCHIVED) {
            throw new DocumentArchivedException(document.getId());
        }
        if (INDEXING_IN_FLIGHT.contains(document.getProcessingStatus())) {
            throw new DocumentNotProcessedException(document.getId(), document.getProcessingStatus());
        }
    }

    /**
     * The media type recorded at upload, or {@code application/pdf} when that column cannot be
     * parsed -- an unparseable header would fail the request over metadata, when the bytes
     * themselves are fine and are a PDF anyway.
     */
    private static MediaType contentTypeOf(Document document) {
        String mimeType = document.getMimeType();
        if (mimeType == null || mimeType.isBlank()) {
            return FALLBACK_CONTENT_TYPE;
        }
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (InvalidMediaTypeException e) {
            return FALLBACK_CONTENT_TYPE;
        }
    }

    /**
     * The document name, carrying the extension of its media type.
     *
     * <p>{@code name} is free text at upload, so it may arrive without an extension (or with
     * none at all when it was blank). Every stored file is a PDF, so the name the browser
     * receives ends in {@code .pdf}: without it, a download lands on disk as a file the system
     * no longer knows how to open.
     */
    private static String filenameOf(Document document, MediaType contentType) {
        String name = document.getName() == null ? "" : document.getName().replaceAll(UNSAFE_IN_FILENAME, "_").strip();
        if (name.isBlank()) {
            name = "documento-" + document.getId();
        }
        boolean isPdf = MediaType.APPLICATION_PDF.equalsTypeAndSubtype(contentType);
        if (isPdf && !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name + ".pdf";
        }
        return name;
    }

    /**
     * When the document last changed, to the second.
     *
     * <p>Truncated because an HTTP date carries no fraction: keeping milliseconds here would make
     * the {@code ETag} disagree with {@code Last-Modified} about the same version of the file.
     */
    private static Instant lastModifiedOf(Document document) {
        OffsetDateTime changedAt = document.getUpdatedAt() != null ? document.getUpdatedAt() : document.getCreatedAt();
        Instant instant = changedAt == null ? Instant.EPOCH : changedAt.toInstant();
        return instant.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * A strong validator for this version of the file.
     *
     * <p>Built from identity, size and last change instead of from a hash of the bytes: hashing
     * would mean reading the whole blob to answer a revalidation, which is the one thing a
     * conditional request is there to avoid.
     */
    private static String etagOf(Document document, Instant lastModified) {
        return "\"%d-%d-%d\"".formatted(document.getId(), document.getFileSize(), lastModified.toEpochMilli());
    }

}
