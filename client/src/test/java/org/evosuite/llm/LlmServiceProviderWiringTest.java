package org.evosuite.llm;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmServiceProviderWiringTest {

    private Properties.LlmProvider originalProvider;
    private String originalModel;
    private String originalApiKey;
    private String originalBaseUrl;
    private boolean originalRequireJdkCompiler;

    @BeforeEach
    void setUp() {
        originalProvider = Properties.LLM_PROVIDER;
        originalModel = Properties.LLM_MODEL;
        originalApiKey = Properties.LLM_API_KEY;
        originalBaseUrl = Properties.LLM_BASE_URL;
        originalRequireJdkCompiler = Properties.LLM_REQUIRE_JDK_COMPILER;
        LlmService.setCompilerAvailableForTesting(null);
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROVIDER = originalProvider;
        Properties.LLM_MODEL = originalModel;
        Properties.LLM_API_KEY = originalApiKey;
        Properties.LLM_BASE_URL = originalBaseUrl;
        Properties.LLM_REQUIRE_JDK_COMPILER = originalRequireJdkCompiler;
        LlmService.setCompilerAvailableForTesting(null);
        LlmService.resetInstanceForTesting();
    }

    @Test
    void openAiProviderIsAvailableWhenConfigured() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_MODEL = "gpt-4o-mini";
        Properties.LLM_API_KEY = "test-api-key";
        Properties.LLM_BASE_URL = "";

        LlmService service = LlmService.getInstance();
        assertTrue(service.isAvailable());
    }

    @Test
    void openAiProviderIsUnavailableWhenMissingApiKey() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_MODEL = "gpt-4o-mini";
        Properties.LLM_API_KEY = "";
        Properties.LLM_BASE_URL = "";

        LlmService service = LlmService.getInstance();
        assertFalse(service.isAvailable());
    }

    @Test
    void ollamaProviderIsAvailableWhenConfigured() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OLLAMA;
        Properties.LLM_MODEL = "llama3.1";
        Properties.LLM_BASE_URL = "http://localhost:11434";
        Properties.LLM_API_KEY = "";

        LlmService service = LlmService.getInstance();
        assertTrue(service.isAvailable());
    }

    @Test
    void anthropicProviderIsAvailableWhenConfigured() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.ANTHROPIC;
        Properties.LLM_MODEL = "claude-3-5-sonnet-latest";
        Properties.LLM_API_KEY = "test-api-key";
        Properties.LLM_BASE_URL = "";

        LlmService service = LlmService.getInstance();
        assertTrue(service.isAvailable());
    }

    @Test
    void missingCompiler_softFallbackDisablesLlm() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_MODEL = "gpt-4o-mini";
        Properties.LLM_API_KEY = "test-api-key";
        Properties.LLM_REQUIRE_JDK_COMPILER = false;
        LlmService.setCompilerAvailableForTesting(false);

        LlmService service = LlmService.getInstance();
        assertFalse(service.isAvailable());
    }

    @Test
    void missingCompiler_strictModeHardFails() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OPENAI;
        Properties.LLM_MODEL = "gpt-4o-mini";
        Properties.LLM_API_KEY = "test-api-key";
        Properties.LLM_REQUIRE_JDK_COMPILER = true;
        LlmService.setCompilerAvailableForTesting(false);

        assertThrows(IllegalStateException.class, LlmService::getInstance);
    }
}
