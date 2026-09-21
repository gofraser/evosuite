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
package org.evosuite.runtime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;

/**
 * Verifies that {@link ReflectionAccess} reproduces, without the client JVM's
 * {@code --add-opens}/{@code --add-exports} flags, the same allow/deny answer a stock
 * JVM would give for {@code setAccessible}/{@code trySetAccessible}. Several of the
 * "denied" cases below target packages that {@code runtime/pom.xml}'s surefire
 * {@code argLine} qualified-opens to {@code ALL-UNNAMED} (java.lang, java.net) --
 * a raw {@code field.setAccessible(true)} call would succeed right here in this test
 * JVM, which is exactly the gap this class closes: a qualified opens/exports is
 * invisible to the one-arg {@code Module#isOpen}/{@code isExported} this class uses,
 * so the emulated answer still matches what Defects4J's stock test JVM would do.
 */
public class ReflectionAccessTest {

    /**
     * This JVM was started by surefire with {@code --add-opens} for java.lang, java.util and
     * java.net (see the root pom). Play the master: declare those as EvoSuite's private
     * opens, so they must not count, exactly as in a client JVM.
     */
    @BeforeAll
    public static void declarePrivateOpens() {
        java.lang.System.setProperty(ReflectionAccess.PRIVATE_ACCESS_PROPERTY,
                "java.base/java.lang,java.base/java.util,java.base/java.net");
        ReflectionAccess.reloadPrivateAccess();
    }

    @AfterAll
    public static void clearPrivateOpens() {
        java.lang.System.clearProperty(ReflectionAccess.PRIVATE_ACCESS_PROPERTY);
        ReflectionAccess.reloadPrivateAccess();
    }

    @Test
    public void testPackageOpenedByTheUserIsHonoured() throws Exception {
        // java.util is opened to this JVM by surefire; once it is no longer declared
        // private it is the user's, and a stock JVM started the same way would allow it.
        Assumptions.assumeTrue(Object.class.getModule().isOpen("java.util", ReflectionAccessTest.class.getModule()));
        Field comparator = java.util.TreeMap.class.getDeclaredField("comparator");
        java.lang.System.setProperty(ReflectionAccess.PRIVATE_ACCESS_PROPERTY, "java.base/java.lang");
        ReflectionAccess.reloadPrivateAccess();
        try {
            Assertions.assertDoesNotThrow(() -> ReflectionAccess.setAccessible(comparator, true));
            Assertions.assertTrue(ReflectionAccess.trySetAccessible(comparator));
        } finally {
            declarePrivateOpens();
        }
    }

    @Test
    public void testPackageOpenedOnlyForEvoSuiteIsDenied() throws Exception {
        Assumptions.assumeTrue(Object.class.getModule().isOpen("java.util", ReflectionAccessTest.class.getModule()));
        Field comparator = java.util.TreeMap.class.getDeclaredField("comparator");

        Assertions.assertThrows(InaccessibleObjectException.class,
                () -> ReflectionAccess.setAccessible(comparator, true));
        Assertions.assertFalse(ReflectionAccess.trySetAccessible(comparator));
    }

    @Test
    public void testProtectedConstructorOfPublicJdkClassIsDenied() throws Exception {
        Constructor<?> ctor = AccessibleObject.class.getDeclaredConstructor();

        InaccessibleObjectException e = Assertions.assertThrows(InaccessibleObjectException.class,
                () -> ReflectionAccess.setAccessible(ctor, true));
        Assertions.assertEquals("java.lang.reflect.AccessibleObject", e.getStackTrace()[0].getClassName(),
                "verifyException(...) in generated tests keys on the top stack frame's class");
    }

    @Test
    public void testPrivateFieldOfPublicJdkClassInNonExportedContextIsDenied() throws Exception {
        Field field = java.net.URL.class.getDeclaredField("hostAddress");

        Assertions.assertThrows(InaccessibleObjectException.class,
                () -> ReflectionAccess.setAccessible(field, true));
    }

    @Test
    public void testPrivateFieldOfJavaLangClassIsDenied() throws Exception {
        Field field = String.class.getDeclaredField("value");

        Assertions.assertThrows(InaccessibleObjectException.class,
                () -> ReflectionAccess.setAccessible(field, true));
    }

    @Test
    public void testPublicMethodOfObjectIsAllowed() throws Exception {
        Method hashCode = Object.class.getMethod("hashCode");

        Assertions.assertDoesNotThrow(() -> ReflectionAccess.setAccessible(hashCode, true));
    }

    @Test
    public void testPublicMethodOfPublicClassInExportedPackageIsAllowed() throws Exception {
        Method size = java.util.ArrayList.class.getMethod("size");

        Assertions.assertDoesNotThrow(() -> ReflectionAccess.setAccessible(size, true));
    }

    @Test
    public void testMemberOfUnnamedModuleClassIsAlwaysAllowed() throws Exception {
        // unnamed module: always allowed
        Field field = Fixture.class.getDeclaredField("secret");

        Assertions.assertDoesNotThrow(() -> ReflectionAccess.setAccessible(field, true));
        Assertions.assertTrue(field.isAccessible());
    }

    @Test
    public void testSetAccessibleFalseIsAlwaysAllowed() throws Exception {
        Constructor<?> ctor = AccessibleObject.class.getDeclaredConstructor();

        Assertions.assertDoesNotThrow(() -> ReflectionAccess.setAccessible(ctor, false));
    }

    @Test
    public void testTrySetAccessibleReturnsFalseWhenDenied() throws Exception {
        Field field = java.net.URL.class.getDeclaredField("hostAddress");

        Assertions.assertFalse(ReflectionAccess.trySetAccessible(field));
    }

    @Test
    public void testTrySetAccessibleReturnsTrueWhenAllowed() throws Exception {
        Method hashCode = Object.class.getMethod("hashCode");

        Assertions.assertTrue(ReflectionAccess.trySetAccessible(hashCode));
    }

    @Test
    public void testDenialMessageFormat() throws Exception {
        Field field = java.net.URL.class.getDeclaredField("hostAddress");

        InaccessibleObjectException e = Assertions.assertThrows(InaccessibleObjectException.class,
                () -> ReflectionAccess.setAccessible(field, true));

        Assertions.assertTrue(e.getMessage().startsWith("Unable to make field " + field + " accessible: "),
                e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("module java.base does not \"opens java.net\" to "),
                e.getMessage());
    }

    @Test
    public void testArrayVariantValidatesEveryElementBeforeSettingAny() throws Exception {
        Method allowed = Object.class.getMethod("hashCode");
        Field denied = java.net.URL.class.getDeclaredField("hostAddress");

        Assertions.assertThrows(InaccessibleObjectException.class,
                () -> ReflectionAccess.setAccessible(new AccessibleObject[]{allowed, denied}, true));

        // Real JDK behaviour (verified against JDK 17 source and empirically): the array
        // overload checks every element before flipping any of them, so the earlier,
        // otherwise-allowed element must be left untouched once a later one is denied.
        Assertions.assertFalse(allowed.isAccessible());
    }

    public static class Fixture {
        private int secret;
    }
}
