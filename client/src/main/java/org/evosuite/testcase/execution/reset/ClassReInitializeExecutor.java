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
package org.evosuite.testcase.execution.reset;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.coverage.mutation.MutationObserver;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.Runtime;
import org.evosuite.runtime.classhandling.ClassResetter;
import org.evosuite.runtime.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class implements the actual invocation to the __STATIC_RESET() method
 * when a class is decided to be re-initialized.
 *
 * @author galeotti
 */
class ClassReInitializeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ClassReInitializeExecutor.class);

    private static final ClassReInitializeExecutor instance = new ClassReInitializeExecutor();

    /**
     * Classes whose {@code __STATIC_RESET()} timed out. These are skipped on
     * subsequent reset attempts to avoid wasting time and leaving more zombie
     * threads.
     */
    private final Set<String> timedOutClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ClassReInitializeExecutor() {
    }

    /**
     * Clears the set of timed-out classes, allowing them to be reset again.
     * Call this at phase boundaries (e.g., before minimization) when the
     * environment may have changed enough to make previously stuck resets succeed.
     */
    public void clearTimedOutClasses() {
        timedOutClasses.clear();
    }

    public static synchronized ClassReInitializeExecutor getInstance() {
        return instance;
    }

    /**
     * Resets the classes in the list using the Class Loader from the current
     * Test Generation context.
     *
     * @param classesToReset the classes to reset.
     */
    public void resetClasses(List<String> classesToReset) {
        ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        resetClasses(classesToReset, loader);
    }

    /**
     * Resets the classes passes in the list using the given class loader.
     *
     * @param classesToReset the classes to reset.
     * @param loader the class loader.
     */
    public void resetClasses(List<String> classesToReset, ClassLoader loader) {
        // try to reset each collected class

        ClassResetter.getInstance().setClassLoader(loader);

        long start = System.currentTimeMillis();

        for (String className : classesToReset) {
            // re-initialization can be expensive
            long elapsed = System.currentTimeMillis() - start;

            if (!className.equals(Properties.TARGET_CLASS)
                    && (!TimeController.getInstance().isThereStillTimeInThisPhase()
                    || elapsed > Properties.TIMEOUT_RESET)) {
                logger.info("Skipping reset of non-target class {} (elapsed {}ms, budget {}ms)",
                        className, elapsed, Properties.TIMEOUT_RESET);
                continue;
            }
            resetClass(className);
        }
    }

    private void resetClass(String className) {

        if (timedOutClasses.contains(className)) {
            logger.debug("Skipping reset of {} — previously timed out", className);
            return;
        }

        // className.__STATIC_RESET() exists
        logger.debug("Resetting class " + className);

        int mutationActive = MutationObserver.activeMutation;
        MutationObserver.deactivateMutation();

        // execute __STATIC_RESET()
        Sandbox.goingToExecuteSUTCode();
        TestGenerationContext.getInstance().goingToExecuteSUTCode();

        Runtime.getInstance().resetRuntime(); // it is important to initialize
        // the VFS
        boolean wasLoopCheckOn = LoopCounter.getInstance().isActivated();

        try {
            Method resetMethod = ClassResetter.getInstance().getResetMethod(className);
            if (resetMethod != null) {
                LoopCounter.getInstance().setActive(false);
                invokeResetWithTimeout(resetMethod, className);
            } else {
                logger.debug("ClassReInitializeExecutor: no {} in {}, using reflective static reset fallback",
                        ClassResetter.STATIC_RESET, className);
                resetStaticFieldsReflectively(className);
            }
        } catch (Throwable e) {
            ClassResetter.getInstance().logWarn(className,
                    e.getClass() + " thrown during execution of method  __STATIC_RESET() for class " + className + ", "
                            + e.getCause());
        } finally {
            Sandbox.doneWithExecutingSUTCode();
            TestGenerationContext.getInstance().doneWithExecutingSUTCode();
            MutationObserver.activateMutation(mutationActive);
            LoopCounter.getInstance().setActive(wasLoopCheckOn);
        }
    }

    private void resetStaticFieldsReflectively(String className) {
        try {
            ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
            Class<?> clazz = Class.forName(className, false, loader);
            int resetCount = 0;
            for (Field field : clazz.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (!Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
                    continue;
                }
                field.setAccessible(true);
                Class<?> type = field.getType();
                if (!type.isPrimitive()) {
                    field.set(null, null);
                } else if (type == Boolean.TYPE) {
                    field.setBoolean(null, false);
                } else if (type == Byte.TYPE) {
                    field.setByte(null, (byte) 0);
                } else if (type == Short.TYPE) {
                    field.setShort(null, (short) 0);
                } else if (type == Integer.TYPE) {
                    field.setInt(null, 0);
                } else if (type == Long.TYPE) {
                    field.setLong(null, 0L);
                } else if (type == Float.TYPE) {
                    field.setFloat(null, 0f);
                } else if (type == Double.TYPE) {
                    field.setDouble(null, 0d);
                } else if (type == Character.TYPE) {
                    field.setChar(null, '\u0000');
                }
                resetCount++;
            }
            logger.debug("ClassReInitializeExecutor: reflective static reset fallback applied to {} field(s) in {}",
                    resetCount, className);
        } catch (Throwable t) {
            logger.debug("ClassReInitializeExecutor: reflective static reset fallback failed for {}: {}",
                    className, t.getMessage());
        }
    }

    /**
     * Invokes the __STATIC_RESET() method on a separate daemon thread with a timeout.
     * This prevents hangs when a class's static initializer blocks on operations
     * like Object.wait(), network I/O, or semaphore acquisition that would otherwise
     * stall the search indefinitely.
     */
    private void invokeResetWithTimeout(Method resetMethod, String className) throws Throwable {
        ClassLoader sutClassLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();

        FutureTask<Void> task = new FutureTask<>(() -> {
            resetMethod.invoke(null, (Object[]) null);
            return null;
        });

        Thread resetThread = new Thread(task, "StaticReset-" + className);
        resetThread.setDaemon(true);
        resetThread.setContextClassLoader(sutClassLoader);
        resetThread.start();

        try {
            task.get(Properties.TIMEOUT_RESET, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.warn("Static reset for {} timed out after {}ms — interrupting reset thread. "
                    + "This class will be skipped on subsequent reset attempts.",
                    className, Properties.TIMEOUT_RESET);
            timedOutClasses.add(className);
            resetThread.interrupt();
            // Give the thread a short grace period to react to the interrupt.
            // Object.wait() and Thread.sleep() throw InterruptedException promptly.
            try {
                resetThread.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (resetThread.isAlive()) {
                logger.warn("Static reset thread for {} still alive after interrupt, abandoning.", className);
            }
        } catch (ExecutionException e) {
            // Unwrap and rethrow so the caller sees the original exception
            throw e.getCause() != null ? e.getCause() : e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
