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

import com.examples.with.different.packagename.UnexportedPeerFixture;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.evosuite.testcase.variable.VariableReference;
import java.lang.reflect.Method;
import org.evosuite.setup.TestUsageChecker;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Types from packages their module does not export (java.awt.peer, sun.security.validator, ...)
 * must never be imported or named by generated tests: javac rejects the whole file with
 * "package ... is not visible", dropping every test in it.
 */
public class UnexportedPackageRenderingTest {

    @Test
    public void methodReturningUnexportedTypeIsNotUsableAndIsNotCallableFromTests() throws Exception {
        Method getPeer = UnexportedPeerFixture.class.getMethod("getPeer");
        TestCase tc = new DefaultTestCase();
        VariableReference fixture = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(UnexportedPeerFixture.class.getConstructor(),
                        UnexportedPeerFixture.class), 0, 0);
        TestFactory.getInstance().addMethodFor(tc, fixture,
                new GenericMethod(getPeer, UnexportedPeerFixture.class), 1);
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String source = "import com.examples.with.different.packagename.UnexportedPeerFixture;\n"
                + "public class UnexportedCallSnippet {\n  public void test0() throws Throwable {\n"
                + visitor.getCode() + "\n  }\n}\n";

        // javac really rejects the call (so a cluster admitting getPeer() yields uncompilable tests) ...
        assertFalse(JavacScratchCompiler.compile("evosuite-unexported-call", "UnexportedCallSnippet",
                source).success, source);
        // ... and the cluster predicate keeps the method out.
        assertFalse(TestUsageChecker.canUse(getPeer));
    }

    @Test
    public void importOfUnexportedReturnTypeIsOmitted() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference fixture = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(UnexportedPeerFixture.class.getConstructor(),
                        UnexportedPeerFixture.class), 0, 0);
        TestFactory.getInstance().addMethodFor(tc, fixture,
                new GenericMethod(UnexportedPeerFixture.class.getMethod("getPeer"),
                        UnexportedPeerFixture.class), 1);

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        for (Class<?> imported : visitor.getImports()) {
            assertFalse(imported.getName().startsWith("java.awt.peer"), imported.getName());
        }
    }

    @Test
    public void exceptionFromUnexportedPackageIsReportedAsVisibleSuperclass() throws Exception {
        Class<?> hidden = Class.forName("sun.security.validator.ValidatorException");
        Throwable exception = (Throwable) new ObjenesisStd().newInstance(hidden);

        String fail = new TestCodeVisitor().generateFailAssertion(null, exception);

        assertFalse(fail.contains("ValidatorException"), fail);
        assertTrue(fail.contains("Expecting exception: CertificateException"), fail);
    }
}
