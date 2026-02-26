package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;

import java.util.Optional;

/**
 * Abstraction for extracting CUT context to include in LLM prompts.
 * Each implementation provides a different representation of the class under test.
 */
public interface SutContextProvider {

    /**
     * Extract context for the given class.
     *
     * @param className fully qualified class name
     * @param cluster   current test cluster (may be null)
     * @return context text, or empty if extraction fails
     */
    Optional<String> getContext(String className, TestCluster cluster);

    /**
     * Human-readable label for the context mode (used in prompt formatting).
     */
    String modeLabel();
}
