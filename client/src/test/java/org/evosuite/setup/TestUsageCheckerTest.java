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
package org.evosuite.setup;

import com.examples.with.different.packagename.EnumWithUserMethodsFixture;
import com.examples.with.different.packagename.PureEnumFixture;
import org.evosuite.Properties;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

class TestUsageCheckerTest {

    private final boolean defaultUseVFS = RuntimeSettings.useVFS;
    private final boolean defaultUseVNET = RuntimeSettings.useVNET;
    private final String defaultClassPrefix = Properties.CLASS_PREFIX;
    private final String defaultTargetClass = Properties.TARGET_CLASS;

    private static class CompilerAccessorNameFallbackFixture {
        static void access$600() {
            // simulate an accessor whose synthetic bit was stripped by instrumentation
        }

        public static void userVisibleMethod() {
            // no-op
        }
    }

    @AfterEach
    void restoreRuntimeSettings() {
        RuntimeSettings.useVFS = defaultUseVFS;
        RuntimeSettings.useVNET = defaultUseVNET;
        Properties.CLASS_PREFIX = defaultClassPrefix;
        Properties.TARGET_CLASS = defaultTargetClass;
    }

    @Test
    void testEnvironmentDataClassUsableWhenVfsEnabled() {
        RuntimeSettings.useVFS = true;
        RuntimeSettings.useVNET = false;

        Assertions.assertTrue(TestUsageChecker.canUse(EvoSuiteFile.class));
    }

    @Test
    void testEnvironmentDataClassNotUsableWhenVfsDisabled() {
        RuntimeSettings.useVFS = false;
        RuntimeSettings.useVNET = false;

        Assertions.assertFalse(TestUsageChecker.canUse(EvoSuiteFile.class));
    }

    @Test
    void testCompilerGeneratedEnumValuesExcluded() throws NoSuchMethodException {
        Method values = PureEnumFixture.class.getDeclaredMethod("values");

        Assertions.assertTrue(TestUsageChecker.isCompilerGeneratedEnumMethod(values));
        Assertions.assertFalse(TestUsageChecker.canUse(values, PureEnumFixture.class));
    }

    @Test
    void testCompilerGeneratedEnumValueOfExcluded() throws NoSuchMethodException {
        Method valueOf = PureEnumFixture.class.getDeclaredMethod("valueOf", String.class);

        Assertions.assertTrue(TestUsageChecker.isCompilerGeneratedEnumMethod(valueOf));
        Assertions.assertFalse(TestUsageChecker.canUse(valueOf, PureEnumFixture.class));
    }

    @Test
    void testCustomEnumMethodsRemainUsable() throws NoSuchMethodException {
        Method customFactory = EnumWithUserMethodsFixture.class.getDeclaredMethod("value", int.class);
        Method customValue = EnumWithUserMethodsFixture.class.getDeclaredMethod("customValue");

        Assertions.assertFalse(TestUsageChecker.isCompilerGeneratedEnumMethod(customFactory));
        Assertions.assertFalse(TestUsageChecker.isCompilerGeneratedEnumMethod(customValue));
        Assertions.assertTrue(TestUsageChecker.canUse(customFactory, EnumWithUserMethodsFixture.class));
        Assertions.assertTrue(TestUsageChecker.canUse(customValue, EnumWithUserMethodsFixture.class));
    }

    @Test
    void testCompilerAccessorNameFallbackExcludedWhenSyntheticBitMissing() throws NoSuchMethodException {
        Properties.CLASS_PREFIX = "org.evosuite.setup";
        Properties.TARGET_CLASS = CompilerAccessorNameFallbackFixture.class.getName();

        Method accessor = CompilerAccessorNameFallbackFixture.class.getDeclaredMethod("access$600");
        Method userVisibleMethod = CompilerAccessorNameFallbackFixture.class.getDeclaredMethod("userVisibleMethod");

        Assertions.assertTrue(TestUsageChecker.isCompilerGeneratedAccessorMethod(accessor));
        Assertions.assertFalse(TestUsageChecker.canUse(accessor, CompilerAccessorNameFallbackFixture.class));
        Assertions.assertFalse(TestUsageChecker.isCompilerGeneratedAccessorMethod(userVisibleMethod));
        Assertions.assertTrue(TestUsageChecker.canUse(userVisibleMethod, CompilerAccessorNameFallbackFixture.class));
    }
}
