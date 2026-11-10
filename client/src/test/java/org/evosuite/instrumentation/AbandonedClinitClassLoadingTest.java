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

import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.TooManyResourcesException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A static initializer that the executor abandoned must not be stopped while it is still
 * loading classes through the {@link InstrumentingClassLoader}: throwing into it marks its
 * class erroneous for the rest of the run, although it is only slow (a large framework
 * instrumented over a slow file system). An initializer that only spins is still stopped.
 */
public class AbandonedClinitClassLoadingTest {

    private static final String[] CLASSES = {
            "com.examples.with.different.packagename.ArrayStack",
            "com.examples.with.different.packagename.Buffer",
            "com.examples.with.different.packagename.BufferUnderflowException",
            "com.examples.with.different.packagename.Dummy",
            "com.examples.with.different.packagename.ClassWithPublicField",
            "com.examples.with.different.packagename.EnumUser",
            "com.examples.with.different.packagename.DiamondInterfaceA",
    };

    /** Longer than the one second grace of an abandoned initializer. */
    private static final long RUN_MILLIS = 3_000L;

    private static volatile InstrumentingClassLoader loader;
    private static volatile boolean loadClasses;
    private static volatile int probeIndex;
    private static volatile boolean finished;
    private static volatile boolean threw;
    private static volatile int loaded;

    private boolean loopCounterState;

    @BeforeAll
    public static void initClasspath() {
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(
                System.getProperty("user.dir") + "/target/test-classes");
    }

    @BeforeEach
    public void init() {
        LoopCounter.getInstance().setDelegate(null);
        loopCounterState = LoopCounter.getInstance().isActivated();
        LoopCounter.getInstance().setActive(false);
        probeIndex = LoopCounter.getInstance().getNewIndex();
        loader = new InstrumentingClassLoader();
        finished = false;
        threw = false;
        loaded = 0;
    }

    @AfterEach
    public void tearDown() {
        LoopCounter.getInstance().setActive(loopCounterState);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testAbandonedInitializerStillLoadingClassesIsNotStopped() throws Exception {
        loadClasses = true;
        runAbandonedInitializer(LoadingClinit.class);

        assertTrue(finished, "the initializer should have run to completion");
        assertFalse(threw, "an abandoned initializer that keeps loading classes must not be stopped");
        assertTrue(loaded >= CLASSES.length, "the fixture should have loaded its classes");
    }

    /**
     * The initializer runs for 0.6 s before it starts loading a class whose instrumentation
     * takes another 0.6 s and reaches termination probes meanwhile; the class is still being
     * loaded after the one second grace window of the abandonment has passed.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testAbandonedInitializerInsideSlowInstrumentationIsNotStopped() throws Exception {
        loader = new InstrumentingClassLoader() {
            @Override
            protected byte[] getTransformedBytes(String className, InputStream is) throws IOException {
                long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
                while (System.nanoTime() < end) {
                    LoopCounter.getInstance().checkLoop(probeIndex);
                }
                return super.getTransformedBytes(className, is);
            }
        };
        runAbandonedInitializer(SlowInstrumentationClinit.class);

        assertTrue(finished, "the initializer should have run to completion");
        assertFalse(threw, "an initializer must not be stopped while a class is being instrumented");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testAbandonedSpinningInitializerIsStopped() throws Exception {
        loadClasses = false;
        runAbandonedInitializer(SpinningClinit.class);

        assertTrue(threw, "an abandoned initializer that loads no classes must still be stopped");
    }

    private void runAbandonedInitializer(Class<?> fixture) throws InterruptedException {
        Thread worker = new Thread(() -> {
            try {
                Class.forName(fixture.getName());
            } catch (Throwable ignored) {
                // The fixture records what happened; a stopped initializer fails here.
            }
        }, "abandoned-loading-clinit");
        worker.setDaemon(true);
        LoopCounter.getInstance().abandonStaticInitializer(worker);
        worker.start();
        try {
            worker.join(15_000);
            assertFalse(worker.isAlive(), "the initializer must finish within the test deadline");
        } finally {
            if (worker.isAlive()) {
                worker.interrupt();
            }
        }
    }

    private static class LoadingClinit {

        static {
            runSlowInitializer();
        }
    }

    private static class SlowInstrumentationClinit {

        static {
            try {
                long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
                while (System.nanoTime() < end) {
                    LoopCounter.getInstance().checkLoop(probeIndex);
                }
                loader.loadClass(CLASSES[0]);
                finished = true;
            } catch (TooManyResourcesException e) {
                threw = true;
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static class SpinningClinit {

        static {
            runSlowInitializer();
        }
    }

    /** Runs inside the fixtures' static initializers, so that their frames are on the stack. */
    private static void runSlowInitializer() {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);
        long nextLoad = System.nanoTime();
        try {
            while (System.nanoTime() < end || (loadClasses && loaded < CLASSES.length)) {
                LoopCounter.getInstance().checkLoop(probeIndex);
                if (loadClasses && loaded < CLASSES.length && System.nanoTime() >= nextLoad) {
                    loader.loadClass(CLASSES[loaded++]);
                    nextLoad = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
                }
            }
            finished = true;
        } catch (TooManyResourcesException e) {
            threw = true;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
