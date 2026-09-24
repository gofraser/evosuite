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
package org.evosuite;

import com.examples.with.different.packagename.FlagExample1;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.graphs.GraphPool;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.runtime.classhandling.ClassResetter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JUnit check creates a fresh instrumenting class loader for every check it runs.
 * Instrumenting registers the class in static pools keyed by that loader, so unless the loader
 * is released afterwards it stays reachable, together with everything it instrumented.
 */
public class TestGenerationContextReleaseClassLoaderTest {

    private static final String TARGET_CLASS = FlagExample1.class.getName();

    private final String defaultTargetClass = Properties.TARGET_CLASS;
    private final Properties.Criterion[] defaultCriteria = Properties.CRITERION;

    @BeforeEach
    public void setUp() {
        Properties.TARGET_CLASS = TARGET_CLASS;
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.BRANCH};
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = defaultTargetClass;
        Properties.CRITERION = defaultCriteria;
    }

    @Test
    public void releasedLoaderBecomesUnreachable() throws Exception {
        WeakReference<ClassLoader> ref = instrumentReleaseAndForget();
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(ref.get(), "the released class loader is still reachable");
    }

    @Test
    public void loaderOfTheContextIsNeverReleased() {
        ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        GraphPool pool = GraphPool.getInstance(sutLoader);

        TestGenerationContext.getInstance().releaseClassLoader(sutLoader);

        assertSame(pool, GraphPool.getInstance(sutLoader));
    }

    private WeakReference<ClassLoader> instrumentReleaseAndForget() throws Exception {
        InstrumentingClassLoader loader = new InstrumentingClassLoader();
        Class<?> clazz = loader.loadClass(TARGET_CLASS);
        assertTrue(clazz.getClassLoader() == loader, "the target must be instrumented by the new loader");
        assertNotNull(GraphPool.getInstance(loader).getRawCFGs(TARGET_CLASS));
        assertFalse(BranchPool.getInstance(loader).getAllBranches().isEmpty());
        ClassResetter.getInstance().setClassLoader(loader);
        ClassResetter.getInstance().getResetMethod(TARGET_CLASS);

        TestGenerationContext.getInstance().releaseClassLoader(loader);
        return new WeakReference<>(loader);
    }
}
