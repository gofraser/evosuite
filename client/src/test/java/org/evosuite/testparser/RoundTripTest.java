/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testparser;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.*;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: build a TestCase → generate source via TestCodeVisitor →
 * parse the source back → verify the resulting TestCase has the same structure.
 */
class RoundTripTest {

    private String generateCode(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        visitor.visitTestCase(tc);
        for (int i = 0; i < tc.size(); i++) {
            visitor.visitStatement(tc.getStatement(i));
        }
        return visitor.getCode();
    }

    private ParseResult parseCode(String code) {
        TestParser parser = new TestParser(getClass().getClassLoader());
        return parser.parseTestMethodBody(code, List.of(
                "import java.util.*;",
                "import java.util.concurrent.TimeUnit;"
        ));
    }

    @Test
    void roundTripPrimitives() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 42));
        tc.addStatement(new DoublePrimitiveStatement(tc, 3.14));
        tc.addStatement(new BooleanPrimitiveStatement(tc, true));
        tc.addStatement(new StringPrimitiveStatement(tc, "hello"));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(tc.size(), parsed.size(), "Statement count should match");

        assertInstanceOf(IntPrimitiveStatement.class, parsed.getStatement(0));
        assertInstanceOf(DoublePrimitiveStatement.class, parsed.getStatement(1));
        assertInstanceOf(BooleanPrimitiveStatement.class, parsed.getStatement(2));
        assertInstanceOf(StringPrimitiveStatement.class, parsed.getStatement(3));

        assertEquals(42, ((IntPrimitiveStatement) parsed.getStatement(0)).getValue().intValue());
        assertEquals(3.14, ((DoublePrimitiveStatement) parsed.getStatement(1)).getValue(), 0.001);
        assertTrue(((BooleanPrimitiveStatement) parsed.getStatement(2)).getValue());
        assertEquals("hello", ((StringPrimitiveStatement) parsed.getStatement(3)).getValue());
    }

    @Test
    void roundTripConstructorAndMethod() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // ArrayList list = new ArrayList();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        VariableReference listRef = tc.addStatement(
                new ConstructorStatement(tc, gc, Collections.emptyList()));

        // list.clear();
        GenericMethod gm = new GenericMethod(
                ArrayList.class.getMethod("clear"),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new MethodStatement(tc, gm, listRef, Collections.emptyList()));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(2, parsed.size());
        assertInstanceOf(ConstructorStatement.class, parsed.getStatement(0));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(1));
    }

    @Test
    void roundTripNullStatement() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new NullStatement(tc, Object.class));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(1, parsed.size());
        assertInstanceOf(NullStatement.class, parsed.getStatement(0));
    }
}
