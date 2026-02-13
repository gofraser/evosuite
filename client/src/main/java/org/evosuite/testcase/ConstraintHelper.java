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
package org.evosuite.testcase;


import org.evosuite.runtime.util.Inputs;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericMethod;

/**
 * Class used to help the verification and proper use of constraints.
 *
 * <p>Created by Andrea Arcuri on 29/06/15.
 */
public class ConstraintHelper {


    /**
     * Counts the number of new instances of a given class in the test case.
     *
     * @param test  the test case to check
     * @param klass the class to count instances of
     * @return the number of new instances
     * @throws IllegalArgumentException if test or klass is null
     */
    public static int countNumberOfNewInstances(TestCase test, Class<?> klass) throws IllegalArgumentException {
        Inputs.checkNull(test, klass);

        int counter = 0;

        for (int i = 0; i < test.size(); i++) {
            Statement st = test.getStatement(i);
            if (st instanceof ConstructorStatement) {
                ConstructorStatement cs = (ConstructorStatement) st;
                if (klass.isAssignableFrom(cs.getConstructor().getDeclaringClass())) {
                    counter++;
                }
            }
        }

        return counter;
    }

    /**
     * This ignores the input parameters.
     *
     * @param test       the test case.
     * @param klass      the class.
     * @param methodName the name of the method.
     * @return the number of calls to the specified method.
     * @throws IllegalArgumentException if inputs are null.
     */
    public static int countNumberOfMethodCalls(TestCase test, Class<?> klass, String methodName)
            throws IllegalArgumentException {
        Inputs.checkNull(test, klass);
        int counter = 0;
        for (int i = 0; i < test.size(); i++) {
            Statement st = test.getStatement(i);
            if (st instanceof MethodStatement) {
                MethodStatement ms = (MethodStatement) st;
                GenericMethod gm = ms.getMethod();
                if (gm.getDeclaringClass().equals(klass) && gm.getName().equals(methodName)) {
                    counter++;
                }
            }
        }

        return counter;
    }

    /**
     * Finds the last position of a method call.
     *
     * @param test         the test case.
     * @param className    the name of the class.
     * @param methodName   the name of the method.
     * @param lastPosition the last position to check.
     * @return a negative value if it is not present.
     */
    public static int getLastPositionOfMethodCall(TestCase test, String className, String methodName,
                                                  int lastPosition) {
        Inputs.checkNull(test, className, methodName);

        int pos = -1;
        for (int i = 0; i < lastPosition; i++) {
            Statement st = test.getStatement(i);
            if (st instanceof MethodStatement) {
                MethodStatement ms = (MethodStatement) st;
                GenericMethod gm = ms.getMethod();
                if (gm.getDeclaringClass().getCanonicalName().equals(className) && gm.getName().equals(methodName)) {
                    pos = i;
                }
            }
        }

        return pos;
    }

    /**
     * Extracts class and method name from a string.
     *
     * @param s the string.
     * @param c the character.
     * @return an array of size 2.
     */
    public static String[] getClassAndMethod(String s, Class<?> c) {
        String klassName = null;
        String methodName = null;
        if (s.contains("#")) {
            int pos = s.indexOf('#');
            klassName = s.substring(0, pos);
            methodName = s.substring(pos + 1);
        } else {
            klassName = c.getCanonicalName();
            methodName = s;
        }
        return new String[]{klassName, methodName};
    }

    /**
     * Checks if a variable reference represents a null value.
     *
     * @param vr the variable reference
     * @param tc the test case
     * @return true if it is null
     */
    public static boolean isNull(VariableReference vr, TestCase tc) {

        if (vr instanceof NullReference) {
            return true;
        }

        Statement varSource = tc.getStatement(vr.getStPosition());
        if (varSource instanceof PrimitiveStatement) { //eg for String
            Object obj = ((PrimitiveStatement<?>) varSource).getValue();
            return obj == null;
        }

        return false;
    }

}
