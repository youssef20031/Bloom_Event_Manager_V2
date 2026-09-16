package com.bloom.eventmanager.chat.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.chat.domain.ChatResult;
import com.bloom.eventmanager.chat.domain.ChatWithDocumentsUseCase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatWithDocumentsUseCase useCase;

    @Test
    void answersInV1sShape() throws Exception {
        when(useCase.execute("hi", null)).thenReturn(new ChatResult("hello", "why", "session_1_abcdefg", List.of("g.pdf")));

        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.answer").value("hello"))
                .andExpect(jsonPath("$.thoughts").value("why"))
                .andExpect(jsonPath("$.sessionId").value("session_1_abcdefg"))
                .andExpect(jsonPath("$.sources[0]").value("g.pdf"));
    }

    @Test
    void rejectsABlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        verify(useCase, never()).execute(any(), any());
    }
}
