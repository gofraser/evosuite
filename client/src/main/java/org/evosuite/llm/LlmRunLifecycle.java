/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite contributors.
 */
package org.evosuite.llm;

import org.evosuite.llm.prompt.DecompiledContextProvider;
import org.evosuite.llm.prompt.SutContextProviderFactory;
import org.evosuite.llm.response.TestRepairLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One production lifecycle boundary for process-scoped LLM collaborators.
 * EvoSuite can execute multiple generation runs in the same JVM; none of their
 * provider configuration, budgets, context caches, or blocked workers may leak
 * into the next run.
 */
public final class LlmRunLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(LlmRunLifecycle.class);

    private LlmRunLifecycle() {
    }

    /** Release all process-scoped LLM state after runtime metrics were published. */
    public static void completeRun() {
        cleanup("service", LlmService::closeAndResetForRunCompletion);
        cleanup("SUT context cache", SutContextProviderFactory::resetForRunCompletion);
        cleanup("decompiler worker", DecompiledContextProvider::resetForRunCompletion);
        cleanup("parse-compile worker", TestRepairLoop::resetForRunCompletion);
    }

    private static void cleanup(String component, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            // LLM cleanup must never replace an otherwise valid generation result.
            logger.warn("Could not reset LLM {} after run completion: {}",
                    component, failure.toString());
        }
    }
}
