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
package org.evosuite.runtime.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Class which controls enabling and disabling sandbox.
 * EvoSuite uses its own customized security manager.
 */
public class Sandbox {

    public enum SandboxMode {
        OFF, RECOMMENDED, IO
    }

    private static final Logger logger = LoggerFactory.getLogger(Sandbox.class);
    private static final Logger evoLogger = LoggerFactory.getLogger("evo_logger");

    /**
     * Flag indicating whether Security Manager is supported in this JVM.
     * Java 17+ deprecated Security Manager, and Java 24+ completely removed support
     * for System.setSecurityManager() - it throws UnsupportedOperationException.
     */
    private static final boolean SECURITY_MANAGER_SUPPORTED;

    static {
        boolean supported = true;
        try {
            // Check if setSecurityManager is supported by attempting to get/set null
            // This will throw UnsupportedOperationException on Java 24+
            SecurityManager current = System.getSecurityManager();
            System.setSecurityManager(current);
        } catch (UnsupportedOperationException e) {
            supported = false;
            evoLogger.warn("* Security Manager is not supported in this JVM (Java 24+). "
                    + "Sandbox functionality will be disabled.");
        } catch (SecurityException e) {
            // Security Manager is supported but we don't have permission to change it
            // This is fine - we'll handle it when we actually try to apply our manager
        }
        SECURITY_MANAGER_SUPPORTED = supported;
    }

    /**
     * Returns whether Security Manager is supported in this JVM.
     *
     * @return true if Security Manager can be used, false otherwise (Java 24+)
     */
    public static boolean isSecurityManagerSupported() {
        return SECURITY_MANAGER_SUPPORTED;
    }

    private static volatile MSecurityManager manager;

    /**
     * count how often we tried to init the sandbox.
     *
     * <p>
     * Ideally, the sandbox should be init only once.
     * Problem is, that we do compile and run the JUnit test cases (eg
     * to see if they compile with no problems, if their assertions
     * are stable, etc), and those test cases do init/reset the sandbox
     * </p>
     */
    private static volatile int counter;

    private static boolean checkForInitialization = false;
    /**
     * Tracks per-thread temporary privilege downgrades while executing SUT code.
     * A value > 0 means the current thread was privileged and has been switched to
     * sandboxed checks via {@link MSecurityManager#goingToExecuteUnsafeCodeOnSameThread()}.
     */
    private static final ThreadLocal<Integer> privilegedDowngradeDepth =
            ThreadLocal.withInitial(() -> 0);

    public static void setCheckForInitialization(boolean checkForInitialization) {
        Sandbox.checkForInitialization = checkForInitialization;
    }

    /**
     * Create and initialize security manager for SUT.
     */
    public static synchronized void initializeSecurityManagerForSUT(Set<Thread> privileged) {
        if (!SECURITY_MANAGER_SUPPORTED) {
            // On Java 24+, Security Manager is not supported.
            // We still increment counter to maintain symmetry with resetDefaultSecurityManager()
            counter++;
            return;
        }

        if (manager != null) {
            SecurityManager currentManager = System.getSecurityManager();
            if (currentManager != manager) {
                logger.warn("Sandbox state out of sync: internal manager is {}, JVM manager is {}. "
                                + "Resetting sandbox state and reinitializing.",
                        manager.getClass().getName(),
                        currentManager == null ? "null" : currentManager.getClass().getName());
                manager = null;
                counter = 0;
            } else {
                logger.warn("Sandbox can be initalized only once");
                counter++;
                return;
            }
        }

        if (manager == null) {
            MSecurityManager newManager = new MSecurityManager();

            if (privileged == null) {
                newManager.makePrivilegedAllCurrentThreads();
            } else {
                for (Thread t : privileged) {
                    newManager.addPrivilegedThread(t);
                }
            }

            try {
                newManager.apply();
                manager = newManager;
            } catch (RuntimeException e) {
                // Avoid stale internal state if applying the manager fails.
                manager = null;
                throw e;
            }
        }

        counter++;
    }

    /**
     * Create and initialize security manager for SUT.
     */
    public static synchronized void initializeSecurityManagerForSUT() {
        initializeSecurityManagerForSUT(null);
    }

    /**
     * Adds a thread to the list of privileged threads.
     *
     * @param t the thread to add
     */
    public static void addPrivilegedThread(Thread t) {
        if (manager != null) {
            manager.addPrivilegedThread(t);
        }
    }

    /**
     * Reset default security manager.
     *
     * @return a set of the threads that were marked as privileged. This is useful
     *     if then we want to reactivate the security manager with the same priviliged threads.
     */
    public static synchronized Set<Thread> resetDefaultSecurityManager() {

        Set<Thread> privileged = null;
        if (manager != null) {
            privileged = manager.getPrivilegedThreads();
        }

        if (counter > 0) {
            counter--;
        } else {
            counter = 0;
        }

        if (counter == 0) {
            MSecurityManager oldManager = manager;
            manager = null;
            if (oldManager != null) {
                try {
                    oldManager.restoreDefaultManager();
                } catch (SecurityException e) {
                    SecurityManager currentManager = System.getSecurityManager();
                    logger.warn("Cannot restore default SecurityManager; current JVM manager is {}. "
                                    + "Sandbox internal state was cleared for recovery.",
                            currentManager == null ? "null" : currentManager.getClass().getName(), e);
                }
            }
        }

        return privileged;
    }

    /**
     * Returns true if the security manager is initialized.
     *
     * @return true if the security manager is initialized
     */
    public static boolean isSecurityManagerInitialized() {
        return manager != null;
    }

    /**
     * Call before starting executing SUT code.
     */
    public static void goingToExecuteSUTCode() {
        if (!isSecurityManagerInitialized()) {
            if (checkForInitialization && SECURITY_MANAGER_SUPPORTED) {
                logger.error("Sandbox is not initialized!");
            }
            return;
        }
        boolean downgraded = false;
        try {
            /*
             * If the current thread is privileged (eg JUnit check runner),
             * temporarily downgrade it so SUT calls are still sandboxed.
             * Otherwise SUT code could bypass checks like setSecurityManager.
             */
            manager.goingToExecuteUnsafeCodeOnSameThread();
            downgraded = true;
            privilegedDowngradeDepth.set(privilegedDowngradeDepth.get() + 1);
        } catch (SecurityException ignored) {
            // Non-privileged threads are already sandboxed.
        } catch (IllegalStateException e) {
            logger.debug("Ignoring sandbox privilege downgrade race: {}", e.getMessage());
        }
        try {
            manager.goingToExecuteTestCase();
        } catch (RuntimeException e) {
            if (downgraded) {
                restorePrivilegedThreadIfDowngraded();
            }
            throw e;
        }
        PermissionStatistics.getInstance().getAndResetExceptionInfo();
    }

    /**
     * Call after finishing executing SUT code.
     */
    public static void doneWithExecutingSUTCode() {
        if (!isSecurityManagerInitialized()) {
            if (checkForInitialization && SECURITY_MANAGER_SUPPORTED) {
                logger.error("Sandbox is not initialized!");
            }
            return;
        }
        try {
            if (!manager.isExecutingTestCase()) {
                logger.debug("Ignoring sandbox cleanup request because no test case is marked as executing");
                return;
            }
            manager.goingToEndTestCase();
        } catch (IllegalStateException e) {
            // Teardown can race with timeout recovery when stale execution threads are replaced.
            logger.debug("Ignoring sandbox cleanup race while ending test case: {}", e.getMessage());
        } finally {
            // Safety net: the executingTestCase flag is global while the
            // sandbox lock is per-thread reentrant. They can desync (e.g. a
            // nested release clears the flag first, leaving the outer cleanup
            // to see flag=false and skip goingToEndTestCase()). If the current
            // thread still holds the lock here, drop it unconditionally —
            // otherwise it leaks and every subsequent test execution on any
            // thread deadlocks forever.
            if (manager.isSandboxLockHeldByCurrentThread()) {
                logger.warn("Force-releasing leaked sandbox lock on thread '{}'",
                        Thread.currentThread().getName());
                manager.forceUnlockSandboxLockIfHeld();
            }
            restorePrivilegedThreadIfDowngraded();
        }
    }

    private static void restorePrivilegedThreadIfDowngraded() {
        int depth = privilegedDowngradeDepth.get();
        if (depth <= 0) {
            return;
        }
        if (manager == null) {
            privilegedDowngradeDepth.remove();
            return;
        }
        try {
            manager.doneWithExecutingUnsafeCodeOnSameThread();
        } catch (SecurityException | IllegalStateException e) {
            logger.debug("Ignoring sandbox privilege restore race: {}", e.getMessage());
        } finally {
            int updated = depth - 1;
            if (updated <= 0) {
                privilegedDowngradeDepth.remove();
            } else {
                privilegedDowngradeDepth.set(updated);
            }
        }
    }

    /**
     * Returns true if the sandbox is on and currently executing SUT code.
     *
     * @return true if executing SUT code
     */
    public static boolean isOnAndExecutingSUTCode() {
        if (!isSecurityManagerInitialized()) {
            return false;
        }
        return manager.isExecutingTestCase();
    }

    /**
     * Temporarily elevates privileges for the current thread to execute unsafe code.
     *
     * @throws SecurityException     if current thread is not privileged
     * @throws IllegalStateException if already executing unsafe code
     */
    public static void goingToExecuteUnsafeCodeOnSameThread() throws SecurityException,
            IllegalStateException {
        if (!isSecurityManagerInitialized()) {
            return;
        }
        manager.goingToExecuteUnsafeCodeOnSameThread();
    }

    /**
     * Restores the privileged status of the current thread after executing unsafe code.
     *
     * @throws SecurityException     if current thread is not privileged
     * @throws IllegalStateException if not executing unsafe code
     */
    public static void doneWithExecutingUnsafeCodeOnSameThread()
            throws SecurityException, IllegalStateException {
        if (!isSecurityManagerInitialized()) {
            return;
        }
        manager.doneWithExecutingUnsafeCodeOnSameThread();
    }


    /**
     * Returns true if it is safe to execute SUT code.
     *
     * @return true if safe to execute SUT code
     */
    public static boolean isSafeToExecuteSUTCode() {
        if (!isSecurityManagerInitialized()) {
            return false;
        }
        return manager.isSafeToExecuteSUTCode();
    }
}
