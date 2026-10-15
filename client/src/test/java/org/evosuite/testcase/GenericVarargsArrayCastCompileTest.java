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
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Arrays.asList(T...)} was bound to T = DefaultSqlFormatter while the
 * argument was a String[], and the rendered {@code (DefaultSqlFormatter[]) stringArray0} cast is
 * rejected by javac as an inconvertible array cast.
 */
public class GenericVarargsArrayCastCompileTest {

    @Test
    public void inconvertibleArrayArgumentIsNotCastToTheBoundTypeArgument() throws Exception {
        String oldPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "org.evosuite.testcase";
        try {
            TestCase tc = new DefaultTestCase();
            ArrayStatement array = new ArrayStatement(tc, String[].class, 1);
            tc.addStatement(array);
            VariableReference text = tc.addStatement(new StringPrimitiveStatement(tc, "AT"));
            tc.addStatement(new AssignmentStatement(tc,
                    new ArrayIndex(tc, array.getArrayReference(), 0), text));
            GenericMethod asList = new GenericMethod(Arrays.class.getMethod("asList", Object[].class),
                    Arrays.class);
            asList.setTypeParameters(Collections.singletonList(GenericClassFactory.get(File.class)));
            VariableReference list = tc.addStatement(new MethodStatement(tc, asList, null,
                    Collections.singletonList(array.getArrayReference())));
            tc.addStatement(new MethodStatement(tc,
                    new GenericMethod(List.class.getMethod("size"), List.class), list,
                    Collections.emptyList()));

            TestCodeVisitor visitor = new TestCodeVisitor();
            tc.accept(visitor);
            StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
            for (Class<?> imported : visitor.getImports()) {
                source.append("import ").append(imported.getCanonicalName()).append(";\n");
            }
            String name = "GenericVarargsArrayProbe";
            source.append("public class ").append(name)
                    .append(" { public static void run() throws Throwable {\n")
                    .append(visitor.getCode()).append("}}\n");
            JavacScratchCompiler.Result compiled = JavacScratchCompiler.compile(
                    "generic-varargs-array", name, source.toString());
            assertTrue(compiled.success, source + "\n" + compiled.stderr);
        } finally {
            Properties.CLASS_PREFIX = oldPrefix;
        }
    }
}
