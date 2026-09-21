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
package org.evosuite.utils;

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

class MethodUtilTest {

    private static final String EXAMPLE_CLASS =
            "com.examples.with.different.packagename.objectconstruction.instancefield"
                    + ".PublicInstanceFieldExample";
    private static final String EXAMPLE_METHOD = "targetM(I)V";

    @BeforeEach
    void setUp() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        MethodUtil.resetCache();
    }

    @AfterEach
    void tearDown() {
        MethodUtil.resetCache();
        ClassPathHandler.resetSingleton();
    }

    @Test
    void getMethodNodeGracefullyHandlesMissingClassBytecode() {
        InstrumentingClassLoader loader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();

        assertNull(MethodUtil.getMethodNode(loader,
                "com.examples.with.different.packagename.ClassThatDoesNotExist",
                "method()V"));
    }

    /**
     * getMethodNode() used to re-parse the whole class with ASM on every call, the same shape of
     * bug fixed in {@code DefUseAnalyzer.getMethodNode}. It now caches the resolved MethodNode per
     * classloader, so two lookups of the same class/method must return the exact same instance.
     */
    @Test
    void getMethodNodeCachesTheResolvedMethodNode() {
        InstrumentingClassLoader loader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();

        MethodNode first = MethodUtil.getMethodNode(loader, EXAMPLE_CLASS, EXAMPLE_METHOD);
        MethodNode second = MethodUtil.getMethodNode(loader, EXAMPLE_CLASS, EXAMPLE_METHOD);

        assertNotNull(first);
        assertSame(first, second);
    }

    /** resetCache() must drop the cached MethodNode so a fresh context reparses the bytecode. */
    @Test
    void resetCacheClearsTheMethodNodeCache() {
        InstrumentingClassLoader loader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();

        MethodNode first = MethodUtil.getMethodNode(loader, EXAMPLE_CLASS, EXAMPLE_METHOD);
        MethodUtil.resetCache();
        MethodNode second = MethodUtil.getMethodNode(loader, EXAMPLE_CLASS, EXAMPLE_METHOD);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);
    }
}
