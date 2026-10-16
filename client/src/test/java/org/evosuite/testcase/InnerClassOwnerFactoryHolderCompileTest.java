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
 * You should have received a copy of the GNU Lesser Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testcase;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers factory owner binding when the enclosing value is a static nested generic subclass. */
public class InnerClassOwnerFactoryHolderCompileTest {

    private double originalObjectReuseProbability;

    @BeforeEach
    public void setUp() {
        originalObjectReuseProbability = Properties.OBJECT_REUSE_PROBABILITY;
        Properties.OBJECT_REUSE_PROBABILITY = 1.0;
    }

    @AfterEach
    public void tearDown() {
        Properties.OBJECT_REUSE_PROBABILITY = originalObjectReuseProbability;
    }

    public static class Outer<E> {
        public E value;

        public class Inner {
            public E next() {
                return value;
            }

            public void set(E value) {
                Outer.this.value = value;
            }
        }
    }

    public static class FactoryHolder<U> {
        public static class NestedDerived extends Outer<String> {
        }
    }

    public static int useString(String value) {
        return value == null ? 0 : value.length();
    }

    @Test
    public void testFactoryBindsStaticNestedSubclassOwnerThroughCloneAndMutation()
            throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(FactoryHolder.NestedDerived.class.getConstructor(),
                        FactoryHolder.NestedDerived.class),
                Arrays.asList()));
        Type modelOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, Integer.class), Outer.Inner.class);
        GenericConstructor innerConstructor = new GenericConstructor(
                Outer.Inner.class.getConstructor(Outer.class), modelOwner);
        VariableReference inner = TestFactory.getInstance().addConstructor(
                tc, innerConstructor, tc.size(), 0);
        Type boundOwner = TypeUtils.parameterizeWithOwner(
                TypeUtils.parameterize(Outer.class, String.class), Outer.Inner.class);
        tc.addStatement(new StringPrimitiveStatement(tc, "value"));
        TestFactory.getInstance().addMethodFor(
                tc, inner,
                new GenericMethod(Outer.Inner.class.getMethod("set", Object.class),
                        boundOwner),
                tc.size(), new GenerationContext());
        VariableReference element = TestFactory.getInstance().addMethodFor(
                tc, inner,
                new GenericMethod(Outer.Inner.class.getMethod("next"), boundOwner),
                tc.size(), new GenerationContext());
        GenericMethod use = new GenericMethod(
                InnerClassOwnerFactoryHolderCompileTest.class.getMethod("useString", String.class),
                InnerClassOwnerFactoryHolderCompileTest.class);
        tc.addStatement(new MethodStatement(tc, use, null, Arrays.asList(element)));

        String originalCode = assertCompiles(tc);
        assertTrue(originalCode.contains(".set("), originalCode);
        assertTrue(originalCode.contains("Outer<String>.Inner"), originalCode);
        DefaultTestCase clone = tc.clone();
        String cloneCode = assertCompiles(clone);
        assertTrue(cloneCode.contains("Outer<String>.Inner"), cloneCode);

        Statement clonedInner = clone.getStatement(inner.getStPosition());
        TestFactory.getInstance().changeCall(clone, clonedInner,
                new GenericConstructor(Outer.Inner.class.getConstructor(Outer.class), modelOwner));
        String mutatedCode = assertCompiles(clone);
        assertTrue(mutatedCode.contains("Outer<String>.Inner"), mutatedCode);
    }

    private static String assertCompiles(TestCase tc) {
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
        String className = "FactoryHolderProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-inner-factory-holder", className, source.toString());
        assertTrue(result.success,
                "Rendered test did not compile:\n" + source + "\n" + result.stderr);
        return code;
    }
}
