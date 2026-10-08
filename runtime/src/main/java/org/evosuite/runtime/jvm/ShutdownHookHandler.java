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
package org.evosuite.runtime.jvm;

import org.evosuite.runtime.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * This singleton class is used to store/run the the shutdown thread hooks that
 * have been registered during test execution.
 *
 * @author arcuri
 */
public class ShutdownHookHandler {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownHookHandler.class);

    private static final ShutdownHookHandler instance = new ShutdownHookHandler();

    /**
     * A reference to the actual map in the JVM that holds the shutdown
     * threads. This is initialized by reflection
     */
    private IdentityHashMap<Thread, Thread> hooksReference;

    /**
     * Map of existing hooks before test case execution.
     * This is needed to identify the new ones that will be added.
     */
    private IdentityHashMap<Thread, Thread> existingHooks;

    /**
     * Singleton constructor.
     *
     * @throws Exception if reflection fails
     * @throws SecurityException if access is denied
     * @throws NoSuchFieldException if the field doesn't exist
     */
    @SuppressWarnings("unchecked")
    private ShutdownHookHandler() {

        try {
            Field field = Class.forName("java.lang.ApplicationShutdownHooks").getDeclaredField("hooks");
            field.setAccessible(true);
            hooksReference = (IdentityHashMap<Thread, Thread>) field.get(null);

        } catch (Exception e) {
            /*
             * This should never happen, unless new JVM do change the API we are
             * accessing by reflection
             */
            String msg = "Failed to initialize shutdown hook handling";
            logger.error(msg);
        }
    }

    public static ShutdownHookHandler getInstance() {
        return instance;
    }

    /**
     * Important to check what hooks are currently registered.
     */
    public void initHandler() {
        if (hooksReference == null) {
            return; //
        }

        if (existingHooks != null) {
            List<Thread> list = removeNewHooks();
            if (list != null && !list.isEmpty()) {
                //only log if there were SUT hooks not executed
                logger.warn("Previous hooks were not executed. Going to remove them");
            }
        }

        existingHooks = new IdentityHashMap<>();
        existingHooks.putAll(hooksReference);
    }

    /**
     * Get all hooks added since the call to initHandler
     * but before executeAddedHooks.
     *
     * @return the list of added hooks
     */
    public List<Thread> getAddedHooks() {
        if (hooksReference == null || existingHooks == null) {
            return null;
        }

        List<Thread> list = new ArrayList<>();
        for (Thread t : hooksReference.values()) {
            if (!existingHooks.containsKey(t)) {
                list.add(t);
            }
        }

        return list;
    }

    /**
     * This is mainly needed for test/debugging.
     *
     * @return the number of existing hooks
     */
    public int getNumberOfAllExistingHooks() {
        if (hooksReference == null) {
            return -1;
        }
        return hooksReference.size();
    }

    /**
     * If the JVM is halted, then no shutdown hook should be executed.
     * This usually happen when Runtime.getRuntime().halt() is called.
     */
    public void processWasHalted() {
        removeNewHooks();
    }


    /**
     * Run {@link ShutdownHookHandler#executeAddedHooks()} in a try/catch.
     *
     * @return a negative value if there was any exception
     */
    public int safeExecuteAddedHooks() {
        return safeExecuteAddedHooks(() -> false);
    }

    /**
     * Whether running shutdown hooks while the sandbox is on but no test case is
     * marked as executing deserves a warning.
     *
     * <p>The state is expected, and not worth a warning, on a test thread whose
     * execution timed out: the thread that waited for it gave up, reclaimed the
     * sandbox and cleared the process-global "executing" flag, and the worker
     * goes on to finish its test (observers, hooks) afterwards. Whether the
     * worker was given up on is read after the sandbox state, so a timeout that
     * lands in between is still seen.
     *
     * @param workerWasGivenUp tells whether the calling thread's execution was given up on
     * @return true if the state is unexpected
     */
    static boolean isUnexpectedIdleSandbox(BooleanSupplier workerWasGivenUp) {
        return Sandbox.isSecurityManagerInitialized() && !Sandbox.isOnAndExecutingSUTCode()
                && !workerWasGivenUp.getAsBoolean();
    }

    /**
     * Same as {@link #safeExecuteAddedHooks()}, but a worker whose execution was
     * given up on (timed out) does not trigger the warning about a sandbox that
     * is on with no test case marked as executing.
     *
     * @param workerWasGivenUp tells whether the calling thread's execution was given up on
     * @return a negative value if there was any exception
     */
    public int safeExecuteAddedHooks(BooleanSupplier workerWasGivenUp) {

        //the shutdown hook threads should still be checked against the sandbox
        boolean safe = Sandbox.isSafeToExecuteSUTCode();
        int n = -1;

        /*
         * The sandbox being on while no test case is marked as executing is not
         * a state this method can rule out. The "executing" flag is process-global
         * and is also written by threads we have given up on: a JUnit check runner
         * that was abandoned (and had its lock reclaimed) goes on to run its
         * scaffolding teardown, which resets and re-initializes the sandbox and
         * clears the flag while the next test execution is already under way
         * (SF110 vuze SESecurityManagerImpl, apbsmem Main). Asserting here turned
         * that into a framework error that failed the whole class. It is harmless
         * to carry on: the hooks run on this thread, which is checked by the
         * security manager like any other thread, because permission checks of
         * unprivileged threads do not depend on the flag, and privileged threads
         * are downgraded below.
         */
        if (isUnexpectedIdleSandbox(workerWasGivenUp)) {
            logger.warn("Executing shutdown hooks while the sandbox is on but no test case is marked as "
                    + "executing; the sandbox state was changed by another thread");
        }

        try {
            if (!safe) {
                Sandbox.goingToExecuteUnsafeCodeOnSameThread();
            }
            n = executeAddedHooks();
        } catch (Throwable t) {
            logger.debug("Shutdown hooks threw exception: " + t);
        } finally {
            if (!safe) {
                try {
                    Sandbox.doneWithExecutingUnsafeCodeOnSameThread();
                } catch (IllegalStateException e) {
                    // The sandbox tracks the thread running unsafe code in a single
                    // field with a nesting count, and a shutdown hook belongs to the
                    // SUT: if one of them leaves that count unbalanced, the state we
                    // are trying to restore is already gone. Nothing useful is left
                    // to do about it here, and letting it out would replace the test
                    // result with a framework error -- which is how apbsmem's
                    // JmolPanel lost its whole generated suite to a Jmol hook.
                    logger.debug("Sandbox state was already reset while running "
                            + "shutdown hooks: " + e);
                }
            }
        }
        return n;
    }

    /**
     * Execute all added shutdown hooks on this thread.
     * They are removed from the JVM.
     *
     * @return the number of executed shutdown threads
     */
    public int executeAddedHooks() {
        List<Thread> list = removeNewHooks();
        if (list == null || list.isEmpty()) {
            return 0;
        }

        /* now execute on this thread.
         *
         * note: they are not executed in parallel due to simplify testing/debugging
         * of the generated test cases
         *
         * if they throw an exception, or if they hang, that will be handled by the
         * generated tests
         */

        for (Thread t : list) {
            t.run();
        }

        return list.size();
    }

    private List<Thread> removeNewHooks() {
        List<Thread> list = getAddedHooks();
        existingHooks = null;

        if (list == null || list.isEmpty()) {
            return null;
        }

        //first remove them from JVM hooks
        for (Thread t : list) {
            if (t.getName().equals("CloverShutdownFlusher")) {
                // Clover uses a shutdown hook to write coverage data.
                // If we kill this, coverage data may be incomplete.
                continue;
            }
            hooksReference.remove(t);
        }

        return list;
    }
}
