/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite;

import org.evosuite.Properties.Criterion;
import org.evosuite.instrumentation.LinePool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestSuiteGeneratorHelperTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        LinePool.reset();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        LinePool.reset();
    }

    @Test
    public void removesLineCriteriaWhenOnlyInvalidLineNumbersExist() {
        Properties.TARGET_CLASS = "com.example.Foo";
        LinePool.addLine("com.example.Foo", "m()V", 0);
        LinePool.addLine("com.example.Foo$Inner", "n()V", -1);

        Criterion[] filtered = TestSuiteGeneratorHelper.removeDebugInfoDependentCriteriaIfMissing(
                new Criterion[]{Criterion.LINE, Criterion.BRANCH, Criterion.ONLYLINE});

        Assertions.assertArrayEquals(new Criterion[]{Criterion.BRANCH}, filtered);
    }

    @Test
    public void keepsLineCriteriaWhenValidLineNumbersExist() {
        Properties.TARGET_CLASS = "com.example.Foo";
        LinePool.addLine("com.example.Foo", "m()V", 12);
        LinePool.addLine("com.example.Foo", "m()V", 0);

        Criterion[] filtered = TestSuiteGeneratorHelper.removeDebugInfoDependentCriteriaIfMissing(
                new Criterion[]{Criterion.LINE, Criterion.BRANCH});

        Assertions.assertArrayEquals(new Criterion[]{Criterion.LINE, Criterion.BRANCH}, filtered);
    }

    @Test
    public void keepsCriteriaUnchangedWhenNoLineCriterionIsRequested() {
        Properties.TARGET_CLASS = "com.example.Foo";

        Criterion[] filtered = TestSuiteGeneratorHelper.removeDebugInfoDependentCriteriaIfMissing(
                new Criterion[]{Criterion.BRANCH, Criterion.EXCEPTION});

        Assertions.assertArrayEquals(new Criterion[]{Criterion.BRANCH, Criterion.EXCEPTION}, filtered);
    }
}
