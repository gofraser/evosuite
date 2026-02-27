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
package org.evosuite.coverage;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModernLanguageFeaturesTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        BytecodeInstructionPool.clearAll();
    }

    @Test
    public void testSwitchExpressionsJava17() throws Exception {
        String className = "bytecode_tests.SwitchExpressionFixture";
        Properties.TARGET_CLASS = className;

        BytecodeFixtureClassLoader loader = new BytecodeFixtureClassLoader("17");

        byte[] rawBytes = loader.getClassBytes(className);
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        byte[] instrumentedBytes = instrumentation.transformBytes(loader, className.replace('.', '/'), new ClassReader(rawBytes));

        assertNotNull(instrumentedBytes);
        int branchCount = org.evosuite.coverage.branch.BranchPool.getInstance(loader).getBranchCountForClass(className);
        assertTrue(branchCount > 0, "No branches found for switch expression in Java 17");
    }

    @Test
    public void testRecordsJava24() throws Exception {
        String className = "bytecode_tests.RecordFixture";
        Properties.TARGET_CLASS = className;

        // Records are fully supported in Java 16+. We built ours for 24.
        BytecodeFixtureClassLoader loader = new BytecodeFixtureClassLoader("24");

        byte[] rawBytes = loader.getClassBytes(className);
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        byte[] instrumentedBytes = instrumentation.transformBytes(loader, className.replace('.', '/'), new ClassReader(rawBytes));

        assertNotNull(instrumentedBytes);
        int methodCount = org.evosuite.graphs.cfg.BytecodeInstructionPool.getInstance(loader).knownMethods(className).size();
        
        // A record has implicitly generated methods like equals, hashCode, toString, and accessor methods.
        assertTrue(methodCount >= 4, "A Record class should have at least 4 methods (init, equals, hashCode, toString, accessors)");
    }
}
