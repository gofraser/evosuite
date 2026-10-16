/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 2.1 of the License, or (at your option) any later
 * version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testcase;

import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers inconvertible casts from a static nested generic subclass. */
public class StaticNestedParameterizedCastCompileTest {

    public static class Outer<T> {
    }

    public static class Holder<U> {
        public static class D extends Outer<String> {
        }
    }

    public static Outer<Integer> target;

    @Test
    public void testStaticNestedSubclassCastCompiles() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference value = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.D.class.getDeclaredConstructor(), Holder.D.class),
                Collections.emptyList()));
        Field targetField = StaticNestedParameterizedCastCompileTest.class.getField("target");
        FieldReference targetReference = new FieldReference(tc,
                new GenericField(targetField, StaticNestedParameterizedCastCompileTest.class));
        tc.addStatement(new AssignmentStatement(tc, targetReference, value));

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
        String className = "StaticNestedParameterizedCastProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-static-nested-parameterized-cast", className, source.toString());
        assertTrue(result.success,
                "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
