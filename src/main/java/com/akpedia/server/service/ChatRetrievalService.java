package com.akpedia.server.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.config.ChatProperties;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.repository.EmbeddingRepository;

/**
 * Finds the passages the chat answers a question from.
 *
 * <p>Only documents the user is allowed to see take part: approved, indexed, and in a category
 * their sector sees, read from {@link CategoryAccessService} on every question, as the search does.
 * The language model only ever reads what comes out of here, so this is where the chat's
 * permissions are enforced -- akpedia-ml checks none.
 *
 * <p>The passages closest to the question come back, several per document if that is where they
 * are, up to {@code akpedia.chat.max-passages}, and none below {@code akpedia.chat.minimum-score}.
 */
@Service
public class ChatRetrievalService {

    /** Column positions of {@link EmbeddingRepository#findTopChunks}, in the order it selects them. */
    private static final int CONTENT = 0;
    private static final int DOCUMENT_ID = 1;
    private static final int NAME = 2;
    private static final int CATEGORY = 3;
    private static final int SCORE = 4;
    private static final int CHUNK_INDEX = 5;

    private final EmbeddingClient embeddingClient;
    private final EmbeddingRepository embeddings;
    private final CategoryAccessService categoryAccess;
    private final ChatProperties properties;

    public ChatRetrievalService(
            EmbeddingClient embeddingClient,
            EmbeddingRepository embeddings,
            CategoryAccessService categoryAccess,
            ChatProperties properties) {
        this.embeddingClient = embeddingClient;
        this.embeddings = embeddings;
        this.categoryAccess = categoryAccess;
        this.properties = properties;
    }

    /**
     * The passages closest to the question, closest first.
     *
     * <p>Empty when nothing is close enough, and also when the user's sector sees no category -- in
     * that case without the question ever reaching the embedding service.
     *
     * @param question what the user asked, not blank
     * @param userId   who is asking
     * @throws com.akpedia.server.exception.EmbeddingException if the question could not be turned
     *         into a vector, or the vector cannot be compared with the indexed ones
     */
    public List<RetrievedPassage> retrieve(String question, Long userId) {
        Set<Long> allowed = categoryAccess.allowedCategoryIds(userId);
        if (allowed.isEmpty()) {
            return List.of();
        }

        QueryEmbeddingResponse embedding = embeddingClient.embedQuery(question.strip());
        QueryVectors.validate(embedding);

        return embeddings.findTopChunks(
                        QueryVectors.literal(embedding.embedding()),
                        embedding.model().name(),
                        embedding.model().dimensions(),
                        properties.minimumScore(),
                        allowed,
                        properties.maxPassages())
                .stream()
                .map(ChatRetrievalService::toPassage)
                .toList();
    }

    /** One row of the query, read by position -- named above, so a shifted column cannot go unnoticed. */
    private static RetrievedPassage toPassage(Object[] row) {
        return new RetrievedPassage(
                ((Number) row[DOCUMENT_ID]).longValue(),
                (String) row[NAME],
                (String) row[CATEGORY],
                (String) row[CONTENT],
                ((Number) row[SCORE]).doubleValue(),
                ((Number) row[CHUNK_INDEX]).intValue());
    }
}
