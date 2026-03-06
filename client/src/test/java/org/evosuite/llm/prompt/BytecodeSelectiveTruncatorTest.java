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
package org.evosuite.llm.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeSelectiveTruncatorTest {

    private final BytecodeSelectiveTruncator truncator = new BytecodeSelectiveTruncator();

    private static final String SAMPLE_BYTECODE =
            "// class version 52.0 (52)\n"
            + "// access flags 0x21\n"
            + "public class com/example/Foo {\n"
            + "\n"
            + "  // access flags 0x2\n"
            + "  private I x\n"
            + "\n"
            + "  // access flags 0x1\n"
            + "  public <init>(I)V\n"
            + "    ALOAD 0\n"
            + "    INVOKESPECIAL java/lang/Object.<init> ()V\n"
            + "    ALOAD 0\n"
            + "    ILOAD 1\n"
            + "    PUTFIELD com/example/Foo.x : I\n"
            + "    RETURN\n"
            + "    MAXSTACK = 2\n"
            + "    MAXLOCALS = 2\n"
            + "\n"
            + "  // access flags 0x1\n"
            + "  public getX()I\n"
            + "    ALOAD 0\n"
            + "    GETFIELD com/example/Foo.x : I\n"
            + "    IRETURN\n"
            + "    MAXSTACK = 1\n"
            + "    MAXLOCALS = 1\n"
            + "\n"
            + "  // access flags 0x1\n"
            + "  public longMethod()Ljava/lang/String;\n"
            + "    LDC \"\"\n"
            + "    ASTORE 1\n"
            + "    ICONST_0\n"
            + "    ISTORE 2\n"
            + "    GOTO L1\n"
            + "   L0\n"
            + "    NEW java/lang/StringBuilder\n"
            + "    DUP\n"
            + "    INVOKESPECIAL java/lang/StringBuilder.<init> ()V\n"
            + "    ALOAD 1\n"
            + "    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    LDC \"iteration\"\n"
            + "    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;\n"
            + "    ILOAD 2\n"
            + "    INVOKEVIRTUAL java/lang/StringBuilder.append (I)Ljava/lang/StringBuilder;\n"
            + "    INVOKEVIRTUAL java/lang/StringBuilder.toString ()Ljava/lang/String;\n"
            + "    ASTORE 1\n"
            + "    IINC 2 1\n"
            + "   L1\n"
            + "    ILOAD 2\n"
            + "    BIPUSH 100\n"
            + "    IF_ICMPLT L0\n"
            + "    ALOAD 1\n"
            + "    ARETURN\n"
            + "    MAXSTACK = 3\n"
            + "    MAXLOCALS = 3\n"
            + "\n"
            + "  // access flags 0x2\n"
            + "  private helper()V\n"
            + "    GETSTATIC java/lang/System.out : Ljava/io/PrintStream;\n"
            + "    LDC \"helper\"\n"
            + "    INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V\n"
            + "    RETURN\n"
            + "    MAXSTACK = 2\n"
            + "    MAXLOCALS = 1\n"
            + "}\n";

    @Test
    void returnsNullForNonBytecodeInput() {
        assertNull(truncator.truncate("not bytecode at all", 10));
    }

    @Test
    void returnsTextWhenWithinBudget() {
        assertEquals(SAMPLE_BYTECODE, truncator.truncate(SAMPLE_BYTECODE, 100_000));
    }

    @Test
    void stubsPrivateMethodsFirst() {
        String result = truncator.truncate(SAMPLE_BYTECODE, SAMPLE_BYTECODE.length() - 30);
        assertNotNull(result);
        // Private method body should be stubbed
        assertTrue(result.contains("// body omitted"), "Should contain stub marker");
        // Public methods should still have their bytecode
        assertTrue(result.contains("GETFIELD com/example/Foo.x"), "Should keep public getX body");
        // Constructor should be kept
        assertTrue(result.contains("INVOKESPECIAL java/lang/Object.<init>"), "Should keep constructor body");
    }

    @Test
    void stubsLargestPublicMethodFirst() {
        // Budget that forces stubbing of the large method
        String result = truncator.truncate(SAMPLE_BYTECODE, 600);
        assertNotNull(result);
        // Small public method getX should be preserved if possible
        assertTrue(result.contains("getX"), "Should keep small public method signature");
        // Large method's body instructions should be gone
        assertFalse(result.contains("StringBuilder"), "Large method body should be stubbed");
    }

    @Test
    void preservesConstructor() {
        String result = truncator.truncate(SAMPLE_BYTECODE, SAMPLE_BYTECODE.length() - 30);
        assertNotNull(result);
        assertTrue(result.contains("<init>"), "Should preserve constructor");
    }

    @Test
    void preservesHeader() {
        String result = truncator.truncate(SAMPLE_BYTECODE, SAMPLE_BYTECODE.length() - 30);
        assertNotNull(result);
        assertTrue(result.contains("public class com/example/Foo"), "Should preserve class header");
        assertTrue(result.contains("private I x"), "Should preserve field declarations");
    }

    @Test
    void returnsNullWhenFullyStubbedStillOverBudget() {
        assertNull(truncator.truncate(SAMPLE_BYTECODE, 50));
    }
}
