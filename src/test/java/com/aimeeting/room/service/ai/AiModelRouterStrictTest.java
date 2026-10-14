package com.aimeeting.room.service.ai;

import com.aimeeting.room.dto.ChatResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelRouterStrictTest {
    @Test
    void genericClientUsesStrictNoSearchPath() {
        AiModelRouter router = new AiModelRouter();
        GenericAiClient client = mock(GenericAiClient.class);
        when(client.supports("selected")).thenReturn(true);
        when(client.chatWithEvidenceStrict("selected", "system", "question", 300))
                .thenReturn(new ChatResult("answer"));
        ReflectionTestUtils.setField(router, "clients", List.of(client));

        assertEquals("answer", router.chatExStrict("selected", "system", "question", 300).content);
        verify(client, never()).chatWithEvidence(eq("selected"), eq("system"), eq("question"), eq(300));
    }

    @Test
    void unknownSelectedModelDoesNotFallBackToFirstClient() {
        AiModelRouter router = new AiModelRouter();
        AiModelClient client = mock(AiModelClient.class);
        when(client.supports("unknown-model")).thenReturn(false);
        ReflectionTestUtils.setField(router, "clients", List.of(client));

        ChatResult result = router.chatExStrict("unknown-model", "system", "question");

        assertNull(result.content);
        verify(client, never()).chat("unknown-model", "system", "question");
    }
}
