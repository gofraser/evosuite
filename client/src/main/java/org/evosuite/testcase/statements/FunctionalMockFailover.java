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
package org.evosuite.testcase.statements;

import org.evosuite.Properties;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.variable.VariableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide state that {@link FunctionalMockStatement} accumulates while mocks fail to be
 * created or turn out to be of the wrong type: the classes that cannot be mocked, the global
 * switch-off of functional mocking, and the mock types that were upgraded after a failed cast.
 */
public final class FunctionalMockFailover {

    private static final Logger logger = LoggerFactory.getLogger(FunctionalMockFailover.class);
    private static final Set<String> unmockableTargetClasses = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean functionalMockingGloballyDisabled = new AtomicBoolean(false);
    private static final AtomicInteger functionalMockingAttempts = new AtomicInteger(0);
    private static final AtomicInteger functionalMockingInitFailures = new AtomicInteger(0);

    /**
     * Maps a declared mock type to a more specific type that should be used instead.
     * Populated at runtime when a ClassCastException reveals that the SUT downcasts
     * a mock parameter (e.g., Graphics → Graphics2D).
     */
    private static final ConcurrentHashMap<String, String> mockTypeUpgrades = new ConcurrentHashMap<>();

    /**
     * Registers a mock type upgrade: future mocks of {@code declaredType} should
     * use {@code targetType} instead (because the SUT casts to it).
     */
    public static void registerMockTypeUpgrade(String declaredType, String targetType) {
        if (mockTypeUpgrades.putIfAbsent(declaredType, targetType) == null) {
            logger.debug("Registered mock type upgrade: {} → {}", declaredType, targetType);
        }
    }

    /**
     * Returns the upgraded mock type for the given declared type, or null if
     * no upgrade is registered.
     */
    public static String getMockTypeUpgrade(String declaredType) {
        return mockTypeUpgrades.get(declaredType);
    }

    /**
     * Detects when a ClassCastException was caused by a mock being the wrong
     * type, and registers an upgrade so future mocks use the cast target type.
     *
     * <p>For example, if {@code mock(Graphics.class)} is passed to SUT code
     * that casts to {@code Graphics2D}, this registers
     * {@code Graphics → Graphics2D} so subsequent mocks use Graphics2D.</p>
     *
     * @param e    the ClassCastException
     * @param s    the statement that was executing when the exception occurred
     * @param tc   the test case
     */
    public static void detectMockTypeUpgrade(ClassCastException e, Statement s, TestCase tc) {
        try {
            String targetType = parseCastTargetType(e);
            if (targetType == null) {
                return;
            }

            if (!(s instanceof EntityWithParametersStatement)) {
                return;
            }
            for (VariableReference ref :
                    ((EntityWithParametersStatement) s).getParameterReferences()) {
                Statement defStmt = tc.getStatement(ref.getStPosition());
                if (!(defStmt instanceof FunctionalMockStatement)) {
                    continue;
                }
                FunctionalMockStatement mock = (FunctionalMockStatement) defStmt;
                Class<?> mockClass = mock.getTargetClass();
                try {
                    Class<?> castTarget = Class.forName(targetType, false,
                            mockClass.getClassLoader());
                    if (mockClass.isAssignableFrom(castTarget)) {
                        registerMockTypeUpgrade(mockClass.getName(), targetType);
                    }
                } catch (ClassNotFoundException expected) {
                }
            }
        } catch (Exception ex) {
            logger.debug("Failed to detect mock type upgrade", ex);
        }
    }

    /**
     * Parses the cast target type from a ClassCastException message.
     * Returns null if the exception doesn't involve a Mockito mock.
     */
    private static String parseCastTargetType(ClassCastException e) {
        String msg = e.getMessage();
        if (msg == null || !msg.contains("MockitoMock")) {
            return null;
        }
        // JDK 11+: "... cannot be cast to class java.awt.Graphics2D (module info...)"
        int idx = msg.indexOf("cannot be cast to class ");
        if (idx >= 0) {
            String after = msg.substring(idx + "cannot be cast to class ".length());
            int end = indexOfAny(after, ' ', '(');
            return end > 0 ? after.substring(0, end) : after.trim();
        }
        // Older JDKs: "... cannot be cast to java.awt.Graphics2D"
        idx = msg.indexOf("cannot be cast to ");
        if (idx >= 0) {
            return msg.substring(idx + "cannot be cast to ".length()).trim();
        }
        return null;
    }

    private static int indexOfAny(String s, char... chars) {
        int min = -1;
        for (char c : chars) {
            int idx = s.indexOf(c);
            if (idx >= 0 && (min < 0 || idx < min)) {
                min = idx;
            }
        }
        return min;
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> expectedType) {
        Throwable current = throwable;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    /**
     * True if the JVM's module system refused the reflective access Mockito needs to create the
     * mock (java.lang.reflect.InaccessibleObjectException, a RuntimeException since Java 9). The
     * module setup of the JVM running the search, not EvoSuite's own state, decides this, so it
     * ends the mock attempt rather than the whole generation. The type is matched by name because
     * this module is compiled for Java 8.
     */
    static boolean isMockAccessRefusal(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if ("java.lang.reflect.InaccessibleObjectException".equals(current.getClass().getName())) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    static boolean isCodeUnderTestInitializationFailure(Throwable throwable) {
        return hasCause(throwable, NoClassDefFoundError.class)
                || hasCause(throwable, ExceptionInInitializerError.class)
                || hasCause(throwable, ClassNotFoundException.class)
                || hasCause(throwable, LinkageError.class);
    }

    private static boolean shouldDisableFunctionalMockingGlobally(int attempts, int failures) {
        // Only the GLOBAL mode triggers the global disable.  CLASS mode only
        // blacklists individual problematic classes — it must NOT disable mocking
        // globally, because that prevents mocking of unrelated classes that work
        // fine (e.g., MainFrame mock for a JDialog-extending CUT).
        if (Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE != Properties.FunctionalMockingFailoverMode.GLOBAL) {
            return false;
        }
        if (attempts <= 0 || failures < Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT) {
            return false;
        }
        double ratio = ((double) failures) / ((double) attempts);
        return ratio >= Properties.FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO;
    }

    private static boolean isClassLevelFailoverEnabled() {
        return Properties.FUNCTIONAL_MOCKING_FAILOVER_MODE == Properties.FunctionalMockingFailoverMode.CLASS;
    }

    static boolean isClassBlacklisted(String className) {
        return unmockableTargetClasses.contains(className);
    }

    /**
     * @return true once the GLOBAL mock failover has permanently disabled functional mocking
     */
    public static boolean isFunctionalMockingGloballyDisabled() {
        return functionalMockingGloballyDisabled.get();
    }

    static void registerMockAttempt() {
        functionalMockingAttempts.incrementAndGet();
    }

    static void handleRecoverableMockFailure(String className, Throwable failure)
            throws CodeUnderTestException {
        int failures = functionalMockingInitFailures.incrementAndGet();
        int attempts = functionalMockingAttempts.get();

        if (isClassLevelFailoverEnabled() && unmockableTargetClasses.add(className)) {
            AtMostOnceLogger.warn(logger,
                    "Disabling functional mocking for class " + className
                            + " due to initialization failure: "
                            + failure.getClass().getSimpleName());
        }

        if (shouldDisableFunctionalMockingGlobally(attempts, failures)
                && functionalMockingGloballyDisabled.compareAndSet(false, true)) {
            Properties.P_FUNCTIONAL_MOCKING = 0.0;
            Properties.MOCK_IF_NO_GENERATOR = false;
            AtMostOnceLogger.warn(logger,
                    "Disabling functional mocking globally after " + failures + "/" + attempts
                            + " initialization failures");
        }

        throw new CodeUnderTestException(failure);
    }

    /** Forgets every class, counter and switch recorded so far. */
    public static void reset() {
        unmockableTargetClasses.clear();
        functionalMockingGloballyDisabled.set(false);
        functionalMockingAttempts.set(0);
        functionalMockingInitFailures.set(0);
    }

    private FunctionalMockFailover() {
    }
}
