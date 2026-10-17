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

import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The three predicates that decide a generic cast is inconvertible and has to go through the raw
 * type, side by side with javac:
 * <ul>
 *     <li><b>argument</b>: {@code requiresRawCastForInconvertibleParameterizedTypes}, used for
 *     method and constructor arguments (and for the return cast a raw argument forces);</li>
 *     <li><b>assignment</b>: {@code parameterizedCastIsInconvertible}, used for assignments and,
 *     before the argument predicate, for arguments;</li>
 *     <li><b>return</b>: {@code returnCastIsInconvertible}, used for the cast of a call's result.</li>
 * </ul>
 * The javac columns say whether {@code (Target) source} and {@code Target t = source} are
 * rejected. Each row is pinned as it is today, so a change to one predicate shows up here next to
 * the other two and next to javac.
 *
 * <p>The predicates do not agree where they overlap, and the differences are not explained by
 * the place they are used in:
 * <ul>
 *     <li>argument looks at the whole types: it is true whenever the raw types are related and
 *     the types are not assignable, including a cast javac accepts ({@code List<?>} to
 *     {@code List<String>}) and a downcast ({@code List<Integer>} to {@code ArrayList<String>});
 *     it is false whenever the source is not parameterized;</li>
 *     <li>assignment only compares the source viewed as the target's raw class, one level deep:
 *     it misses a nested distinction ({@code List<List<Integer>>} to {@code List<List<String>>},
 *     {@code Set<Class>} to {@code Set<Class<Object>>}), but it is the only one that handles a
 *     non-parameterized source ({@code String} to {@code Comparable<Object>}), a type variable
 *     in the source and a wildcard bound that rejects a raw subclass;</li>
 *     <li>return requires the same raw class on both sides and recurses into type arguments, so it
 *     misses every cast between a subtype and a supertype.</li>
 * </ul>
 */
class InconvertibleCastMatrixTest {

    /** A class extending a raw generic type: its List supertype is raw. */
    @SuppressWarnings("rawtypes")
    static class RawSequence extends ArrayList {
        private static final long serialVersionUID = 1L;
    }

    /** Every method exists only to provide a real JDK-produced parameter type for a row. */
    @SuppressWarnings({"unused", "rawtypes"})
    private static class Sigs<E> {
        void listOfString(List<String> p) {
        }

        void listOfInteger(List<Integer> p) {
        }

        void listOfObject(List<Object> p) {
        }

        void listOfWildcard(List<?> p) {
        }

        void rawList(List p) {
        }

        void listOfE(List<E> p) {
        }

        void listOfExtendsNumber(List<? extends Number> p) {
        }

        void arrayListOfString(ArrayList<String> p) {
        }

        void arrayListOfInteger(ArrayList<Integer> p) {
        }

        void collectionOfString(Collection<String> p) {
        }

        void setOfClassObject(Set<Class<Object>> p) {
        }

        void setOfRawClass(Set<Class> p) {
        }

        void listOfListOfString(List<List<String>> p) {
        }

        void listOfListOfInteger(List<List<Integer>> p) {
        }

        void mapStringListString(Map<String, List<String>> p) {
        }

        void hashMapStringListInteger(HashMap<String, List<Integer>> p) {
        }

        void mapStringInteger(Map<String, Integer> p) {
        }

        void hashMapObjectInteger(HashMap<Object, Integer> p) {
        }

        void mapStringExtendsListString(Map<String, ? extends List<String>> p) {
        }

        void mapStringRawSequence(Map<String, RawSequence> p) {
        }

        void linkedHashMapStringBoolean(LinkedHashMap<String, Boolean> p) {
        }

        void linkedHashMapEBoolean(LinkedHashMap<E, Boolean> p) {
        }

        void comparableOfObject(Comparable<Object> p) {
        }

        void comparableOfString(Comparable<String> p) {
        }

        void string(String p) {
        }

        void classOfInteger(Class<Integer> p) {
        }

        void classOfNumber(Class<Number> p) {
        }
    }

    /** The source text of each {@link Sigs} parameter type, kept in step by hand. */
    private static String source(String sig) {
        switch (sig) {
            case "listOfString": return "List<String>";
            case "listOfInteger": return "List<Integer>";
            case "listOfObject": return "List<Object>";
            case "listOfWildcard": return "List<?>";
            case "rawList": return "List";
            case "listOfE": return "List<E>";
            case "listOfExtendsNumber": return "List<? extends Number>";
            case "arrayListOfString": return "ArrayList<String>";
            case "arrayListOfInteger": return "ArrayList<Integer>";
            case "collectionOfString": return "Collection<String>";
            case "setOfClassObject": return "Set<Class<Object>>";
            case "setOfRawClass": return "Set<Class>";
            case "listOfListOfString": return "List<List<String>>";
            case "listOfListOfInteger": return "List<List<Integer>>";
            case "mapStringListString": return "Map<String, List<String>>";
            case "hashMapStringListInteger": return "HashMap<String, List<Integer>>";
            case "mapStringInteger": return "Map<String, Integer>";
            case "hashMapObjectInteger": return "HashMap<Object, Integer>";
            case "mapStringExtendsListString": return "Map<String, ? extends List<String>>";
            case "mapStringRawSequence": return "Map<String, InconvertibleCastMatrixTest.RawSequence>";
            case "linkedHashMapStringBoolean": return "LinkedHashMap<String, Boolean>";
            case "linkedHashMapEBoolean": return "LinkedHashMap<E, Boolean>";
            case "comparableOfObject": return "Comparable<Object>";
            case "comparableOfString": return "Comparable<String>";
            case "string": return "String";
            case "classOfInteger": return "Class<Integer>";
            case "classOfNumber": return "Class<Number>";
            default: throw new IllegalArgumentException(sig);
        }
    }

    /** Pairs of (target, source). */
    private static final String[][] ROWS = {
            {"listOfString", "listOfString"},
            {"collectionOfString", "arrayListOfString"},
            {"listOfString", "listOfInteger"},
            {"listOfObject", "listOfString"},
            {"listOfString", "arrayListOfInteger"},
            {"arrayListOfString", "listOfInteger"},
            {"listOfString", "listOfWildcard"},
            {"listOfString", "rawList"},
            {"listOfString", "listOfE"},
            {"listOfExtendsNumber", "listOfInteger"},
            {"listOfExtendsNumber", "listOfString"},
            {"setOfClassObject", "setOfRawClass"},
            {"listOfListOfString", "listOfListOfInteger"},
            {"mapStringListString", "hashMapStringListInteger"},
            {"mapStringInteger", "hashMapObjectInteger"},
            {"mapStringExtendsListString", "mapStringRawSequence"},
            {"linkedHashMapStringBoolean", "linkedHashMapEBoolean"},
            {"comparableOfObject", "string"},
            {"comparableOfString", "string"},
            {"classOfInteger", "classOfNumber"},
    };

    private static Type sig(String name) {
        for (Method m : Sigs.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m.getGenericParameterTypes()[0];
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    void predicatesNextToJavac() {
        TestCodeVisitor visitor = new TestCodeVisitor();
        StringBuilder table = new StringBuilder();
        for (String[] row : ROWS) {
            Type target = sig(row[0]);
            Type value = sig(row[1]);
            String targetSource = source(row[0]);
            String valueSource = source(row[1]);
            boolean argument = visitor.requiresRawCastForInconvertibleParameterizedTypes(target, value);
            boolean assignment = visitor.parameterizedCastIsInconvertible(target, value,
                    TestCodeVisitor.safeErasure(value));
            boolean returned = visitor.returnCastIsInconvertible(target, value);
            boolean castRejected = !compiles("Object x = (" + targetSource + ") arg;", valueSource);
            boolean assignmentRejected = !compiles(targetSource + " x = arg;", valueSource);
            table.append(String.format("%-36s <- %-52s argument=%s assignment=%s return=%s"
                            + " | javac rejects cast=%s assignment=%s%n",
                    targetSource, valueSource, flag(argument), flag(assignment), flag(returned),
                    flag(castRejected), flag(assignmentRejected)));
        }
        assertEquals("List<String>                         <- List<String>                                         argument=- assignment=- return=- | javac rejects cast=- assignment=-\n"
                + "Collection<String>                   <- ArrayList<String>                                    argument=- assignment=- return=- | javac rejects cast=- assignment=-\n"
                + "List<String>                         <- List<Integer>                                        argument=Y assignment=Y return=Y | javac rejects cast=Y assignment=Y\n"
                + "List<Object>                         <- List<String>                                         argument=Y assignment=Y return=Y | javac rejects cast=Y assignment=Y\n"
                + "List<String>                         <- ArrayList<Integer>                                   argument=Y assignment=Y return=- | javac rejects cast=Y assignment=Y\n"
                + "ArrayList<String>                    <- List<Integer>                                        argument=Y assignment=- return=- | javac rejects cast=Y assignment=Y\n"
                + "List<String>                         <- List<?>                                              argument=Y assignment=- return=- | javac rejects cast=- assignment=Y\n"
                + "List<String>                         <- List                                                 argument=- assignment=- return=- | javac rejects cast=- assignment=-\n"
                + "List<String>                         <- List<E>                                              argument=- assignment=Y return=- | javac rejects cast=- assignment=Y\n"
                + "List<? extends Number>               <- List<Integer>                                        argument=- assignment=- return=- | javac rejects cast=- assignment=-\n"
                + "List<? extends Number>               <- List<String>                                         argument=Y assignment=- return=- | javac rejects cast=Y assignment=Y\n"
                + "Set<Class<Object>>                   <- Set<Class>                                           argument=Y assignment=- return=Y | javac rejects cast=Y assignment=Y\n"
                + "List<List<String>>                   <- List<List<Integer>>                                  argument=Y assignment=- return=Y | javac rejects cast=Y assignment=Y\n"
                + "Map<String, List<String>>            <- HashMap<String, List<Integer>>                       argument=Y assignment=- return=- | javac rejects cast=Y assignment=Y\n"
                + "Map<String, Integer>                 <- HashMap<Object, Integer>                             argument=Y assignment=Y return=- | javac rejects cast=Y assignment=Y\n"
                + "Map<String, ? extends List<String>>  <- Map<String, InconvertibleCastMatrixTest.RawSequence> argument=- assignment=Y return=- | javac rejects cast=Y assignment=Y\n"
                + "LinkedHashMap<String, Boolean>       <- LinkedHashMap<E, Boolean>                            argument=- assignment=Y return=- | javac rejects cast=- assignment=Y\n"
                + "Comparable<Object>                   <- String                                               argument=- assignment=Y return=- | javac rejects cast=Y assignment=Y\n"
                + "Comparable<String>                   <- String                                               argument=- assignment=- return=- | javac rejects cast=- assignment=-\n"
                + "Class<Integer>                       <- Class<Number>                                        argument=Y assignment=Y return=Y | javac rejects cast=Y assignment=Y\n", table.toString());
    }

    private static String flag(boolean value) {
        return value ? "Y" : "-";
    }

    private static final AtomicLong PROBE_COUNTER = new AtomicLong();

    private static boolean compiles(String statement, String valueSource) {
        String className = "InconvertibleCastProbe" + PROBE_COUNTER.incrementAndGet();
        String source = "package org.evosuite.testcase;\n"
                + "import java.util.*;\n"
                + "public class " + className + "<E> {\n"
                + "    " + valueSource + " arg;\n"
                + "    void probe() { " + statement + " }\n"
                + "}\n";
        return JavacScratchCompiler.compile("evosuite-inconvertible-cast-matrix", className, source).success;
    }
}
