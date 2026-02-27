/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.assertion.stable;

import com.examples.with.different.packagename.stable.RandomUser;
import com.examples.with.different.packagename.stable.RandomUUIDUser;
import com.examples.with.different.packagename.stable.ResetOrderClassA;
import com.examples.with.different.packagename.stable.ResourceLoaderUser;
import com.examples.with.different.packagename.stable.SecureRandomUser;
import com.examples.with.different.packagename.stable.SingletonUser;
import com.examples.with.different.packagename.stable.FinalSingletonUser;
import com.examples.with.different.packagename.stable.HashCodeClassInit;
import com.examples.with.different.packagename.stable.NoClinit;
import com.examples.with.different.packagename.stable.HasClinit;
import com.examples.with.different.packagename.stable.TypeErasure;
import com.examples.with.different.packagename.stable.MapContainerUser;
import com.examples.with.different.packagename.stable.ListContainerUser;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.statistics.RuntimeVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class StableAssertionModeMatrixSystemTest extends SystemTestBase {

    private enum Mode {
        LEGACY_JUNIT4_SCAFFOLDING,
        JUNIT5_LEGACY_RUNNER,
        JUNIT5_EXTENSION_MODE
    }

    private static final List<Class<?>> TARGETS = Arrays.asList(
            RandomUser.class,
            ResetOrderClassA.class,
            ResourceLoaderUser.class,
            SingletonUser.class,
            FinalSingletonUser.class,
            HashCodeClassInit.class,
            NoClinit.class,
            HasClinit.class,
            RandomUUIDUser.class,
            SecureRandomUser.class,
            TypeErasure.class,
            MapContainerUser.class,
            ListContainerUser.class
    );

    private final Properties.OutputFormat defaultTestFormat = Properties.TEST_FORMAT;
    private final boolean defaultTestScaffolding = Properties.TEST_SCAFFOLDING;
    private final boolean defaultTestExtensionMode = Properties.TEST_EXTENSION_MODE;
    private final boolean defaultNoRuntimeDependency = Properties.NO_RUNTIME_DEPENDENCY;
    private final boolean defaultSandbox = Properties.SANDBOX;
    private final boolean defaultResetStaticFields = Properties.RESET_STATIC_FIELDS;
    private final boolean defaultReplaceCalls = Properties.REPLACE_CALLS;
    private final Properties.JUnitCheckValues defaultJunitCheck = Properties.JUNIT_CHECK;
    private final boolean defaultJunitTests = Properties.JUNIT_TESTS;
    private final boolean defaultPureInspectors = Properties.PURE_INSPECTORS;
    private final boolean defaultJunitCheckOnSeparateProcess = Properties.JUNIT_CHECK_ON_SEPARATE_PROCESS;
    private final String defaultOutputVariables = Properties.OUTPUT_VARIABLES;
    private final boolean defaultUseSeparateClassLoader = Properties.USE_SEPARATE_CLASSLOADER;
    private final String defaultJunitSuffix = Properties.JUNIT_SUFFIX;

    @AfterEach
    public void restoreModeProperties() {
        Properties.TEST_FORMAT = defaultTestFormat;
        Properties.TEST_SCAFFOLDING = defaultTestScaffolding;
        Properties.TEST_EXTENSION_MODE = defaultTestExtensionMode;
        Properties.NO_RUNTIME_DEPENDENCY = defaultNoRuntimeDependency;
        Properties.SANDBOX = defaultSandbox;
        Properties.RESET_STATIC_FIELDS = defaultResetStaticFields;
        Properties.REPLACE_CALLS = defaultReplaceCalls;
        Properties.JUNIT_CHECK = defaultJunitCheck;
        Properties.JUNIT_TESTS = defaultJunitTests;
        Properties.PURE_INSPECTORS = defaultPureInspectors;
        Properties.JUNIT_CHECK_ON_SEPARATE_PROCESS = defaultJunitCheckOnSeparateProcess;
        Properties.OUTPUT_VARIABLES = defaultOutputVariables;
        Properties.USE_SEPARATE_CLASSLOADER = defaultUseSeparateClassLoader;
        Properties.JUNIT_SUFFIX = defaultJunitSuffix;
    }

    @ParameterizedTest(name = "{index} => mode={0}, target={1}")
    @MethodSource("modeTargetMatrix")
    public void shouldKeepStableAssertionSignalAcrossOutputModes(Mode mode, Class<?> target) {
        String context = mode + " / " + target.getSimpleName();
        configureCommonStableAssertionProperties();
        configureMode(mode);

        EvoSuite evosuite = new EvoSuite();
        String targetClass = target.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        try {
            Object result = evosuite.parseCommandLine(command);
            Assertions.assertNotNull(result, "No result returned for " + context);
            checkUnstable();
        } catch (AssertionError | RuntimeException e) {
            throw new AssertionError("Mode matrix failed for " + context + ": " + e.getMessage(), e);
        }
    }

    private static Stream<Arguments> modeTargetMatrix() {
        return Arrays.stream(Mode.values())
                .flatMap(mode -> TARGETS.stream().map(target -> Arguments.of(mode, target)));
    }

    private static void configureCommonStableAssertionProperties() {
        Properties.SANDBOX = true;
        Properties.RESET_STATIC_FIELDS = true;
        Properties.REPLACE_CALLS = true;
        Properties.JUNIT_CHECK = Properties.JUnitCheckValues.TRUE;
        Properties.JUNIT_TESTS = true;
        Properties.PURE_INSPECTORS = true;
        Properties.JUNIT_CHECK_ON_SEPARATE_PROCESS = false;
        Properties.OUTPUT_VARIABLES = RuntimeVariable.HadUnstableTests.toString();
        Properties.JUNIT_SUFFIX = "_ESTest";
    }

    private static void configureMode(Mode mode) {
        Properties.NO_RUNTIME_DEPENDENCY = false;
        switch (mode) {
            case LEGACY_JUNIT4_SCAFFOLDING:
                Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
                Properties.TEST_SCAFFOLDING = true;
                Properties.TEST_EXTENSION_MODE = false;
                Properties.USE_SEPARATE_CLASSLOADER = true;
                break;
            case JUNIT5_LEGACY_RUNNER:
                Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
                Properties.TEST_SCAFFOLDING = true;
                Properties.TEST_EXTENSION_MODE = false;
                Properties.USE_SEPARATE_CLASSLOADER = false;
                break;
            case JUNIT5_EXTENSION_MODE:
                Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
                Properties.TEST_SCAFFOLDING = false;
                Properties.TEST_EXTENSION_MODE = true;
                Properties.USE_SEPARATE_CLASSLOADER = false;
                break;
            default:
                throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
    }
}
