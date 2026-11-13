package com.akpedia.server.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.ChatRequest;
import com.akpedia.server.dto.ChatResponse;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.ChatService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Chat", description = "Short answers to questions, written from the documents the user may see.")
public class ChatController {

    private static final String ERROR_SCHEMA = "application/json";

    private final ChatService chatService;
    private final CurrentUser currentUser;

    public ChatController(ChatService chatService, CurrentUser currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    @Operation(
            summary = "Answer a question from the documents the caller may see",
            description = """
                    The passages closest to `question` are looked for among the approved, indexed documents in
                    the categories the caller's sector sees -- several per document if that is where they are, up
                    to `akpedia.chat.max-passages`, none below `akpedia.chat.minimum-score`. akpedia-ml writes a
                    short answer from them, tagging each statement with the passage it came from (`[1]`).

                    With no passage above the threshold, the answer is `found: false` with a sentence saying so,
                    and the language model is not asked. `found` is also false when the passages do not answer
                    the question. `sources` lists the documents the answer cites, in order of first citation,
                    each with what is needed to open it; it is empty when nothing was found.

                    The question is not recorded: the audit trail keeps the request, never its body.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The answer, found or not."),
        @ApiResponse(responseCode = "400", description = "`invalid_request`: `question` is missing, blank or over 1000 characters.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "`chat_unavailable`: akpedia-ml or its language model failed; try again later.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody @Valid ChatRequest request) {
        return chatService.answer(request.question(), currentUser.id());
    }
}
