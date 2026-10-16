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
package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on weka: {@code CoverTree.MyHeap} hides the inherited {@code NearestNeighbourSearch.MyHeap}.
 * Creating the inherited one on a {@code CoverTree} instance as {@code coverTree0.new MyHeap(1)} makes
 * javac pick the subclass's member, so the result cannot be assigned to the inherited type.
 */
public class HiddenInnerClassCreationCompileTest {

    public static class Base {
        public class Heap {
            public Heap(int size) {
            }
        }
    }

    public static class Derived extends Base {
        public class Heap {
            public Heap(int size) {
            }
        }
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = HiddenInnerClassCreationCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void inheritedInnerClassHiddenBySubclassMemberCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference derived = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Derived.class.getConstructor(), Derived.class),
                Arrays.asList()));
        VariableReference intValue = tc.addStatement(
                new org.evosuite.testcase.statements.numeric.IntPrimitiveStatement(tc, 1));
        // The outer instance is a Derived, the constructor is that of the inherited Base.Heap.
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Base.Heap.class.getConstructor(Base.class, int.class),
                        Base.Heap.class),
                Arrays.asList(derived, intValue)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "HiddenInnerProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-hidden-inner", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
