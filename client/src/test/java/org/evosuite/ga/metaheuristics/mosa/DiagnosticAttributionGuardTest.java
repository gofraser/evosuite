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
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.llm.search.InjectionAttemptMetadata;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.testcase.InjectionSource;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link AbstractMOSA#shouldAttributeDiagnostics}: card-informed gains
 * must be attributed for both SYNC (stagnation) and ASYNC injections, and for
 * nothing else.
 */
class DiagnosticAttributionGuardTest {

    private static InjectionAttemptMetadata withCards() {
        return new InjectionAttemptMetadata("attempt-1",
                Collections.singletonList(ProblemCardType.ENVIRONMENT_BARRIER));
    }

    private static InjectionAttemptMetadata withoutCards() {
        return new InjectionAttemptMetadata("attempt-2", Collections.emptyList());
    }

    @Test
    void stagnationWithCardsIsAttributed() {
        assertTrue(AbstractMOSA.shouldAttributeDiagnostics(
                InjectionSource.LLM_STAGNATION, withCards()));
    }

    @Test
    void asyncWithCardsIsAttributed() {
        assertTrue(AbstractMOSA.shouldAttributeDiagnostics(
                InjectionSource.LLM_ASYNC, withCards()));
    }

    @Test
    void nonLlmSourcesAreNotAttributed() {
        assertFalse(AbstractMOSA.shouldAttributeDiagnostics(
                InjectionSource.ISLAND_IMMIGRANT, withCards()));
        assertFalse(AbstractMOSA.shouldAttributeDiagnostics(
                InjectionSource.LOCAL_SEARCH, withCards()));
        assertFalse(AbstractMOSA.shouldAttributeDiagnostics(null, withCards()));
    }

    @Test
    void missingOrEmptyCardTypesAreNotAttributed() {
        assertFalse(AbstractMOSA.shouldAttributeDiagnostics(
                InjectionSource.LLM_STAGNATION, null));
        assertFalse(AbstractMOSA.shouldAttributeDiagnostics(
                InjectionSource.LLM_STAGNATION, withoutCards()));
        assertFalse(AbstractMOSA.shouldAttributeDiagnostics(
                InjectionSource.LLM_ASYNC, withoutCards()));
    }
}
