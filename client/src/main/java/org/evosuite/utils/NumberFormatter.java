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
package org.evosuite.utils;

import org.apache.commons.lang3.StringEscapeUtils;
import org.evosuite.testcase.TestCodeVisitor;

/**
 * <p>
 * NumberFormatter class.
 * </p>
 *
 * @author Gordon Fraser
 */
public class NumberFormatter {

    public static String getNumberString(Object value) {
        return getNumberString(value, null);
    }

    /**
     * Decides whether {@code value} is an enum constant, for the purpose of
     * routing it to the enum-specific assertion/rendering code.
     *
     * <p>This is simply {@code value instanceof Enum}. An earlier form of this
     * check, {@code value.getClass().isEnum() || value instanceof Enum}, also
     * tested {@code getClass().isEnum()}, but that clause is dead: a value for
     * which it is true is necessarily an instance of a class declared as an
     * {@code enum}, which always extends {@link Enum}, so
     * {@code value instanceof Enum} already holds. The converse is where
     * {@code getClass().isEnum()} actually fails -- an enum constant with its
     * own constant-specific class body (e.g. one overriding a method, such as
     * {@code java.util.concurrent.TimeUnit}'s constants) has an anonymous
     * subclass as its runtime class, for which {@code isEnum()} is false --
     * which is exactly the case {@code instanceof} is needed for. So the
     * combined expression reduces to {@code instanceof} alone.
     *
     * <p>Two other sites in the codebase still use the narrower,
     * {@code isEnum()}-only check and deliberately do not call this method:
     * {@code TestCodeVisitor#visitPrimitiveFieldAssertion} (a field assertion
     * never had an enum-specific branch to begin with) and
     * {@code Assertion#changeClassLoader} (reattaching an enum constant across
     * a classloader, not rendering code). Both are pre-existing behaviour and
     * out of scope here.
     *
     * @param value the value to check, possibly {@code null}
     * @return {@code true} if {@code value} is an enum constant
     */
    public static boolean isEnum(Object value) {
        return value instanceof Enum;
    }

    /**
     * <p>
     * getNumberString.
     * </p>
     *
     * @param value a {@link java.lang.Object} object.
     * @param visitor a {@link org.evosuite.testcase.TestCodeVisitor} object.
     * @return a {@link java.lang.String} object.
     */
    public static String getNumberString(Object value, TestCodeVisitor visitor) {
        if (value == null) {
            return "null";
        } else if (value.getClass().equals(char.class)
                || value.getClass().equals(Character.class)) {
            // StringEscapeUtils fails to escape a single quote char
            if (Character.valueOf('\'').equals(value)) {
                return "'\\''";
            } else {
                return "'"
                        + StringEscapeUtils.escapeJava(Character.toString((Character) value))
                        + "'";
            }
        } else if (value.getClass().equals(String.class)) {
            return "\"" + StringEscapeUtils.escapeJava((String) value) + "\"";
        } else if (value.getClass().equals(float.class)
                || value.getClass().equals(Float.class)) {
            if (value.toString().equals("" + Float.NaN)) {
                return "Float.NaN";
            } else if (value.toString().equals("" + Float.NEGATIVE_INFINITY)) {
                return "Float.NEGATIVE_INFINITY";
            } else if (value.toString().equals("" + Float.POSITIVE_INFINITY)) {
                return "Float.POSITIVE_INFINITY";
            } else if (((Float) value) < 0F) {
                return "(" + value + "F)";
            } else {
                return value + "F";
            }
        } else if (value.getClass().equals(double.class)
                || value.getClass().equals(Double.class)) {
            if (value.toString().equals("" + Double.NaN)) {
                return "Double.NaN";
            } else if (value.toString().equals("" + Double.NEGATIVE_INFINITY)) {
                return "Double.NEGATIVE_INFINITY";
            } else if (value.toString().equals("" + Double.POSITIVE_INFINITY)) {
                return "Double.POSITIVE_INFINITY";
            } else if (((Double) value) < 0.0) {
                return "(" + value + ")";
            } else {
                return value.toString();
            }
        } else if (value.getClass().equals(long.class)
                || value.getClass().equals(Long.class)) {
            if (((Long) value) < 0) {
                return "(" + value + "L)";
            } else {
                return value + "L";
            }
        } else if (value.getClass().equals(byte.class)
                || value.getClass().equals(Byte.class)) {
            if (((Byte) value) < 0) {
                return "(byte) (" + value + ")";
            } else {
                return "(byte)" + value;
            }
        } else if (value.getClass().equals(short.class)
                || value.getClass().equals(Short.class)) {
            if (((Short) value) < 0) {
                return "(short) (" + value + ")";
            } else {
                return "(short)" + value;
            }
        } else if (value.getClass().equals(int.class)
                || value.getClass().equals(Integer.class)) {
            int val = (Integer) value;
            if (val == Integer.MAX_VALUE) {
                return "Integer.MAX_VALUE";
            } else if (val == Integer.MIN_VALUE) {
                return "Integer.MIN_VALUE";
            } else if (((Integer) value) < 0) {
                return "(" + value + ")";
            } else {
                return "" + val;
            }
        } else if (isEnum(value)) {
            String className;
            Class<?> enumClass = value.getClass();
            if (enumClass.isAnonymousClass()) {
                enumClass = enumClass.getEnclosingClass();
            }

            // Only take the richer, import-aware, conflict-resolving naming path
            // when the enum constant can actually be named in the generated test;
            // otherwise fall back to the same simple-name chaining used when no
            // visitor is available at all, which at least degrades predictably.
            // This must use the same, narrower check that EnumAssertionCodeRenderer
            // itself used to decide this branch is reachable at all --
            // isEnumConstantNameableInGeneratedTest -- rather than the blanket,
            // deliberately over-conservative isClassLiteralReferenceableFromGeneratedTest
            // used for ".class" literals. The blanket check rejects every nested
            // class, including perfectly public, nameable ones, which forced even
            // reachable nested enums through the naive fallback below: that path
            // chains raw getSimpleName()s with no import registration and no
            // conflict resolution, so when another, already-imported class in the
            // same generated test happens to share the enum's enclosing simple
            // name, the rendered reference silently resolves to the wrong class
            // (see ProductionEnumAssertionCompileTest's collision case). Callers
            // that can restructure the surrounding expression (PrimitiveAssertion,
            // InspectorAssertion) check referenceability themselves and render a
            // by-name comparison instead, so they never reach this branch for a
            // non-referenceable (e.g. source-private) enum.
            if (visitor != null && TestCodeVisitor.isEnumConstantNameableInGeneratedTest(enumClass)) {
                className = visitor.getClassName(enumClass);
                Class<?> enclosingClass = enumClass.getEnclosingClass();
                if (enclosingClass != null && className.indexOf('.') < 0) {
                    String enclosingName = visitor.getClassName(enclosingClass);
                    if (enclosingName != null && !enclosingName.isEmpty()) {
                        className = enclosingName + "." + className;
                    }
                }
            } else {
                Class<?> clazz = enumClass;
                className = clazz.getSimpleName();
                while (clazz.getEnclosingClass() != null) {
                    String enclosingName = clazz.getEnclosingClass().getSimpleName();
                    className = enclosingName + "." + className;
                    clazz = clazz.getEnclosingClass();
                }
            }

            // We have to do this here to avoid a double colon in the TimeUnit example
            if (!className.endsWith(".")) {
                className += ".";
            }
            try {
                if (value.getClass().getField(value.toString()) != null) {
                    return className + value;
                } else if (((Enum<?>) value).name() != null) {
                    return className + ((Enum<?>) value).name();
                } else {
                    return "Enum.valueOf(" + className + "class, \"" + value + "\")";
                }
            } catch (Throwable t) {
                if (((Enum<?>) value).name() != null) {
                    return className + ((Enum<?>) value).name();
                } else {
                    return "Enum.valueOf(" + className + "class /* " + t + " */, \"" + value + "\")";
                }
                // return className + "valueOf(\"" + value + "\")";
            }
        } else if (value.getClass().equals(Boolean.class)) {
            return value.toString();
        } else {
            // This should not happen
            assert (false);
            return value.toString();
        }
    }

    /**
     * <p>
     * getBoxedClassName.
     * </p>
     *
     * @param value a {@link java.lang.Object} object.
     * @return a {@link java.lang.String} object.
     */
    public static String getBoxedClassName(Object value) {
        if (value.getClass().equals(Double.class)) {
            return "double";
        } else if (value.getClass().equals(Float.class)) {
            return "float";
        } else if (value.getClass().equals(Long.class)) {
            return "long";
        } else if (value.getClass().equals(Boolean.class)) {
            return "boolean";
        } else if (value.getClass().equals(Short.class)) {
            return "short";
        } else if (value.getClass().equals(Integer.class)) {
            return "int";
        } else if (value.getClass().equals(Byte.class)) {
            return "byte";
        } else if (value.getClass().equals(Character.class)) {
            return "char";
        } else {
            return value.getClass().getSimpleName();
        }

    }

}
