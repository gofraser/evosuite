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
package org.evosuite.llm.response;

import org.evosuite.Properties;
import org.evosuite.testparser.TestMethodParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmResponseParserTest {

    private final LlmResponseParser parser = new LlmResponseParser();
    private final Properties.OutputFormat originalFormat = Properties.TEST_FORMAT;

    @AfterEach
    void restoreFormat() {
        Properties.TEST_FORMAT = originalFormat;
    }

    @Test
    void extractsJavaCodeFences() {
        String response = "Here is code:\n```java\n@Test\npublic void t(){}\n```";
        List<String> blocks = parser.extractCodeBlocks(response);

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).contains("public void t"));
    }

    @Test
    void wrapsMethodBodyIntoClass() {
        String response = "```java\n@org.junit.Test\npublic void x(){ int a = 1; }\n```";
        String code = parser.extractAllTestClassesWithMetadata(response, "MyGeneratedTest", null).get(0).getSource();

        assertTrue(code.contains("public class MyGeneratedTest"));
        assertTrue(code.contains("public void x()"));
    }

    @Test
    void extractsAllDetectedCodeBlocksAsClasses() {
        String response = "```java\n"
                + "@org.junit.Test public void first(){ int a = 1; }\n"
                + "```\n"
                + "Some prose in between\n"
                + "```java\n"
                + "@org.junit.Test public void second(){ int b = 2; }\n"
                + "```";

        List<LlmResponseParser.ExtractionResult> results =
                parser.extractAllTestClassesWithMetadata(response, "GeneratedLlmTest", null);

        assertEquals(2, results.size());
        assertTrue(results.get(0).getSource().contains("public void first()"));
        assertTrue(results.get(1).getSource().contains("public void second()"));
    }

    @Test
    void responseWithoutCodeYieldsOneBlockThatHasNoCode() {
        List<LlmResponseParser.ExtractionResult> results =
                parser.extractAllTestClassesWithMetadata("", "MyGeneratedTest", null);

        assertEquals(1, results.size());
        assertFalse(results.get(0).hasCode());
        assertEquals(LlmResponseParser.Recovery.NONE, results.get(0).getRecovery());
    }

    @Test
    void genericFenceWithProseIsNotMisdetectedAsJava() {
        String response = "```\\nIn class design discussions we assert quality and readability.\\n```";
        List<String> blocks = parser.extractCodeBlocks(response);

        assertTrue(blocks.isEmpty());
    }

    @Test
    void recoversTruncatedClassByDroppingIncompleteMethod() {
        String response = "```java\n"
                + "public class ExampleTest {\n"
                + "  @org.junit.Test\n"
                + "  public void complete(){ int x = 1; }\n"
                + "  @org.junit.Test\n"
                + "  public void broken(){ int y =\n"
                + "```";
        LlmResponseParser.ExtractionResult result =
                parser.extractAllTestClassesWithMetadata(response, "MyGeneratedTest", null).get(0);

        assertEquals(LlmResponseParser.Recovery.TRUNCATED, result.getRecovery());
        assertTrue(result.getSource().contains("public void complete()"));
        assertFalse(result.getSource().contains("public void broken()"));
        assertTrue(result.getSource().trim().endsWith("}"));
    }

    @Test
    void keepsValidClassWithoutRecovery() {
        String response = "```java\n"
                + "public class AlreadyValid {\n"
                + "  @org.junit.Test\n"
                + "  public void t(){ int x = 1; }\n"
                + "}\n"
                + "```";

        LlmResponseParser.ExtractionResult result =
                parser.extractAllTestClassesWithMetadata(response, "IgnoredName", null).get(0);

        assertTrue(result.getSource().contains("public class AlreadyValid")
                        || result.getSource().contains("public class IgnoredName"),
                "valid class should remain parseable after extraction/normalization");
        assertTrue(result.getSource().contains("public void t()"));
    }

    /**
     * Regression: Java has no `import ... as ...` syntax. JavaParser silently
     * drops the rest of the file when it hits one, leaving zero @Test methods.
     * The sanitizer must rewrite the alias to the fully qualified name so the
     * downstream parser sees a complete class.
     */
    @Test
    void rewritesAliasImportToFullyQualifiedName() {
        String response = "```java\n"
                + "import net.sourceforge.beanbin.query.Query as BeanBinQuery;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "\n"
                + "class EJB3SearcherTest {\n"
                + "  @Test\n"
                + "  void t() {\n"
                + "    BeanBinQuery q = null;\n"
                + "  }\n"
                + "}\n"
                + "```";

        String source = parser.extractAllTestClassesWithMetadata(response, "Ignored", null).get(0).getSource();

        assertFalse(source.contains(" as BeanBinQuery"),
                "alias import should be removed");
        assertFalse(source.contains("BeanBinQuery"),
                "all alias references should be rewritten to FQN");
        assertTrue(source.contains("net.sourceforge.beanbin.query.Query q = null"),
                "body reference should be rewritten to fully qualified name");
    }

    @Test
    void aliasSanitizerHandlesMultipleAliasesAndPreservesPlainImports() {
        String response = "```java\n"
                + "import javax.persistence.Query;\n"
                + "import net.sourceforge.beanbin.query.Query as BeanBinQuery;\n"
                + "import java.util.List as Lst;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "\n"
                + "class T {\n"
                + "  @Test\n"
                + "  void t() {\n"
                + "    Query q = null;\n"
                + "    BeanBinQuery b = null;\n"
                + "    Lst items = null;\n"
                + "  }\n"
                + "}\n"
                + "```";

        String source = parser.extractAllTestClassesWithMetadata(response, "Ignored", null).get(0).getSource();

        assertFalse(source.contains(" as BeanBinQuery"));
        assertFalse(source.contains(" as Lst"));
        assertTrue(source.contains("import javax.persistence.Query;"),
                "non-aliased imports must be preserved verbatim");
        assertTrue(source.contains("net.sourceforge.beanbin.query.Query b = null"));
        assertTrue(source.contains("java.util.List items = null"));
        assertTrue(source.contains("Query q = null"),
                "non-aliased simple-name reference must be left alone");
    }

    @Test
    void aliasSanitizerLeavesSourcesWithoutAliasesUnchanged() {
        String code = "import java.util.List;\n"
                + "class T { @org.junit.Test public void t(){ List l = null; } }";
        assertEquals(code, TestMethodParser.sanitizeAliasImports(code));
    }

    @Test
    void aliasSanitizerYieldsParseableClassWithTestMethods() {
        String response = "```java\n"
                + "import net.sourceforge.beanbin.query.Query as BeanBinQuery;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "\n"
                + "class EJB3SearcherTest {\n"
                + "  @Test\n"
                + "  void t() {\n"
                + "    BeanBinQuery q = null;\n"
                + "  }\n"
                + "}\n"
                + "```";

        String source = parser.extractAllTestClassesWithMetadata(response, "Ignored", null).get(0).getSource();
        org.evosuite.testparser.TestMethodParser methodParser =
                new org.evosuite.testparser.TestMethodParser();
        com.github.javaparser.ast.CompilationUnit cu = methodParser.parseSource(source);

        assertEquals(1, methodParser.findTestMethods(cu).size(),
                "alias-sanitized source must yield exactly one @Test method");
    }

    @Test
    void normalizesSafeSingleWrapperClassWithOnlyTestMethods() {
        String response = "```java\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class Wrapper {\n"
                + "  @Test void t1(){ int x = 1; }\n"
                + "  @Test void t2(){ int y = 2; }\n"
                + "}\n"
                + "```";

        LlmResponseParser.ExtractionResult result =
                parser.extractAllTestClassesWithMetadata(response, "NormalizedWrapperTest", null).get(0);

        assertEquals(LlmResponseParser.Recovery.WRAPPER_NORMALIZED, result.getRecovery());
        assertTrue(result.getRecoveryReason().contains("wrapper-normalized"));
        assertTrue(result.getSource().contains("public class NormalizedWrapperTest"));
        assertTrue(result.getSource().contains("void t1()"));
        assertTrue(result.getSource().contains("void t2()"));
    }

    @Test
    void doesNotNormalizeWrapperClassWhenHelperMethodExists() {
        String response = "```java\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class WrapperWithHelper {\n"
                + "  private void helper(){ int z = 3; }\n"
                + "  @Test void t1(){ helper(); }\n"
                + "}\n"
                + "```";

        String source = parser.extractAllTestClassesWithMetadata(response, "NormalizedWrapperTest", null).get(0).getSource();

        assertTrue(source.contains("class WrapperWithHelper"));
        assertFalse(source.contains("public class NormalizedWrapperTest"),
                "unsafe wrapper should not be rewritten");
    }

    @Test
    void rewritesReflectiveAssertThrowsOnTheExtractedSource() {
        String response = "```java\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "import java.lang.reflect.Method;\n"
                + "public class ReflectiveTest {\n"
                + "  @org.junit.jupiter.api.Test\n"
                + "  public void test0() throws Throwable {\n"
                + "    Method method0 = Object.class.getDeclaredMethod(\"toString\");\n"
                + "    assertThrows(IllegalStateException.class, () -> method0.invoke(null));\n"
                + "  }\n"
                + "}\n"
                + "```";

        LlmResponseParser.ExtractionResult result =
                parser.extractAllTestClassesWithMetadata(response, "Ignored", null).get(0);

        assertEquals(1, result.getReflectiveRewrites());
        assertNull(result.getInvalidSource());
        assertTrue(result.getSource().contains("java.lang.reflect.InvocationTargetException invocationTargetException0"),
                result.getSource());
        assertTrue(result.getSource().contains("(IllegalStateException) invocationTargetException0.getCause()"),
                result.getSource());
    }

    private static final String REFLECTIVE_RESPONSE = "```java\n"
            + "import static org.junit.Assert.*;\n"
            + "import java.lang.reflect.Method;\n"
            + "public class ReflectiveTest {\n"
            + "  @org.junit.Test\n"
            + "  public void test0() {\n"
            + "    Method method0 = Object.class.getDeclaredMethod(\"toString\");\n"
            + "    assertThrows(IllegalStateException.class, () -> method0.invoke(null));\n"
            + "  }\n"
            + "}\n"
            + "```";

    private static java.io.File jarOf(Class<?> type) throws Exception {
        return new java.io.File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static boolean compiles(String source, String classpath) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("reflective");
        java.nio.file.Path file = dir.resolve("Ignored.java");
        java.nio.file.Files.write(file, source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int status = compiler.run(null, null, err, "-proc:none", "-d", dir.toString(),
                "-classpath", classpath, file.toString());
        if (status != 0) {
            System.err.println(err);
        }
        return status == 0;
    }

    @Test
    void reflectiveAssertThrowsUsesTryFailCatchUnderJunit4() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;

        String source = parser.extractAllTestClassesWithMetadata(REFLECTIVE_RESPONSE, "Ignored", null)
                .get(0).getSource();

        assertFalse(source.contains("jupiter"), source);
        assertFalse(source.contains("assertThrows"), source);
        assertTrue(source.contains("org.junit.Assert.fail(\"Expecting exception: "
                + "java.lang.reflect.InvocationTargetException\")"), source);
        assertTrue(source.contains("(IllegalStateException) invocationTargetException0.getCause()"), source);
        assertTrue(source.contains("throws Throwable"), source);
        // Only JUnit 4 (and its Hamcrest) on the compile classpath: no Jupiter.
        String junit4Only = jarOf(org.junit.Assert.class) + java.io.File.pathSeparator
                + jarOf(org.hamcrest.Matcher.class);
        assertTrue(compiles(source, junit4Only), source);
    }

    @Test
    void reflectiveAssertThrowsUsesTryFailCatchUnderJunit3() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT3;

        String source = parser.extractAllTestClassesWithMetadata(REFLECTIVE_RESPONSE, "Ignored", null)
                .get(0).getSource();

        assertTrue(source.contains("junit.framework.Assert.fail("), source);
        assertFalse(source.contains("jupiter"), source);
    }

    @Test
    void reflectiveAssertThrowsKeepsJupiterUnderJunit5() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;

        String source = parser.extractAllTestClassesWithMetadata(REFLECTIVE_RESPONSE, "Ignored", null)
                .get(0).getSource();

        assertTrue(source.contains("org.junit.jupiter.api.Assertions.assertThrows("), source);
    }

    @Test
    void recoveredSourceIsNormalizedWithoutRewrites() {
        String response = "```java\n"
                + "public class ExampleTest {\n"
                + "  @org.junit.Test\n"
                + "  public void complete(){ int x = 1; }\n"
                + "  @org.junit.Test\n"
                + "  public void broken(){ int y =\n"
                + "```";

        LlmResponseParser.ExtractionResult result =
                parser.extractAllTestClassesWithMetadata(response, "Ignored", null).get(0);

        assertEquals(LlmResponseParser.Recovery.TRUNCATED, result.getRecovery());
        assertEquals(0, result.getReflectiveRewrites());
        assertNull(result.getInvalidSource());
    }

    @Test
    void deeplyNestedExpressionIsAParseFailureNotAnError() {
        StringBuilder nested = new StringBuilder();
        int depth = 100000;
        for (int i = 0; i < depth; i++) {
            nested.append('(');
        }
        nested.append('1');
        for (int i = 0; i < depth; i++) {
            nested.append(')');
        }
        String response = "```java\n@org.junit.Test\npublic void deep(){ int x = " + nested + "; }\n```";

        List<LlmResponseParser.ExtractionResult> results =
                assertDoesNotThrow(() -> parser.extractAllTestClassesWithMetadata(response, "DeepTest", null));

        assertEquals(1, results.size());
    }
}
