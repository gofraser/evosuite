/**
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
package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TestClusterSummarizerTest {

    @Test
    void summarizeUsesClusterSurfacesBeyondAnalyzedClasses() {
        TestCluster cluster = mock(TestCluster.class);
        when(cluster.getAnalyzedClasses()).thenReturn(Collections.<Class<?>>emptySet());

        GenericAccessibleObject<?> call = mock(GenericAccessibleObject.class);
        GenericClass<?> owner = GenericClassFactory.get(String.class);
        GenericClass<?> generated = GenericClassFactory.get(Integer.class);
        doReturn(owner).when(call).getOwnerClass();
        doReturn(generated).when(call).getGeneratedClass();

        when(cluster.getGenerators()).thenReturn(Collections.<GenericAccessibleObject<?>>singleton(call));
        when(cluster.getModifiers()).thenReturn(Collections.<GenericAccessibleObject<?>>emptySet());
        when(cluster.getTestCalls()).thenReturn(Arrays.<GenericAccessibleObject<?>>asList(call));

        TestClusterSummarizer summarizer = new TestClusterSummarizer();
        String summary = summarizer.summarize(cluster);

        assertTrue(summary.contains("java.lang.String"));
        assertTrue(summary.contains("java.lang.Integer"));
    }
}
