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

import org.evosuite.classpath.ResourceList;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.mutation.MutationPool;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.runtime.classhandling.ClassResetter;
import org.evosuite.setup.TestClusterGenerator;
import org.evosuite.testcase.factories.ObjectRuleRuntime;
import org.evosuite.utils.MasterClassLoaderBridge;

/**
 * Context for test generation.
 *
 * @author Gordon Fraser
 */
public class TestGenerationContext {

    private static final TestGenerationContext singleton = new TestGenerationContext();

    /**
     * This is the classloader that does the instrumentation - it needs to be used by all test code.
     */
    private InstrumentingClassLoader classLoader;

    /**
     * The classloader used to load this class.
     */
    private final ClassLoader originalClassLoader;

    /**
     * To avoid duplicate analyses we cache the cluster generator.
     */
    private TestClusterGenerator testClusterGenerator;

    /**
     * True while the assertion-generation reloader is rebuilding the target cluster. Certain
     * setup-time optimizations (eg reflective static-field singleton probes) should be skipped in
     * this mode to avoid poisoning the fresh classloader with target-class initialization failures
     * before the later non-initializing fallback has a chance to load the CUT.
     */
    private boolean assertionGenerationContext;

    /** Mutable EvoObj state scoped to this test-generation context. */
    private final ObjectRuleRuntime objectRuleRuntime = new ObjectRuleRuntime();

    /**
     * Private singleton constructor.
     */
    private TestGenerationContext() {
        originalClassLoader = this.getClass().getClassLoader();
        classLoader = new InstrumentingClassLoader(getPreferredParentClassLoader());
    }

    public static TestGenerationContext getInstance() {
        return singleton;
    }

    public ObjectRuleRuntime getObjectRuleRuntime() {
        return objectRuleRuntime;
    }

    public boolean isAssertionGenerationContext() {
        return assertionGenerationContext;
    }

    public void setAssertionGenerationContext(boolean assertionGenerationContext) {
        this.assertionGenerationContext = assertionGenerationContext;
    }

    /**
     * This is pretty important if the SUT use classloader of the running thread. If we do not set
     * this up, we will end up with cast exceptions.
     *
     * <p>Note, an example in which this happens is org.dom4j.bean.BeanAttribute.
     */
    public void goingToExecuteSUTCode() {

        Thread.currentThread().setContextClassLoader(classLoader);
    }

    /**
     * Restore the original class loader.
     */
    public void doneWithExecutingSUTCode() {
        Thread.currentThread().setContextClassLoader(originalClassLoader);
    }

    /**
     * Get the class loader for the system under test.
     *
     * @return the class loader for the SUT
     */
    public InstrumentingClassLoader getClassLoaderForSUT() {
        return classLoader;
    }

    private ClassLoader getPreferredParentClassLoader() {
        ClassLoader loader = MasterClassLoaderBridge.getMasterClassLoaderIfInitialized();
        if (loader != null) {
            return loader;
        }
        return this.getClass().getClassLoader();
    }

    /**
     * Replaces the instrumenting class loader if its parent is no longer the preferred parent class loader.
     */
    public synchronized void rebindToPreferredParentClassLoader() {
        ClassLoader preferred = getPreferredParentClassLoader();
        if (classLoader == null || classLoader.getParent() != preferred) {
            classLoader = new InstrumentingClassLoader(preferred);
        }
    }

    /**
     * Replaces the instrumenting class loader with a fresh one, so that classes can be
     * instrumented again.
     */
    public synchronized void renewClassLoader() {
        classLoader = new InstrumentingClassLoader(getPreferredParentClassLoader());
    }

    /**
     * Get the test cluster generator.
     *
     * @return the test cluster generator
     */
    public TestClusterGenerator getTestClusterGenerator() {
        return testClusterGenerator;
    }

    /**
     * Set the test cluster generator.
     *
     * @param generator the test cluster generator to set
     */
    public void setTestClusterGenerator(TestClusterGenerator generator) {
        testClusterGenerator = generator;
    }

    /**
     * Returns the class loader used for instrumentation.
     *
     * @return The instrumenting class loader.
     * @deprecated use {@link #getInstance()} and then {@link #getClassLoaderForSUT()}
     */
    @Deprecated
    public static ClassLoader getClassLoader() {
        return getInstance().classLoader;
    }

    /**
     * Releases everything the static, class-loader-keyed pools recorded for a class loader
     * other than the one of this context, such as the throw-away loaders the JUnit check
     * creates to execute the generated tests on freshly loaded classes.
     *
     * <p>Instrumenting a class registers its bytecode instructions, control flow graphs,
     * branches and mutants in pools keyed by the loader that instrumented it. Those entries
     * reference the loader, so without this call every such loader, and all it instrumented,
     * stays reachable until the next {@link ContextResetter#reset()}. The loader of this context is
     * never released.
     *
     * @param loader a class loader that is no longer used
     */
    public void releaseClassLoader(ClassLoader loader) {
        if (loader == null || loader == classLoader) {
            return;
        }
        BytecodeInstructionPool.release(loader);
        GraphPool.release(loader);
        BranchPool.release(loader);
        MutationPool.release(loader);
        ResourceList.release(loader);
        ClassResetter.getInstance().releaseClassLoader(loader);
    }
}
