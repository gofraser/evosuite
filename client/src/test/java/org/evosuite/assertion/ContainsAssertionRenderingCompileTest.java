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
package org.evosuite.assertion;

import com.examples.with.different.packagename.ObjectTypedCollectionSource;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code ContainsAssertion} is created from the variable as the observer saw it (narrowed to
 * its runtime class by {@code Scope#setObject}), but the production visitor renders the
 * declaration from the variable's type in the final test. For a method declared to return
 * {@code Object} that is {@code Object}, and {@code object0.contains(7)} does not compile. The
 * rendered code is compiled here, not string-matched.
 */
public class ContainsAssertionRenderingCompileTest {

    private static String render(String methodName, boolean contains) throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference source = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(ObjectTypedCollectionSource.class.getConstructor(),
                        ObjectTypedCollectionSource.class), 0, 0);
        VariableReference items = TestFactory.getInstance().addMethodFor(tc, source,
                new GenericMethod(ObjectTypedCollectionSource.class.getMethod(methodName),
                        ObjectTypedCollectionSource.class), 1);

        ContainsAssertion assertion = new ContainsAssertion();
        assertion.source = items;
        assertion.containedVariable = new ConstantValue(tc, GenericClassFactory.get(int.class), 7);
        assertion.value = contains;
        tc.getStatement(items.getStPosition()).addAssertion(assertion);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = "import com.examples.with.different.packagename.ObjectTypedCollectionSource;\n"
                + "import java.util.List;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "public class ContainsSnippet {\n  public void test0() throws Throwable {\n"
                + visitor.getCode() + "\n  }\n}\n";
        JavacScratchCompiler.Result compiled = JavacScratchCompiler.compile(
                "evosuite-contains-object", "ContainsSnippet", code);
        assertTrue(compiled.success, code + "\n" + compiled.stderr);
        return code;
    }

    @Test
    public void containsOnVariableRenderedAsObjectIsOmittedAndSuiteCompiles() throws Exception {
        String code = render("items", false);
        assertFalse(code.contains(".contains("), code);
    }

    @Test
    public void containsOnVariableRenderedAsListIsStillEmitted() throws Exception {
        String code = render("typedItems", true);
        assertTrue(code.contains("assertTrue(list0.contains(7))"), code);
    }
}
