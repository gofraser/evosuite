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

    @Test
    void roundTripArray() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new ArrayStatement(tc, int[].class, 3));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(1, parsed.size());
        assertInstanceOf(ArrayStatement.class, parsed.getStatement(0));
    }

    @Test
    void roundTripMethodWithArgs() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // ArrayList list = new ArrayList();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        VariableReference listRef = tc.addStatement(
                new ConstructorStatement(tc, gc, Collections.emptyList()));

        // String s = "hello";
        VariableReference strRef = tc.addStatement(
                new StringPrimitiveStatement(tc, "hello"));

        // list.add(s);
        GenericMethod gm = new GenericMethod(
                ArrayList.class.getMethod("add", Object.class),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new MethodStatement(tc, gm, listRef, List.of(strRef)));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertTrue(parsed.size() >= 3, "Should have at least 3 statements: " + parsed.size());
        assertInstanceOf(ConstructorStatement.class, parsed.getStatement(0));
        assertInstanceOf(StringPrimitiveStatement.class, parsed.getStatement(1));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(2));
        assertEquals("add", ((MethodStatement) parsed.getStatement(2)).getMethodName());
    }

    @Test
    void roundTripUninterpretedStatement() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 5));
        tc.addStatement(new UninterpretedStatement(tc, "// custom code"));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        // UninterpretedStatement with a comment may not survive round-trip as a statement,
        // but the int should
        assertInstanceOf(IntPrimitiveStatement.class, parsed.getStatement(0));
        assertEquals(5, ((IntPrimitiveStatement) parsed.getStatement(0)).getValue().intValue());
    }

    @Test
    void roundTripStaticMethod() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // int x = 42;
        VariableReference xRef = tc.addStatement(new IntPrimitiveStatement(tc, 42));

        // String s = String.valueOf(x);
        GenericMethod gm = new GenericMethod(
                String.class.getMethod("valueOf", int.class),
                GenericClassFactory.get(String.class));
        tc.addStatement(new MethodStatement(tc, gm, null, List.of(xRef)));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(2, parsed.size());
        assertInstanceOf(IntPrimitiveStatement.class, parsed.getStatement(0));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(1));
        assertEquals("valueOf", ((MethodStatement) parsed.getStatement(1)).getMethodName());
    }

    @Test
    void roundTripMultipleMethods() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // ArrayList list = new ArrayList();
        GenericConstructor gc = new GenericConstructor(
                ArrayList.class.getConstructor(),
                GenericClassFactory.get(ArrayList.class));
        VariableReference listRef = tc.addStatement(
                new ConstructorStatement(tc, gc, Collections.emptyList()));

        // int size = list.size();
        GenericMethod sizeMethod = new GenericMethod(
                ArrayList.class.getMethod("size"),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new MethodStatement(tc, sizeMethod, listRef, Collections.emptyList()));

        // boolean empty = list.isEmpty();
        GenericMethod isEmptyMethod = new GenericMethod(
                ArrayList.class.getMethod("isEmpty"),
                GenericClassFactory.get(ArrayList.class));
        tc.addStatement(new MethodStatement(tc, isEmptyMethod, listRef, Collections.emptyList()));

        String code = generateCode(tc);
        ParseResult result = parseCode(code);

        TestCase parsed = result.getTestCase();
        assertFalse(result.hasErrors(), "Round-trip should have no errors: " + result.getDiagnostics());
        assertEquals(3, parsed.size());
        assertInstanceOf(ConstructorStatement.class, parsed.getStatement(0));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(1));
        assertInstanceOf(MethodStatement.class, parsed.getStatement(2));
        assertEquals("size", ((MethodStatement) parsed.getStatement(1)).getMethodName());
        assertEquals("isEmpty", ((MethodStatement) parsed.getStatement(2)).getMethodName());
    }
}
