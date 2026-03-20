package com.harmony.backend.ai.rag.controller;

import com.harmony.backend.ai.rag.config.RagOcrProperties;
import com.harmony.backend.ai.rag.service.OcrService;
import com.harmony.backend.ai.rag.service.OcrSettingsService;
import com.harmony.backend.ai.rag.service.RagAsyncService;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.ai.rag.service.support.RagOcrOptimizer;
import com.harmony.backend.common.config.RagIngestFilepathProperties;
import com.harmony.backend.modules.chat.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.net.URI;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RagControllerSecurityTest {

    @Test
    void rejectsLoopbackRemoteUri() {
        RagController controller = createController();

        assertFalse(controller.isSafeRemoteUri(URI.create("http://127.0.0.1/image.png")));
        assertFalse(controller.isSafeRemoteUri(URI.create("http://localhost/image.png")));
    }

    @Test
    void allowsPublicRemoteUri() {
        RagController controller = createController();

        assertTrue(controller.isSafeRemoteUri(URI.create("https://example.com/image.png")));
    }

    @Test
    void rejectsRedirectToPrivateAddress() {
        RagController controller = createController();

        URI redirected = controller.resolveRedirectUri(
                URI.create("https://example.com/a.png"),
                "http://127.0.0.1/internal.png");

        assertNull(redirected);
    }

    @Test
    void resolvesSafeRelativeRedirect() {
        RagController controller = createController();

        URI redirected = controller.resolveRedirectUri(
                URI.create("https://example.com/assets/a.png"),
                "../img/b.png");

        assertEquals(URI.create("https://example.com/img/b.png"), redirected);
    }

    private RagController createController() {
        return new RagController(
                mock(RagService.class),
                mock(SessionService.class),
                mock(OcrService.class),
                mock(RagOcrProperties.class),
                mock(RedisTemplate.class),
                mock(OcrSettingsService.class),
                mock(com.harmony.backend.common.mapper.UserMapper.class),
                mock(RagAsyncService.class),
                mock(RagOcrOptimizer.class),
                mock(RagIngestFilepathProperties.class),
                mock(Executor.class)
        );
    }
}
