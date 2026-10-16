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
package com.examples.with.different.packagename;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A class whose own logic branches on the boolean-returning comparisons that
 * {@code StringTransformation} and {@code ContainerTransformation} rewrite.
 *
 * <p>Each method returns a value that depends on which side of the branch was taken, so a caller
 * can tell from the return value alone whether the comparison was evaluated correctly. Nothing
 * here depends on EvoSuite: run uninstrumented, every method returns the plain-Java answer.
 */
public class StringEqualsBranchVictim {

    public static int equalsBranch(String actual, String expected) {
        if (actual.equals(expected)) {
            return 1;
        }
        return 2;
    }

    public static int equalsIgnoreCaseBranch(String actual, String expected) {
        if (actual.equalsIgnoreCase(expected)) {
            return 1;
        }
        return 2;
    }

    public static int startsWithBranch(String actual, String prefix) {
        if (actual.startsWith(prefix)) {
            return 1;
        }
        return 2;
    }

    public static int endsWithBranch(String actual, String suffix) {
        if (actual.endsWith(suffix)) {
            return 1;
        }
        return 2;
    }

    /**
     * The shape that broke Apache {@code Metaphone}: the comparison result is stored in a local
     * and only then branched on, which is what javac emits for a named boolean variable.
     */
    public static int equalsViaLocal(String actual, String expected) {
        boolean matches = actual.equals(expected);
        if (matches) {
            return 1;
        }
        return 2;
    }

    /**
     * The exact {@code Metaphone.regionMatch} idiom: compare a substring against a literal.
     */
    public static int regionMatchIdiom(String text, int index, String test) {
        boolean matches = false;
        if (index >= 0 && index + test.length() - 1 < text.length()) {
            String substring = text.substring(index, index + test.length());
            matches = substring.equals(test);
        }
        return matches ? 1 : 2;
    }

    /**
     * A {@code ||} chain feeding a single branch: javac evaluates each comparison with its own
     * short-circuiting jump, so this exercises a control-flow join at the shared "true" target
     * rather than a stored boolean.
     */
    public static int orChainBranch(String a, String b, String c, String d) {
        if (a.equals(b) || c.equals(d)) {
            return 1;
        }
        return 2;
    }

    /**
     * A comparison result assigned inside a loop and branched on after the loop: the loop header
     * merges the pre-loop {@code false} (a plain int) with the in-loop {@code String.equals} result
     * (a tagged value) on every back edge, which is a different merge shape than an if/else join.
     */
    public static int loopAssignedBranch(String[] values, String needle) {
        boolean matches = false;
        for (String value : values) {
            matches = value.equals(needle);
            if (matches) {
                break;
            }
        }
        return matches ? 1 : 2;
    }

    /**
     * A ternary whose two arms are different string comparisons: both arms are tagged, so the merge
     * at the join point combines two tagged values rather than a tagged one with an untagged one.
     */
    public static int ternaryBranch(boolean useFirst, String a, String b, String c, String d) {
        boolean matches = useFirst ? a.equals(b) : c.equals(d);
        return matches ? 1 : 2;
    }

    public static int collectionIsEmptyBranch(boolean addSomething) {
        List<String> list = new ArrayList<>();
        if (addSomething) {
            list.add("x");
        }
        if (list.isEmpty()) {
            return 1;
        }
        return 2;
    }

    public static int collectionContainsBranch(String needle) {
        List<String> list = new ArrayList<>();
        list.add("present");
        if (list.contains(needle)) {
            return 1;
        }
        return 2;
    }

    public static int mapContainsKeyBranch(String needle) {
        Map<String, String> map = new HashMap<>();
        map.put("present", "value");
        if (map.containsKey(needle)) {
            return 1;
        }
        return 2;
    }
}
