package com.akpedia.server.service;

/**
 * One passage the chat may answer from: a chunk of a document the user is allowed to see.
 *
 * @param documentId the document it belongs to
 * @param name       the document's name, to cite it
 * @param category   the name of the document's category
 * @param content    the chunk's text, whole
 * @param score      cosine similarity between the question and the chunk, from -1 to 1
 * @param chunkIndex the chunk's position in the document, counted from 0
 */
public record RetrievedPassage(
        Long documentId,
        String name,
        String category,
        String content,
        double score,
        int chunkIndex) {
}
