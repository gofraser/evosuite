/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.llm;

import org.evosuite.Properties;
import org.evosuite.llm.postprocess.LlmPostProcessor;
import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.testcase.execution.ClientSandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A run without an LLM provider must not create LLM services or workers. */
class LlmDefaultPathTest {

    private static final String PARSE_COMPILE_THREAD = "evosuite-llm-parse-compile";

    private Properties.LlmProvider originalProvider;
    private boolean originalPostProcessing;

    @BeforeEach
    void setUp() {
        originalProvider = Properties.LLM_PROVIDER;
        originalPostProcessing = Properties.LLM_POSTPROCESSING_ENABLED;
        Properties.LLM_PROVIDER = Properties.LlmProvider.NONE;
        LlmService.resetInstanceForTesting();
    }

    @AfterEach
    void tearDown() {
        Properties.LLM_PROVIDER = originalProvider;
        Properties.LLM_POSTPROCESSING_ENABLED = originalPostProcessing;
        LlmService.resetInstanceForTesting();
    }

    @Test
    void postProcessorDoesNotCreateServiceWithoutProvider() {
        new LlmPostProcessor();

        assertFalse(LlmService.hasInstance());
    }

    @Test
    void postProcessorDoesNotCreateServiceWhenPostProcessingIsOff() {
        Properties.LLM_PROVIDER = Properties.LlmProvider.OLLAMA;
        Properties.LLM_POSTPROCESSING_ENABLED = false;

        new LlmPostProcessor();

        assertFalse(LlmService.hasInstance());
    }

    @Test
    void sandboxActivationStartsNoParseCompileWorkerWithoutProvider() throws Exception {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        // Replace the worker so no thread from an earlier test is left, then wait for it to end.
        TestRepairLoop.resetForRunCompletion();
        awaitNoParseCompileThread();
        try {
            ClientSandbox.activate();
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("Security Manager is unavailable on this JVM");
        }
        try {
            assertFalse(parseCompileThreadAlive());
        } finally {
            Sandbox.resetDefaultSecurityManager();
        }
    }

    private static void awaitNoParseCompileThread() throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (parseCompileThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(!parseCompileThreadAlive(), "stale parse-compile worker did not stop");
    }

    private static boolean parseCompileThreadAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && PARSE_COMPILE_THREAD.equals(t.getName())) {
                return true;
            }
        }
        return false;
    }
}
