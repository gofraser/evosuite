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

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on Jillion's {@code DefaultContig.Builder} (a static nested class of the generic
 * {@code DefaultContig}): the non-generic builder inherits
 * {@code addAllReads(Iterable<P>)} from {@code AbstractContigBuilder<AssembledRead, ...>}, and
 * an {@code ArrayList<AceAssembledReadAdapter>} was passed to it. The call is checked against
 * the receiver's (inherited) parameterization, {@code Iterable<AssembledRead>}, which is
 * invariant in its element type.
 */
public class InheritedTypeArgumentCallCompileTest {

    public static class Base<P extends CharSequence> {
        public void addAll(Iterable<P> items) {
        }
    }

    public static class Holder<T> {
        // A static nested class of a generic outer: gentyref treats it as raw.
        public static class Derived extends Base<CharSequence> {
        }
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = InheritedTypeArgumentCallCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    @Test
    public void testIterableOfSubtypeToInheritedInvariantParameterCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference derived = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.Derived.class.getConstructor(), Holder.Derived.class),
                Arrays.asList()));
        Type listType = TypeUtils.parameterize(ArrayList.class, Integer.class);
        VariableReference list = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(ArrayList.class.getConstructor(), listType),
                Arrays.asList()));
        // The method model as the search sees it: owner Derived, parameter Iterable<P> resolved through the superclass.
        GenericMethod addAll = new GenericMethod(Base.class.getMethod("addAll", Iterable.class),
                Holder.Derived.class);
        tc.addStatement(new MethodStatement(tc, addAll, derived, Arrays.asList(list)));

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
        String className = "InheritedArgProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-inherited-arg", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
