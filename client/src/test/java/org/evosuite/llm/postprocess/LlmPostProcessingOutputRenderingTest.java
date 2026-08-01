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
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.junit.writer.TestSuiteWriter;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestPresentationMetadata;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.DoublePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPostProcessingOutputRenderingTest {

    private static LlmPostProcessingEditApplier.ApplyResult apply(
            TestCase test, LlmPostProcessingReferences references, LlmPostProcessingResponse response) {
        return apply(test, references, response, true, null);
    }

    private static LlmPostProcessingEditApplier.ApplyResult apply(
            TestCase test, LlmPostProcessingReferences references, LlmPostProcessingResponse response,
            boolean assertionsAllowed) {
        return apply(test, references, response, assertionsAllowed, null);
    }

    private static LlmPostProcessingEditApplier.ApplyResult apply(
            TestCase test, LlmPostProcessingReferences references, LlmPostProcessingResponse response,
            boolean assertionsAllowed, ExecutionResult executionResult) {
        return LlmPostProcessingEditApplier.apply(test, references, response, assertionsAllowed,
                executionResult, PostProcessingOptions.fromProperties());
    }

    private final Properties.OutputFormat originalOutputFormat = Properties.TEST_FORMAT;
    private final boolean originalTestScaffolding = Properties.TEST_SCAFFOLDING;
    private final boolean originalNoRuntimeDependency = Properties.NO_RUNTIME_DEPENDENCY;
    private final Properties.OutputGranularity originalOutputGranularity = Properties.OUTPUT_GRANULARITY;
    private final boolean originalReplaceCalls = Properties.REPLACE_CALLS;
    private final boolean originalVirtualFs = Properties.VIRTUAL_FS;
    private final boolean originalResetStaticFields = Properties.RESET_STATIC_FIELDS;
    private final boolean originalVirtualNet = Properties.VIRTUAL_NET;
    private final boolean originalReplaceGui = Properties.REPLACE_GUI;
    private final boolean originalUseSeparateClassLoader = Properties.USE_SEPARATE_CLASSLOADER;
    private final String[] originalIgnoreThreads = Properties.IGNORE_THREADS;
    private final String originalClassPrefix = Properties.CLASS_PREFIX;
    private final Properties.TestNamingStrategy originalTestNamingStrategy = Properties.TEST_NAMING_STRATEGY;

    private static final class RenderingOnlyWriter extends TestSuiteWriter {
        @Override
        protected ExecutionResult runTest(TestCase test) {
            ExecutionResult result = new ExecutionResult(test);
            result.setTrace(new ExecutionTraceImpl());
            return result;
        }
    }

    @BeforeEach
    void configureProperties() {
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.TEST_SCAFFOLDING = false;
        Properties.NO_RUNTIME_DEPENDENCY = true;
        Properties.OUTPUT_GRANULARITY = Properties.OutputGranularity.MERGED;
        Properties.REPLACE_CALLS = false;
        Properties.VIRTUAL_FS = false;
        Properties.RESET_STATIC_FIELDS = false;
        Properties.VIRTUAL_NET = false;
        Properties.REPLACE_GUI = false;
        Properties.USE_SEPARATE_CLASSLOADER = true;
        Properties.IGNORE_THREADS = new String[]{};
        Properties.CLASS_PREFIX = "";
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
    }

    @AfterEach
    void restoreProperties() {
        Properties.TEST_FORMAT = originalOutputFormat;
        Properties.TEST_SCAFFOLDING = originalTestScaffolding;
        Properties.NO_RUNTIME_DEPENDENCY = originalNoRuntimeDependency;
        Properties.OUTPUT_GRANULARITY = originalOutputGranularity;
        Properties.REPLACE_CALLS = originalReplaceCalls;
        Properties.VIRTUAL_FS = originalVirtualFs;
        Properties.RESET_STATIC_FIELDS = originalResetStaticFields;
        Properties.VIRTUAL_NET = originalVirtualNet;
        Properties.REPLACE_GUI = originalReplaceGui;
        Properties.USE_SEPARATE_CLASSLOADER = originalUseSeparateClassLoader;
        Properties.IGNORE_THREADS = originalIgnoreThreads;
        Properties.CLASS_PREFIX = originalClassPrefix;
        Properties.TEST_NAMING_STRATEGY = originalTestNamingStrategy;
    }

    @Test
    void writeTestSuite_rendersAcceptedNamesCommentsSectionBreaksAndFinalScopeAssertion() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"testName\":\"usesReadablePostProcessedOutput\","
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"Keep the observed count.\"}],"
                        + "\"sectionBreaksAfter\":[\"s0\"],"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v0\","
                        + "\"purpose\":\"The count remains stable.\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();
        apply(test, references, response);

        String code = writeCompileAndRunSuite(test, "UnifiedReadableOutputTest",
                "usesReadablePostProcessedOutput");

        assertTrue(code.contains("public void usesReadablePostProcessedOutput()"), code);
        assertTrue(code.contains("int count = 7;"), code);
        assertTrue(code.contains("// Keep the observed count."), code);
        assertTrue(code.contains("assertEquals(7, count); // The count remains stable."), code);
        assertTrue(code.indexOf("// Keep the observed count.") < code.indexOf("int count = 7;"), code);
        assertTrue(code.indexOf("int count = 7;") < code.indexOf("int int0 = 8;"), code);
        assertTrue(code.indexOf("int int0 = 8;") < code.indexOf("assertEquals(7, count);"), code);
    }

    @Test
    void renderedSourceMatchesGoldenDigest() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"testName\":\"usesReadablePostProcessedOutput\","
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"Keep the observed count.\"}],"
                        + "\"sectionBreaksAfter\":[\"s0\"],"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v0\","
                        + "\"purpose\":\"The count remains stable.\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();
        apply(test, references, response);

        String code = writeCompileAndRunSuite(test, "GoldenReadableOutputTest",
                "usesReadablePostProcessedOutput");

        assertEquals("26538cc06e8d0e1ec166be9db2fc721be72de03760d6ce18743672c69157bdac",
                sha256(code));
    }

    @Test
    void generatedJUnit3JUnit4AndJUnit5SuitesCompileAndExecuteUnifiedAssertions() throws Exception {
        for (Properties.OutputFormat format : new Properties.OutputFormat[]{
                Properties.OutputFormat.JUNIT3,
                Properties.OutputFormat.JUNIT4,
                Properties.OutputFormat.JUNIT5}) {
            Properties.TEST_FORMAT = format;
            DefaultTestCase test = executableAssertionCoverageTest();

            String code = writeCompileAndRunSuite(test, "UnifiedExecutable" + format.name() + "Test",
                    "test0");

            assertTrue(code.contains("assertEquals(7, java.lang.Math.abs(value));"), code);
            assertTrue(code.contains("assertEquals(new java.math.BigInteger(\"7\"), "
                    + "java.math.BigInteger.valueOf(7L));"), code);
            if (format == Properties.OutputFormat.JUNIT3) {
                assertTrue(code.contains("assertTrue(java.util.Arrays.equals(new String[] { null }, values));"),
                        code);
            } else {
                assertTrue(code.contains("assertArrayEquals(new String[] { null }, values);"), code);
            }
        }
    }

    @Test
    void generatedJUnit3NotEqualsLoweringsCompileAndExecute() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT3;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new DoublePrimitiveStatement(test, Double.NaN));

        addTemplateAssertion(test, "notObject", LlmPostProcessingResponse.AssertionKind.NOT_EQUALS,
                "\"left\"", "\"right\"", null, Collections.<String, Integer>emptyMap());
        addTemplateAssertion(test, "notIntegral", LlmPostProcessingResponse.AssertionKind.NOT_EQUALS,
                "7", "8", null, Collections.<String, Integer>emptyMap());
        addTemplateAssertion(test, "notFloatingWithNan", LlmPostProcessingResponse.AssertionKind.NOT_EQUALS,
                "0.0", "v1", "0.01", binding("v1", 1));

        String code = writeCompileAndRunSuite(test, "UnifiedJUnit3NotEqualsTest", "test0");

        assertTrue(code.contains("assertFalse(java.util.Objects.equals(\"left\", \"right\"));"), code);
        assertTrue(code.contains("assertFalse(java.util.Objects.equals(7, 8));"), code);
        assertTrue(code.contains("assertTrue(Double.compare((double)(0.0), (double)(double0)) != 0"), code);
        assertTrue(code.contains("Math.abs((double)(0.0) - (double)(double0)) <= 0.01"), code);
    }

    @Test
    void throwingAssertionIneligibleTestKeepsReadabilityButDoesNotRenderLlmAssertions() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"testName\":\"throwingReadableOnly\","
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"Readable edits still apply.\"}],"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v0\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();
        apply(test, references, response, false);

        String code = writeCompileAndRunSuite(test, "UnifiedThrowingReadableOnlyTest",
                "throwingReadableOnly");

        assertTrue(code.contains("public void throwingReadableOnly()"), code);
        assertTrue(code.contains("int count = 7;"), code);
        assertTrue(code.contains("// Readable edits still apply."), code);
        assertFalse(code.contains("assertEquals(7, count);"), code);
        assertEquals(0, test.getAssertions().size());
    }

    @Test
    void primitiveStatementValuesRemainEquivalentAfterApplyingReadabilityEdits() {
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement intStatement = new IntPrimitiveStatement(test, 7);
        StringPrimitiveStatement stringStatement = new StringPrimitiveStatement(test, "value");
        test.addStatement(intStatement);
        test.addStatement(stringStatement);
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"variableNames\":{\"v0\":\"count\",\"v1\":\"label\"},"
                        + "\"comments\":[{\"afterStatementId\":\"s1\",\"text\":\"Inputs are unchanged.\"}],"
                        + "\"sectionBreaksAfter\":[\"s1\"]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        apply(test, references, response);

        assertEquals(Integer.valueOf(7), intStatement.getValue());
        assertEquals("value", stringStatement.getValue());
    }

    private static DefaultTestCase executableAssertionCoverageTest() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, -7));
        test.addStatement(new ArrayStatement(test, String[].class, 1));
        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(test);
        metadata.putVariableName(0, "value");
        metadata.putVariableName(1, "values");
        addTemplateAssertion(test, "pureStatic", LlmPostProcessingResponse.AssertionKind.EQUALS,
                "7", "java.lang.Math.abs(v0)", null, binding("v0", 0));
        addTemplateAssertion(test, "immutableConstructor", LlmPostProcessingResponse.AssertionKind.EQUALS,
                "new java.math.BigInteger(\"7\")", "java.math.BigInteger.valueOf(7L)",
                null, Collections.<String, Integer>emptyMap());
        addTemplateAssertion(test, "literalArray", LlmPostProcessingResponse.AssertionKind.EQUALS,
                "new String[] { null }", "v1", null, binding("v1", 1));
        return test;
    }

    private static void addTemplateAssertion(DefaultTestCase test,
                                             String assertionId,
                                             LlmPostProcessingResponse.AssertionKind kind,
                                             String expected,
                                             String actual,
                                             String delta,
                                             Map<String, Integer> bindings) {
        TemplateCodeAssertion assertion = new TemplateCodeAssertion(assertionId, kind,
                expected, actual, delta, bindings, "");
        test.getStatement(test.size() - 1).addAssertion(assertion);
    }

    private static Map<String, Integer> binding(String variableId, int position) {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        bindings.put(variableId, position);
        return bindings;
    }

    private static String writeCompileAndRunSuite(DefaultTestCase test, String suiteName,
                                                  String... methodNames) throws Exception {
        Path tempDir = Files.createTempDirectory("evosuite-llm-postprocess-output-");
        try {
            TestSuiteWriter writer = new RenderingOnlyWriter();
            writer.insertTest(test);
            writer.writeTestSuite(suiteName, tempDir.toString(), Collections.emptyList());
            Path sourceFile = tempDir.resolve(suiteName + ".java");
            String code = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
            compileAndRun(tempDir, sourceFile, suiteName, methodNames);
            return code;
        } finally {
            deleteTempDir(tempDir);
        }
    }

    private static void compileAndRun(Path tempDir, Path sourceFile, String suiteName,
                                      String... methodNames) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler is required for generated output verification");
        Path outputDir = tempDir.resolve("classes");
        Files.createDirectories(outputDir);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int compileResult = compiler.run(null, null, errors,
                "-classpath", System.getProperty("java.class.path"),
                "-d", outputDir.toString(),
                sourceFile.toString());
        assertEquals(0, compileResult,
                "Generated suite should compile:\n" + errors.toString(StandardCharsets.UTF_8.name()));

        try (URLClassLoader loader = new URLClassLoader(new URL[]{outputDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            Class<?> suiteClass = Class.forName(suiteName, true, loader);
            Object instance = suiteClass.getDeclaredConstructor().newInstance();
            for (String methodName : methodNames) {
                Method method = suiteClass.getMethod(methodName);
                try {
                    method.invoke(instance);
                } catch (InvocationTargetException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof AssertionError) {
                        throw (AssertionError) cause;
                    }
                    throw new AssertionError("Generated test method failed: " + methodName, cause);
                }
            }
        }
    }

    private static void deleteTempDir(Path tempDir) throws IOException {
        try (Stream<Path> stream = Files.walk(tempDir)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best effort cleanup for test-only directories.
                        }
                    });
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            hex.append(String.format("%02x", item & 0xff));
        }
        return hex.toString();
    }
}
