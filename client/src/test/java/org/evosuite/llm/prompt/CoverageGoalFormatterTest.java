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
package org.evosuite.llm.prompt;

import org.evosuite.Properties;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoverageGoalFormatterTest {

    private final Properties.LlmGoalFormat originalFormat = Properties.LLM_GOAL_FORMAT;

    @AfterEach
    void restoreProperties() {
        Properties.LLM_GOAL_FORMAT = originalFormat;
    }

    // ---- format (empty / null) ----

    @Test
    void format_emptyGoals_returnsNoGoalsMessage() {
        CoverageGoalFormatter formatter = new CoverageGoalFormatter();
        assertEquals("No uncovered goals available.", formatter.format(null));
        assertEquals("No uncovered goals available.", formatter.format(Collections.emptyList()));
    }

    // ---- format RAW mode ----

    @Test
    void format_rawMode_usesToString() {
        Properties.LLM_GOAL_FORMAT = Properties.LlmGoalFormat.RAW;

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("goal-1-desc");

        CoverageGoalFormatter formatter = new CoverageGoalFormatter();
        String result = formatter.format(Collections.singletonList(goal));

        assertEquals("1. goal-1-desc", result);
    }

    // ---- format LLM_FRIENDLY mode ----

    @Test
    void format_llmFriendlyMode_groupsByMethod() {
        Properties.LLM_GOAL_FORMAT = Properties.LlmGoalFormat.LLM_FRIENDLY;

        TestFitnessFunction goal1 = mock(TestFitnessFunction.class);
        when(goal1.toString()).thenReturn("goal-1");
        when(goal1.getTargetClass()).thenReturn("com.example.Foo");
        when(goal1.getTargetMethod()).thenReturn("foo(I)V");

        TestFitnessFunction goal2 = mock(TestFitnessFunction.class);
        when(goal2.toString()).thenReturn("goal-2");
        when(goal2.getTargetClass()).thenReturn("com.example.Bar");
        when(goal2.getTargetMethod()).thenReturn("bar(Z)V");

        CoverageGoalFormatter formatter = new CoverageGoalFormatter();
        String result = formatter.format(Arrays.asList(goal1, goal2));

        assertTrue(result.contains("## Method:"), "should have method headers");
        assertTrue(result.contains("## Method: com.example.Foo.foo(int)"));
        assertTrue(result.contains("## Method: com.example.Bar.bar(boolean)"));
        // Two distinct method groups
        int count = 0;
        int idx = 0;
        while ((idx = result.indexOf("## Method:", idx)) >= 0) {
            count++;
            idx++;
        }
        assertEquals(2, count, "should have 2 method groups");
    }

    // ---- fitness annotation ----

    @Test
    void format_fitnessAnnotation_almostCovered() {
        Properties.LLM_GOAL_FORMAT = Properties.LlmGoalFormat.LLM_FRIENDLY;

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("some-goal");
        when(goal.getTargetMethod()).thenReturn("foo(I)V");

        Map<TestFitnessFunction, Double> distances = new HashMap<>();
        distances.put(goal, 0.05);

        CoverageGoalFormatter formatter = new CoverageGoalFormatter();
        String result = formatter.format(Collections.singletonList(goal), distances);

        assertTrue(result.contains("[fitness:"), "should include fitness annotation");
        assertTrue(result.contains("almost covered]"), "should mark as almost covered");
    }

    // ---- overflow cap ----

    @Test
    void format_overflowCap_showsAndNMore() {
        Properties.LLM_GOAL_FORMAT = Properties.LlmGoalFormat.LLM_FRIENDLY;

        List<TestFitnessFunction> goals = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TestFitnessFunction g = mock(TestFitnessFunction.class);
            when(g.toString()).thenReturn("goal-" + i);
            when(g.getTargetMethod()).thenReturn("foo(I)V");
            goals.add(g);
        }

        CoverageGoalFormatter formatter =
                new CoverageGoalFormatter(new GoalDescriptionMapper(), 2);
        String result = formatter.format(goals);

        assertTrue(result.contains("... and 3 more"),
                "should indicate 3 remaining goals overflow");
    }

    // ---- groupByMethod ----

    @Test
    void groupByMethod_separatesGoals() {
        TestFitnessFunction goal1 = mock(TestFitnessFunction.class);
        when(goal1.getTargetClass()).thenReturn("com.example.Foo");
        when(goal1.getTargetMethod()).thenReturn("foo(I)V");

        TestFitnessFunction goal2 = mock(TestFitnessFunction.class);
        when(goal2.getTargetClass()).thenReturn("com.example.Bar");
        when(goal2.getTargetMethod()).thenReturn("bar(Z)V");

        CoverageGoalFormatter formatter = new CoverageGoalFormatter();
        Map<String, List<TestFitnessFunction>> grouped =
                formatter.groupByMethod(Arrays.asList(goal1, goal2));

        assertEquals(2, grouped.size(), "should have 2 method groups");
        assertTrue(grouped.containsKey("com.example.Foo.foo(int)"));
        assertTrue(grouped.containsKey("com.example.Bar.bar(boolean)"));
    }

    @Test
    void groupByMethod_otherGroupForUnknownMethod() {
        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.getTargetMethod()).thenReturn(null);

        CoverageGoalFormatter formatter = new CoverageGoalFormatter();
        Map<String, List<TestFitnessFunction>> grouped =
                formatter.groupByMethod(Collections.singletonList(goal));

        assertTrue(grouped.containsKey("(other)"),
                "goal with null method should be in '(other)' group");
    }
}
