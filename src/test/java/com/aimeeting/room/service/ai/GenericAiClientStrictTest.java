package com.aimeeting.room.service.ai;

import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.dto.ChatResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenericAiClientStrictTest {
    @Test
    void builtInSearchModelIsRejectedBeforeSearchOrFallback() {
        GenericAiClient client = new GenericAiClient();
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        ReflectionTestUtils.setField(client, "registry", registry);
        ModelEntry entry = new ModelEntry();
        entry.setEnabled(true);
        entry.setModelId("deepseek-v4-pro");
        entry.setApiFormat("claude");
        entry.setApiUrl("https://example.test/anthropic/v1/messages");
        when(registry.getByModelId("selected")).thenReturn(entry);

        ChatResult result = client.chatWithEvidenceStrict("selected", "system", "prompt", 300);

        assertNull(result.content);
        assertEquals("unseparable_builtin_search", result.modelStatus);
    }
}
