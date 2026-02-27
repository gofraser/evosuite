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
package org.evosuite.runtime;

import org.evosuite.runtime.agent.InstrumentingAgent;
import org.evosuite.runtime.agent.TransformerForTests;
import org.evosuite.runtime.classhandling.ClassResetter;
import org.evosuite.runtime.classhandling.ClassStateSupport;
import org.evosuite.runtime.classhandling.JDKClassResetter;
import org.evosuite.runtime.instrumentation.MethodCallReplacementCache;
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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Experimental JUnit 5 extension entry point for EvoSuite-generated tests.
 *
 * <p>This extension centralizes runtime lifecycle handling and avoids metadata-file
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

    /**
     * Create a new EvoSuite extension for the given test class.
     *
     * @param testClass the test class.
     */
    public EvoSuiteExtension(Class<?> testClass) {
        this(testClass, new String[0]);
    }

    /**
     * Create a new EvoSuite extension for the given test class.
     *
     * @param testClass the test class.
     * @param explicitClassesToInitialize classes to explicitly initialize.
     */
    public EvoSuiteExtension(Class<?> testClass, String... explicitClassesToInitialize) {
        this.testClass = testClass;
        EvoRunnerParameters parameters = configureRuntimeSettings(testClass);
        Set<String> threadsToIgnore = new LinkedHashSet<>(Arrays.asList(parameters.ignoreThreads()));
        // Common test runner threads to ignore
        threadsToIgnore.add("junit");
        threadsToIgnore.add("pit");
        threadsToIgnore.add("surefire");
        threadsToIgnore.add("AWT-EventQueue");
        this.threadStopper = new ThreadStopper(KillSwitchHandler.getInstance(), parameters.timeout(),
                threadsToIgnore.toArray(new String[0]));
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
        configureRuntimeSettings(testClass);
        maybeInitializeGui();
        if (needsAgentLifecycle()) {
            try {
                InstrumentingAgent.initialize();
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Failed to initialize instrumentation agent in EvoSuiteExtension", e);
            }
            // Ensure the transformer is active for class loading during initialization.
            // When the agent was already loaded from a previous test run (e.g. prior PIT
            // mutation), loadAgent() returns immediately without calling agentmain(), so
            // activateIfRequestedAtStartup() never fires.  Activate the transformer
            // directly so that initializeDiscoveredClasses() instruments classes loaded
            // through new classloaders (such as PIT's MutationClassLoader).
            // We only activate the transformer — MockFramework stays disabled until
            // beforeEach() calls InstrumentingAgent.activate().
            try {
                TransformerForTests t = InstrumentingAgent.getTransformer();
                if (t != null) {
                    t.activate();
                }
            } catch (IllegalStateException ignored) {
                // Transformer not available — agent could not be loaded
            }
        }
        if (RuntimeSettings.resetStaticState || RuntimeSettings.isUsingAnyMocking()) {
            if (RuntimeSettings.resetStaticState) {
                Sandbox.initializeSecurityManagerForSUT();
                JDKClassResetter.init();
            }
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
            syncMockFrameworkToSystem();
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
                failure = recordFailureOrSuppress(failure,
                        () -> ShutdownHookHandler.getInstance().safeExecuteAddedHooks());
            }
            if (RuntimeSettings.resetStaticState) {
                failure = recordFailureOrSuppress(failure, JDKClassResetter::reset);
                failure = recordFailureOrSuppress(failure, this::resetDiscoveredClasses);
                failure = recordFailureOrSuppress(failure, Sandbox::doneWithExecutingSUTCode);
            }
            if (needsAgentLifecycle()) {
                failure = recordFailureOrSuppress(failure, InstrumentingAgent::deactivate);
                syncMockFrameworkToSystem();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (needsAgentLifecycle()) {
            try {
                InstrumentingAgent.deactivate();
                syncMockFrameworkToSystem();
            } catch (RuntimeException e) {
                logger.warn("Could not deactivate instrumentation agent in afterAll: {}", e.getMessage());
            }
        }
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
        if (RuntimeSettings.isUsingAnyMocking()) {
            ClassStateSupport.retransformIfNeeded(testClass.getClassLoader(), classesToInitialize);
        }
        boolean problem = ClassStateSupport.initializeClasses(testClass.getClassLoader(), classesToInitialize);
        if (problem) {
            // Recover when SUT classes were loaded before the extension got control.
            ClassStateSupport.retransformIfNeeded(testClass.getClassLoader(), classesToInitialize);
            ClassStateSupport.initializeClasses(testClass.getClassLoader(), classesToInitialize);
        }
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

    private static EvoRunnerParameters configureRuntimeSettings(Class<?> testClass) {
        EvoRunnerParameters parameters = testClass.getAnnotation(EvoRunnerParameters.class);
        if (parameters == null) {
            throw new IllegalStateException("EvoSuite test class " + testClass.getName()
                    + " is not annotated with EvoRunnerParameters");
        }

        RuntimeSettings.className = inferTargetClassName(testClass);
        RuntimeSettings.resetStaticState = parameters.resetStaticState();
        RuntimeSettings.mockJVMNonDeterminism = parameters.mockJVMNonDeterminism();
        RuntimeSettings.mockGUI = parameters.mockGUI();
        RuntimeSettings.useVFS = parameters.useVFS();
        RuntimeSettings.useVNET = parameters.useVNET();
        RuntimeSettings.useSeparateClassLoader = parameters.separateClassLoader();
        RuntimeSettings.useJEE = parameters.useJEE();
        RuntimeSettings.maxNumberOfThreads = parameters.maxNumberOfThreads();
        RuntimeSettings.maxNumberOfIterationsPerLoop = parameters.maxNumberOfIterationsPerLoop();

        syncSettingsToSystemClassLoader();

        return parameters;
    }

    private static void syncMockFrameworkToSystem() {
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (EvoSuiteExtension.class.getClassLoader() == systemLoader) {
            return;
        }
        try {
            Class<?> systemMockFramework = Class.forName("org.evosuite.runtime.mock.MockFramework", true, systemLoader);
            String methodName = org.evosuite.runtime.mock.MockFramework.isEnabled() ? "enable" : "disable";
            Method m = systemMockFramework.getMethod(methodName);
            m.invoke(null);
        } catch (Exception e) {
            logger.warn("Could not synchronize MockFramework state to system classloader: {}", e.getMessage());
        }
    }

    private static void syncSettingsToSystemClassLoader() {
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (EvoSuiteExtension.class.getClassLoader() == systemLoader) {
            return;
        }

        try {
            Class<?> systemSettings = Class.forName(RuntimeSettings.class.getName(), true, systemLoader);
            copyField(RuntimeSettings.class, systemSettings, "className");
            copyField(RuntimeSettings.class, systemSettings, "resetStaticState");
            copyField(RuntimeSettings.class, systemSettings, "mockJVMNonDeterminism");
            copyField(RuntimeSettings.class, systemSettings, "mockGUI");
            copyField(RuntimeSettings.class, systemSettings, "useVFS");
            copyField(RuntimeSettings.class, systemSettings, "useVNET");
            copyField(RuntimeSettings.class, systemSettings, "useSeparateClassLoader");
            copyField(RuntimeSettings.class, systemSettings, "useJEE");
            copyField(RuntimeSettings.class, systemSettings, "maxNumberOfThreads");
            copyField(RuntimeSettings.class, systemSettings, "maxNumberOfIterationsPerLoop");
            copyField(RuntimeSettings.class, systemSettings, "mockSystemIn");
            copyField(RuntimeSettings.class, systemSettings, "applyUIDTransformation");
            copyField(RuntimeSettings.class, systemSettings, "isRunningASystemTest");

            // Sync MockFramework state
            syncMockFrameworkToSystem();

            // Setup Delegation
            setupDelegate(org.evosuite.runtime.vfs.VirtualFileSystem.class, systemLoader);
            setupDelegate(org.evosuite.runtime.LoopCounter.class, systemLoader);

            Class<?> systemCache = Class.forName(MethodCallReplacementCache.class.getName(), true, systemLoader);
            Method resetCache = systemCache.getMethod("resetSingleton");
            resetCache.invoke(null);

            Class<?> systemRuntime = Class.forName("org.evosuite.runtime.Runtime", true, systemLoader);
            Method getRuntime = systemRuntime.getMethod("getInstance");
            Object runtimeInstance = getRuntime.invoke(null);
            Method resetRuntime = systemRuntime.getMethod("resetRuntime");
            resetRuntime.invoke(runtimeInstance);
        } catch (Exception e) {
            logger.warn("Could not synchronize RuntimeSettings to system classloader: {}", e.getMessage());
        }
    }

    private static void setupDelegate(Class<?> clazz, ClassLoader systemLoader) throws Exception {
        Class<?> systemClass = Class.forName(clazz.getName(), true, systemLoader);
        Method getSystemInstance = systemClass.getMethod("getInstance");
        Object systemInstance = getSystemInstance.invoke(null);

        Method getInstance = clazz.getMethod("getInstance");
        Object localInstance = getInstance.invoke(null);

        Method setDelegate = clazz.getMethod("setDelegate", Object.class);
        setDelegate.invoke(localInstance, systemInstance);
    }

    private static void copyField(Class<?> source, Class<?> target, String fieldName) throws Exception {
        java.lang.reflect.Field srcField = source.getField(fieldName);
        java.lang.reflect.Field tgtField = target.getField(fieldName);
        tgtField.set(null, srcField.get(null));
    }
}
