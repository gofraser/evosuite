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
package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SignatureContextProviderTest {

    @Test
    void getContextFallsBackToReflectionWhenClusterIsNull() {
        SignatureContextProvider provider = new SignatureContextProvider();

        Optional<String> context = provider.getContext("java.util.ArrayList", null);

        assertTrue(context.isPresent());
        assertTrue(context.get().contains("class ArrayList"));
        assertTrue(context.get().contains("// Public methods"));
    }

    @Test
    void getContextReturnsEmptyWhenTargetCannotBeResolved() {
        SignatureContextProvider provider = new SignatureContextProvider();
        TestCluster cluster = mock(TestCluster.class);

        when(cluster.getAnalyzedClasses()).thenReturn(Collections.<Class<?>>emptySet());

        Optional<String> context = provider.getContext("com.example.DoesNotExist", cluster);

        // Dependency context is now handled separately by PromptBuilder, not SignatureContextProvider
        assertTrue(!context.isPresent());
    }

    @Test
    void getContextReturnsCutSignaturesWithoutDependencies() {
        SignatureContextProvider provider = new SignatureContextProvider();
        TestCluster cluster = mock(TestCluster.class);

        when(cluster.getAnalyzedClasses()).thenReturn(Collections.<Class<?>>singleton(TrivialCut.class));

        Optional<String> context = provider.getContext(TrivialCut.class.getName(), cluster);

        assertTrue(context.isPresent());
        assertTrue(context.get().contains("class TrivialCut"));
        // Dependency context is handled by PromptBuilder.addDependencyContext(), not here
        assertTrue(!context.get().contains("dependency"));
    }

    public static class TrivialCut {
        public TrivialCut() {
        }
    }
}
