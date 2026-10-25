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
package org.evosuite.coverage.io;

import org.evosuite.Properties;
import org.evosuite.coverage.io.input.InputCoverageGoal;
import org.evosuite.coverage.io.output.OutputCoverageGoal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pins the descriptors, and their order, that observed argument and return values produce.
 */
public class IOObservedGoalsTest {

    private static final String CLASS = "Foo";
    private static final String DEQUE = "Ljava/util/ArrayDeque;";

    private String oldTargetClass;

    @BeforeEach
    public void setUp() {
        oldTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = CLASS;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = oldTargetClass;
    }

    private static List<String> input(String desc, Object... args) {
        Set<InputCoverageGoal> goals = InputCoverageGoal.createCoveredGoalsFromParameters(CLASS, "m", desc,
                Arrays.asList(args));
        List<String> out = new ArrayList<>();
        for (InputCoverageGoal goal : goals) {
            out.add(goal.getArgIndex() + "|" + goal.getValueDescriptor() + "|" + goal.getNumericValue());
        }
        return out;
    }

    private static List<String> output(String desc, Object value) {
        Set<OutputCoverageGoal> goals = OutputCoverageGoal.createGoalsFromObject(CLASS, "m", desc, value);
        List<String> out = new ArrayList<>();
        for (OutputCoverageGoal goal : goals) {
            out.add(goal.getValueDescriptor() + "|" + goal.getNumericValue());
        }
        return out;
    }

    @Test
    public void inputPrimitives() {
        Assertions.assertEquals(Arrays.asList("0|True|null"), input("(Z)V", true));
        Assertions.assertEquals(Arrays.asList("0|AlphabeticChar|97"), input("(C)V", 'a'));
        Assertions.assertEquals(Arrays.asList("0|Digit|55"), input("(C)V", '7'));
        Assertions.assertEquals(Arrays.asList("0|OtherChar|45"), input("(C)V", '-'));
        Assertions.assertEquals(Arrays.asList("0|Negative|-2.0"), input("(I)V", -2));
        Assertions.assertEquals(Arrays.asList("0|Zero|0.0"), input("(D)V", 0.0));
        Assertions.assertEquals(Arrays.asList("0|Positive|5.0", "1|False|null"), input("(JZ)V", 5L, false));
    }

    @Test
    public void inputWrappersStringsAndArrays() {
        Assertions.assertEquals(Arrays.asList("0|Null|null"), input("(Ljava/lang/Integer;)V", (Object) null));
        Assertions.assertEquals(Arrays.asList("0|Positive|4.0"), input("(Ljava/lang/Integer;)V", 4));
        Assertions.assertEquals(Arrays.asList("0|Null|null"), input("(Ljava/lang/String;)V", (Object) null));
        Assertions.assertEquals(Arrays.asList("0|NonNull|null", "0|EmptyString|null"),
                input("(Ljava/lang/String;)V", ""));
        Assertions.assertEquals(Arrays.asList("0|NonNull|null", "0|NonEmptyString|null"),
                input("(Ljava/lang/String;)V", "x"));
        Assertions.assertEquals(Arrays.asList("0|EmptyArray|null"), input("([I)V", (Object) new int[0]));
        Assertions.assertEquals(Arrays.asList("0|NonEmptyArray|null"), input("([I)V", (Object) new int[1]));
    }

    @Test
    public void inputCollectionsAndInspectors() {
        Assertions.assertEquals(Arrays.asList("0|NonNull|null", "0|EmptyList|null"),
                input("(Ljava/util/List;)V", new ArrayList<>()));
        Assertions.assertEquals(Arrays.asList("0|NonNull|null", "0|NonEmptySet|null"),
                input("(Ljava/util/Set;)V", new LinkedHashSet<>(Collections.singleton(1))));
        Assertions.assertEquals(Arrays.asList("0|NonNull|null", "0|EmptyMap|null"),
                input("(Ljava/util/Map;)V", new HashMap<>()));
        List<String> sample = input("(" + DEQUE + ")V", new ArrayDeque<>());
        Assertions.assertEquals("0|NonNull|null", sample.get(0));
        Assertions.assertTrue(sample.contains("0|NonNull:java.util.ArrayDeque:isEmpty()Z:True|null"), sample.toString());
        Assertions.assertTrue(sample.contains("0|NonNull:java.util.ArrayDeque:size()I:Zero|null"),
                sample.toString());
    }

    @Test
    public void outputValues() {
        Assertions.assertEquals(Arrays.asList("False|null"), output("()Z", false));
        Assertions.assertEquals(Arrays.asList("AlphabeticChar|97"), output("()C", 'a'));
        Assertions.assertEquals(Arrays.asList("Negative|-7.0"), output("()I", -7));
        Assertions.assertEquals(Arrays.asList("Zero|0.0"), output("()D", 0.0));
        Assertions.assertEquals(Arrays.asList("Null|null"), output("()Ljava/lang/Integer;", null));
        Assertions.assertEquals(Arrays.asList("Positive|2.0"), output("()Ljava/lang/Integer;", 2));
        Assertions.assertEquals(Arrays.asList("Null|null"), output("()[I", null));
        Assertions.assertEquals(Arrays.asList("EmptyArray|null"), output("()[I", new int[0]));
        Assertions.assertEquals(Arrays.asList("Null|null"), output("()Ljava/lang/String;", null));
        Assertions.assertEquals(Arrays.asList("NonNull|null", "NonEmptyString|null"),
                output("()Ljava/lang/String;", "x"));
        Assertions.assertEquals(Arrays.asList("NonNull|null", "NonNull:java.util.List:isEmpty()Z:True|null",
                "NonNull:java.util.List:size()I:Zero|null"), output("()Ljava/util/List;", new ArrayList<>()));
        Assertions.assertTrue(output("()" + DEQUE, new ArrayDeque<>())
                .contains("NonNull:java.util.ArrayDeque:isEmpty()Z:True|null"));
        Assertions.assertTrue(output("()V", null).isEmpty());
    }

    @Test
    public void outputIgnoresOtherClassesAndHashCode() {
        Assertions.assertTrue(OutputCoverageGoal.createGoalsFromObject("Bar", "m", "()I", 1).isEmpty());
        Assertions.assertTrue(OutputCoverageGoal.createGoalsFromObject(CLASS, "hashCode", "()I", 1).isEmpty());
    }
}
