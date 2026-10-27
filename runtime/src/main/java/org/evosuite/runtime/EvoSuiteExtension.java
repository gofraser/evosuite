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
import org.evosuite.runtime.vnet.NonFunctionalRequirementExtension;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Experimental JUnit 5 extension entry point for EvoSuite-generated tests.
 *
 * <p>This extension centralizes runtime lifecycle handling and avoids metadata-file
 * based initialization by inferring reset/init candidates from test class names.
 */
public class EvoSuiteExtension implements TestInstanceFactory, BeforeAllCallback, BeforeEachCallback,
        AfterEachCallback, AfterAllCallback, TestExecutionExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(EvoSuiteExtension.class);
    static final String EAGER_GUI_INIT_PROPERTY = "evosuite.extension.eager_gui_init";
    private static final Pattern ESTEST_PATTERN = Pattern.compile("(.+)_ESTest(?:_\\d+)?");
    private static final Pattern FAILED_ESTEST_PATTERN = Pattern.compile("(.+)_Failed_ESTest(?:_\\d+)?");
    private static final Pattern ANALYZER_TMP_BASE_PATTERN = Pattern.compile("(.+)_\\d+_tmp_");

    private final Class<?> testClass;
    private final ThreadStopper threadStopper;
    private final String[] classesToInitialize;
    /**
     * Alternating names and values of the system properties the tests read, or null if the
     * suite does not touch system properties.
     */
    private final String[] systemProperties;
    private java.util.Properties defaultProperties;
    private final NonFunctionalRequirementExtension nonFunctionalRequirements = new NonFunctionalRequirementExtension();

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
        this(testClass, explicitClassesToInitialize, null);
    }

    /**
     * Create a new EvoSuite extension for the given test class, which makes the system
     * properties the tests read have the values they had during test generation and puts
     * all properties back to their original values after each test and after the class.
     *
     * @param testClass the test class.
     * @param explicitClassesToInitialize classes to explicitly initialize.
     * @param systemProperties alternating names and values of the system properties to set,
     *                         or null to leave system properties alone.
     */
    public EvoSuiteExtension(Class<?> testClass, String[] explicitClassesToInitialize, String[] systemProperties) {
        this.testClass = testClass;
        this.systemProperties = systemProperties == null ? null : systemProperties.clone();
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
        // Before the classes are initialised: a static initialiser may wrap the streams too.
        saveStandardStreams(context);
        configureRuntimeSettings(testClass);
        saveDefaultProperties();
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
        saveStandardStreams(context);
        setSystemProperties();
        threadStopper.storeCurrentThreads();
        threadStopper.startRecordingTime();
        if (RuntimeSettings.mockJVMNonDeterminism) {
            ShutdownHookHandler.getInstance().initHandler();
        }
        if (RuntimeSettings.resetStaticState || RuntimeSettings.mockJVMNonDeterminism) {
            // Mark the sandbox as executing SUT code whenever shutdown hooks may later be
            // drained in afterEach(): ShutdownHookHandler.safeExecuteAddedHooks() asserts
            // that hooks only ever run from within this window while a security manager is
            // active. Hook draining is gated purely on mockJVMNonDeterminism, independent of
            // resetStaticState, so the sandbox window has to be independent too, or that
            // assertion trips for the (legitimate, user-reachable) combination of
            // mockJVMNonDeterminism=true with resetStaticState=false. This mirrors
            // TestCaseExecutor, which brackets SUT execution unconditionally regardless of
            // which mocking flags are enabled.
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

    /**
     * A test that ends on one of EvoSuite's resource limits passes, as with the
     * {@link NonFunctionalRequirementExtension} that scaffolding-based suites register: test
     * classes in extension mode have no scaffolding, so this extension is their only handler.
     */
    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        nonFunctionalRequirements.handleTestExecutionException(context, throwable);
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
            }
            // After the class reset, which runs static initializers again.
            failure = recordFailureOrSuppress(failure, () -> restoreStandardStreams(context));
            if (RuntimeSettings.resetStaticState || RuntimeSettings.mockJVMNonDeterminism) {
                // Close out the sandbox window opened in beforeEach() (see the comment
                // there); must stay in sync with that condition.
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

    /**
     * Streams saved per test invocation (or per class for beforeAll/afterAll), keyed by the
     * unique id of the context, so that tests of one class running in parallel do not
     * overwrite each other's snapshot. A null context, which only unit tests pass, uses the
     * key "".
     */
    private final Map<String, StandardStreams.Snapshot> standardStreams = new ConcurrentHashMap<>();

    private static String streamsKey(ExtensionContext context) {
        String id = context == null ? null : context.getUniqueId();
        return id == null ? "" : id;
    }

    /**
     * Code under test may wrap System.out/err on construction (a redirecting logger). The
     * streams are handed back after each test and after the class, or the wrappers stack up
     * and every print fans out through all of them.
     */
    private void saveStandardStreams(ExtensionContext context) {
        standardStreams.put(streamsKey(context), StandardStreams.save());
    }

    private void restoreStandardStreams(ExtensionContext context) {
        StandardStreams.restore(standardStreams.remove(streamsKey(context)));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        try {
            afterAllCleanup();
        } finally {
            restoreStandardStreams(context);
        }
    }

    private void afterAllCleanup() {
        restoreDefaultProperties();
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
            return normalizeAnalyzerTmpBase(matcher.group(1));
        }
        matcher = ESTEST_PATTERN.matcher(fqcn);
        if (matcher.matches()) {
            return normalizeAnalyzerTmpBase(matcher.group(1));
        }
        return RuntimeSettings.className != null ? RuntimeSettings.className : "unknown";
    }

    private static String normalizeAnalyzerTmpBase(String inferredClassName) {
        Matcher tmpMatcher = ANALYZER_TMP_BASE_PATTERN.matcher(inferredClassName);
        if (tmpMatcher.matches()) {
            return tmpMatcher.group(1);
        }
        return inferredClassName;
    }

    private void initializeDiscoveredClasses() {
        if (classesToInitialize.length == 0) {
            return;
        }
        if (RuntimeSettings.isUsingAnyMocking()) {
            // A class the JVM loaded before the agent was attached (linking the test class to
            // read its extension field loads the classes it passes as a supertype) has not been
            // initialized yet. Retransform it now, so that its static initializer runs mocked:
            // initializeClasses only retransforms after initializing.
            ClassStateSupport.retransformIfNeeded(testClass.getClassLoader(), classesToInitialize);
        }
        ClassStateSupport.initializeClasses(testClass.getClassLoader(), classesToInitialize);
    }

    private void resetDiscoveredClasses() {
        String[] classesToReset = resolveClassesToReset();
        if (classesToReset.length == 0) {
            return;
        }
        ClassResetter.getInstance().setClassLoader(testClass.getClassLoader());
        ClassStateSupport.resetClasses(classesToReset);
    }

    private String[] resolveClassesToReset() {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        classes.addAll(Arrays.asList(classesToInitialize));
        try {
            TransformerForTests transformer = InstrumentingAgent.getTransformer();
            if (transformer != null) {
                List<String> instrumented = transformer.getViewOfInstrumentedClasses();
                classes.addAll(instrumented);
            }
        } catch (RuntimeException e) {
            logger.debug("Could not access transformer instrumented-class snapshot: {}", e.getMessage());
        }
        return classes.toArray(new String[0]);
    }

    private boolean needsAgentLifecycle() {
        return RuntimeSettings.isUsingAnyMocking() || RuntimeSettings.resetStaticState;
    }

    private void saveDefaultProperties() {
        if (systemProperties != null) {
            defaultProperties = (java.util.Properties) java.lang.System.getProperties().clone();
            setSystemProperties();
        }
    }

    private void restoreDefaultProperties() {
        if (defaultProperties != null) {
            java.lang.System.setProperties((java.util.Properties) defaultProperties.clone());
            defaultProperties = null;
        }
    }

    /**
     * Puts all properties back to their original values, which discards what a previous test
     * wrote, and then sets the ones the tests read.
     */
    private void setSystemProperties() {
        if (defaultProperties == null) {
            return;
        }
        java.lang.System.setProperties((java.util.Properties) defaultProperties.clone());
        for (int i = 0; i + 1 < systemProperties.length; i += 2) {
            java.lang.System.setProperty(systemProperties[i], systemProperties[i + 1]);
        }
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
        RuntimeSettings.apply(parameters);
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
            copySettings(systemSettings);

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

    /**
     * Copies the current values of the settings to the {@code RuntimeSettings} class of another
     * class loader.
     */
    static void copySettings(Class<?> targetSettings) throws Exception {
        copyField(RuntimeSettings.class, targetSettings, "className");
        copyField(RuntimeSettings.class, targetSettings, "resetStaticState");
        copyField(RuntimeSettings.class, targetSettings, "mockJVMNonDeterminism");
        copyField(RuntimeSettings.class, targetSettings, "mockGUI");
        copyField(RuntimeSettings.class, targetSettings, "useVFS");
        copyField(RuntimeSettings.class, targetSettings, "useVNET");
        copyField(RuntimeSettings.class, targetSettings, "useSeparateClassLoader");
        copyField(RuntimeSettings.class, targetSettings, "maxNumberOfThreads");
        copyField(RuntimeSettings.class, targetSettings, "maxNumberOfIterationsPerLoop");
        copyField(RuntimeSettings.class, targetSettings, "mockSystemIn");
        copyField(RuntimeSettings.class, targetSettings, "applyUIDTransformation");
        copyField(RuntimeSettings.class, targetSettings, "isRunningASystemTest");
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
