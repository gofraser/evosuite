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
package org.evosuite.instrumentation;

import com.examples.with.different.packagename.instrumentation.StackOverflowHandlerHelper;
import com.examples.with.different.packagename.instrumentation.StackOverflowHandlerLoadsClass;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression test for {@link InstrumentingClassLoader#instrumentClass}: a {@link StackOverflowError}
 * thrown while instrumenting a class used to be wrapped into a {@link ClassNotFoundException},
 * which the JVM turns into a {@link NoClassDefFoundError} that HotSpot caches permanently for
 * that constant-pool entry (JVMS 5.4.3). {@link StackOverflowHandlerLoadsClass#probe} mimics
 * Jackson's {@code BeanSerializerBase.serializeFields}: it overflows the stack and, inside its
 * own handler with almost no stack left, resolves {@link StackOverflowHandlerHelper} for the
 * first time. With the fix, {@code VirtualMachineError} is rethrown unchanged so the retry one
 * frame up succeeds, exactly as it would on an uninstrumented JVM.
 */
public class InstrumentingClassLoaderStackOverflowTest {

    private static final String TARGET_CLASS = StackOverflowHandlerLoadsClass.class.getName();
    private static final String HELPER_CLASS = StackOverflowHandlerHelper.class.getName();

    private final String defaultTargetClass = Properties.TARGET_CLASS;
    private final Properties.Criterion[] defaultCriteria = Properties.CRITERION;
    private final long defaultMaxLoopIterations = Properties.MAX_LOOP_ITERATIONS;

    @BeforeEach
    public void setUp() {
        Properties.TARGET_CLASS = TARGET_CLASS;
        // Default criteria include weak mutation, whose instrumentation adds LoopCounter checks
        // to probe()'s recursion; that would trip TooManyResourcesException long before the real
        // stack overflows. Restrict to BRANCH and disable the loop counter outright.
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.BRANCH};
        Properties.MAX_LOOP_ITERATIONS = -1;
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = defaultTargetClass;
        Properties.CRITERION = defaultCriteria;
        Properties.MAX_LOOP_ITERATIONS = defaultMaxLoopIterations;
    }

    @Test
    public void survivesStackOverflowWhileResolvingHelperClass() throws Exception {
        InstrumentingClassLoader loader = new InstrumentingClassLoader();

        // The helper must only be resolved lazily, from inside probe()'s own StackOverflowError
        // handler, with the stack nearly exhausted - not upfront by test setup.
        assertNull(findLoadedClass(loader, HELPER_CLASS),
                "helper class must not be pre-loaded before twice() runs");

        assertEquals("deep/deep", runTwiceOnSmallStack(loader));
        // If the fix regresses, the StackOverflowError hit while resolving the helper class the
        // first time around gets wrapped into a permanently-cached NoClassDefFoundError, so this
        // second call would fail even though nothing about the class or loader changed.
        assertEquals("deep/deep", runTwiceOnSmallStack(loader));
    }

    /**
     * Runs {@code StackOverflowHandlerLoadsClass.twice()} through the given loader on a dedicated
     * thread with a small stack. The main thread's 8 MB stack would make the recursion in
     * {@code probe()} take far too long to overflow; a small dedicated stack overflows quickly,
     * as a typical JVM thread would.
     */
    private static String runTwiceOnSmallStack(InstrumentingClassLoader loader) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable task = () -> {
            try {
                Class<?> target = Class.forName(TARGET_CLASS, true, loader);
                Method twice = target.getMethod("twice");
                result.set((String) twice.invoke(null));
            } catch (InvocationTargetException e) {
                failure.set(e.getCause());
            } catch (Throwable t) {
                failure.set(t);
            }
        };
        Thread thread = new Thread(null, task, "sut", 512 * 1024);
        thread.start();
        thread.join();

        Throwable cause = failure.get();
        if (cause != null) {
            fail("SUT thread failed with " + cause.getClass().getName() + ": " + cause.getMessage());
        }
        return result.get();
    }

    private static Class<?> findLoadedClass(InstrumentingClassLoader loader, String name) throws Exception {
        Method findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        findLoadedClass.setAccessible(true);
        return (Class<?>) findLoadedClass.invoke(loader, name);
    }
}
