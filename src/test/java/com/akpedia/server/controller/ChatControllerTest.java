package com.akpedia.server.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.dto.ChatResponse;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.ChatUnavailableException;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.ChatService;

/** Pins the JSON of {@code POST /api/v1/chat}, the contract the home page's chat mode is built against. */
@WebMvcTest(controllers = ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class ChatControllerTest {

    private static final long USER_ID = 20L;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ChatService chatService;

    @MockBean
    private CurrentUser currentUser;

    @BeforeEach
    void logIn() {
        given(currentUser.id()).willReturn(USER_ID);
    }

    private static String question(String text) {
        return "{\"question\": \"" + text + "\"}";
    }

    @Test
    @DisplayName("answers answer, found and sources with document_id, name and category")
    void answersTheAgreedShape() throws Exception {
        given(chatService.answer("Qual o prazo?", USER_ID)).willReturn(new ChatResponse(
                "O reembolso sai em 7 dias [1].", true,
                List.of(new ChatResponse.Source(42L, "Manual de Reembolso", "Financeiro"))));

        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(question("Qual o prazo?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.answer").value("O reembolso sai em 7 dias [1]."))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].*", hasSize(3)))
                .andExpect(jsonPath("$.sources[0].document_id").value(42))
                .andExpect(jsonPath("$.sources[0].name").value("Manual de Reembolso"))
                .andExpect(jsonPath("$.sources[0].category").value("Financeiro"));
    }

    @Test
    @DisplayName("not found is 200 with found false and an empty list of sources")
    void notFoundIsOk() throws Exception {
        given(chatService.answer(anyString(), any()))
                .willReturn(new ChatResponse(ChatService.NOT_FOUND_ANSWER, false, List.of()));

        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(question("Qual o prazo?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.sources", hasSize(0)));
    }

    @Test
    @DisplayName("a blank question is 400 and never reaches the service")
    void blankQuestionIsRefused() throws Exception {
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(question("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        then(chatService).should(never()).answer(anyString(), any());
    }

    @Test
    @DisplayName("a question over 1000 characters is 400")
    void tooLongQuestionIsRefused() throws Exception {
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(question("a".repeat(1001))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("akpedia-ml failing is 503 chat_unavailable")
    void akpediaMlFailureIs503() throws Exception {
        given(chatService.answer(anyString(), any())).willThrow(new ChatUnavailableException("down"));

        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(question("Qual o prazo?")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("chat_unavailable"));
    }
}
