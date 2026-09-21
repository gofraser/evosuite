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
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DefUseAnalyzerTest {

    private static final String EXAMPLE_CLASS =
            "com.examples.with.different.packagename.objectconstruction.instancefield"
                    + ".PublicInstanceFieldExample";
    private static final String EXAMPLE_METHOD = "targetM(I)V";

    @BeforeEach
    void setUp() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        DefUseAnalyzer.resetSingleton();
    }

    @AfterEach
    void tearDown() {
        DefUseAnalyzer.resetSingleton();
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

    /**
     * getMethodNode() used to re-parse the whole class with ASM on every call. It now caches the
     * resolved MethodNode per classloader, so two lookups of the same class/method must return the
     * exact same instance rather than two separately parsed copies.
     */
    @Test
    void getMethodNodeCachesTheResolvedMethodNode() {
        InstrumentingClassLoader loader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();

        MethodNode first = DefUseAnalyzer.getMethodNode(loader, EXAMPLE_CLASS, EXAMPLE_METHOD);
        MethodNode second = DefUseAnalyzer.getMethodNode(loader, EXAMPLE_CLASS, EXAMPLE_METHOD);

        assertNotNull(first);
        assertSame(first, second);
    }

    /**
     * resetSingleton() is called from ObjectRuleRuntime.reset() on every fresh test-generation
     * context (mirroring GraphPool/BytecodeInstructionPool's own per-context clearing). It must
     * drop the cached MethodNode so a new context never reuses bytecode parsed for a previous one.
     */
    @Test
    void resetSingletonClearsTheMethodNodeCache() {
        InstrumentingClassLoader loader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();

        MethodNode first = DefUseAnalyzer.getMethodNode(loader, EXAMPLE_CLASS, EXAMPLE_METHOD);
        DefUseAnalyzer.resetSingleton();
        MethodNode second = DefUseAnalyzer.getMethodNode(loader, EXAMPLE_CLASS, EXAMPLE_METHOD);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);
    }
}
