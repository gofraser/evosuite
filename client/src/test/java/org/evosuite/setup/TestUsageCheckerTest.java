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
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

class TestUsageCheckerTest {

    private final boolean defaultUseVFS = RuntimeSettings.useVFS;
    private final boolean defaultUseVNET = RuntimeSettings.useVNET;

    @AfterEach
    void restoreRuntimeSettings() {
        RuntimeSettings.useVFS = defaultUseVFS;
        RuntimeSettings.useVNET = defaultUseVNET;
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
}
