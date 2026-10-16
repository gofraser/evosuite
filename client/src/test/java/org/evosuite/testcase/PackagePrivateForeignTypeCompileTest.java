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

import com.examples.with.different.packagename.hiddenpkg.Visible;
import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Observed on corina, whose class under test builds an xerces {@code PSVIErrorList}: a
 * package-private class of another package with a public constructor. The reflective-constructor
 * fallback declared the variable, and cast the result, with the class's own name, which javac
 * rejects with 'is not public in org.apache.xerces.impl.xs; cannot be accessed from outside
 * package'. The declaration has to use an accessible supertype instead.
 */
public class PackagePrivateForeignTypeCompileTest {

    private String originalTargetClass;
    private String originalClassPrefix;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalClassPrefix = Properties.CLASS_PREFIX;
        Properties.TARGET_CLASS = PackagePrivateForeignTypeCompileTest.class.getName();
        Properties.resetTargetClass();
        // Generated tests live in the package of the class under test.
        Properties.CLASS_PREFIX = PackagePrivateForeignTypeCompileTest.class.getPackage().getName();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
        Properties.CLASS_PREFIX = originalClassPrefix;
    }

    @Test
    public void packagePrivateClassOfAnotherPackageIsNotNamedInTheTest() throws Exception {
        Class<?> hidden = Class.forName(Visible.class.getPackage().getName() + ".Hidden");
        Constructor<?> constructor = hidden.getDeclaredConstructor(String[].class, boolean.class);

        TestCase tc = new DefaultTestCase();
        ArrayReference array = new ArrayReference(tc, GenericClassFactory.get(String[].class), 2);
        tc.addStatement(new ArrayStatement(tc, array));
        VariableReference flag = tc.addStatement(new BooleanPrimitiveStatement(tc, false));
        tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(constructor, hidden), Arrays.asList(array, flag)));

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
        String className = "PackagePrivateForeignProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");

        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-package-private-foreign", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
