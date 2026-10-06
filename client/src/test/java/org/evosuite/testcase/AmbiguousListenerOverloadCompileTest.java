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
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on Vuze's DeviceImpl: addListener(DeviceListener) and addListener(TranscodeTargetListener)
 * are both applicable to an argument implementing both interfaces, so the call is ambiguous
 * unless the argument is cast to the selected parameter type.
 */
public class AmbiguousListenerOverloadCompileTest {

    public interface FirstListener {
    }

    public interface SecondListener {
    }

    public static class BothListener implements FirstListener, SecondListener {
    }

    public static class Subject {
        public void addListener(FirstListener listener) {
        }

        public void addListener(SecondListener listener) {
        }
    }

    public static class Base {
        public void addListener(SecondListener listener) {
        }
    }

    public static class Derived extends Base {
        public void addListener(FirstListener listener) {
        }
    }

    public static class SubjectChild extends Subject {
    }

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = AmbiguousListenerOverloadCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
    }

    private void check(Class<?> receiverClass, Class<?> methodOwner, Class<?> parameterType)
            throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference subject = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(receiverClass.getConstructor(), receiverClass),
                Collections.emptyList()));
        VariableReference listener = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(BothListener.class.getConstructor(), BothListener.class),
                Collections.emptyList()));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(methodOwner.getMethod("addListener", parameterType), methodOwner),
                subject, Collections.singletonList(listener)));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "AmbiguousListenerProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : visitor.getCode().split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-ambiguous-listener", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }

    @Test
    public void ambiguousOverloadOnDeclaringReceiverCompiles() throws Exception {
        check(Subject.class, Subject.class, FirstListener.class);
        check(Subject.class, Subject.class, SecondListener.class);
    }

    @Test
    public void ambiguousOverloadOnSubclassReceiverCompiles() throws Exception {
        check(SubjectChild.class, Subject.class, FirstListener.class);
        check(SubjectChild.class, Subject.class, SecondListener.class);
    }

    @Test
    public void ambiguousOverloadSplitAcrossHierarchyCompiles() throws Exception {
        check(Derived.class, Derived.class, FirstListener.class);
        check(Derived.class, Base.class, SecondListener.class);
    }
}
