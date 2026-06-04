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
package org.evosuite.llm.seeding;

import org.evosuite.seeding.ConstantPool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SutConstantVocabularyTest {

    @Test
    void emptyPoolProducesEmptyHint() {
        ConstantPool pool = stubPool(Collections.<String>emptyList(), Collections.<Integer>emptyList(),
                Collections.<Long>emptyList(), Collections.<Float>emptyList(), Collections.<Double>emptyList());
        assertEquals("", SutConstantVocabulary.buildVocabularyHint(pool, 10));
    }

    @Test
    void zeroCapDisablesHint() {
        ConstantPool pool = stubPool(Arrays.asList("hello"), Arrays.asList(42),
                Collections.<Long>emptyList(), Collections.<Float>emptyList(), Collections.<Double>emptyList());
        assertEquals("", SutConstantVocabulary.buildVocabularyHint(pool, 0));
    }

    @Test
    void includesStringsAndAllNumericTypes() {
        ConstantPool pool = stubPool(
                Arrays.asList("modify", "create"),
                Arrays.asList(0, 1, 42),
                Arrays.asList(100L, 9_000_000_000L),
                Arrays.asList(1.5f, 3.14f),
                Arrays.asList(0.0, 2.71));
        String hint = SutConstantVocabulary.buildVocabularyHint(pool, 10);

        assertTrue(hint.contains("Domain vocabulary already used by the SUT"),
                "should label the section");
        assertTrue(hint.contains("\"modify\""), "strings should be quoted: " + hint);
        assertTrue(hint.contains("\"create\""), "strings should be quoted: " + hint);
        assertTrue(hint.contains("- Ints: 0, 1, 42"), "ints rendered as bare literals: " + hint);
        assertTrue(hint.contains("- Longs: 100L, 9000000000L"), "longs rendered with L suffix: " + hint);
        assertTrue(hint.contains("- Floats: 1.5f, 3.14f"), "floats rendered with f suffix: " + hint);
        assertTrue(hint.contains("- Doubles: 0.0, 2.71"), "doubles rendered as decimals: " + hint);
    }

    @Test
    void capLimitsAppliedPerType() {
        ConstantPool pool = stubPool(
                Arrays.asList("a", "b", "c", "d", "e"),
                Arrays.asList(1, 2, 3, 4, 5),
                Collections.<Long>emptyList(),
                Collections.<Float>emptyList(),
                Collections.<Double>emptyList());
        String hint = SutConstantVocabulary.buildVocabularyHint(pool, 2);

        assertTrue(hint.contains("\"a\", \"b\""), "first 2 strings: " + hint);
        assertFalse(hint.contains("\"c\""), "third string should be capped: " + hint);
        assertTrue(hint.contains("- Ints: 1, 2"), "first 2 ints: " + hint);
        assertFalse(hint.contains("- Ints: 1, 2, 3"), "third int should be capped: " + hint);
    }

    @Test
    void specialCharsInStringsAreEscaped() {
        ConstantPool pool = stubPool(
                Arrays.asList("with \"quote\"", "line\nbreak", "tab\there", "back\\slash"),
                Collections.<Integer>emptyList(), Collections.<Long>emptyList(),
                Collections.<Float>emptyList(), Collections.<Double>emptyList());
        String hint = SutConstantVocabulary.buildVocabularyHint(pool, 10);

        assertTrue(hint.contains("\"with \\\"quote\\\"\""), "quotes escaped: " + hint);
        assertTrue(hint.contains("\"line\\nbreak\""), "newline escaped: " + hint);
        assertTrue(hint.contains("\"tab\\there\""), "tab escaped: " + hint);
        assertTrue(hint.contains("\"back\\\\slash\""), "backslash escaped: " + hint);
    }

    @Test
    void veryLongStringIsTruncatedInHint() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            huge.append('x');
        }
        ConstantPool pool = stubPool(
                Arrays.asList(huge.toString()),
                Collections.<Integer>emptyList(), Collections.<Long>emptyList(),
                Collections.<Float>emptyList(), Collections.<Double>emptyList());
        String hint = SutConstantVocabulary.buildVocabularyHint(pool, 10);

        // Truncation marker present, and rendered length is well below the source length.
        assertTrue(hint.contains("..."), "truncation marker present: ");
        assertTrue(hint.length() < 500, "hint is bounded even for huge strings: " + hint.length());
    }

    @Test
    void nullPoolReturnsEmpty() {
        assertEquals("", SutConstantVocabulary.buildVocabularyHint(null, 10));
    }

    private static ConstantPool stubPool(List<String> strings, List<Integer> ints,
                                         List<Long> longs, List<Float> floats, List<Double> doubles) {
        return new ConstantPool() {
            @Override
            public String getRandomString() {
                return strings.isEmpty() ? "" : strings.get(0);
            }

            @Override
            public org.objectweb.asm.Type getRandomType() {
                return org.objectweb.asm.Type.getType(Object.class);
            }

            @Override
            public int getRandomInt() {
                return ints.isEmpty() ? 0 : ints.get(0);
            }

            @Override
            public float getRandomFloat() {
                return floats.isEmpty() ? 0f : floats.get(0);
            }

            @Override
            public double getRandomDouble() {
                return doubles.isEmpty() ? 0d : doubles.get(0);
            }

            @Override
            public long getRandomLong() {
                return longs.isEmpty() ? 0L : longs.get(0);
            }

            @Override
            public void add(Object object) {
                // not used by hint
            }

            @Override
            public Collection<String> getStrings() {
                return new ArrayList<>(strings);
            }

            @Override
            public Collection<Integer> getInts() {
                return new ArrayList<>(ints);
            }

            @Override
            public Collection<Long> getLongs() {
                return new ArrayList<>(longs);
            }

            @Override
            public Collection<Float> getFloats() {
                return new ArrayList<>(floats);
            }

            @Override
            public Collection<Double> getDoubles() {
                return new ArrayList<>(doubles);
            }

            @Override
            public String toString() {
                return "stubPool";
            }
        };
    }
}
