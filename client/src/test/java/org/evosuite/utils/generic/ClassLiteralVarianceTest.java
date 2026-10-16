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
package org.evosuite.utils.generic;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Table-driven coverage for {@code GenericClassUtils.isClassLiteralAssignable}, the helper behind
 * {@code Class<T>} assignability (eg, deciding whether a {@code Class<List<Integer>>}-typed variable
 * may be used where a {@code Class<List<String>>} is expected).
 *
 * <p>{@code Class<T>} is invariant in {@code T} except for the normal Java wildcard-containment rules
 * (JLS 4.5.1), which are not uniform: an unbounded wildcard on the left accepts anything, a bounded
 * wildcard accepts subtypes/supertypes of its bound, but a wildcard <em>nested inside</em> a type
 * argument (eg, the {@code ?} in {@code List<?>}) does not get that leniency -- at that nesting level
 * comparison is exact structural equality.
 *
 * <p>Each case here is checked two ways: against {@link GenericClassUtils#isAssignable(Type, Type)}
 * using real reflected generic signatures (not hand-built synthetic types), and against an actual
 * {@code javac} compile of the equivalent assignment, so the expected verdicts are grounded rather
 * than asserted from a list. See {@code GeneratedAssertionCompileCheck} and {@code JUnitAnalyzer} for
 * the precedent of in-test compilation in this repository.
 */
class ClassLiteralVarianceTest {

    /**
     * Pure method-signature fixture: every method here exists only so that
     * {@link Method#getGenericParameterTypes()} hands back a real, JDK-produced
     * {@link java.lang.reflect.Type} for the case table below.
     */
    @SuppressWarnings("unused")
    private static class Sigs {
        static void classOfListOfString(Class<List<String>> c) {}
        static void classOfListOfInteger(Class<List<Integer>> c) {}
        static void classOfWildcard(Class<?> c) {}
        static void classOfExtendsNumber(Class<? extends Number> c) {}
        static void classOfInteger(Class<Integer> c) {}
        static void classOfListOfWildcard(Class<List<?>> c) {}
        static void classOfObject(Class<Object> c) {}
        static void rawClass(Class c) {}
        static void listOfString(List<String> l) {}
        static void listOfInteger(List<Integer> l) {}
        static void arrayListOfInteger(ArrayList<Integer> l) {}
    }

    private static Type sig(String methodName) throws NoSuchMethodException {
        for (Method m : Sigs.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return m.getGenericParameterTypes()[0];
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    /** One row of the table: a target ("lhs") signature and an argument ("rhs") signature. */
    private static final class Case {
        final String description;
        final String lhsMethod;
        final String rhsMethod;
        final boolean expectAssignable;

        Case(String description, String lhsMethod, String rhsMethod, boolean expectAssignable) {
            this.description = description;
            this.lhsMethod = lhsMethod;
            this.rhsMethod = rhsMethod;
            this.expectAssignable = expectAssignable;
        }
    }

    private static final List<Case> CASES = List.of(
            // The reported bug: nested type arguments were erased before comparison, so
            // Class<List<Integer>> and Class<List<String>> both collapsed to Class<List> and
            // compared equal. Not assignable: Class<T> is invariant in T.
            new Case("Class<List<Integer>> -> Class<List<String>> (reported bug)",
                    "classOfListOfString", "classOfListOfInteger", false),

            // Same type argument: still assignable.
            new Case("Class<List<String>> -> Class<List<String>>",
                    "classOfListOfString", "classOfListOfString", true),

            // Unbounded wildcard on the left: this is the opposite-direction bug. The old code
            // erased the wildcard via getRawClass, yielding Object.class, which then compared
            // unequal to List.class and wrongly rejected a valid assignment.
            new Case("Class<List<String>> -> Class<?>",
                    "classOfWildcard", "classOfListOfString", true),

            // Bounded wildcard: Integer <: Number, so this is assignable (covariant at this level).
            new Case("Class<Integer> -> Class<? extends Number>",
                    "classOfExtendsNumber", "classOfInteger", true),

            // Nested wildcard: List<?> and List<String> are compared structurally once we are a
            // level below the Class<...> wildcard-containment position, so this is NOT assignable
            // even though the outer Class<...> position tolerates wildcards.
            new Case("Class<List<String>> -> Class<List<?>>",
                    "classOfListOfWildcard", "classOfListOfString", false),

            // ff45d8b1f's own case: a Class<Integer> must not satisfy a Class<Object> parameter,
            // even though Integer is-a Object -- Class<T> is invariant, not covariant.
            new Case("Class<Integer> -> Class<Object>",
                    "classOfObject", "classOfInteger", false)
    );

    @TestFactory
    Stream<DynamicTest> classLiteralAssignabilityMatchesJavac() {
        return CASES.stream().map(c -> DynamicTest.dynamicTest(c.description, () -> {
            Type lhsType = sig(c.lhsMethod);
            Type rhsType = sig(c.rhsMethod);

            boolean helperVerdict = GenericClassUtils.isAssignable(lhsType, rhsType);
            boolean javacVerdict = compiles(parameterTypeSource(c.lhsMethod), parameterTypeSource(c.rhsMethod));

            assertEquals(c.expectAssignable, javacVerdict,
                    c.description + ": test table's expectation disagrees with an actual javac compile");
            assertEquals(javacVerdict, helperVerdict,
                    c.description + ": GenericClassUtils.isAssignable disagrees with javac");
        }));
    }

    /**
     * Regression guard: a raw {@code Class} (no type argument at all) must not be affected by the
     * structural type-argument comparison -- {@code isClassLiteralAssignable} bails out before ever
     * reaching it, the same way it did before this change.
     */
    @org.junit.jupiter.api.Test
    void rawClassIsUnaffected() throws Exception {
        Type rawClass = sig("rawClass");
        Type classOfInteger = sig("classOfInteger");

        assertEquals(compiles("Class", "Class<Integer>"),
                GenericClassUtils.isAssignable(rawClass, classOfInteger));
    }

    /**
     * Regression guard: ordinary (non-{@code Class}) generic types never reach
     * {@code isClassLiteralAssignable} and must keep their existing invariant behaviour.
     */
    @org.junit.jupiter.api.Test
    void nonClassTypesAreUnaffected() throws Exception {
        Type listOfString = sig("listOfString");
        Type listOfInteger = sig("listOfInteger");
        Type arrayListOfInteger = sig("arrayListOfInteger");

        assertEquals(compiles("List<String>", "List<String>"),
                GenericClassUtils.isAssignable(listOfString, listOfString));
        assertEquals(compiles("List<String>", "List<Integer>"),
                GenericClassUtils.isAssignable(listOfString, listOfInteger));
        assertEquals(compiles("List<Integer>", "ArrayList<Integer>"),
                GenericClassUtils.isAssignable(listOfInteger, arrayListOfInteger));
    }

    // -- javac grounding -----------------------------------------------------------------------

    /**
     * The source text of the declared parameter type of the given {@link Sigs} method, eg
     * {@code "Class<java.util.List<java.lang.String>>"}. Built from the method's own source rather
     * than hand-typed, so it always matches exactly what {@link #sig(String)} reflects.
     */
    private static String parameterTypeSource(String methodName) {
        switch (methodName) {
            case "classOfListOfString": return "Class<List<String>>";
            case "classOfListOfInteger": return "Class<List<Integer>>";
            case "classOfWildcard": return "Class<?>";
            case "classOfExtendsNumber": return "Class<? extends Number>";
            case "classOfInteger": return "Class<Integer>";
            case "classOfListOfWildcard": return "Class<List<?>>";
            case "classOfObject": return "Class<Object>";
            case "rawClass": return "Class";
            case "listOfString": return "List<String>";
            case "listOfInteger": return "List<Integer>";
            case "arrayListOfInteger": return "ArrayList<Integer>";
            default: throw new IllegalArgumentException(methodName);
        }
    }

    /**
     * Compiles {@code static void target(<targetType> p) {}} plus
     * {@code static void call(<argType> a) { target(a); }} and reports whether it succeeded, so test
     * expectations are grounded in an actual javac verdict rather than reasoning about JLS variance
     * rules by hand. See {@link JavacScratchCompiler}, which owns the scratch directory this needs
     * and cleans it up again -- shared with {@link GenericAssignabilityMatrixTest} since this is
     * otherwise the same compile-a-throwaway-probe boilerplate.
     */
    private static boolean compiles(String targetType, String argType) {
        String className = "ClassLiteralVarianceProbe" + System.nanoTime();
        String source = "import java.util.List;\n"
                + "import java.util.ArrayList;\n"
                + "public class " + className + " {\n"
                + "    static void target(" + targetType + " p) {}\n"
                + "    static void call(" + argType + " a) { target(a); }\n"
                + "}\n";

        return JavacScratchCompiler.compile(
                "evosuite-class-literal-variance-compile", className, source).success;
    }
}
