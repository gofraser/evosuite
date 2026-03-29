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
package org.evosuite.setup;

import com.examples.with.different.packagename.setup.ClassUsingCycleWithEscape;
import com.examples.with.different.packagename.setup.CycleWithEscapeX;
import com.examples.with.different.packagename.setup.CycleWithEscapeY;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@code removeDirectCycle} does not over-aggressively remove
 * generators when the owner class has an alternative constructor that
 * breaks the cycle.
 *
 * <p>Scenario: X has constructors X() and X(Y), plus a method Y getY().
 * The cycle X(Y) &harr; x.getY() is escapable via the no-arg constructor X(),
 * so Y should still have a generator after cleanup.</p>
 */
public class RemoveDirectCycleTest {

    @Test
    public void testRemoveDirectCycleAggressiveness() throws Exception {
        String targetClass = ClassUsingCycleWithEscape.class.getName();
        Properties.TARGET_CLASS = targetClass;
        List<String> classpath = new ArrayList<>();
        String cp = System.getProperty("user.dir") + "/target/test-classes";
        classpath.add(cp);
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();

        DependencyAnalysis.analyzeClass(targetClass, classpath);

        ClassLoader cl = TestGenerationContext.getInstance().getClassLoaderForSUT();

        assertTrue(TestCluster.getInstance().hasGenerator(
                        cl.loadClass(CycleWithEscapeX.class.getName())),
                "X should have generators");

        assertTrue(TestCluster.getInstance().hasGenerator(
                        cl.loadClass(CycleWithEscapeY.class.getName())),
                "Y should still have generator because X has a no-arg constructor");
    }
}
