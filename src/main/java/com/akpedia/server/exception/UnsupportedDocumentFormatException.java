package com.akpedia.server.exception;

import java.util.List;

/** Thrown when a document is in a format the analysis does not accept. */
public class UnsupportedDocumentFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> supportedExtensions;

    /**
     * @param filename            name the upload arrived with, so the message names the file
     * @param supportedExtensions formats the caller may send instead
     */
    public UnsupportedDocumentFormatException(String filename, List<String> supportedExtensions) {
        super("O formato de '%s' nao e aceito. Envie um arquivo %s.".formatted(
                (filename == null || filename.isBlank()) ? "arquivo sem nome" : filename,
                readableList(supportedExtensions)));
        this.supportedExtensions = List.copyOf(supportedExtensions);
    }

    public List<String> getSupportedExtensions() {
        return supportedExtensions;
    }

    /** Renders the list the way the sentence needs it: "PDF, DOC, DOCX, XLS ou XLSX". */
    private static String readableList(List<String> extensions) {
        List<String> upper = extensions.stream().map(String::toUpperCase).toList();
        if (upper.size() < 2) {
            return String.join("", upper);
        }
        return String.join(", ", upper.subList(0, upper.size() - 1)) + " ou " + upper.get(upper.size() - 1);
    }

}
