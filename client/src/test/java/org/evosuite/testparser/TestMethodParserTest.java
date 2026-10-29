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
package org.evosuite.testparser;

import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMethodParserTest {

    private final TestMethodParser parser = new TestMethodParser();

    @Test
    void parseSourceRewritesAliasImportToFullyQualifiedName() {
        String source = "import org.junit.Test;\n"
                + "import java.util.List as Items;\n"
                + "public class T {\n"
                + "    @Test public void t() { Items x = null; }\n"
                + "}\n";
        CompilationUnit cu = parser.parseSource(source);
        assertEquals(1, parser.findTestMethods(cu).size());
        assertTrue(cu.toString().contains("java.util.List x"));
        assertFalse(cu.toString().contains("Items"));
    }

    @Test
    void recognisesTestAnnotationsInSourceText() {
        assertTrue(TestMethodParser.mentionsTestAnnotation("@Test void a() {}"));
        assertTrue(TestMethodParser.mentionsTestAnnotation("@org.junit.jupiter.api.Test void a() {}"));
        assertFalse(TestMethodParser.mentionsTestAnnotation("void a() {}"));
        assertFalse(TestMethodParser.mentionsTestAnnotation(null));
    }

    @Test
    void aliasRecoveryLeavesLiteralsCommentsAndTextBlocksAlone() {
        String src = "import a.b.Real as Alias;\n"
                + "class T {\n"
                + "  // Alias in a line comment\n"
                + "  /* Alias in a block comment */\n"
                + "  Object o = new Alias(); String s = \"Alias \\\" Alias\"; char c = 'A'; long l = 1L;\n"
                + "  String t = \"\"\"\n    Alias \\\"\"\" Alias\n    \"\"\"; Alias x;\n"
                + "}\n";
        assertEquals("class T {\n"
                + "  // Alias in a line comment\n"
                + "  /* Alias in a block comment */\n"
                + "  Object o = new a.b.Real(); String s = \"Alias \\\" Alias\"; char c = 'A'; long l = 1L;\n"
                + "  String t = \"\"\"\n    Alias \\\"\"\" Alias\n    \"\"\"; a.b.Real x;\n"
                + "}\n", TestMethodParser.sanitizeAliasImports(src));
    }

    @Test
    void aliasRecoveryReplacesWholeTokensOnceWithOverlappingAliases() {
        String src = "import x.Foo as Bar;\nimport y.Bar as Baz;\nimport z.Q as Q2;\n"
                + "class T { Bar a; Baz b; Bars c; Q2 d; Q2s e; my_Bar f; Bar$In g; }\n";
        assertEquals("class T { x.Foo a; y.Bar b; Bars c; z.Q d; Q2s e; my_Bar f; Bar$In g; }\n",
                TestMethodParser.sanitizeAliasImports(src));
    }
}
