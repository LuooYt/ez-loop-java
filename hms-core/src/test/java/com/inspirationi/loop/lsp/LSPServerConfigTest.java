package com.inspirationi.loop.lsp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LSPServerConfigTest {

    @Test
    void serverConfigValidatesRequiredFields() {
        assertThrows(IllegalArgumentException.class, () ->
                new LSPServerConfig("x", "", List.of(), Map.of(".java", "java"),
                        null, Map.of(), Map.of(), null, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new LSPServerConfig("x", "jdtls", List.of(), Map.of(),
                        null, Map.of(), Map.of(), null, 0, 0));
    }

    @Test
    void defaultsAreApplied() {
        LSPServerConfig cfg = new LSPServerConfig("x", "jdtls", null,
                Map.of(".java", "java"), null, null, null, null, 0, 0);
        assertEquals(List.of(), cfg.args());
        assertEquals("stdio", cfg.transport());
        assertEquals(30_000, cfg.startupTimeout());
        assertEquals(3, cfg.maxRestarts());
    }
}
