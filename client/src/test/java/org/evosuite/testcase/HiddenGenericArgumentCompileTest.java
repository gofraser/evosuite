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

import com.examples.with.different.packagename.HiddenGenericArgumentFixture;
import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class HiddenGenericArgumentCompileTest {
    @Test
    public void nonNullHiddenSubtypePassedToGenericMethodCompiles() throws Exception {
        renderAndCompile(HiddenGenericArgumentFixture.hiddenBase());
    }

    @Test
    public void sameHiddenTypeGenericArgumentStillCompiles() throws Exception {
        renderAndCompile(HiddenGenericArgumentFixture.hiddenChild());
    }

    @Test
    public void accessibleGenericArgumentCastStillCompiles() throws Exception {
        renderAndCompile(HiddenGenericArgumentFixture.Api.class);
    }

    @Test
    public void boundedGenericArgumentWithUnrelatedMarkerInterfaceCompiles() throws Exception {
        renderAndCompile(HiddenGenericArgumentFixture.serializableChild(),
                HiddenGenericArgumentFixture.serializableChild());
    }

    @Test
    public void boundedGenericSubtypeArgumentWithUnrelatedMarkerInterfaceCompiles() throws Exception {
        renderAndCompile(HiddenGenericArgumentFixture.serializableChild(),
                HiddenGenericArgumentFixture.serializableGrandChild());
    }

    private void renderAndCompile(Class<?> genericType) throws Exception {
        renderAndCompile(genericType, HiddenGenericArgumentFixture.hiddenChild());
    }

    private void renderAndCompile(Class<?> genericType, Class<?> child) throws Exception {
        String oldPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "org.evosuite.testcase";
        try {
            TestCase test = new DefaultTestCase();
            VariableReference instance = test.addStatement(new ConstructorStatement(test,
                    new GenericConstructor(child.getConstructor(), child), Collections.emptyList()));
            GenericMethod consumer = new GenericMethod(HiddenGenericArgumentFixture.class.getMethod(
                    "consume", HiddenGenericArgumentFixture.Api.class), HiddenGenericArgumentFixture.class);
            // Bind the method directly; this regression does not depend on random generic choices.
            consumer.setTypeParameters(Collections.singletonList(GenericClassFactory.get(genericType)));
            assertEquals(genericType, consumer.getParameterTypes()[0]);
            test.addStatement(new MethodStatement(test, consumer, null,
                    Collections.singletonList(instance)));
            if (!java.io.Serializable.class.isAssignableFrom(child)) {
                test.addStatement(new MethodStatement(test,
                        new GenericMethod(HiddenGenericArgumentFixture.Api.class.getMethod("value"),
                                HiddenGenericArgumentFixture.Api.class), instance, Collections.emptyList()));
            }
            TestCodeVisitor visitor = new TestCodeVisitor();
            test.accept(visitor);
            StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
            for (Class<?> imported : visitor.getImports()) {
                source.append("import ").append(imported.getCanonicalName()).append(";\n");
            }
            String name = "HiddenGenericArgumentProbe";
            source.append("public class ").append(name)
                    .append(" { public static void run() throws Throwable {\n")
                    .append(visitor.getCode()).append("}}\n");
            JavacScratchCompiler.Result compiled = JavacScratchCompiler.compile(
                    "hidden-generic-argument", name, source.toString());
            assertTrue(compiled.success, source + "\n" + compiled.stderr);
            assertTrue(visitor.getCode().contains("constructor0.newInstance()"), visitor.getCode());
            assertFalse(visitor.getCode().contains("consume(null)"), visitor.getCode());
        } finally {
            Properties.CLASS_PREFIX = oldPrefix;
        }
    }
}
