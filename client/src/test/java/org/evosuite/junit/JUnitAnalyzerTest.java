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
package org.evosuite.junit;

import com.examples.with.different.packagename.sandbox.OpenStream;
import com.examples.with.different.packagename.test.DowncastExample;
import org.apache.commons.io.FileUtils;
import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.instrumentation.NonInstrumentingClassLoader;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.JUnitTestCarvedChromosomeFactory;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.symbolic.TestCaseBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class JUnitAnalyzerTest {

    //we use carver to simplify the creation of test case chromosomes

    private static final Properties.Criterion[] defaultCriterion = Properties.CRITERION;
    private static final String defaultSelectedJUnit = Properties.SELECTED_JUNIT;
    private static final int defaultSeedMutations = Properties.SEED_MUTATIONS;
    private static final double defaultSeedClone = Properties.SEED_CLONE;
    private static final boolean DEFAULT_VFS = Properties.VIRTUAL_FS;
    private static final boolean DEFAULT_SANDBOX = Properties.SANDBOX;
    private static final boolean DEFAULT_ASSERTS_FOR_EVO = Properties.ENABLE_ASSERTS_FOR_EVOSUITE;
    private static final boolean DEFAULT_SCAFFOLDING = Properties.TEST_SCAFFOLDING;
    private static final Properties.OutputFormat DEFAULT_TEST_FORMAT = Properties.TEST_FORMAT;
    private static final boolean DEFAULT_TEST_EXTENSION_MODE = Properties.TEST_EXTENSION_MODE;
    private static final boolean DEFAULT_RESET_STATIC_FIELDS = Properties.RESET_STATIC_FIELDS;
    private static final String DEFAULT_TARGET_CLASS = Properties.TARGET_CLASS;
    private static final boolean DEFAULT_REPLACE_CALLS = Properties.REPLACE_CALLS;
    private static final boolean DEFAULT_REPLACE_GUI = Properties.REPLACE_GUI;

    private File file = new File(OpenStream.FILE_NAME);


    @BeforeEach
    public void init() {

        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();

        if (file.exists()) {
            file.delete();
        }
        file.deleteOnExit();
    }

    @AfterEach
    public void reset() {
        Properties.CRITERION = defaultCriterion;
        Properties.SELECTED_JUNIT = defaultSelectedJUnit;
        Properties.SEED_MUTATIONS = defaultSeedMutations;
        Properties.SEED_CLONE = defaultSeedClone;
        Properties.VIRTUAL_FS = DEFAULT_VFS;
        Properties.SANDBOX = DEFAULT_SANDBOX;
        Properties.ENABLE_ASSERTS_FOR_EVOSUITE = DEFAULT_ASSERTS_FOR_EVO;
        Properties.TEST_SCAFFOLDING = DEFAULT_SCAFFOLDING;
        Properties.TEST_FORMAT = DEFAULT_TEST_FORMAT;
        Properties.TEST_EXTENSION_MODE = DEFAULT_TEST_EXTENSION_MODE;
        Properties.RESET_STATIC_FIELDS = DEFAULT_RESET_STATIC_FIELDS;
        Properties.TARGET_CLASS = DEFAULT_TARGET_CLASS;
        Properties.REPLACE_CALLS = DEFAULT_REPLACE_CALLS;
        Properties.REPLACE_GUI = DEFAULT_REPLACE_GUI;
    }

    @Test
    public void testSandboxIssue() throws Exception {

        // Skip test if Security Manager is not supported (Java 24+)
        if (!Sandbox.isSecurityManagerSupported()) {
            System.out.println("Skipping testSandboxIssue: Security Manager not supported in this JVM (Java 24+)");
            return;
        }

        //First, get a TestCase from a carved JUnit

        Properties.SELECTED_JUNIT = com.examples.with.different.packagename.sandbox.OpenStreamInATryCatch_FakeTestToCarve.class.getCanonicalName();
        Properties.TARGET_CLASS = com.examples.with.different.packagename.sandbox.OpenStreamInATryCatch.class.getCanonicalName();

        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.BRANCH};
        Properties.SEED_MUTATIONS = 0;
        Properties.SEED_CLONE = 1;
        Properties.VIRTUAL_FS = false;
        Properties.SANDBOX = true;
        Properties.ENABLE_ASSERTS_FOR_EVOSUITE = true; //needed for setLoggingForJUnit
        Properties.TEST_SCAFFOLDING = false;

        //FIXME
        Sandbox.initializeSecurityManagerForSUT();

        //file should never be created
        Assertions.assertFalse(file.exists());

        JUnitTestCarvedChromosomeFactory factory = new JUnitTestCarvedChromosomeFactory(null);
        TestChromosome carved = factory.getChromosome();

        /*
         * FIXME: issue with carver
         */
        Files.deleteIfExists(file.toPath());

        Assertions.assertFalse(file.exists());

        Assertions.assertNotNull(carved);

        TestCase test = carved.getTestCase();

        Assertions.assertEquals(3, test.size(), "Should be: constructor, 1 variable and 1 method");

        //Now that we have a test case, we check its execution after
        //recompiling it to JUnit, and see if sandbox kicks in

        List<TestCase> list = new ArrayList<>();
        list.add(test);

        Assertions.assertFalse(file.exists());

        //NOTE: following order of checks reflects what is done
        // in EvoSuite after the search is finished

        System.out.println("\n COMPILATION CHECK \n");
        //first try to compile (which implies execution)
        JUnitAnalyzer.removeTestsThatDoNotCompile(list);
        Assertions.assertEquals(1, list.size());
        Assertions.assertFalse(file.exists());

        System.out.println("\n FIRST STABILITY CHECK \n");
        //try once
        JUnitAnalyzer.handleTestsThatAreUnstable(list);
        Assertions.assertEquals(1, list.size());
        Assertions.assertFalse(file.exists());

        System.out.println("\n SECOND STABILITY CHECK \n");
        //try again
        JUnitAnalyzer.handleTestsThatAreUnstable(list);
        Assertions.assertEquals(1, list.size());
        Assertions.assertFalse(file.exists());

        System.out.println("\n FINAL VERIFICATION \n");
        JUnitAnalyzer.verifyCompilationAndExecution(list);
        Assertions.assertEquals(1, list.size());
        Assertions.assertFalse(file.exists());
    }

    @Test
    public void testCreationOfTmpDir() throws IOException {

        File dir = JUnitAnalyzer.createNewTmpDir();
        Assertions.assertNotNull(dir);
        Assertions.assertTrue(dir.exists());

        FileUtils.deleteDirectory(dir);
        Assertions.assertFalse(dir.exists());
    }

    @Test
    public void testOrderSensitivityAnalysisForTrivialSuites() {
        JUnitAnalyzer.OrderSensitivityAnalysis empty = JUnitAnalyzer.analyzeOrderSensitivity(Collections.emptyList());
        Assertions.assertFalse(empty.isOrderSensitive());
        Assertions.assertTrue(empty.getForwardFailures().isEmpty());
        Assertions.assertTrue(empty.getReverseFailures().isEmpty());

        List<TestCase> single = new ArrayList<>();
        single.add(new org.evosuite.testcase.DefaultTestCase());
        JUnitAnalyzer.OrderSensitivityAnalysis one = JUnitAnalyzer.analyzeOrderSensitivity(single);
        Assertions.assertFalse(one.isOrderSensitive());
        Assertions.assertTrue(one.getForwardFailures().isEmpty());
        Assertions.assertTrue(one.getReverseFailures().isEmpty());
    }

    @Test
    public void testInfrastructureOrderSensitivityFailureDetection() {
        Assertions.assertTrue(JUnitAnalyzer.isInfrastructureOrderSensitivityFailure(
                Collections.singleton("load-error")));
        Assertions.assertTrue(JUnitAnalyzer.isInfrastructureOrderSensitivityFailure(
                Collections.singleton("execution-error")));
        Assertions.assertFalse(JUnitAnalyzer.isInfrastructureOrderSensitivityFailure(
                Collections.singleton("test0")));
        Assertions.assertFalse(JUnitAnalyzer.isInfrastructureOrderSensitivityFailure(
                new java.util.LinkedHashSet<>(Arrays.asList("load-error", "test0"))));
        Assertions.assertFalse(JUnitAnalyzer.isInfrastructureOrderSensitivityFailure(Collections.emptySet()));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testLoadTestsReturnsNullWhenClassFileIsMissing() throws Exception {
        Method loadTests = JUnitAnalyzer.class.getDeclaredMethod("loadTests", List.class);
        loadTests.setAccessible(true);

        File dir = Files.createTempDirectory("evosuite-missing-class").toFile();
        File javaFile = new File(dir, "MissingClass_ESTest.java");
        Files.write(javaFile.toPath(),
                Collections.singletonList("public class MissingClass_ESTest {}"),
                StandardCharsets.UTF_8);
        javaFile.deleteOnExit();
        dir.deleteOnExit();

        Class<?>[] classes = (Class<?>[]) loadTests.invoke(null, Collections.singletonList(javaFile));
        Assertions.assertNull(classes);
    }

    @Test
    public void testHasCompiledClassFilesForGeneratedSourcesDetectsMissingOutput() throws Exception {
        Method method = JUnitAnalyzer.class.getDeclaredMethod(
                "hasCompiledClassFilesForGeneratedSources", List.class);
        method.setAccessible(true);

        File dir = Files.createTempDirectory("evosuite-missing-output").toFile();
        File javaFile = new File(dir, "Generated_ESTest.java");
        Files.write(javaFile.toPath(),
                Collections.singletonList("public class Generated_ESTest {}"),
                StandardCharsets.UTF_8);
        javaFile.deleteOnExit();
        dir.deleteOnExit();

        Assertions.assertFalse((boolean) method.invoke(null, Collections.singletonList(javaFile)));

        File classFile = new File(dir, "Generated_ESTest.class");
        Files.write(classFile.toPath(), new byte[]{0});
        classFile.deleteOnExit();
        Assertions.assertTrue((boolean) method.invoke(null, Collections.singletonList(javaFile)));
    }

    @Test
    public void testAnalyzerSelectionFollowsCurrentTestFormat() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Assertions.assertFalse(JUnitAnalyzer.isJUnit5AnalyzerSelectedForCurrentFormat());

        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Assertions.assertTrue(JUnitAnalyzer.isJUnit5AnalyzerSelectedForCurrentFormat());

        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Assertions.assertFalse(JUnitAnalyzer.isJUnit5AnalyzerSelectedForCurrentFormat());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCompileChecksInExtensionModeUseTargetClassForInitOrder() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TEST_EXTENSION_MODE = true;
        Properties.RESET_STATIC_FIELDS = true;
        Properties.TARGET_CLASS = OpenStream.class.getCanonicalName();

        File dir = JUnitAnalyzer.createNewTmpDir();
        Assertions.assertNotNull(dir);
        try {
            Method compileTests = JUnitAnalyzer.class.getDeclaredMethod("compileTests", List.class, File.class);
            compileTests.setAccessible(true);

            List<TestCase> tests = new ArrayList<>();
            tests.add(new DefaultTestCase());
            List<File> generated = (List<File>) compileTests.invoke(null, tests, dir);

            Assertions.assertNotNull(generated);
            File javaFile = generated.stream()
                    .filter(file -> file.getName().endsWith(".java"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected at least one generated Java file"));
            String code = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);

            Assertions.assertTrue(code.contains("private static final String[] EVO_INIT_ORDER = {\""
                    + Properties.TARGET_CLASS + "\"};"));
            Assertions.assertFalse(code.contains("_tmp_\"};"));
        } finally {
            FileUtils.deleteDirectory(dir);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCompileCheckRenderingDoesNotMutateInputTests() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TARGET_CLASS = DowncastExample.class.getCanonicalName();

        TestCaseBuilder builder = new TestCaseBuilder();
        VariableReference receiver = builder.appendConstructor(DowncastExample.class.getConstructor());
        VariableReference value = builder.appendIntPrimitive(42);
        VariableReference number = builder.appendMethod(receiver,
                DowncastExample.class.getMethod("getANumber", int.class), value);
        number.setType(Integer.class);
        builder.appendMethod(receiver,
                DowncastExample.class.getMethod("testMe", Number.class), number);
        TestCase test = builder.getDefaultTestCase();
        String before = test.toCode();

        File dir = JUnitAnalyzer.createNewTmpDir();
        Assertions.assertNotNull(dir);
        try {
            Method compileTests = JUnitAnalyzer.class.getDeclaredMethod(
                    "compileTests", List.class, File.class);
            compileTests.setAccessible(true);
            List<File> generated = (List<File>) compileTests.invoke(
                    null, Collections.singletonList(test), dir);

            Assertions.assertNotNull(generated);
            Assertions.assertEquals(Integer.class, test.getStatement(2).getReturnClass());
            Assertions.assertEquals(before, test.toCode());
        } finally {
            FileUtils.deleteDirectory(dir);
        }
    }

    @Test
    public void testMalformedManifestClassPathJarIsSanitizedForCompiler() throws Exception {
        File malformedJar = Files.createTempFile("evosuite-malformed-manifest", ".jar").toFile();
        malformedJar.deleteOnExit();

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "\\");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(malformedJar.toPath()), manifest)) {
            out.putNextEntry(new JarEntry("sample.txt"));
            out.write("sample".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        Method sanitizeEntry = JUnitAnalyzer.class.getDeclaredMethod("sanitizeClasspathEntryForCompiler", String.class);
        sanitizeEntry.setAccessible(true);
        String sanitized = (String) sanitizeEntry.invoke(null, malformedJar.getAbsolutePath());

        Assertions.assertNotNull(sanitized);
        Assertions.assertNotEquals(malformedJar.getAbsolutePath(), sanitized);
        File sanitizedEntry = new File(sanitized);
        Assertions.assertTrue(sanitizedEntry.exists());
        Assertions.assertTrue(sanitizedEntry.isDirectory());
    }

    @Test
    public void testReflectiveAssertThrowsMismatchDetection() throws Exception {
        Method detector = JUnitAnalyzer.class.getDeclaredMethod(
                "isReflectiveAssertThrowsMismatch", JUnitFailure.class);
        detector.setAccessible(true);

        JUnitFailure reflectiveMismatch = new JUnitFailure(
                "Unexpected exception type thrown, expected: <x.E> but was: <java.lang.reflect.InvocationTargetException>",
                "org.opentest4j.AssertionFailedError",
                "test0",
                true,
                "");
        reflectiveMismatch.addToExceptionStackTrace("java.lang.reflect.Method.invoke(Method.java:566)");
        reflectiveMismatch.addToExceptionStackTrace("x.Test.test0(Test.java:42)");

        JUnitFailure nonReflective = new JUnitFailure(
                "Unexpected exception type thrown, expected: <x.E> but was: <java.lang.RuntimeException>",
                "org.opentest4j.AssertionFailedError",
                "test0",
                true,
                "");
        nonReflective.addToExceptionStackTrace("x.Test.test0(Test.java:42)");

        boolean detected = (boolean) detector.invoke(null, reflectiveMismatch);
        boolean notDetected = (boolean) detector.invoke(null, nonReflective);

        Assertions.assertTrue(detected);
        Assertions.assertFalse(notDetected);
    }

    @Test
    public void testInitialJUnitClassLoaderSelectionFollowsReplacementSettings() throws Exception {
        Method factory = JUnitAnalyzer.class.getDeclaredMethod("createInitialJUnitClassLoader", List.class);
        factory.setAccessible(true);
        Properties.REPLACE_CALLS = false;
        Properties.REPLACE_GUI = false;
        InstrumentingClassLoader selected = (InstrumentingClassLoader) factory.invoke(null, Collections.emptyList());
        Assertions.assertTrue(selected instanceof NonInstrumentingClassLoader,
                "Without replacements, JUnit check should start with NonInstrumentingClassLoader");

        Properties.REPLACE_CALLS = true;
        selected = (InstrumentingClassLoader) factory.invoke(null, Collections.emptyList());
        Assertions.assertFalse(selected instanceof NonInstrumentingClassLoader,
                "With call replacements enabled, JUnit check must use InstrumentingClassLoader");

        Properties.REPLACE_CALLS = false;
        Properties.REPLACE_GUI = true;
        selected = (InstrumentingClassLoader) factory.invoke(null, Collections.emptyList());
        Assertions.assertFalse(selected instanceof NonInstrumentingClassLoader,
                "With GUI replacements enabled, JUnit check must use InstrumentingClassLoader");
    }

    @Test
    public void testInstrumentingRetryDetectsHeadlessFailuresWithJUnit4ClassPrefix() throws Exception {
        Method shouldRetry = JUnitAnalyzer.class.getDeclaredMethod(
                "shouldRetryWithInstrumentingAfterFailure", JUnitResult.class);
        shouldRetry.setAccessible(true);

        JUnitResult result = new JUnitResult(false, 1, 1);
        JUnitFailure failure = new JUnitFailure(
                "headless",
                "class java.awt.HeadlessException",
                "test0",
                false,
                "");
        result.addFailure(failure);

        boolean retry = (boolean) shouldRetry.invoke(null, result);
        Assertions.assertTrue(retry, "Headless failures should trigger instrumented retry");
    }

    @Test
    public void testInstrumentingRetryDetectsLocalGraphicsEnvironmentAwtError() throws Exception {
        Method shouldRetry = JUnitAnalyzer.class.getDeclaredMethod(
                "shouldRetryWithInstrumentingAfterFailure", JUnitResult.class);
        shouldRetry.setAccessible(true);

        JUnitResult result = new JUnitResult(false, 1, 1);
        JUnitFailure failure = new JUnitFailure(
                "Local GraphicsEnvironment must not be null",
                "class java.awt.AWTError",
                "test0",
                false,
                "");
        failure.addToExceptionStackTrace("java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment(GraphicsEnvironment.java:123)");
        result.addFailure(failure);

        boolean retry = (boolean) shouldRetry.invoke(null, result);
        Assertions.assertTrue(retry, "GraphicsEnvironment-null AWTError should trigger instrumented retry");
    }

    @Test
    public void testDeterministicGuiFailureDetectionForLocalGraphicsEnvironmentAwtError() throws Exception {
        Method detector = JUnitAnalyzer.class.getDeclaredMethod(
                "isDeterministicGuiEnvironmentFailure", JUnitFailure.class);
        detector.setAccessible(true);

        JUnitFailure failure = new JUnitFailure(
                "Local GraphicsEnvironment must not be null",
                "java.awt.AWTError",
                "test0",
                false,
                "");
        failure.addToExceptionStackTrace("java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment(GraphicsEnvironment.java:123)");

        boolean detected = (boolean) detector.invoke(null, failure);
        Assertions.assertTrue(detected);
    }

    @Test
    public void testDeterministicGuiFailureDetectionForHeadlessException() throws Exception {
        Method detector = JUnitAnalyzer.class.getDeclaredMethod(
                "isDeterministicGuiEnvironmentFailure", JUnitFailure.class);
        detector.setAccessible(true);

        JUnitFailure failure = new JUnitFailure(
                "No X11 DISPLAY variable was set, but this program performed an operation which requires it.",
                "class java.awt.HeadlessException",
                "test0",
                false,
                "");
        failure.addToExceptionStackTrace("java.awt.GraphicsEnvironment.checkHeadless(GraphicsEnvironment.java:204)");
        failure.addToExceptionStackTrace("java.awt.Window.<init>(Window.java:553)");

        boolean detected = (boolean) detector.invoke(null, failure);
        Assertions.assertTrue(detected);
    }

    @Test
    public void testDeterministicGuiFailureDetectionIgnoresNonGuiFailure() throws Exception {
        Method detector = JUnitAnalyzer.class.getDeclaredMethod(
                "isDeterministicGuiEnvironmentFailure", JUnitFailure.class);
        detector.setAccessible(true);

        JUnitFailure failure = new JUnitFailure(
                "boom",
                "java.lang.NullPointerException",
                "test0",
                false,
                "");
        failure.addToExceptionStackTrace("x.Foo.bar(Foo.java:10)");

        boolean detected = (boolean) detector.invoke(null, failure);
        Assertions.assertFalse(detected);
    }

    @Test
    public void testJavacInfrastructureFailureDetectionSupportsErrorAndCauseChains() throws Exception {
        Method detector = JUnitAnalyzer.class.getDeclaredMethod(
                "isJavacInfrastructureFailure", Throwable.class);
        detector.setAccessible(true);

        Error providerLoadingError = new Error("Circular loading of installed providers detected");
        providerLoadingError.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "java.nio.file.spi.FileSystemProvider",
                        "installedProviders",
                        "FileSystemProvider.java",
                        198)
        });
        Assertions.assertTrue((boolean) detector.invoke(null, providerLoadingError));

        RuntimeException wrapped = new RuntimeException("wrapper", new NoSuchElementException("missing provider"));
        wrapped.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("x.Foo", "bar", "Foo.java", 1)
        });
        Assertions.assertTrue((boolean) detector.invoke(null, wrapped));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFindFileSystemProviderServiceDeclarationsScansJarAndDirectory() throws Exception {
        File jarWithProvider = Files.createTempFile("evosuite-fs-provider", ".jar").toFile();
        jarWithProvider.deleteOnExit();

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarWithProvider.toPath()))) {
            out.putNextEntry(new JarEntry("META-INF/services/java.nio.file.spi.FileSystemProvider"));
            out.write(("# comment\n" +
                    "x.provider.First\n" +
                    "x.provider.First\n" +
                    "x.provider.Second # inline comment\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        File providerDir = Files.createTempDirectory("evosuite-fs-provider-dir").toFile();
        providerDir.deleteOnExit();
        File serviceFile = new File(providerDir, "META-INF/services/java.nio.file.spi.FileSystemProvider");
        Assertions.assertTrue(serviceFile.getParentFile().mkdirs() || serviceFile.getParentFile().isDirectory());
        Files.write(serviceFile.toPath(), Collections.singletonList("y.provider.Only"), StandardCharsets.UTF_8);
        serviceFile.deleteOnExit();

        Method scanner = JUnitAnalyzer.class.getDeclaredMethod(
                "findFileSystemProviderServiceDeclarations", String.class);
        scanner.setAccessible(true);

        String classpath = jarWithProvider.getAbsolutePath() + File.pathSeparator + providerDir.getAbsolutePath();
        Map<String, List<String>> providersByEntry = (Map<String, List<String>>) scanner.invoke(null, classpath);

        Assertions.assertEquals(2, providersByEntry.size());
        Assertions.assertEquals(Arrays.asList("x.provider.First", "x.provider.Second"),
                providersByEntry.get(jarWithProvider.getAbsolutePath()));
        Assertions.assertEquals(Collections.singletonList("y.provider.Only"),
                providersByEntry.get(providerDir.getAbsolutePath()));
    }

    @Test
    public void testDisableCompileCheckDueToInfrastructureSetsRunWideFlag() throws Exception {
        java.lang.reflect.Field disabledField = JUnitAnalyzer.class.getDeclaredField(
                "COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE");
        disabledField.setAccessible(true);
        java.lang.reflect.Field loggedField = JUnitAnalyzer.class.getDeclaredField("COMPILE_CHECK_DISABLED_LOGGED");
        loggedField.setAccessible(true);

        boolean previousDisabled = disabledField.getBoolean(null);
        boolean previousLogged = loggedField.getBoolean(null);
        try {
            disabledField.setBoolean(null, false);
            loggedField.setBoolean(null, false);

            Method disable = JUnitAnalyzer.class.getDeclaredMethod(
                    "disableCompileCheckDueToInfrastructure", Throwable.class, String.class);
            disable.setAccessible(true);
            disable.invoke(null, new RuntimeException("infra"), "");

            Assertions.assertTrue(disabledField.getBoolean(null));
            Assertions.assertFalse(loggedField.getBoolean(null));
        } finally {
            disabledField.setBoolean(null, previousDisabled);
            loggedField.setBoolean(null, previousLogged);
        }
    }

    @Test
    public void testShouldSkipCompileDependentExecutionAfterCompileReflectsInfraFlag() throws Exception {
        java.lang.reflect.Field disabledField = JUnitAnalyzer.class.getDeclaredField(
                "COMPILE_CHECK_DISABLED_DUE_TO_INFRASTRUCTURE");
        disabledField.setAccessible(true);
        java.lang.reflect.Field skippedLoggedField = JUnitAnalyzer.class.getDeclaredField(
                "COMPILE_DEPENDENT_EXECUTION_SKIPPED_LOGGED");
        skippedLoggedField.setAccessible(true);

        Method shouldSkip = JUnitAnalyzer.class.getDeclaredMethod(
                "shouldSkipCompileDependentExecutionAfterCompile");
        shouldSkip.setAccessible(true);

        boolean previousDisabled = disabledField.getBoolean(null);
        boolean previousSkippedLogged = skippedLoggedField.getBoolean(null);
        try {
            disabledField.setBoolean(null, false);
            skippedLoggedField.setBoolean(null, false);
            Assertions.assertFalse((boolean) shouldSkip.invoke(null));
            Assertions.assertFalse(skippedLoggedField.getBoolean(null));

            disabledField.setBoolean(null, true);
            skippedLoggedField.setBoolean(null, false);
            Assertions.assertTrue((boolean) shouldSkip.invoke(null));
            Assertions.assertTrue(skippedLoggedField.getBoolean(null));

            skippedLoggedField.setBoolean(null, true);
            Assertions.assertTrue((boolean) shouldSkip.invoke(null));
            Assertions.assertTrue(skippedLoggedField.getBoolean(null));
        } finally {
            disabledField.setBoolean(null, previousDisabled);
            skippedLoggedField.setBoolean(null, previousSkippedLogged);
        }
    }

}
