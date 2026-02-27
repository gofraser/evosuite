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

import org.evosuite.runtime.instrumentation.InstrumentedClass;
import org.evosuite.runtime.classhandling.ClassStateSupport;
import org.evosuite.runtime.classhandling.JDKClassResetter;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.thread.KillSwitchHandler;
import org.evosuite.runtime.util.JOptionPaneInputs;
import org.evosuite.runtime.util.SystemInUtil;
import org.evosuite.runtime.agent.InstrumentingAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

public class EvoSuiteExtensionLifecycleTest {

    @EvoRunnerParameters
    public static class Annotated_ESTest {
    }

    @EvoRunnerParameters(mockJVMNonDeterminism = true, mockGUI = true, useVFS = true, useVNET = true,
            resetStaticState = false, separateClassLoader = false, useJEE = true)
    public static class AnnotatedWithSettings_ESTest {
    }

    @EvoRunnerParameters(separateClassLoader = true)
    public static class SeparateClassLoader_ESTest {
    }

    @EvoRunnerParameters
    public static class NoDefaultConstructor_ESTest {
        private NoDefaultConstructor_ESTest(String ignored) {
        }
    }

    public static class MissingAnnotation_ESTest {
    }

    @EvoRunnerParameters(resetStaticState = true)
    public static class Resetting_ESTest {
    }

    @EvoRunnerParameters(mockJVMNonDeterminism = true)
    public static class Mocking_ESTest {
    }

    @EvoRunnerParameters(mockJVMNonDeterminism = true, resetStaticState = true)
    public static class MockingAndResetting_ESTest {
    }

    @EvoRunnerParameters(mockJVMNonDeterminism = true)
    public static class MockingLifecycle_ESTest {
        public void testMethodOne() {
        }

        public void testMethodTwo() {
        }
    }

    public static class Resetting implements InstrumentedClass {
    }

    public static class MockingAndResetting implements InstrumentedClass {
    }

    private final String defaultClassName = RuntimeSettings.className;
    private final boolean defaultMockJVM = RuntimeSettings.mockJVMNonDeterminism;
    private final boolean defaultMockSystemIn = RuntimeSettings.mockSystemIn;
    private final boolean defaultMockGui = RuntimeSettings.mockGUI;
    private final boolean defaultUseVfs = RuntimeSettings.useVFS;
    private final boolean defaultUseVnet = RuntimeSettings.useVNET;
    private final boolean defaultResetStaticState = RuntimeSettings.resetStaticState;
    private final boolean defaultSeparateClassLoader = RuntimeSettings.useSeparateClassLoader;
    private final boolean defaultUseJee = RuntimeSettings.useJEE;

    @AfterEach
    public void restoreRuntimeSettings() {
        RuntimeSettings.className = defaultClassName;
        RuntimeSettings.mockJVMNonDeterminism = defaultMockJVM;
        RuntimeSettings.mockSystemIn = defaultMockSystemIn;
        RuntimeSettings.mockGUI = defaultMockGui;
        RuntimeSettings.useVFS = defaultUseVfs;
        RuntimeSettings.useVNET = defaultUseVnet;
        RuntimeSettings.resetStaticState = defaultResetStaticState;
        RuntimeSettings.useSeparateClassLoader = defaultSeparateClassLoader;
        RuntimeSettings.useJEE = defaultUseJee;
        java.lang.System.clearProperty(EvoSuiteExtension.EAGER_GUI_INIT_PROPERTY);
        SystemInUtil.resetSingleton();
        JOptionPaneInputs.resetSingleton();
    }

    @Test
    public void shouldFailFastForMissingEvoRunnerParametersAnnotation() {
        Assertions.assertThrows(IllegalStateException.class, () -> new EvoSuiteExtension(MissingAnnotation_ESTest.class));
    }

    @Test
    public void shouldApplyRuntimeSettingsFromAnnotation() {
        new EvoSuiteExtension(AnnotatedWithSettings_ESTest.class);

        Assertions.assertTrue(RuntimeSettings.mockJVMNonDeterminism);
        Assertions.assertTrue(RuntimeSettings.mockGUI);
        Assertions.assertTrue(RuntimeSettings.useVFS);
        Assertions.assertTrue(RuntimeSettings.useVNET);
        Assertions.assertFalse(RuntimeSettings.resetStaticState);
        Assertions.assertFalse(RuntimeSettings.useSeparateClassLoader);
        Assertions.assertTrue(RuntimeSettings.useJEE);
    }

    @Test
    public void shouldInstantiateAnnotatedTestClassWithDefaultConstructor() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);

        Object instance = extension.createTestInstance(factoryContext(Annotated_ESTest.class), null);

        Assertions.assertEquals(Annotated_ESTest.class, instance.getClass());
    }

    @Test
    public void shouldRejectSeparateClassLoaderForJUnit5Factory() {
        EvoSuiteExtension extension = new EvoSuiteExtension(SeparateClassLoader_ESTest.class);

        Assertions.assertThrows(TestInstantiationException.class,
                () -> extension.createTestInstance(factoryContext(SeparateClassLoader_ESTest.class), null));
    }

    @Test
    public void shouldFailToInstantiateWhenNoDefaultConstructorExists() {
        EvoSuiteExtension extension = new EvoSuiteExtension(NoDefaultConstructor_ESTest.class);

        Assertions.assertThrows(TestInstantiationException.class,
                () -> extension.createTestInstance(factoryContext(NoDefaultConstructor_ESTest.class), null));
    }

    @Test
    public void shouldInferClassNameDuringBeforeAll() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);
        RuntimeSettings.className = "unknown";

        extension.beforeAll(null);

        Assertions.assertEquals("org.evosuite.runtime.EvoSuiteExtensionLifecycleTest$Annotated", RuntimeSettings.className);
    }

    @Test
    public void shouldResetInputStateAcrossBeforeEachCalls() throws IOException {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);

        extension.beforeEach(null);
        SystemInUtil.addInputLine("abc");
        JOptionPaneInputs.enqueueInputString("queued");
        Assertions.assertTrue(SystemInUtil.getInstance().available() > 0);
        Assertions.assertTrue(JOptionPaneInputs.getInstance().containsStringInput());
        extension.afterEach(null);

        extension.beforeEach(null);
        Assertions.assertEquals(0, SystemInUtil.getInstance().available());
        Assertions.assertFalse(JOptionPaneInputs.getInstance().containsStringInput());
        extension.afterEach(null);
    }

    @Test
    public void shouldRunFullLifecycleWithStaticResetEnabled() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Resetting_ESTest.class);

        try (MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            Assertions.assertDoesNotThrow(() -> {
                extension.beforeAll(null);
                extension.beforeEach(null);
                extension.afterEach(null);
                extension.afterAll(null);
            });
            mockedAgent.verify(InstrumentingAgent::initialize);
        }
    }

    @Test
    public void shouldRespectSandboxExecutionStateWhenStaticResetEnabled() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Resetting_ESTest.class);
        try (MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            extension.beforeAll(null);

            extension.beforeEach(null);
            if (Sandbox.isSecurityManagerSupported()) {
                Assertions.assertTrue(Sandbox.isOnAndExecutingSUTCode());
            } else {
                Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());
            }

            extension.afterEach(null);
            Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());
            extension.afterAll(null);
            mockedAgent.verify(InstrumentingAgent::initialize);
        }
    }

    @Test
    public void shouldDeactivateMockFrameworkAndResetKillSwitchAfterEach() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Mocking_ESTest.class);

        MockFramework.disable();
        extension.beforeEach(null);
        Assertions.assertTrue(MockFramework.isEnabled());

        extension.afterEach(null);
        Assertions.assertFalse(MockFramework.isEnabled());
        Assertions.assertDoesNotThrow(() -> KillSwitchHandler.getInstance().checkTimeout());
    }

    @Test
    public void shouldCleanupRuntimeStateEvenIfAfterEachFailsEarly() {
        EvoSuiteExtension extension = new EvoSuiteExtension(MockingAndResetting_ESTest.class);
        try (MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            mockedAgent.when(InstrumentingAgent::deactivate).thenCallRealMethod();
            extension.beforeAll(null);
            MockFramework.enable();

            Assertions.assertThrows(IllegalStateException.class, () -> extension.afterEach(null));
            Assertions.assertFalse(MockFramework.isEnabled());
            Assertions.assertFalse(Sandbox.isOnAndExecutingSUTCode());
            extension.afterAll(null);
            mockedAgent.verify(InstrumentingAgent::initialize);
        }
    }

    @Test
    public void shouldFailBeforeAllIfAgentInitializationThrows() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Mocking_ESTest.class);
        try (MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            mockedAgent.when(InstrumentingAgent::initialize).thenThrow(new RuntimeException("agent-init-failed"));

            IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class,
                    () -> extension.beforeAll(null));
            Assertions.assertEquals("Failed to initialize instrumentation agent in EvoSuiteExtension",
                    exception.getMessage());
            Assertions.assertTrue(exception.getCause() instanceof RuntimeException);
            mockedAgent.verify(InstrumentingAgent::initialize);
        }
    }

    @Test
    public void shouldNotInitializeAgentWhenRuntimeLifecycleDoesNotNeedIt() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);
        try (MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            extension.beforeAll(null);
            mockedAgent.verifyNoInteractions();
        }
    }

    @Test
    public void shouldIgnoreGuiInitializationFailureInBeforeAll() {
        java.lang.System.setProperty(EvoSuiteExtension.EAGER_GUI_INIT_PROPERTY, "true");
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);
        try (MockedStatic<GuiSupport> mockedGui = Mockito.mockStatic(GuiSupport.class)) {
            mockedGui.when(GuiSupport::initialize).thenThrow(new RuntimeException("gui-init-failed"));

            Assertions.assertDoesNotThrow(() -> extension.beforeAll(null));
            mockedGui.verify(GuiSupport::initialize);
        }
    }

    @Test
    public void shouldActivateAndDeactivateAgentAroundEachWhenNeeded() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Mocking_ESTest.class);
        try (MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            extension.beforeEach(null);
            extension.afterEach(null);

            mockedAgent.verify(InstrumentingAgent::activate);
            mockedAgent.verify(InstrumentingAgent::deactivate);
        }
    }

    @Test
    public void shouldNotActivateOrDeactivateAgentAroundEachWhenNotNeeded() {
        EvoSuiteExtension extension = new EvoSuiteExtension(Annotated_ESTest.class);
        try (MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            extension.beforeEach(null);
            extension.afterEach(null);

            mockedAgent.verifyNoInteractions();
        }
    }

    @Test
    public void shouldUseExplicitInitializationOrderWhenProvided() {
        EvoSuiteExtension extension = new EvoSuiteExtension(
                Resetting_ESTest.class,
                "z.InitLast",
                "a.InitFirst",
                "m.InitMiddle"
        );
        try (MockedStatic<ClassStateSupport> mockedStateSupport = Mockito.mockStatic(ClassStateSupport.class);
             MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            extension.beforeAll(null);
            extension.beforeEach(null);
            extension.afterEach(null);
            extension.afterAll(null);

            mockedStateSupport.verify(() -> ClassStateSupport.initializeClasses(
                    Mockito.any(ClassLoader.class),
                    Mockito.eq("z.InitLast"),
                    Mockito.eq("a.InitFirst"),
                    Mockito.eq("m.InitMiddle")
            ));
            mockedStateSupport.verify(() -> ClassStateSupport.resetClasses(
                    Mockito.eq("z.InitLast"),
                    Mockito.eq("a.InitFirst"),
                    Mockito.eq("m.InitMiddle")
            ));
            mockedAgent.verify(InstrumentingAgent::initialize);
        }
    }

    @Test
    public void shouldContinueCleanupWhenSandboxDoneThrows() {
        EvoSuiteExtension extension = new EvoSuiteExtension(MockingAndResetting_ESTest.class);
        try (MockedStatic<Sandbox> mockedSandbox = Mockito.mockStatic(Sandbox.class);
             MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class);
             MockedStatic<JDKClassResetter> mockedJdkResetter = Mockito.mockStatic(JDKClassResetter.class)) {
            mockedSandbox.when(Sandbox::doneWithExecutingSUTCode).thenThrow(new IllegalStateException("sandbox-cleanup-failed"));

            RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> extension.afterEach(null));
            Assertions.assertTrue(Arrays.stream(ex.getSuppressed())
                    .anyMatch(t -> t instanceof IllegalStateException
                            && "sandbox-cleanup-failed".equals(t.getMessage())));
            mockedJdkResetter.verify(JDKClassResetter::reset);
            mockedSandbox.verify(Sandbox::doneWithExecutingSUTCode);
            mockedAgent.verify(InstrumentingAgent::deactivate);
        }
    }

    @Test
    public void shouldContinueCleanupWhenJdkResetThrows() {
        EvoSuiteExtension extension = new EvoSuiteExtension(MockingAndResetting_ESTest.class);
        try (MockedStatic<Sandbox> mockedSandbox = Mockito.mockStatic(Sandbox.class);
             MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class);
             MockedStatic<JDKClassResetter> mockedJdkResetter = Mockito.mockStatic(JDKClassResetter.class);
             MockedStatic<ClassStateSupport> mockedClassState = Mockito.mockStatic(ClassStateSupport.class)) {
            mockedJdkResetter.when(JDKClassResetter::reset).thenThrow(new IllegalStateException("jdk-reset-failed"));

            RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> extension.afterEach(null));
            Assertions.assertTrue(Arrays.stream(ex.getSuppressed())
                    .anyMatch(t -> t instanceof IllegalStateException
                            && "jdk-reset-failed".equals(t.getMessage())));
            mockedClassState.verify(() -> ClassStateSupport.resetClasses(
                    "org.evosuite.runtime.EvoSuiteExtensionLifecycleTest$MockingAndResetting"));
            mockedSandbox.verify(Sandbox::doneWithExecutingSUTCode);
            mockedAgent.verify(InstrumentingAgent::deactivate);
        }
    }

    @Test
    public void shouldIsolateStateAcrossTwoMethodsWithinSingleLifecycle() throws IOException {
        EvoSuiteExtension extension = new EvoSuiteExtension(MockingLifecycle_ESTest.class);
        ExtensionContext classContext = classContext(MockingLifecycle_ESTest.class);
        ExtensionContext firstMethod = methodContext(MockingLifecycle_ESTest.class, "testMethodOne");
        ExtensionContext secondMethod = methodContext(MockingLifecycle_ESTest.class, "testMethodTwo");

        try (MockedStatic<InstrumentingAgent> mockedAgent = Mockito.mockStatic(InstrumentingAgent.class)) {
            extension.beforeAll(classContext);

            extension.beforeEach(firstMethod);
            Assertions.assertTrue(MockFramework.isEnabled());
            SystemInUtil.addInputLine("first-call");
            JOptionPaneInputs.enqueueInputString("first-call");
            Assertions.assertTrue(SystemInUtil.getInstance().available() > 0);
            Assertions.assertTrue(JOptionPaneInputs.getInstance().containsStringInput());
            extension.afterEach(firstMethod);

            extension.beforeEach(secondMethod);
            Assertions.assertTrue(MockFramework.isEnabled());
            Assertions.assertEquals(0, SystemInUtil.getInstance().available());
            Assertions.assertFalse(JOptionPaneInputs.getInstance().containsStringInput());
            extension.afterEach(secondMethod);

            extension.afterAll(classContext);

            mockedAgent.verify(InstrumentingAgent::initialize);
            // activate/deactivate are called from both beforeEach/afterEach (2x each)
            // and from ClassStateSupport.loadClasses() during initializeDiscoveredClasses().
            mockedAgent.verify(InstrumentingAgent::activate, Mockito.atLeast(2));
            mockedAgent.verify(InstrumentingAgent::deactivate, Mockito.atLeast(2));
        }
    }

    private static TestInstanceFactoryContext factoryContext(Class<?> testClass) {
        return new TestInstanceFactoryContext() {
            @Override
            public Class<?> getTestClass() {
                return testClass;
            }

            @Override
            public Optional<Object> getOuterInstance() {
                return Optional.empty();
            }
        };
    }

    private static ExtensionContext classContext(Class<?> testClass) {
        ExtensionContext context = Mockito.mock(ExtensionContext.class);
        Mockito.when(context.getTestClass()).thenReturn(Optional.of(testClass));
        Mockito.when(context.getTestMethod()).thenReturn(Optional.empty());
        return context;
    }

    private static ExtensionContext methodContext(Class<?> testClass, String methodName) {
        ExtensionContext context = Mockito.mock(ExtensionContext.class);
        Method method;
        try {
            method = testClass.getDeclaredMethod(methodName);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Missing method '" + methodName + "' on " + testClass.getName(), e);
        }
        Mockito.when(context.getTestClass()).thenReturn(Optional.of(testClass));
        Mockito.when(context.getTestMethod()).thenReturn(Optional.of(method));
        Mockito.when(context.getRequiredTestMethod()).thenReturn(method);
        return context;
    }
}
