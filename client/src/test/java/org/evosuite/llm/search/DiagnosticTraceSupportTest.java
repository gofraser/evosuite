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
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagnosticTraceSupportTest {

    @Test
    void enablesTraceForWildcardTargets() {
        Logger logger = warnLogger();
        String oldTargetClass = Properties.TARGET_CLASS;
        String oldTraceTargets = Properties.LLM_DIAGNOSTIC_EXTRACTOR_TRACE_TARGETS;
        try {
            Properties.TARGET_CLASS = "com.example.Target";
            Properties.LLM_DIAGNOSTIC_EXTRACTOR_TRACE_TARGETS = "*";

            assertTrue(DiagnosticTraceSupport.shouldWarnForCurrentTarget(logger));

            Properties.LLM_DIAGNOSTIC_EXTRACTOR_TRACE_TARGETS = "all";
            assertTrue(DiagnosticTraceSupport.shouldWarnForCurrentTarget(logger));
        } finally {
            Properties.TARGET_CLASS = oldTargetClass;
            Properties.LLM_DIAGNOSTIC_EXTRACTOR_TRACE_TARGETS = oldTraceTargets;
        }
    }

    @Test
    void doesNotEnableTraceForNonMatchingTarget() {
        Logger logger = warnLogger();
        String oldTargetClass = Properties.TARGET_CLASS;
        String oldTraceTargets = Properties.LLM_DIAGNOSTIC_EXTRACTOR_TRACE_TARGETS;
        try {
            Properties.TARGET_CLASS = "com.example.Target";
            Properties.LLM_DIAGNOSTIC_EXTRACTOR_TRACE_TARGETS = "com.example.OtherTarget";

            assertFalse(DiagnosticTraceSupport.shouldWarnForCurrentTarget(logger));
        } finally {
            Properties.TARGET_CLASS = oldTargetClass;
            Properties.LLM_DIAGNOSTIC_EXTRACTOR_TRACE_TARGETS = oldTraceTargets;
        }
    }

    private static Logger warnLogger() {
        Logger logger = mock(Logger.class);
        when(logger.isWarnEnabled()).thenReturn(true);
        return logger;
    }
}
