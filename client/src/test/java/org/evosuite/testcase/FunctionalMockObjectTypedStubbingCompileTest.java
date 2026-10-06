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

import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A functional mock whose variable is typed {@code Object} (a type-variable parameter of a
 * constructor erases to it) still has its stubbed method called on the declared variable, and
 * {@code Object} has no {@code compareTo}. The rendered code is compiled, not string-matched.
 */
public class FunctionalMockObjectTypedStubbingCompileTest {

    @Test
    public void stubbingOnObjectTypedMockCompiles() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        FunctionalMockStatement mock = new FunctionalMockStatement(
                tc, Object.class, GenericClassFactory.get(Comparable.class));
        tc.addStatement(mock);
        VariableReference zero = tc.addStatement(
                new org.evosuite.testcase.statements.numeric.IntPrimitiveStatement(tc, 0));
        Method compareTo = Comparable.class.getMethod("compareTo", Object.class);
        MethodDescriptor descriptor =
                new MethodDescriptor(compareTo, GenericClassFactory.get(Comparable.class));
        descriptor.increaseCounter();
        mock.addMethodStubbing(descriptor, Collections.singletonList(zero));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        source.append("import static org.mockito.Mockito.*;\n")
                .append("import static org.mockito.ArgumentMatchers.*;\n");
        String className = "ObjectTypedMockProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-object-typed-mock", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
