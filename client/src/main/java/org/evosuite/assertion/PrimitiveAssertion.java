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

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.NumberFormatter;

public class PrimitiveAssertion extends Assertion {

    private static final long serialVersionUID = -3394333075511344913L;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCode() {

        if (value == null) {
            return "assertNull(" + source.getName() + ");";
        } else if (source.getVariableClass().equals(float.class)) {
            return "assertEquals(" + NumberFormatter.getNumberString(value) + ", "
                    + source.getName() + ", " + Properties.FLOAT_PRECISION + "F);";
        } else if (source.getVariableClass().equals(double.class)) {
            return "assertEquals(" + NumberFormatter.getNumberString(value) + ", "
                    + source.getName() + ", " + Properties.DOUBLE_PRECISION + ");";
        } else if (NumberFormatter.isEnum(value)) {
            return EnumAssertionCodeRenderer.getEnumAssertionCode(value, source.getName());
        } else if (source.isWrapperType()) {
            if (source.getVariableClass().equals(Float.class)) {
                return "assertEquals(" + NumberFormatter.getNumberString(value)
                        + ", (float)" + source.getName() + ", " + Properties.FLOAT_PRECISION + "F);";
            } else if (source.getVariableClass().equals(Double.class)) {
                return "assertEquals(" + NumberFormatter.getNumberString(value)
                        + ", (double)" + source.getName() + ", " + Properties.DOUBLE_PRECISION + ");";
            } else if (NumberFormatter.isEnum(value)) {
                return EnumAssertionCodeRenderer.getEnumAssertionCode(value, source.getName());
            } else {
                return "assertEquals(" + NumberFormatter.getNumberString(value) + ", ("
                        + NumberFormatter.getBoxedClassName(value) + ")"
                        + source.getName() + ");";
            }
        } else if (needsUnboxingCast(source, value)) {
            return "assertEquals(" + NumberFormatter.getNumberString(value) + ", ("
                    + NumberFormatter.getBoxedClassName(value) + ")" + source.getName() + ");";
        } else {
            return "assertEquals(" + NumberFormatter.getNumberString(value) + ", "
                    + source.getName() + ");";
        }

    }

    /**
     * Whether a bare {@code assertEquals(value, source)} would be ambiguous: the source is
     * a primitive-class variable (eg long) whose declared type may be a different wrapper
     * (eg an Integer constructor owner), so {@code assertEquals(int, Integer)} matches both
     * {@code (long, long)} and {@code (Object, Object)}. The value's own boxed cast resolves it.
     */
    public static boolean needsUnboxingCast(VariableReference source, Object value) {
        Class<?> sourceClass = source.getVariableClass();
        if (!sourceClass.isPrimitive()) {
            return false;
        }
        if (!org.apache.commons.lang3.ClassUtils.isPrimitiveWrapper(value.getClass()) || value instanceof Boolean) {
            return false;
        }
        return !org.apache.commons.lang3.ClassUtils.primitiveToWrapper(sourceClass).equals(value.getClass());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Assertion copy(TestCase newTestCase, int offset) {
        PrimitiveAssertion s = new PrimitiveAssertion();
        s.source = source.copy(newTestCase, offset);
        s.value = value;
        s.comment = comment;
        s.killedMutants.addAll(killedMutants);
        return s;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean evaluate(Scope scope) {
        try {
            if (value != null) {
                return value.equals(source.getObject(scope));
            } else {
                return source.getObject(scope) == null;
            }
        } catch (CodeUnderTestException e) {
            throw new UnsupportedOperationException();
        }
    }

}
