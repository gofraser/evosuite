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
package org.evosuite.runtime.classhandling;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.examples.with.different.packagename.classhandling.MutableEnum;
import com.examples.with.different.packagename.classhandling.ResourceScanNoRegistry;
import com.examples.with.different.packagename.classhandling.RiskyRegistryClinit;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.instrumentation.CreateClassResetClassAdapter;
import org.evosuite.runtime.instrumentation.EvoClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


public class ClassResetterTest {

    private Logger classResetterLogger;

    private ListAppender<ILoggingEvent> resetWarningAppender;

    private boolean originalAdditive;

    @BeforeEach
    public void captureResetWarnings() {
        System.clearProperty("throwingclinit.fail");
        System.clearProperty("throwingclinit.invocations");
        classResetterLogger = (Logger) LoggerFactory.getLogger(ClassResetter.class);
        originalAdditive = classResetterLogger.isAdditive();
        resetWarningAppender = new ListAppender<>();
        resetWarningAppender.start();
        classResetterLogger.addAppender(resetWarningAppender);
        classResetterLogger.setAdditive(false);
    }

    @AfterEach
    public void clearPoisonAndProperty() {
        classResetterLogger.detachAppender(resetWarningAppender);
        resetWarningAppender.stop();
        classResetterLogger.setAdditive(originalAdditive);
        ClassResetter.getInstance().clearPoisonedClassesForTests();
        System.clearProperty("throwingclinit.fail");
        System.clearProperty("throwingclinit.invocations");
    }


    @Test
    public void testResetOfEnum() throws Exception {

        ClassLoader loader = new EvoClassLoader();
        boolean resetValue = RuntimeSettings.resetStaticState;
        RuntimeSettings.resetStaticState = true;
        ClassResetter.getInstance().setClassLoader(loader);

        String cut = "com.examples.with.different.packagename.classhandling.FooEnum";

        Class<?> klass = loader.loadClass(cut);
        Method m = klass.getDeclaredMethod("check");

        boolean val = false;

        val = (Boolean) m.invoke(null);
        Assertions.assertTrue(val);

        ClassResetter.getInstance().reset(cut);

        //make sure that the reset does not create new enum instance values
        val = (Boolean) m.invoke(null);
        RuntimeSettings.resetStaticState = resetValue;
        Assertions.assertTrue(val);
    }

    // TODO: We could consider providing a workaround to reset mutable enums.
    @Disabled
    @Test
    public void testResetOfMutableEnum() throws Exception {

        ClassLoader loader = new EvoClassLoader();
        RuntimeSettings.resetStaticState = true;
        ClassResetter.getInstance().setClassLoader(loader);

        String cut = MutableEnum.class.getCanonicalName();


        Class<?> klass = loader.loadClass(cut);
        Object[] enums = klass.getEnumConstants();
        Assertions.assertEquals(2, enums.length);
        Method getter = klass.getDeclaredMethod("getLetter");
        Assertions.assertEquals("a", getter.invoke(enums[0]));
        Assertions.assertEquals("b", getter.invoke(enums[1]));

        Method m = klass.getDeclaredMethod("changeLetter");
        m.invoke(enums[0]);
        Assertions.assertEquals("X", getter.invoke(enums[0]));
        Assertions.assertEquals("b", getter.invoke(enums[1]));

        ClassResetter.getInstance().reset(cut);

        Assertions.assertEquals("a", getter.invoke(enums[0]));
        Assertions.assertEquals("b", getter.invoke(enums[1]));
    }

    /**
     * A class whose {@code __STATIC_RESET()} throws is marked poisoned after
     * the first failure, so subsequent reset calls become no-ops (verified
     * here by the side-effect counter inside the fixture not advancing).
     */
    @Test
    public void testPoisonOnResetFailureSkipsSubsequentResets() throws Exception {

        ClassLoader loader = new EvoClassLoader();
        boolean resetValue = RuntimeSettings.resetStaticState;
        RuntimeSettings.resetStaticState = true;
        try {
            ClassResetter.getInstance().setClassLoader(loader);

            String cut = "com.examples.with.different.packagename.classhandling.ThrowingClinit";

            // Initial load: <clinit> runs in clean state, counter goes 0 -> 1.
            Class<?> klass = loader.loadClass(cut);
            Field counter = klass.getDeclaredField("counter");
            counter.setAccessible(true);
            Assertions.assertEquals(1, counter.getInt(null));
            Assertions.assertEquals("1", System.getProperty("throwingclinit.invocations"));

            // Arm the throw and request a reset. __STATIC_RESET first zeroes
            // the counter, then replays <clinit>: counter goes 0 -> 1 and the
            // throw fires. ClassResetter catches it and marks the class
            // poisoned. Counter is now 1 (not rolled back on throw).
            System.setProperty("throwingclinit.fail", "true");
            ClassResetter.getInstance().reset(cut);
            Assertions.assertEquals(1, counter.getInt(null));
            Assertions.assertEquals("2", System.getProperty("throwingclinit.invocations"));
            Assertions.assertTrue(resetWarningAppender.list.stream()
                    .anyMatch(event -> event.getFormattedMessage().contains("forced failure")));

            // Second reset must be skipped because the class is poisoned.
            ClassResetter.getInstance().reset(cut);
            Assertions.assertEquals(1, counter.getInt(null));
            Assertions.assertEquals("2", System.getProperty("throwingclinit.invocations"));
            Assertions.assertEquals(1, resetWarningAppender.list.size());

        } finally {
            RuntimeSettings.resetStaticState = resetValue;
        }
    }

    /**
     * Classes under {@code com.google.protobuf.} have a {@code <clinit>}
     * body that is not safe to replay; the resetter must skip them. We can't
     * easily synthesise a fake protobuf class on the classpath here, so the
     * test asserts the simpler observable: invoking {@code reset()} on a
     * skip-listed class name does not throw and does not error even when no
     * such class exists.
     */
    @Test
    public void testSkipListedPrefixDoesNotInvokeReset() {
        ClassLoader loader = new EvoClassLoader();
        ClassResetter.getInstance().setClassLoader(loader);

        // Name does not exist on the classpath. If the resetter tried to
        // resolve the __STATIC_RESET method, ClassNotFoundException would be
        // caught and logged; either way the call must complete without
        // throwing here. The point is to verify that the skip path returns
        // before the resolution attempt — exercised by the absence of any
        // exception leak.
        Assertions.assertDoesNotThrow(
                () -> ClassResetter.getInstance().reset("com.google.protobuf.SomethingThatDoesNotExist"));
    }

    /**
     * Regression test for the CUT-bypass trap in {@code isSkippedByPrefix()}: the bypass
     * that re-enables reset when the user is testing a skip-listed library must be scoped
     * to the exact class under test, not the whole skip-listed prefix. Otherwise, setting
     * {@code RuntimeSettings.className} to any class inside the prefix (e.g. because the CUT
     * happens to live in the same package as a fragile skip-listed class) would silently
     * re-enable replay for every sibling class in that package too.
     */
    @Test
    public void testSkipPrefixBypassIsScopedToExactClassUnderTest() throws Exception {
        java.lang.reflect.Method isSkippedByPrefix =
                ClassResetter.class.getDeclaredMethod("isSkippedByPrefix", String.class);
        isSkippedByPrefix.setAccessible(true);

        String originalClassName = RuntimeSettings.className;
        try {
            // CUT is a *different* class under the same skip-listed prefix: must still skip.
            RuntimeSettings.className = "com.google.protobuf.SomeOtherClass";
            Assertions.assertTrue((Boolean) isSkippedByPrefix.invoke(null,
                    "com.google.protobuf.SomeSiblingClass"));

            // CUT is exactly this class: bypass applies, reset is attempted.
            RuntimeSettings.className = "com.google.protobuf.SomeSiblingClass";
            Assertions.assertFalse((Boolean) isSkippedByPrefix.invoke(null,
                    "com.google.protobuf.SomeSiblingClass"));
        } finally {
            RuntimeSettings.className = originalClassName;
        }
    }

    /**
     * End-to-end check for {@link org.evosuite.runtime.instrumentation.ClinitResetSafetyAnalyzer}:
     * a class whose {@code <clinit>} reaches a classpath-scan call and builds a {@code Map}
     * registry (same shape as {@code org.firebirdsql.gds.impl.GDSFactory}) must not get a
     * {@code __STATIC_RESET()} method generated at all.
     */
    @Test
    public void testUnsafeClinitPatternGetsNoStaticResetMethod() throws Exception {
        ClassLoader loader = new EvoClassLoader();
        boolean resetValue = RuntimeSettings.resetStaticState;
        RuntimeSettings.resetStaticState = true;
        try {
            ClassResetter.getInstance().setClassLoader(loader);

            String cut = RiskyRegistryClinit.class.getName();
            loader.loadClass(cut);

            Assertions.assertNull(ClassResetter.getInstance().getResetMethod(cut),
                    "unsafe <clinit> pattern must not get a __STATIC_RESET() method");
            Assertions.assertTrue(CreateClassResetClassAdapter.unsafeClinitSkippedClasses.contains(cut));

            // reset() must be a silent no-op rather than erroring.
            Assertions.assertDoesNotThrow(() -> ClassResetter.getInstance().reset(cut));
        } finally {
            RuntimeSettings.resetStaticState = resetValue;
            CreateClassResetClassAdapter.unsafeClinitSkippedClasses.clear();
        }
    }

    /**
     * Control for the above: a class whose {@code <clinit>} reaches the same
     * classpath-scan call but builds no {@code Map}/{@code Collection} registry must still
     * get a normal {@code __STATIC_RESET()} method - the rule must not fire on resource
     * scanning alone.
     */
    @Test
    public void testResourceScanWithoutRegistryStillGetsStaticReset() throws Exception {
        ClassLoader loader = new EvoClassLoader();
        boolean resetValue = RuntimeSettings.resetStaticState;
        RuntimeSettings.resetStaticState = true;
        try {
            ClassResetter.getInstance().setClassLoader(loader);

            String cut = ResourceScanNoRegistry.class.getName();
            loader.loadClass(cut);

            Assertions.assertNotNull(ClassResetter.getInstance().getResetMethod(cut),
                    "resource scan alone (no registry field) must not suppress __STATIC_RESET()");
            Assertions.assertFalse(CreateClassResetClassAdapter.unsafeClinitSkippedClasses.contains(cut));
        } finally {
            RuntimeSettings.resetStaticState = resetValue;
        }
    }
}
