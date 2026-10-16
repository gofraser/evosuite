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
package org.evosuite.testcase.synthesizer.var;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.examples.with.different.packagename.objectconstruction.tuning.SyntheticAccessorGetterExample;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The field-getter search used by object-construction synthesis must never pick javac's synthetic
 * {@code access$NNN} stubs: they read the field, but a test calling them does not compile.
 */
public class SyntheticAccessorGetterTest {

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = "";
        Properties.APPLY_OBJECT_RULE = false;
        TestGenerationContext.getInstance().resetContext();
    }

    @Test
    public void getterSearchSkipsSyntheticAccessStubs() throws Exception {
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] {Criterion.BRANCH};
        Properties.TARGET_CLASS = SyntheticAccessorGetterExample.class.getName();
        Properties.APPLY_OBJECT_RULE = true;
        String cp = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(cp);
        List<String> classpath = new ArrayList<>();
        classpath.add(cp);
        DependencyAnalysis.analyzeClass(Properties.TARGET_CLASS, classpath);

        Class<?> sut = TestGenerationContext.getInstance().getClassLoaderForSUT()
                .loadClass(SyntheticAccessorGetterExample.class.getName());
        boolean hasStub = false;
        for (Method m : sut.getDeclaredMethods()) {
            // what ConstructionPathSynthesizer.registerAllMethods does before synthesis
            MethodUtil.registerMethod(sut, m.getName() + MethodUtil.getSignature(m));
            hasStub |= m.isSynthetic() && m.getName().startsWith("access$");
        }
        assertTrue(hasStub, "fixture must compile with a synthetic access$ stub");

        Field items = sut.getDeclaredField("items");
        for (long seed = 0; seed < 40; seed++) {
            Randomness.setSeed(seed);
            Method getter = VariableCodeGenerationUtil.searchForPotentialGetterInClass(sut, items);
            assertNotNull(getter);
            assertEquals("getItems", getter.getName());
        }
    }
}
