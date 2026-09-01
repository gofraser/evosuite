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
package org.evosuite.graphs.interprocedural;

import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class DefUseAnalyzerTest {

    @BeforeEach
    void setUp() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @AfterEach
    void tearDown() {
        ClassPathHandler.resetSingleton();
    }

    @Test
    void getMethodNodeGracefullyHandlesMissingClassBytecode() {
        InstrumentingClassLoader loader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();

        assertNull(DefUseAnalyzer.getMethodNode(loader,
                "com.examples.with.different.packagename.ClassThatDoesNotExist",
                "method()V"));
    }

    @Test
    void analyzeGracefullyHandlesMissingMethodNode() {
        InstrumentingClassLoader loader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();

        new DefUseAnalyzer().analyze(loader, null,
                "com.examples.with.different.packagename.Missing", "method()V", 0);
    }
}
