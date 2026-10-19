package com.akpedia.server.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What file formats this server recognises, and how it tells a PDF from anything else.
 *
 * <p>Shared because the upload and the analysis have to agree: a file the analysis accepted and
 * suggested a category for must not then be refused by the upload that follows it.
 */
final class DocumentFormats {

    /**
     * Formats the analysis accepts, as the message shows them to the user.
     *
     * <p>Narrower than what Gotenberg can convert, on purpose: this is the list the upload screen
     * offers, and widening it silently would send the classifier documents nobody designed it for.
     */
    static final List<String> ACCEPTED_EXTENSIONS = List.of("pdf", "doc", "docx", "xls", "xlsx");

    /**
     * Media types for those same formats.
     *
     * <p>Both the modern OOXML types and the legacy ones are listed because a browser picks the
     * type from the extension and the two generations of Office files do not share it. The
     * generic {@code application/octet-stream} is not here: a browser that cannot identify the
     * file sends it, and accepting it would turn the whitelist into a formality.
     */
    private static final Set<String> ACCEPTED_MEDIA_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private DocumentFormats() {
    }

    /**
     * Whether the analysis accepts this upload.
     *
     * <p>Extension and media type are both consulted, and either one being recognised is enough.
     * Neither is reliable alone: a file saved without an extension still carries a media type, and
     * a browser that does not know the format sends {@code application/octet-stream} for a file
     * whose name says {@code .docx}.
     *
     * @param filename    name the upload arrived with; may be {@code null}
     * @param contentType media type the caller declared; may be {@code null}
     * @return {@code true} when either the extension or the media type is on the list
     */
    static boolean isAcceptedForAnalysis(String filename, String contentType) {
        return hasAcceptedExtension(filename) || hasAcceptedMediaType(contentType);
    }

    private static boolean hasAcceptedExtension(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        return ACCEPTED_EXTENSIONS.stream().anyMatch(extension -> lower.endsWith("." + extension));
    }

    private static boolean hasAcceptedMediaType(String contentType) {
        if (contentType == null) {
            return false;
        }
        // Strips parameters such as "; charset=utf-8", which a media type may legally carry.
        String bare = contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        return ACCEPTED_MEDIA_TYPES.contains(bare);
    }

    /**
     * Whether the bytes are already a PDF, so the conversion can be skipped.
     *
     * <p>The magic bytes win over what the caller declared: a name and a media type are both
     * things the client chose, while {@code %PDF-} is in the file itself.
     *
     * @param filename    name the upload arrived with; may be {@code null}
     * @param contentType media type the caller declared; may be {@code null}
     * @param content     the uploaded bytes
     */
    static boolean isAlreadyPdf(String filename, String contentType, byte[] content) {
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
}
