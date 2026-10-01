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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential oracle for {@link GenericClassUtils#isAssignable(Type, Type)} (and, transitively,
 * every {@code GenericClass.isAssignableTo}/{@code isAssignableFrom} overload, which are thin
 * delegators to it -- see {@code GenericClassImpl#isAssignable(Type, Type)}; that single
 * choke point is why this test targets {@code GenericClassUtils} directly rather than building
 * {@code GenericClass} wrapper instances for every case).
 *
 * <p>javac is the oracle. For every {@code (lhs, rhs)} pair in {@link #CASES} this asks two
 * independent questions of an actual {@code javac} compile:
 * <ul>
 *     <li><b>assignment context</b>: {@code Lhs x = rhsField;} where {@code rhsField} is declared
 *     exactly as {@code Rhs} (relying on the implicit default value -- {@code null}/{@code 0}/
 *     {@code false} -- so this works uniformly for primitives, references and generic arrays
 *     without needing a literal per type);</li>
 *     <li><b>method-invocation context</b>: {@code target(rhsField)} where {@code target} declares
 *     a single parameter of type {@code Lhs} -- this is the context that actually matters for
 *     EvoSuite, since {@code GenericClassUtils.isAssignable} is consulted when deciding whether a
 *     candidate value may be passed as a generated call's argument.</li>
 * </ul>
 *
 * <p>The two javac oracles are asserted to agree with each other (a sanity check on the harness:
 * assignment conversion and method-invocation conversion coincide for every case in this matrix,
 * since none of them are constant expressions eligible for the narrowing-constant special case in
 * JLS 5.2), and the helper's verdict is asserted to agree with the method-invocation-context
 * oracle, since that is the context generated calls actually exercise.
 *
 * <p>Unlike {@link ClassLiteralVarianceTest} (which pins specific known-correct verdicts row by
 * row), this test is a pure differential oracle: it carries no hand-derived "expected" column at
 * all, on the theory that hand-deriving JLS variance verdicts for ~140 pairs is exactly the kind
 * of reasoning that has produced the seven prior point fixes this test exists to stop repeating.
 * Every row's correctness is grounded in an actual compile, not in this file's author's judgement.
 */
class GenericAssignabilityMatrixTest {

    // -- F-bounded fixture types, modelling the reported
    // TypeResolverBuilder<T extends TypeResolverBuilder<T>> shape without a Jackson dependency --

    interface SelfBound<T extends SelfBound<T>> {
    }

    static class SelfBoundImpl implements SelfBound<SelfBoundImpl> {
    }

    static class OtherSelfBoundImpl implements SelfBound<OtherSelfBoundImpl> {
    }

    // A multi-level F-bound chain mirroring the reported
    // TypeResolverBuilder<T extends TypeResolverBuilder<T>> shape more closely: an interface,
    // an abstract class one hop away that is still generic in the same self-bound style, and a
    // concrete leaf several hops down (as Jackson's StdTypeResolverBuilder is from
    // TypeResolverBuilder).
    interface FTop<T extends FTop<T>> {
    }

    static abstract class FMid<T extends FMid<T>> implements FTop<T> {
    }

    static class FLeaf extends FMid<FLeaf> {
    }

    static class OtherFLeaf extends FMid<OtherFLeaf> {
    }

    /**
     * Pure method-signature fixture, as in {@link ClassLiteralVarianceTest.Sigs}: every method
     * exists only so {@link Method#getGenericParameterTypes()} hands back a real, JDK-produced
     * {@link Type} for the matrix below.
     */
    @SuppressWarnings("unused")
    private static class Sigs {
        // -- raw vs parameterised vs wildcard --
        static void rawList(List l) {
        }

        static void listOfString(List<String> l) {
        }

        static void listOfInteger(List<Integer> l) {
        }

        static void listOfWildcard(List<?> l) {
        }

        static void listOfExtendsNumber(List<? extends Number> l) {
        }

        static void listOfSuperInteger(List<? super Integer> l) {
        }

        static void listOfObject(List<Object> l) {
        }

        static void collectionOfString(Collection<String> c) {
        }

        static void arrayListOfString(ArrayList<String> l) {
        }

        static void arrayListOfInteger(ArrayList<Integer> l) {
        }

        // -- nested --
        static void listOfListOfString(List<List<String>> l) {
        }

        static void listOfListOfInteger(List<List<Integer>> l) {
        }

        static void mapStringListInteger(Map<String, List<Integer>> m) {
        }

        static void mapStringListString(Map<String, List<String>> m) {
        }

        // -- wildcards nested at depth --
        static void listOfExtendsListExtendsNumber(List<? extends List<? extends Number>> l) {
        }

        static void listOfArrayListInteger(List<ArrayList<Integer>> l) {
        }

        static void listOfSuperListOfInteger(List<? super List<Integer>> l) {
        }

        static void listOfCollectionInteger(List<Collection<Integer>> l) {
        }

        static void mapExtendsStringExtendsNumber(Map<? extends String, ? extends Number> m) {
        }

        static void mapStringInteger(Map<String, Integer> m) {
        }

        // Collections.max-style self-referencing wildcard: T extends Comparable<? super T>.
        static void listOfExtendsComparableSuperInteger(List<? extends Comparable<? super Integer>> l) {
        }

        // -- Class<T> family --
        static void classOfInteger(Class<Integer> c) {
        }

        static void classOfNumber(Class<Number> c) {
        }

        static void classRaw(Class c) {
        }

        static void classOfWildcard(Class<?> c) {
        }

        static void classOfSuperInteger(Class<? super Integer> c) {
        }

        // -- nested-shared-raw Class<T> cases (the actual shape of 4afbc209f/ff45d8b1f: two
        // different parameterisations of the SAME raw class nested inside Class<...>, where the
        // reverted bug -- erasing the class-literal's type argument via getRawClass -- collapsed
        // both to List.class and could no longer tell them apart). Plain classOfInteger vs
        // classOfNumber above does NOT exercise this: Integer and Number already have different
        // erasures, so the pre-fix erase-then-compare code answers those correctly regardless.
        static void classOfListOfString(Class<List<String>> c) {
        }

        static void classOfListOfInteger(Class<List<Integer>> c) {
        }

        static void classOfListOfWildcard(Class<List<?>> c) {
        }

        static void classOfArrayListOfString(Class<ArrayList<String>> c) {
        }

        static void classOfListOfExtendsNumber(Class<List<? extends Number>> c) {
        }

        static void classOfMapStringInteger(Class<Map<String, Integer>> c) {
        }

        static void classOfMapStringString(Class<Map<String, String>> c) {
        }

        // -- arrays, including generic arrays --
        static void stringArray(String[] a) {
        }

        static void objectArray(Object[] a) {
        }

        static void numberArray(Number[] a) {
        }

        static void integerArray(Integer[] a) {
        }

        static void intArray(int[] a) {
        }

        static void longArray(long[] a) {
        }

        static void listStringArray(List<String>[] a) {
        }

        static void listIntegerArray(List<Integer>[] a) {
        }

        static void listWildcardArray(List<?>[] a) {
        }

        static void listOfStringArray(List<String[]> l) {
        }

        // -- primitives and their boxes --
        static void primInt(int i) {
        }

        static void primLong(long l) {
        }

        static void primDouble(double d) {
        }

        static void primFloat(float f) {
        }

        static void primShort(short s) {
        }

        static void primByte(byte b) {
        }

        static void primChar(char c) {
        }

        static void primBoolean(boolean b) {
        }

        static void boxInteger(Integer i) {
        }

        static void boxLong(Long l) {
        }

        static void boxDouble(Double d) {
        }

        static void boxBoolean(Boolean b) {
        }

        static void boxShort(Short s) {
        }

        static void boxByte(Byte b) {
        }

        static void boxCharacter(Character c) {
        }

        static void objectParam(Object o) {
        }

        static void numberParam(Number n) {
        }

        static void serializableParam(Serializable s) {
        }

        // -- F-bounded generics --
        static void selfBoundWildcard(SelfBound<?> s) {
        }

        static void selfBoundOfImpl(SelfBound<SelfBoundImpl> s) {
        }

        static void selfBoundOfOtherImpl(SelfBound<OtherSelfBoundImpl> s) {
        }

        static void selfBoundImplParam(SelfBoundImpl s) {
        }

        static void otherSelfBoundImplParam(OtherSelfBoundImpl s) {
        }

        static void comparableOfInteger(Comparable<Integer> c) {
        }

        static void comparableWildcard(Comparable<?> c) {
        }

        // -- multi-level F-bound chain (FTop/FMid/FLeaf) --
        static void fTopOfLeaf(FTop<FLeaf> t) {
        }

        static void fTopOfOtherLeaf(FTop<OtherFLeaf> t) {
        }

        static void fTopWildcard(FTop<?> t) {
        }

        static void fMidOfLeaf(FMid<FLeaf> m) {
        }

        static void fLeafParam(FLeaf f) {
        }

        static void otherFLeafParam(OtherFLeaf f) {
        }

        // -- the documented raw-class-literal leniency (90e6a0906): the static type of the
        // expression `LinkedList.class` is `Class<LinkedList>` (LinkedList's own type parameter
        // unfilled, per JLS 15.8.2), modelled here the same way -- a raw LinkedList used as the
        // type *argument* of Class<...>, not a raw Class itself (that is classRaw, above).
        static void classOfRawLinkedList(Class<java.util.LinkedList> c) {
        }

        static void classOfLinkedListOfString(Class<java.util.LinkedList<String>> c) {
        }
    }

    private static Type sig(String methodName) throws NoSuchMethodException {
        for (Method m : Sigs.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return m.getGenericParameterTypes()[0];
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    /**
     * The source text of the declared parameter type of the given {@link Sigs} method, kept in
     * lockstep with the method declarations above by hand (there is no reflection-based way to
     * recover source text, only {@link Type}, which is why {@link ClassLiteralVarianceTest} uses
     * the same duplication).
     */
    private static String typeSource(String methodName) {
        switch (methodName) {
            case "rawList": return "List";
            case "listOfString": return "List<String>";
            case "listOfInteger": return "List<Integer>";
            case "listOfWildcard": return "List<?>";
            case "listOfExtendsNumber": return "List<? extends Number>";
            case "listOfSuperInteger": return "List<? super Integer>";
            case "listOfObject": return "List<Object>";
            case "collectionOfString": return "Collection<String>";
            case "arrayListOfString": return "ArrayList<String>";
            case "arrayListOfInteger": return "ArrayList<Integer>";
            case "listOfListOfString": return "List<List<String>>";
            case "listOfListOfInteger": return "List<List<Integer>>";
            case "mapStringListInteger": return "Map<String, List<Integer>>";
            case "mapStringListString": return "Map<String, List<String>>";
            case "listOfExtendsListExtendsNumber": return "List<? extends List<? extends Number>>";
            case "listOfArrayListInteger": return "List<ArrayList<Integer>>";
            case "listOfSuperListOfInteger": return "List<? super List<Integer>>";
            case "listOfCollectionInteger": return "List<Collection<Integer>>";
            case "mapExtendsStringExtendsNumber": return "Map<? extends String, ? extends Number>";
            case "mapStringInteger": return "Map<String, Integer>";
            case "listOfExtendsComparableSuperInteger": return "List<? extends Comparable<? super Integer>>";
            case "classOfInteger": return "Class<Integer>";
            case "classOfNumber": return "Class<Number>";
            case "classRaw": return "Class";
            case "classOfWildcard": return "Class<?>";
            case "classOfSuperInteger": return "Class<? super Integer>";
            case "classOfListOfString": return "Class<List<String>>";
            case "classOfListOfInteger": return "Class<List<Integer>>";
            case "classOfListOfWildcard": return "Class<List<?>>";
            case "classOfArrayListOfString": return "Class<ArrayList<String>>";
            case "classOfListOfExtendsNumber": return "Class<List<? extends Number>>";
            case "classOfMapStringInteger": return "Class<Map<String, Integer>>";
            case "classOfMapStringString": return "Class<Map<String, String>>";
            case "stringArray": return "String[]";
            case "objectArray": return "Object[]";
            case "numberArray": return "Number[]";
            case "integerArray": return "Integer[]";
            case "intArray": return "int[]";
            case "longArray": return "long[]";
            case "listStringArray": return "List<String>[]";
            case "listIntegerArray": return "List<Integer>[]";
            case "listWildcardArray": return "List<?>[]";
            case "listOfStringArray": return "List<String[]>";
            case "primInt": return "int";
            case "primLong": return "long";
            case "primDouble": return "double";
            case "primFloat": return "float";
            case "primShort": return "short";
            case "primByte": return "byte";
            case "primChar": return "char";
            case "primBoolean": return "boolean";
            case "boxInteger": return "Integer";
            case "boxLong": return "Long";
            case "boxDouble": return "Double";
            case "boxBoolean": return "Boolean";
            case "boxShort": return "Short";
            case "boxByte": return "Byte";
            case "boxCharacter": return "Character";
            case "objectParam": return "Object";
            case "numberParam": return "Number";
            case "serializableParam": return "Serializable";
            case "selfBoundWildcard": return "GenericAssignabilityMatrixTest.SelfBound<?>";
            case "selfBoundOfImpl":
                return "GenericAssignabilityMatrixTest.SelfBound<GenericAssignabilityMatrixTest.SelfBoundImpl>";
            case "selfBoundOfOtherImpl":
                return "GenericAssignabilityMatrixTest.SelfBound<GenericAssignabilityMatrixTest.OtherSelfBoundImpl>";
            case "selfBoundImplParam": return "GenericAssignabilityMatrixTest.SelfBoundImpl";
            case "otherSelfBoundImplParam": return "GenericAssignabilityMatrixTest.OtherSelfBoundImpl";
            case "comparableOfInteger": return "Comparable<Integer>";
            case "comparableWildcard": return "Comparable<?>";
            case "fTopOfLeaf": return "GenericAssignabilityMatrixTest.FTop<GenericAssignabilityMatrixTest.FLeaf>";
            case "fTopOfOtherLeaf": return "GenericAssignabilityMatrixTest.FTop<GenericAssignabilityMatrixTest.OtherFLeaf>";
            case "fTopWildcard": return "GenericAssignabilityMatrixTest.FTop<?>";
            case "fMidOfLeaf": return "GenericAssignabilityMatrixTest.FMid<GenericAssignabilityMatrixTest.FLeaf>";
            case "fLeafParam": return "GenericAssignabilityMatrixTest.FLeaf";
            case "otherFLeafParam": return "GenericAssignabilityMatrixTest.OtherFLeaf";
            case "classOfRawLinkedList": return "Class<LinkedList>";
            case "classOfLinkedListOfString": return "Class<LinkedList<String>>";
            default: throw new IllegalArgumentException(methodName);
        }
    }

    /** One matrix cell: an lhs ("target") signature and an rhs ("argument") signature. */
    private static final class Case {
        final String category;
        final String lhsMethod;
        final String rhsMethod;

        Case(String category, String lhsMethod, String rhsMethod) {
            this.category = category;
            this.lhsMethod = lhsMethod;
            this.rhsMethod = rhsMethod;
        }

        String description() {
            return category + ": " + typeSource(lhsMethod) + " <- " + typeSource(rhsMethod)
                    + " (" + lhsMethod + " <- " + rhsMethod + ")";
        }
    }

    private static void addBothDirections(List<Case> into, String category, String a, String b) {
        into.add(new Case(category, a, b));
        into.add(new Case(category, b, a));
    }

    private static final List<Case> CASES = buildCases();

    private static List<Case> buildCases() {
        List<Case> cases = new ArrayList<>();

        // -- raw vs parameterised, both directions --
        String rawVsParam = "raw-vs-parameterised";
        addBothDirections(cases, rawVsParam, "rawList", "listOfString");
        addBothDirections(cases, rawVsParam, "listOfString", "listOfString");
        addBothDirections(cases, rawVsParam, "listOfString", "listOfInteger");
        addBothDirections(cases, rawVsParam, "listOfWildcard", "listOfString");
        addBothDirections(cases, rawVsParam, "listOfWildcard", "rawList");
        addBothDirections(cases, rawVsParam, "listOfExtendsNumber", "listOfInteger");
        addBothDirections(cases, rawVsParam, "listOfExtendsNumber", "listOfString");
        addBothDirections(cases, rawVsParam, "listOfInteger", "listOfExtendsNumber");
        addBothDirections(cases, rawVsParam, "listOfSuperInteger", "listOfObject");
        addBothDirections(cases, rawVsParam, "listOfSuperInteger", "listOfInteger");
        addBothDirections(cases, rawVsParam, "listOfSuperInteger", "listOfString");
        addBothDirections(cases, rawVsParam, "collectionOfString", "listOfString");
        addBothDirections(cases, rawVsParam, "listOfString", "arrayListOfString");
        addBothDirections(cases, rawVsParam, "listOfInteger", "arrayListOfInteger");
        addBothDirections(cases, rawVsParam, "listOfInteger", "arrayListOfString");

        // -- nested generics --
        String nested = "nested";
        addBothDirections(cases, nested, "listOfListOfString", "listOfListOfString");
        addBothDirections(cases, nested, "listOfListOfString", "listOfListOfInteger");
        addBothDirections(cases, nested, "mapStringListInteger", "mapStringListInteger");
        addBothDirections(cases, nested, "mapStringListInteger", "mapStringListString");
        addBothDirections(cases, nested, "classOfInteger", "classOfInteger");
        addBothDirections(cases, nested, "classOfInteger", "classOfNumber");

        // -- wildcards nested at depth --
        String wildcardDepth = "wildcard-depth";
        addBothDirections(cases, wildcardDepth, "listOfExtendsListExtendsNumber", "listOfArrayListInteger");
        addBothDirections(cases, wildcardDepth, "listOfExtendsListExtendsNumber", "listOfListOfString");
        addBothDirections(cases, wildcardDepth, "listOfExtendsListExtendsNumber", "listOfListOfInteger");
        addBothDirections(cases, wildcardDepth, "listOfSuperListOfInteger", "listOfListOfInteger");
        addBothDirections(cases, wildcardDepth, "listOfSuperListOfInteger", "listOfCollectionInteger");
        addBothDirections(cases, wildcardDepth, "mapExtendsStringExtendsNumber", "mapStringInteger");
        addBothDirections(cases, wildcardDepth, "listOfExtendsComparableSuperInteger", "listOfInteger");

        // -- the Class<T> family (invariant; patched twice) --
        String classFamily = "class-literal";
        addBothDirections(cases, classFamily, "classRaw", "classOfInteger");
        addBothDirections(cases, classFamily, "classOfWildcard", "classOfInteger");
        addBothDirections(cases, classFamily, "classOfWildcard", "classRaw");
        addBothDirections(cases, classFamily, "classOfSuperInteger", "classOfNumber");
        addBothDirections(cases, classFamily, "classOfSuperInteger", "classOfInteger");
        addBothDirections(cases, classFamily, "classOfNumber", "classOfInteger");
        // -- nested-shared-raw: two parameterisations of the same raw class nested inside
        // Class<...>, which is the shape that actually exercises 4afbc209f/ff45d8b1f (see the
        // Sigs comment above the fixtures for why classOfNumber/classOfInteger does not).
        addBothDirections(cases, classFamily, "classOfListOfString", "classOfListOfInteger");
        addBothDirections(cases, classFamily, "classOfListOfString", "classOfListOfWildcard");
        addBothDirections(cases, classFamily, "classOfListOfInteger", "classOfListOfWildcard");
        addBothDirections(cases, classFamily, "classOfListOfString", "classOfArrayListOfString");
        addBothDirections(cases, classFamily, "classOfListOfInteger", "classOfListOfExtendsNumber");
        addBothDirections(cases, classFamily, "classOfMapStringInteger", "classOfMapStringString");

        // -- arrays, including generic arrays --
        String arrays = "arrays";
        addBothDirections(cases, arrays, "objectArray", "stringArray");
        addBothDirections(cases, arrays, "numberArray", "integerArray");
        addBothDirections(cases, arrays, "objectArray", "intArray");
        addBothDirections(cases, arrays, "listStringArray", "listStringArray");
        addBothDirections(cases, arrays, "listStringArray", "listIntegerArray");
        addBothDirections(cases, arrays, "listWildcardArray", "listStringArray");
        addBothDirections(cases, arrays, "objectArray", "listStringArray");
        addBothDirections(cases, arrays, "listOfStringArray", "listOfStringArray");

        // -- primitives and their boxes (assignability differs from reference rules) --
        String primitives = "primitives";
        addBothDirections(cases, primitives, "primInt", "primLong");
        addBothDirections(cases, primitives, "primLong", "primInt");
        addBothDirections(cases, primitives, "primDouble", "primFloat");
        addBothDirections(cases, primitives, "primInt", "primShort");
        addBothDirections(cases, primitives, "primInt", "primByte");
        addBothDirections(cases, primitives, "primInt", "primChar");
        addBothDirections(cases, primitives, "primInt", "boxInteger");
        addBothDirections(cases, primitives, "boxInteger", "primInt");
        addBothDirections(cases, primitives, "objectParam", "primInt");
        addBothDirections(cases, primitives, "objectParam", "boxInteger");
        addBothDirections(cases, primitives, "numberParam", "boxInteger");
        addBothDirections(cases, primitives, "numberParam", "primInt");
        addBothDirections(cases, primitives, "serializableParam", "boxInteger");
        addBothDirections(cases, primitives, "primLong", "boxInteger");
        addBothDirections(cases, primitives, "boxLong", "boxInteger");
        addBothDirections(cases, primitives, "primBoolean", "boxBoolean");
        addBothDirections(cases, primitives, "primDouble", "boxInteger");
        addBothDirections(cases, primitives, "primChar", "primShort");
        addBothDirections(cases, primitives, "primShort", "primByte");

        // -- type variables / F-bounds, modelling T extends TypeResolverBuilder<T> --
        String fBound = "f-bound";
        addBothDirections(cases, fBound, "selfBoundWildcard", "selfBoundImplParam");
        addBothDirections(cases, fBound, "selfBoundOfImpl", "selfBoundImplParam");
        addBothDirections(cases, fBound, "selfBoundOfImpl", "selfBoundOfOtherImpl");
        addBothDirections(cases, fBound, "selfBoundOfOtherImpl", "otherSelfBoundImplParam");
        addBothDirections(cases, fBound, "selfBoundOfImpl", "otherSelfBoundImplParam");
        addBothDirections(cases, fBound, "comparableOfInteger", "boxInteger");
        addBothDirections(cases, fBound, "comparableWildcard", "boxInteger");
        addBothDirections(cases, fBound, "comparableOfInteger", "boxLong");
        addBothDirections(cases, fBound, "fTopOfLeaf", "fLeafParam");
        addBothDirections(cases, fBound, "fTopOfLeaf", "fTopOfOtherLeaf");
        addBothDirections(cases, fBound, "fTopWildcard", "fLeafParam");
        addBothDirections(cases, fBound, "fMidOfLeaf", "fLeafParam");
        addBothDirections(cases, fBound, "fTopOfLeaf", "otherFLeafParam");
        addBothDirections(cases, fBound, "fMidOfLeaf", "otherFLeafParam");

        return cases;
    }

    @TestFactory
    Stream<DynamicTest> assignabilityMatchesJavac() {
        return CASES.stream().map(c -> DynamicTest.dynamicTest(c.description(), () -> {
            Type lhsType = sig(c.lhsMethod);
            Type rhsType = sig(c.rhsMethod);
            String lhsSource = typeSource(c.lhsMethod);
            String rhsSource = typeSource(c.rhsMethod);

            boolean helperVerdict = GenericClassUtils.isAssignable(lhsType, rhsType);
            boolean javacAssignmentVerdict = compiles(assignmentSource(lhsSource, rhsSource));
            boolean javacCallVerdict = compiles(callSource(lhsSource, rhsSource));

            assertEquals(javacCallVerdict, javacAssignmentVerdict,
                    c.description() + ": assignment-context and method-invocation-context javac "
                            + "oracles disagree with each other (unexpected for this matrix; check "
                            + "the generated probe sources)");
            assertEquals(javacCallVerdict, helperVerdict,
                    c.description() + ": GenericClassUtils.isAssignable disagrees with javac "
                            + "(method-invocation context)");
        }));
    }

    /**
     * Documents the one known, deliberate disagreement with javac: {@code isClassLiteralAssignable}
     * (see {@code GenericClassUtils}) treats a raw class literal's reflected type, {@code
     * Class<LinkedList>} (the static type of the expression {@code LinkedList.class} per JLS
     * 15.8.2), as satisfying {@code Class<LinkedList<String>>}. javac itself never allows this in
     * either direction -- there is no surface syntax that produces a {@code Class<LinkedList<String>>}
     * value from {@code LinkedList.class}, so this assignment and the call-argument form are both
     * compile errors. This test pins that javac verdict (not a leniency this test wants "fixed" by
     * surprise) rather than asserting agreement with the helper, which is handled separately in
     * {@link org.evosuite.testcase.statements.StatementTest} / {@code TestCodeVisitorTest} territory:
     * see the handback report for whether the renderer compensates.
     */
    @org.junit.jupiter.api.Test
    void rawClassLiteralLeniencyDisagreesWithJavacInBothDirections() {
        String rawLinkedList = typeSource("classOfRawLinkedList");
        String linkedListOfString = typeSource("classOfLinkedListOfString");

        assertEquals(false, compiles(callSource(linkedListOfString, rawLinkedList)),
                "javac should refuse Class<LinkedList> -> Class<LinkedList<String>>");
        assertEquals(false, compiles(callSource(rawLinkedList, linkedListOfString)),
                "javac should refuse Class<LinkedList<String>> -> Class<LinkedList>");
    }

    // -- javac grounding -----------------------------------------------------------------------

    private static final AtomicLong PROBE_COUNTER = new AtomicLong();

    private static String assignmentSource(String lhsType, String rhsType) {
        String className = "AssignProbe" + PROBE_COUNTER.incrementAndGet();
        return "package org.evosuite.utils.generic;\n"
                + "import java.util.*;\n"
                + "import java.io.Serializable;\n"
                + "public class " + className + " {\n"
                + "    static " + rhsType + " arg;\n"
                + "    static void use() { " + lhsType + " x = arg; }\n"
                + "}\n";
    }

    private static String callSource(String lhsType, String rhsType) {
        String className = "CallProbe" + PROBE_COUNTER.incrementAndGet();
        return "package org.evosuite.utils.generic;\n"
                + "import java.util.*;\n"
                + "import java.io.Serializable;\n"
                + "public class " + className + " {\n"
                + "    static " + rhsType + " arg;\n"
                + "    static void target(" + lhsType + " p) {}\n"
                + "    static void call() { target(arg); }\n"
                + "}\n";
    }

    /**
     * Compiles the given self-contained Java source (which must declare its own top-level class)
     * and reports whether it succeeded.
     */
    private static boolean compiles(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "a system Java compiler is required to run this test");

        String classNameLine = source.lines().filter(l -> l.contains("public class")).findFirst()
                .orElseThrow();
        String className = classNameLine.substring(classNameLine.indexOf("class") + 6,
                classNameLine.indexOf('{')).trim();

        try {
            Path tempDir = Files.createTempDirectory("evosuite-generic-assignability-matrix");
            Path sourceFile = tempDir.resolve(className + ".java");
            Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            String classpath = System.getProperty("java.class.path");
            int result = compiler.run(null, null, new PrintStream(err, true, StandardCharsets.UTF_8),
                    "-classpath", classpath,
                    "-d", tempDir.toString(),
                    sourceFile.toString());

            return result == 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
