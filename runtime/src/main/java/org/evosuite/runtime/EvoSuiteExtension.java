/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.runtime;

import org.evosuite.runtime.agent.InstrumentingAgent;
import org.evosuite.runtime.classhandling.ClassResetter;
import org.evosuite.runtime.classhandling.ClassStateSupport;
import org.evosuite.runtime.classhandling.JDKClassResetter;
import org.evosuite.runtime.jvm.ShutdownHookHandler;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.thread.KillSwitchHandler;
import org.evosuite.runtime.thread.ThreadStopper;
import org.evosuite.runtime.util.JOptionPaneInputs;
import org.evosuite.runtime.util.SystemInUtil;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Experimental JUnit 5 extension entry point for EvoSuite-generated tests.
 * <p>
 * This extension centralizes runtime lifecycle handling and avoids metadata-file
 * based initialization by inferring reset/init candidates from test class names.
 */
public class EvoSuiteExtension implements TestInstanceFactory, BeforeAllCallback, BeforeEachCallback,
        AfterEachCallback, AfterAllCallback {

    private static final Logger logger = LoggerFactory.getLogger(EvoSuiteExtension.class);
    static final String EAGER_GUI_INIT_PROPERTY = "evosuite.extension.eager_gui_init";
    private static final Pattern ESTEST_PATTERN = Pattern.compile("(.+)_ESTest(?:_\\d+)?");
    private static final Pattern FAILED_ESTEST_PATTERN = Pattern.compile("(.+)_Failed_ESTest(?:_\\d+)?");

    private final Class<?> testClass;
    private final ThreadStopper threadStopper;
    private final String[] classesToInitialize;

    public EvoSuiteExtension(Class<?> testClass) {
        this(testClass, new String[0]);
    }

    public EvoSuiteExtension(Class<?> testClass, String... explicitClassesToInitialize) {
        this.testClass = testClass;
        configureRuntimeSettings(testClass);
        this.threadStopper = new ThreadStopper(KillSwitchHandler.getInstance(), 5_000);
        this.classesToInitialize = resolveClassesToInitialize(testClass, explicitClassesToInitialize);
    }

    @Override
    public Object createTestInstance(TestInstanceFactoryContext factoryContext,
                                     ExtensionContext extensionContext) throws TestInstantiationException {
        Class<?> clazz = factoryContext.getTestClass();
        if (RuntimeSettings.useSeparateClassLoader) {
            throw new TestInstantiationException("Could not instantiate the class under test with a EvoClassLoader");
        }
        try {
            return clazz.getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                 | NoSuchMethodException e) {
            throw new TestInstantiationException("Could not instantiate test class", e);
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        RuntimeSettings.className = inferTargetClassName(testClass);
        maybeInitializeGui();
        if (needsAgentLifecycle()) {
            try {
                InstrumentingAgent.initialize();
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Failed to initialize instrumentation agent in EvoSuiteExtension", e);
            }
        }
        if (RuntimeSettings.resetStaticState) {
            Sandbox.initializeSecurityManagerForSUT();
            JDKClassResetter.init();
            initializeDiscoveredClasses();
        }
        Runtime.getInstance().resetRuntime();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        threadStopper.storeCurrentThreads();
        threadStopper.startRecordingTime();
        if (RuntimeSettings.mockJVMNonDeterminism) {
            ShutdownHookHandler.getInstance().initHandler();
        }
        if (RuntimeSettings.resetStaticState) {
            Sandbox.goingToExecuteSUTCode();
        }
        Runtime.getInstance().resetRuntime();
        if (needsAgentLifecycle()) {
            InstrumentingAgent.activate();
        }
        SystemInUtil.getInstance().initForTestCase();
        JOptionPaneInputs.getInstance().initForTestCase();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        RuntimeException failure = null;
        try {
            threadStopper.killAndJoinClientThreads();
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            if (RuntimeSettings.mockJVMNonDeterminism) {
                failure = recordFailureOrSuppress(failure, () -> ShutdownHookHandler.getInstance().safeExecuteAddedHooks());
            }
            if (RuntimeSettings.resetStaticState) {
                failure = recordFailureOrSuppress(failure, JDKClassResetter::reset);
                failure = recordFailureOrSuppress(failure, this::resetDiscoveredClasses);
                failure = recordFailureOrSuppress(failure, Sandbox::doneWithExecutingSUTCode);
            }
            if (needsAgentLifecycle()) {
                failure = recordFailureOrSuppress(failure, InstrumentingAgent::deactivate);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (RuntimeSettings.resetStaticState) {
            Sandbox.resetDefaultSecurityManager();
        }
    }

    static Set<String> discoverClassesToInitialize(Class<?> testClass) {
        Set<String> discovered = new LinkedHashSet<>();
        String inferred = inferTargetClassName(testClass);
        if (!"unknown".equals(inferred)) {
            discovered.add(inferred);
        }
        return discovered;
    }

    static String[] resolveClassesToInitialize(Class<?> testClass, String... explicitClassesToInitialize) {
        if (explicitClassesToInitialize != null && explicitClassesToInitialize.length > 0) {
            return explicitClassesToInitialize.clone();
        }
        return discoverClassesToInitialize(testClass).toArray(new String[0]);
    }

    static String inferTargetClassName(Class<?> testClass) {
        String fqcn = testClass.getName();
        Matcher matcher = FAILED_ESTEST_PATTERN.matcher(fqcn);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        matcher = ESTEST_PATTERN.matcher(fqcn);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return RuntimeSettings.className != null ? RuntimeSettings.className : "unknown";
    }

    private void initializeDiscoveredClasses() {
        if (classesToInitialize.length == 0) {
            return;
        }
        ClassStateSupport.initializeClasses(testClass.getClassLoader(), classesToInitialize);
    }

    private void resetDiscoveredClasses() {
        if (classesToInitialize.length == 0) {
            return;
        }
        ClassResetter.getInstance().setClassLoader(testClass.getClassLoader());
        ClassStateSupport.resetClasses(classesToInitialize);
    }

    private boolean needsAgentLifecycle() {
        return RuntimeSettings.isUsingAnyMocking() || RuntimeSettings.resetStaticState;
    }

    private static RuntimeException recordFailureOrSuppress(RuntimeException failure, Runnable action) {
        try {
            action.run();
            return failure;
        } catch (RuntimeException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
            return failure;
        }
    }

    private void maybeInitializeGui() {
        if (!shouldInitializeGui()) {
            return;
        }
        try {
            GuiSupport.initialize();
        } catch (Throwable t) {
            logger.warn("Could not eagerly initialize GUI support in EvoSuiteExtension: {}", t.getMessage());
        }
    }

    static boolean shouldInitializeGui() {
        return RuntimeSettings.mockGUI || Boolean.getBoolean(EAGER_GUI_INIT_PROPERTY);
    }

    private static void configureRuntimeSettings(Class<?> testClass) {
        EvoRunnerParameters parameters = testClass.getAnnotation(EvoRunnerParameters.class);
        if (parameters == null) {
            throw new IllegalStateException("EvoSuite test class " + testClass.getName()
                    + " is not annotated with EvoRunnerParameters");
        }

        RuntimeSettings.resetStaticState = parameters.resetStaticState();
        RuntimeSettings.mockJVMNonDeterminism = parameters.mockJVMNonDeterminism();
        RuntimeSettings.mockGUI = parameters.mockGUI();
        RuntimeSettings.useVFS = parameters.useVFS();
        RuntimeSettings.useVNET = parameters.useVNET();
        RuntimeSettings.useSeparateClassLoader = parameters.separateClassLoader();
        RuntimeSettings.useJEE = parameters.useJEE();
    }
}
