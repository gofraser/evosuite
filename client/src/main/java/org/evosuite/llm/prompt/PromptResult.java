package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.llm.LlmMessage;

import java.util.List;

/**
 * Immutable result of {@link PromptBuilder#buildWithMetadata()},
 * carrying both the chat messages and context metadata for trace recording.
 */
public final class PromptResult {

    private final List<LlmMessage> messages;
    private final Properties.LlmSutContextMode sutContextMode;
    private final boolean contextUnavailable;

    /** Constructs an immutable prompt result with associated context metadata. */
    public PromptResult(List<LlmMessage> messages,
                        Properties.LlmSutContextMode sutContextMode,
                        boolean contextUnavailable) {
        this.messages = messages;
        this.sutContextMode = sutContextMode;
        this.contextUnavailable = contextUnavailable;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    /**
     * Context mode actually used (may differ from configured mode after fallback),
     * or null if withSutContext was not called.
     */
    public Properties.LlmSutContextMode getSutContextMode() {
        return sutContextMode;
    }

    /** True if context extraction failed and no fallback was available. */
    public boolean isContextUnavailable() {
        return contextUnavailable;
    }
}
