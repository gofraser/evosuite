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
