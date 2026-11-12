package com.akpedia.server.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.akpedia.server.client.ChatClient;
import com.akpedia.server.dto.ChatAnswer;
import com.akpedia.server.dto.ChatResponse;
import com.akpedia.server.exception.ChatUnavailableException;
import com.akpedia.server.exception.EmbeddingException;

/**
 * Answers a question from the documents the user may see.
 *
 * <p>The passages come from {@link ChatRetrievalService}, which applies the permissions; akpedia-ml
 * writes the answer from them. With no passage close enough, the answer is "not found" without
 * asking the language model at all: it would have nothing to answer from, and it is the slow part.
 *
 * <p>Sources are named from the passages sent, never from what akpedia-ml says alone: an id it
 * cites that was not among them is dropped, so the answer can never point at a document the user
 * was not allowed to see.
 */
@Service
public class ChatService {

    /** Same sentence akpedia-ml answers when its passages do not answer the question. */
    public static final String NOT_FOUND_ANSWER = "Não encontrei essa informação nos documentos disponíveis.";

    private final ChatRetrievalService retrieval;
    private final ChatClient chatClient;

    public ChatService(ChatRetrievalService retrieval, ChatClient chatClient) {
        this.retrieval = retrieval;
        this.chatClient = chatClient;
    }

    /**
     * The answer to the question, with the documents it cites.
     *
     * @param question what the user asked, not blank
     * @param userId   who is asking
     * @throws ChatUnavailableException if akpedia-ml failed, either turning the question into a
     *         vector or writing the answer
     */
    public ChatResponse answer(String question, Long userId) {
        String asked = question.strip();
        List<RetrievedPassage> passages = retrieve(asked, userId);
        if (passages.isEmpty()) {
            return notFound();
        }

        ChatAnswer answer = chatClient.answer(asked, passages);
        if (!answer.found()) {
            return new ChatResponse(answer.answer(), false, List.of());
        }
        return new ChatResponse(answer.answer(), true, sourcesOf(answer.sources(), passages));
    }

    /**
     * Any failure of akpedia-ml while embedding the question is the chat being unavailable, a refusal
     * included: the question was already checked here (not blank, at most 1000 characters), so a 4xx
     * from there means the two services disagree, which the person asking cannot fix either.
     */
    private List<RetrievedPassage> retrieve(String question, Long userId) {
        try {
            return retrieval.retrieve(question, userId);
        } catch (EmbeddingException e) {
            throw new ChatUnavailableException(
                    "The question could not be turned into a vector, so no passage was looked for: " + e.getMessage(), e);
        }
    }

    /** The cited documents, in akpedia-ml's order, named by the first passage of each that was sent. */
    private static List<ChatResponse.Source> sourcesOf(List<String> cited, List<RetrievedPassage> passages) {
        Map<String, RetrievedPassage> sent = new LinkedHashMap<>();
        for (RetrievedPassage passage : passages) {
            sent.putIfAbsent(String.valueOf(passage.documentId()), passage);
        }
        return cited.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .distinct()
                .map(sent::get)
                .filter(Objects::nonNull)
                .map(passage -> new ChatResponse.Source(passage.documentId(), passage.name(), passage.category()))
                .toList();
    }

    private static ChatResponse notFound() {
        return new ChatResponse(NOT_FOUND_ANSWER, false, List.of());
    }
}
