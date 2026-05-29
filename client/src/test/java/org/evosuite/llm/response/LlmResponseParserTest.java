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
        String code = parser.extractTestClass(response, "MyGeneratedTest");

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
    void emptyFallbackUsesJUnit4ByDefault() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        String code = parser.extractTestClass("", "MyGeneratedTest");

        assertTrue(code.contains("@org.junit.Test"));
    }

    @Test
    void emptyFallbackUsesJUnit5WhenConfigured() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        String code = parser.extractTestClass("", "MyGeneratedTest");

        assertTrue(code.contains("@org.junit.jupiter.api.Test"));
        assertFalse(code.contains("@org.junit.Test"));
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
                parser.extractTestClassWithMetadata(response, "MyGeneratedTest", null);

        assertTrue(result.isRecoveryApplied());
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
                parser.extractTestClassWithMetadata(response, "IgnoredName", null);

        assertFalse(result.isRecoveryApplied());
        assertTrue(result.getSource().contains("public class AlreadyValid"));
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

        String source = parser.extractTestClass(response, "Ignored");

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

        String source = parser.extractTestClass(response, "Ignored");

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
        assertEquals(code, LlmResponseParser.sanitizeAliasImports(code));
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

        String source = parser.extractTestClass(response, "Ignored");
        org.evosuite.testparser.TestMethodParser methodParser =
                new org.evosuite.testparser.TestMethodParser();
        com.github.javaparser.ast.CompilationUnit cu = methodParser.parseSource(source);

        assertEquals(1, methodParser.findTestMethods(cu).size(),
                "alias-sanitized source must yield exactly one @Test method");
    }
}
