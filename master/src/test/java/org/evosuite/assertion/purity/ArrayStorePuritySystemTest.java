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
package org.evosuite.assertion.purity;

import com.examples.with.different.packagename.purity.ArrayStorePurity;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.assertion.CheapPurityAnalyzer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArrayStorePuritySystemTest extends SystemTestBase {

    private final boolean defaultPureInspectors = Properties.PURE_INSPECTORS;

    @BeforeEach
    public void setupPurityMode() {
        Properties.PURE_INSPECTORS = true;
    }

    @AfterEach
    public void restorePurityMode() {
        Properties.PURE_INSPECTORS = defaultPureInspectors;
    }

    @Test
    public void testArrayStorePurityClassification() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = ArrayStorePurity.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        evosuite.parseCommandLine(command);

        CheapPurityAnalyzer purityAnalyzer = CheapPurityAnalyzer.getInstance();

        assertTrue(purityAnalyzer.isPure(targetClass, "mutateFreshLocalArray",
                Type.getMethodDescriptor(Type.INT_TYPE)));

        assertFalse(purityAnalyzer.isPure(targetClass, "mutateFieldArray",
                Type.getMethodDescriptor(Type.INT_TYPE)));

        assertFalse(purityAnalyzer.isPure(targetClass, "mutateParameterArray",
                Type.getMethodDescriptor(Type.INT_TYPE, Type.getType(int[].class))));

        assertFalse(purityAnalyzer.isPure(targetClass, "mutateArrayFromCall",
                Type.getMethodDescriptor(Type.INT_TYPE)));
    }
}
