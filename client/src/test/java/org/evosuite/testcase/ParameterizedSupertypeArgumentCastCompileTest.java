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

import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An argument whose class implements G&lt;Map&lt;?, ?&gt;&gt; cannot be cast directly to
 * G&lt;Object&gt; (Jackson's MapSerializer passed as JsonSerializer&lt;Object&gt;).
 */
public class ParameterizedSupertypeArgumentCastCompileTest {

    public abstract static class Ser<T> {
    }

    public static class MapSer extends Ser<Map<?, ?>> {
    }

    public static class ObjSer extends Ser<Object> {
    }

    public static String construct(Ser<Object> a) {
        return "x";
    }

    @Test
    public void argumentWithParameterizedSupertypeArgumentCompiles() throws Exception {
        String code = renderAndCompile(MapSer.class);
        assertTrue(code.contains(".Ser) "), code);
    }

    @Test
    public void argumentWithIdenticalSupertypeArgumentKeepsSingleCast() throws Exception {
        String code = renderAndCompile(ObjSer.class);
        assertFalse(code.contains(".Ser) "), code);
    }

    private String renderAndCompile(Class<?> argumentClass) throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        VariableReference value = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(argumentClass.getDeclaredConstructor(), argumentClass),
                Collections.emptyList()));
        GenericMethod construct = new GenericMethod(
                ParameterizedSupertypeArgumentCastCompileTest.class.getMethod("construct", Ser.class),
                ParameterizedSupertypeArgumentCastCompileTest.class);
        tc.addStatement(new MethodStatement(tc, construct, null, Collections.singletonList(value)));

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
        String className = "ParameterizedSupertypeArgumentCastProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-parameterized-supertype-argument-cast", className, source.toString());
        assertTrue(result.success,
                "Rendered test did not compile:\n" + source + "\n" + result.stderr);
        return code;
    }
}
