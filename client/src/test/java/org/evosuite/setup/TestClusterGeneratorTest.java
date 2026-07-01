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

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;

public class TestClusterGeneratorTest {

    private static final boolean defaultVFS = RuntimeSettings.useVFS;
    private static final boolean defaultHandleStaticFields = Properties.HANDLE_STATIC_FIELDS;
    private static final String defaultTargetClass = Properties.TARGET_CLASS;
    private static final String defaultTargetClassPrefix = Properties.TARGET_CLASS_PREFIX;
    private static final String defaultClassPrefix = Properties.CLASS_PREFIX;

    @AfterEach
    public void tearDown() {
        RuntimeSettings.useVFS = defaultVFS;
        Properties.HANDLE_STATIC_FIELDS = defaultHandleStaticFields;
        Properties.TARGET_CLASS = defaultTargetClass;
        Properties.TARGET_CLASS_PREFIX = defaultTargetClassPrefix;
        Properties.CLASS_PREFIX = defaultClassPrefix;
        TestGenerationContext.getInstance().setAssertionGenerationContext(false);
    }

    @Test
    public void test_checkIfCanUse_noVFS() {

        RuntimeSettings.useVFS = false;
        boolean canUse = TestClusterUtils.checkIfCanUse(File.class.getCanonicalName());
        Assertions.assertTrue(canUse);
    }

    @Test
    public void test_checkIfCanUse_withVFS() {

        RuntimeSettings.useVFS = true;
        boolean canUse = TestClusterUtils.checkIfCanUse(File.class.getCanonicalName());
        Assertions.assertTrue(canUse);
    }

    @Test
    public void test_checkIfCanUse_blocksJdkInternalPackages() {
        RuntimeSettings.useVFS = false;
        Assertions.assertFalse(TestClusterUtils.checkIfCanUse("jdk.internal.misc.Unsafe"));
        Assertions.assertFalse(TestClusterUtils.checkIfCanUse("jdk.tools.jlink.internal.plugins.ResourceFilter"));
    }

    @Test
    public void test_checkIfCanUse_allowsRegularJdkApis() {
        RuntimeSettings.useVFS = false;
        Assertions.assertTrue(TestClusterUtils.checkIfCanUse("java.util.ArrayList"));
    }

    @Test
    public void test_checkIfCanUse_allowsConfiguredComAppleTargetPackage() {
        Properties.TARGET_CLASS = "com.apple.spark.util.VersionInfo";
        Properties.CLASS_PREFIX = "com.apple.spark.util";
        Properties.TARGET_CLASS_PREFIX = "";

        Assertions.assertTrue(TestClusterUtils.checkIfCanUse("com.apple.spark.util.VersionInfo"));
        Assertions.assertTrue(TestClusterUtils.checkIfCanUse("com.apple.spark.util.Helper"));
        Assertions.assertFalse(TestClusterUtils.checkIfCanUse("com.apple.other.Helper"));
    }

    @Test
    public void test_shouldHandleStaticFields_skipsDuringAssertionGenerationReload() {
        Properties.HANDLE_STATIC_FIELDS = true;
        TestGenerationContext.getInstance().setAssertionGenerationContext(true);

        Assertions.assertFalse(TestClusterGenerator.shouldHandleStaticFields());
    }

    @Test
    public void test_shouldHandleStaticFields_respectsPropertyOutsideAssertionGenerationReload() {
        TestGenerationContext.getInstance().setAssertionGenerationContext(false);

        Properties.HANDLE_STATIC_FIELDS = true;
        Assertions.assertTrue(TestClusterGenerator.shouldHandleStaticFields());

        Properties.HANDLE_STATIC_FIELDS = false;
        Assertions.assertFalse(TestClusterGenerator.shouldHandleStaticFields());
    }
}
