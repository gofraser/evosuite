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
import java.util.Collections;
import java.util.List;
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


}
