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
     * Whether this JVM lets EvoSuite install its security manager. Java 18 to 23 allow it only
     * when started with {@code -Djava.security.manager=allow}, and Java 24 removed it.
     */
    private static final boolean SECURITY_MANAGER_SUPPORTED;

    /** Returned by {@link #securityManagerUnavailable} when the Java version cannot be parsed. */
    static final String UNKNOWN_VERSION = "unknown Java version";

    static {
        String unavailable = securityManagerUnavailable(System.getProperty("java.specification.version"),
                System.getProperty("java.security.manager"), System.getSecurityManager() != null);
        if (UNKNOWN_VERSION.equals(unavailable)) {
            unavailable = probeSecurityManager();
        }
        SECURITY_MANAGER_SUPPORTED = unavailable == null;
        if (unavailable != null) {
            evoLogger.warn("* The sandbox is off: " + unavailable);
        }
    }

    /**
     * Why this JVM refuses {@code System.setSecurityManager}, decided the way the JDK decides it.
     * Asking the JVM instead is unreliable and noisy: a JVM that disallows security managers, as
     * Java 18 to 23 do by default, still accepts {@code setSecurityManager(null)}, so a check that
     * passes the current, null manager calls the sandbox supported until installing it throws;
     * and on Java 17 every successful call makes the JDK print a deprecation warning attributed
     * to this class, whether or not a sandbox is ever installed.
     *
     * @param specificationVersion the {@code java.specification.version} property, eg 1.8 or 17
     * @param property             the {@code java.security.manager} property, or null
     * @param installed            whether a security manager is already installed
     * @return null if a security manager can be installed, otherwise the reason, for the log;
     *     {@link #UNKNOWN_VERSION} if the version cannot be parsed
     */
    static String securityManagerUnavailable(String specificationVersion, String property, boolean installed) {
        int feature = featureVersion(specificationVersion);
        if (feature <= 0) {
            return UNKNOWN_VERSION;
        }
        if (feature >= 24) {
            return "Java " + feature + " no longer supports a security manager.";
        }
        if (installed) {
            // The JVM was started with one, so it may be replaced, subject to its permissions.
            return null;
        }
        if (feature >= 12 && "disallow".equals(property)) {
            return "the JVM was started with -Djava.security.manager=disallow.";
        }
        if (feature >= 18 && !"allow".equals(property)) {
            return "Java " + feature + " allows a security manager only when started with "
                    + "-Djava.security.manager=allow.";
        }
        return null;
    }

    /** 8 for "1.8", 17 for "17"; 0 if the version cannot be parsed. */
    static int featureVersion(String specificationVersion) {
        if (specificationVersion == null) {
            return 0;
        }
        String[] parts = specificationVersion.trim().split("\\.");
        try {
            int first = Integer.parseInt(parts[0]);
            return first == 1 && parts.length > 1 ? Integer.parseInt(parts[1]) : first;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Asks the JVM, for JVMs whose version cannot be parsed. It takes a real manager to find out,
     * since a null one is accepted even where managers are disallowed; the one installed here
     * allows everything, so that it can be removed again.
     */
    private static String probeSecurityManager() {
        if (System.getSecurityManager() != null) {
            return null;
        }
        try {
            System.setSecurityManager(new SecurityManager() {
                @Override
                public void checkPermission(java.security.Permission perm) {
                    // allow everything, including removing this manager
                }
            });
            System.setSecurityManager(null);
            return null;
        } catch (UnsupportedOperationException e) {
            return "this JVM does not support a security manager.";
        } catch (SecurityException e) {
            // Supported, but changing it needs a permission; dealt with when the sandbox is applied.
            return null;
        }
    }

    /**
     * Returns whether Security Manager is supported in this JVM.
     *
     * @return true if Security Manager can be used, false otherwise (Java 24+, or Java 18 to 23
     *     without {@code -Djava.security.manager=allow})
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

    /**
     * Threads that were given up on while they may still be running SUT or
     * scaffolding code (e.g. the JUnit check runner after a timeout). The sandbox
     * state is process-global, so once the lock of such a thread has been
     * reclaimed for somebody else, whatever the orphan does to that state when it
     * eventually unwinds would corrupt the state of the thread that is legitimately
     * using the sandbox now. State-changing calls from these threads are ignored.
     */
    private static final Set<Thread> abandonedThreads =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<Thread, Boolean>()));

    /**
     * Marks a thread as abandoned and reclaims the sandbox lock it may hold. From
     * now on its calls that would change the global sandbox state are no-ops.
     *
     * @param lostOwner the thread that was given up on, may be null
     * @return true if a leaked lock was reclaimed
     */
    public static boolean abandonThread(Thread lostOwner) {
        if (lostOwner == null) {
            return false;
        }
        abandonedThreads.add(lostOwner);
        return reclaimSandboxLockOwnedBy(lostOwner);
    }

    /**
     * Lifts the abandonment of a thread that is about to be used again, e.g. a
     * pooled worker that starts a new test after having timed out on an earlier
     * one. Its state-changing sandbox calls take effect again.
     *
     * @param thread the thread to reinstate, may be null
     */
    public static void reinstateThread(Thread thread) {
        if (thread != null && !abandonedThreads.isEmpty()) {
            abandonedThreads.remove(thread);
        }
    }

    private static boolean isCurrentThreadAbandoned(String call) {
        if (abandonedThreads.isEmpty() || !abandonedThreads.contains(Thread.currentThread())) {
            return false;
        }
        logger.debug("Ignoring Sandbox.{}() from abandoned thread '{}'", call,
                Thread.currentThread().getName());
        return true;
    }

    private static boolean checkForInitialization = false;
    /**
     * Tracks per-thread temporary privilege downgrades while executing SUT code.
     * A value > 0 means the current thread was privileged and has been switched to
     * sandboxed checks via {@link MSecurityManager#goingToExecuteUnsafeCodeOnSameThread()}.
     */
    private static final java.util.Map<Thread, Integer> privilegedDowngradeDepth =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Kept per thread in a map of its own rather than in a {@link ThreadLocal}: SUT code that
     * clears the thread locals of every thread (through reflection)
     * would reset the depth to 0, so the thread would never be restored to its
     * privileged state and could not write the generated tests afterwards.
     */
    private static int downgradeDepthOf(Thread t) {
        Integer depth = privilegedDowngradeDepth.get(t);
        return depth == null ? 0 : depth;
    }

    private static void setDowngradeDepth(Thread t, int depth) {
        if (depth <= 0) {
            privilegedDowngradeDepth.remove(t);
        } else {
            privilegedDowngradeDepth.put(t, depth);
        }
    }

    public static void setCheckForInitialization(boolean checkForInitialization) {
        Sandbox.checkForInitialization = checkForInitialization;
    }

    /**
     * While set, no thread may exit the JVM, privileged or not, and taking the sandbox down
     * leaves a manager in place that refuses just that, instead of the manager the sandbox
     * replaced.
     *
     * <p>This is for running generated tests in the process that generated them (the JUnit
     * check). That takes the sandbox down for the run, and a generated test brings up its own
     * only between its class set-up and clean-up, which makes every thread alive at that moment
     * privileged. SUT threads left over from earlier tests keep running all along, and once a
     * test has turned the mock framework off their {@code System.exit} calls are no longer
     * replaced: one that came while no manager was installed ended the process, and the suite
     * it had generated was lost.
     *
     * @param refused whether exiting the JVM is refused
     */
    public static synchronized void setJvmExitRefused(boolean refused) {
        MSecurityManager.setJvmExitRefused(refused);
        if (refused) {
            return;
        }
        SecurityManager current = System.getSecurityManager();
        if (current instanceof ExitRefusingSecurityManager) {
            try {
                System.setSecurityManager(((ExitRefusingSecurityManager) current).getReplacedManager());
            } catch (SecurityException | UnsupportedOperationException e) {
                logger.warn("Cannot remove the manager that refused to exit the JVM: {}", e.toString());
            }
        }
    }

    public static boolean isJvmExitRefused() {
        return MSecurityManager.isJvmExitRefused();
    }

    /**
     * Create and initialize security manager for SUT.
     */
    public static synchronized void initializeSecurityManagerForSUT(Set<Thread> privileged) {
        if (isCurrentThreadAbandoned("initializeSecurityManagerForSUT")) {
            return;
        }
        if (!SECURITY_MANAGER_SUPPORTED) {
            // The JVM does not allow a security manager (see securityManagerUnavailable).
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
        if (isCurrentThreadAbandoned("resetDefaultSecurityManager")) {
            return null;
        }

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
                    manager = oldManager;
                    counter++;
                    SecurityManager currentManager = System.getSecurityManager();
                    logger.warn("Cannot restore default SecurityManager; current JVM manager is {}. "
                                    + "Sandbox internal state was retained for recovery.",
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
        if (isCurrentThreadAbandoned("goingToExecuteSUTCode")) {
            return;
        }
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
            Thread self = Thread.currentThread();
            setDowngradeDepth(self, downgradeDepthOf(self) + 1);
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
        if (isCurrentThreadAbandoned("doneWithExecutingSUTCode")) {
            return;
        }
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
            // sandbox lock is owned by a single thread. They can desync (e.g. a
            // release on another thread clears the flag first, leaving this
            // cleanup to see flag=false and skip goingToEndTestCase()). If the
            // current thread still owns the lock here, drop it unconditionally —
            // otherwise it leaks and every subsequent test execution on any
            // thread stalls for the whole acquisition timeout.
            if (manager.isSandboxLockHeldByCurrentThread()) {
                logger.warn("Force-releasing leaked sandbox lock on thread '{}'",
                        Thread.currentThread().getName());
                manager.forceUnlockSandboxLockIfHeld();
            }
            restorePrivilegedThreadIfDowngraded();
        }
    }

    /**
     * Reclaims the sandbox execution lock if it is currently owned by the given
     * thread.
     *
     * <p>Call this right after having killed or abandoned a thread that may have
     * been inside the sandbox (e.g. a test execution thread force-stopped with
     * {@code Thread.stop()}): such a thread never reaches
     * {@link #doneWithExecutingSUTCode()}, so its lock would otherwise stay
     * leaked until the next acquisition burns the whole timeout — and then every
     * later one does too. Reclaiming proactively turns a minutes-long stall into
     * a no-op.
     *
     * <p>Safe to call speculatively: it does nothing unless {@code lostOwner}
     * really is the recorded owner.
     *
     * @param lostOwner the thread that was killed or abandoned
     * @return true if a leaked lock was reclaimed
     */
    public static boolean reclaimSandboxLockOwnedBy(Thread lostOwner) {
        MSecurityManager current = manager;
        if (current == null || lostOwner == null) {
            return false;
        }
        return current.reclaimSandboxLockOwnedBy(lostOwner);
    }

    private static void restorePrivilegedThreadIfDowngraded() {
        Thread self = Thread.currentThread();
        int depth = downgradeDepthOf(self);
        if (depth <= 0) {
            return;
        }
        if (manager == null) {
            setDowngradeDepth(self, 0);
            return;
        }
        try {
            manager.doneWithExecutingUnsafeCodeOnSameThread();
        } catch (SecurityException | IllegalStateException e) {
            logger.debug("Ignoring sandbox privilege restore race: {}", e.getMessage());
        } finally {
            setDowngradeDepth(self, depth - 1);
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
        if (!isSecurityManagerInitialized() || isCurrentThreadAbandoned("goingToExecuteUnsafeCodeOnSameThread")) {
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
        if (!isSecurityManagerInitialized() || isCurrentThreadAbandoned("doneWithExecutingUnsafeCodeOnSameThread")) {
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
