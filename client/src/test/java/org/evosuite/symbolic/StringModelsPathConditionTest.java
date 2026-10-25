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
package org.evosuite.symbolic;

import com.examples.with.different.packagename.solver.TestCaseStringAppendIndexOf;
import org.evosuite.Properties;
import org.evosuite.symbolic.dse.ConcolicExecutorImpl;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the path conditions that the symbolic models of StringBuilder.append,
 * StringBuffer.append, String.indexOf and String.lastIndexOf produce.
 */
public class StringModelsPathConditionTest {

    private static String execute(DefaultTestCase tc) {
        Properties.CLIENT_ON_THREAD = true;
        Properties.PRINT_TO_SYSTEM = true;
        Properties.TIMEOUT = 5000000;
        Properties.CONCOLIC_TIMEOUT = 5000000;

        PathCondition pc = new ConcolicExecutorImpl().execute(tc);
        return pc.getBranchConditions().stream()
                .map(b -> b.getConstraint().toString())
                .collect(Collectors.joining("\n"));
    }

    private static final String EXPECTED =
            "== bufferAppend\n" +
            "((((((((var0(\"abcxyz\") append_Integer var1(3)) append_Integer var2(7)) append_Char var3(99)) append_boolean var4(1)) append_Real var5(1.5)) append_Real var6(2.5)) append_String var0(\"abcxyz\")) equals x) == 0\n" +
            "== builderAppend\n" +
            "((((((((var0(\"abcxyz\") append_Integer var1(3)) append_Integer var2(7)) append_Char var3(99)) append_boolean var4(1)) append_Real var5(1.5)) append_Real var6(2.5)) append_String var0(\"abcxyz\")) equals x) == 0\n" +
            "== builderAppendObject\n" +
            "((var0(\"abcxyz\") append_String c) equals x) == 0\n" +
            "== indexOfChar\n" +
            "(var0(\"abcxyz\") indexOfC '') != 2\n" +
            "== indexOfCharFrom\n" +
            "(var0(\"abcxyz\") indexOfCI var1(3) var2(4)) != 2\n" +
            "== indexOfString\n" +
            "(var0(\"abcxyz\") indexOfS var1(\"c\")) == 2\n" +
            "== indexOfStringFrom\n" +
            "(var0(\"abcxyz\") indexOfSI var1(\"c\") var2(4)) != 2\n" +
            "== lastIndexOfChar\n" +
            "(var0(\"abcxyz\") lastIndexOfC var1(3)) != 2\n" +
            "== lastIndexOfCharFrom\n" +
            "(var0(\"abcxyz\") lastIndexOfCI var1(3) var2(4)) != 2\n" +
            "== lastIndexOfString\n" +
            "(var0(\"abcxyz\") lastIndexOfS var1(\"c\")) == 2\n" +
            "== lastIndexOfStringFrom\n" +
            "(var0(\"abcxyz\") lastIndexOfSI var1(\"c\") var2(4)) == 2\n";

    @Test
    public void testPathConditions() throws Exception {
        StringBuilder out = new StringBuilder();
        Method[] methods = TestCaseStringAppendIndexOf.class.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method m : methods) {
            TestCaseBuilder b = new TestCaseBuilder();
            Class<?>[] types = m.getParameterTypes();
            VariableReference[] args = new VariableReference[types.length];
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (t == int.class) {
                    args[i] = b.appendIntPrimitive(2 + i);
                } else if (t == long.class) {
                    args[i] = b.appendLongPrimitive(7L);
                } else if (t == char.class) {
                    args[i] = b.appendCharPrimitive('c');
                } else if (t == boolean.class) {
                    args[i] = b.appendBooleanPrimitive(true);
                } else if (t == float.class) {
                    args[i] = b.appendFloatPrimitive(1.5F);
                } else if (t == double.class) {
                    args[i] = b.appendDoublePrimitive(2.5);
                } else {
                    args[i] = b.appendStringPrimitive(i == 0 ? "abcxyz" : "c");
                }
            }
            b.appendMethod(null, m, args);
            out.append("== ").append(m.getName()).append('\n').append(execute(b.getDefaultTestCase())).append('\n');
        }
        assertEquals(EXPECTED, out.toString());
    }
}
